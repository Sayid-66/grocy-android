/*
 * This file is part of Grocy Android.
 *
 * Grocy Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grocy Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grocy Android. If not, see http://www.gnu.org/licenses/.
 *
 * Copyright (c) 2020-2024 by Patrick Zedler and Dominic Zedler
 * Copyright (c) 2024-2026 by Patrick Zedler
 */

package xyz.zedler.patrick.grocy.viewmodel;

import android.app.Application;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.FutureTarget;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;
import xyz.zedler.patrick.grocy.Constants;
import xyz.zedler.patrick.grocy.Constants.ACTION;
import xyz.zedler.patrick.grocy.Constants.SETTINGS.STOCK;
import xyz.zedler.patrick.grocy.Constants.SETTINGS_DEFAULT;
import xyz.zedler.patrick.grocy.R;
import xyz.zedler.patrick.grocy.api.GrocyApi;
import xyz.zedler.patrick.grocy.form.FormDataMasterProduct;
import xyz.zedler.patrick.grocy.form.FormDataMasterProductCatQuantityUnit;
import xyz.zedler.patrick.grocy.fragment.MasterProductFragmentArgs;
import xyz.zedler.patrick.grocy.helper.DownloadHelper;
import xyz.zedler.patrick.grocy.model.Event;
import xyz.zedler.patrick.grocy.model.InfoFullscreen;
import xyz.zedler.patrick.grocy.model.PendingProductBarcode;
import xyz.zedler.patrick.grocy.model.Product;
import xyz.zedler.patrick.grocy.model.ProductBarcode;
import xyz.zedler.patrick.grocy.model.ProductDetails;
import xyz.zedler.patrick.grocy.model.QuantityUnit;
import xyz.zedler.patrick.grocy.model.QuantityUnitConversion;
import xyz.zedler.patrick.grocy.repository.MasterProductRepository;
import xyz.zedler.patrick.grocy.util.ArrayUtil;
import xyz.zedler.patrick.grocy.util.NumUtil;
import xyz.zedler.patrick.grocy.util.OffContentAmountUtil;
import xyz.zedler.patrick.grocy.util.PictureUtil;
import xyz.zedler.patrick.grocy.util.PrefsUtil;
import xyz.zedler.patrick.grocy.util.QuickPackagingSyncUtil;
import xyz.zedler.patrick.grocy.util.VersionUtil;
import xyz.zedler.patrick.grocy.web.NetworkQueue;

public class MasterProductViewModel extends BaseViewModel {

  private static final String TAG = MasterProductViewModel.class.getSimpleName();

  // Only images served from this exact OFF host are ever fetched. Verified against the live
  // API (world.openfoodfacts.org product lookup returns image_front_url/image_url on this
  // host) - not just an "https://" prefix, so an offImageUrl smuggled in through the exported
  // grocy:// deep link cannot make the app fetch an arbitrary attacker host.
  private static final String OFF_IMAGE_HOST = "images.openfoodfacts.org";

  private final SharedPreferences sharedPrefs;
  private final DownloadHelper dlHelper;
  private final GrocyApi grocyApi;
  private final MasterProductRepository repository;
  private final FormDataMasterProduct formData;

  private final MutableLiveData<List<PendingProductBarcode>> pendingProductBarcodesLive;
  private final MutableLiveData<Boolean> isLoadingLive;
  private final MutableLiveData<InfoFullscreen> infoFullscreenLive;
  private final MutableLiveData<String> offProductNameLive;
  private final MutableLiveData<String> offBrandLive;
  private final MutableLiveData<String> offBrandFullLive;
  private final MutableLiveData<String> offQuantityLive;
  private final MutableLiveData<String> offImageUrlLive;
  private final MutableLiveData<String> offEnergyPer100gLive;
  private final MutableLiveData<Boolean> hasOffPreviewLive;
  private final MutableLiveData<String> offIngredientsLive;
  private final MutableLiveData<String> offAllergensLive;
  private final MutableLiveData<String> offNutriscoreLive;
  private final MutableLiveData<String> offOriginLive;
  private final MutableLiveData<String> offNutrientsLive;
  private final MutableLiveData<Boolean> hasOffExtraInfoLive;
  private final MutableLiveData<Boolean> offExtraInfoExpandedLive;
  private final MutableLiveData<String> offPackagingTypeLive;
  private final String scannedBarcode;

  // "Learn once, prefill forever" quick packaging/content entry card (only shown for a genuinely
  // new, non-cloned product with a scanned barcode - see getShowQuickPackagingEntryLive()).
  private static final List<String> QUICK_PACKAGING_LABELS = Arrays.asList(
      "Flasche", "Glas", "Dose", "Packung", "Beutel", "Karton", "Schachtel", "Schale",
      "Becher", "Tube", "Stück"
  );
  private final MutableLiveData<Boolean> showQuickPackagingEntryLive;
  private final MutableLiveData<String> quickPackagingLive;
  private final MutableLiveData<String> quickContentAmountLive;
  private final MutableLiveData<String> quickContentUnitLive;
  private final MutableLiveData<Boolean> quickPackagingQuMissingLive;
  private final MutableLiveData<Boolean> quickContentQuMissingLive;
  private final int maxDecimalPlacesAmount;

  private List<Product> products;
  private List<ProductBarcode> productBarcodes;
  private List<PendingProductBarcode> pendingProductBarcodes;
  private List<QuantityUnit> quantityUnits;

  // Tracks which quantity unit the quick packaging/content entry card itself last applied to
  // each of these product fields (null = never applied by the quick card yet), so a later
  // manual choice on the classic quantity unit screen is never silently overridden again - see
  // syncQuickPackagingToProduct()/QuickPackagingSyncUtil#isQuickOwned().
  private Integer lastQuickAppliedStockQuId;
  private Integer lastQuickAppliedPurchaseQuId;
  private Integer lastQuickAppliedPriceQuId;
  // The product's own ambient quantity unit preset (e.g. a "default new-product quantity unit"
  // from Settings) at the exact moment it was constructed, before anything - quick card or user -
  // could have touched it. Captured once, only for the genuinely-new-product branch these fields
  // are ever relevant for; see QuickPackagingSyncUtil#isQuickOwned() for why this is needed.
  private Integer initialPresetStockQuId;
  private Integer initialPresetPurchaseQuId;
  private Integer initialPresetPriceQuId;
  private final Observer<String> quickContentAmountObserver = amount -> syncQuickPackagingToProduct();

  private NetworkQueue.QueueItem extraQueueItem;
  private final boolean debug;
  private final MutableLiveData<Boolean> actionEditLive;
  private final MasterProductFragmentArgs args;
  private final boolean forceSaveWithClose;
  private boolean saveInProgress;

  public MasterProductViewModel(
      @NonNull Application application,
      @NonNull MasterProductFragmentArgs startupArgs
  ) {
    super(application);

    sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplication());
    debug = PrefsUtil.isDebuggingEnabled(sharedPrefs);

    args = startupArgs;
    isLoadingLive = new MutableLiveData<>(false);
    dlHelper = new DownloadHelper(getApplication(), TAG, isLoadingLive::setValue, getOfflineLive());
    grocyApi = new GrocyApi(getApplication());
    repository = new MasterProductRepository(application);
    formData = new FormDataMasterProduct(application, getBeginnerModeEnabled());
    actionEditLive = new MutableLiveData<>();
    actionEditLive.setValue(args.getAction().equals(Constants.ACTION.EDIT));
    forceSaveWithClose = !isActionEdit() && args.getProductName() != null;

    pendingProductBarcodesLive = new MutableLiveData<>();
    infoFullscreenLive = new MutableLiveData<>();
    offProductNameLive = new MutableLiveData<>(args.getProductName());
    offBrandLive = new MutableLiveData<>(args.getOffBrand());
    offBrandFullLive = new MutableLiveData<>(args.getOffBrandFull());
    offQuantityLive = new MutableLiveData<>(args.getOffQuantity());
    // Validated once, here at the source: both the preview (Glide load in MasterProductFragment)
    // and the later upload (handleOffPictureUploadIfNecessary) read this same LiveData, so an
    // offImageUrl smuggled in through the exported grocy:// deep link with a non-OFF host must
    // never reach either of them.
    offImageUrlLive = new MutableLiveData<>(
        isValidOffImageUrl(args.getOffImageUrl()) ? args.getOffImageUrl() : null
    );
    offEnergyPer100gLive = new MutableLiveData<>(args.getOffEnergyPer100g());
    offPackagingTypeLive = new MutableLiveData<>(args.getOffPackagingType());
    hasOffPreviewLive = new MutableLiveData<>(
        !isBlank(offBrandLive.getValue())
            || !isBlank(offQuantityLive.getValue())
            || !isBlank(offImageUrlLive.getValue())
            || !isBlank(offEnergyPer100gLive.getValue())
            || !isBlank(offPackagingTypeLive.getValue())
    );
    offIngredientsLive = new MutableLiveData<>(args.getOffIngredients());
    offAllergensLive = new MutableLiveData<>(args.getOffAllergens());
    offNutriscoreLive = new MutableLiveData<>(args.getOffNutriscore());
    offOriginLive = new MutableLiveData<>(args.getOffOrigin());
    offNutrientsLive = new MutableLiveData<>(args.getOffNutrients());
    hasOffExtraInfoLive = new MutableLiveData<>(
        !isBlank(offIngredientsLive.getValue())
            || !isBlank(offAllergensLive.getValue())
            || !isBlank(offNutriscoreLive.getValue())
            || !isBlank(offOriginLive.getValue())
            || !isBlank(offNutrientsLive.getValue())
            || !isBlank(offBrandFullLive.getValue())
    );
    offExtraInfoExpandedLive = new MutableLiveData<>(false);
    scannedBarcode = args.getBarcode();

    maxDecimalPlacesAmount = sharedPrefs.getInt(
        STOCK.DECIMAL_PLACES_AMOUNT,
        SETTINGS_DEFAULT.STOCK.DECIMAL_PLACES_AMOUNT
    );
    // Explicitly excludes the "clone" case (args.getProduct()!=null or a valid args.getProductId())
    // even though hasScannedBarcode() is already false there today (the copy-existing-product
    // flow never passes a barcode) - the exported grocy:// deep link technically accepts
    // productId and barcode together, so this must not rely on that only being true in practice.
    boolean isClone = args.getProduct() != null || NumUtil.isStringInt(args.getProductId());
    showQuickPackagingEntryLive = new MutableLiveData<>(
        !isActionEdit() && !isClone && hasScannedBarcode()
    );
    String detectedPackagingLabel = args.getOffPackagingType();
    quickPackagingLive = new MutableLiveData<>(
        detectedPackagingLabel != null && QUICK_PACKAGING_LABELS.contains(detectedPackagingLabel)
            ? detectedPackagingLabel : null
    );
    OffContentAmountUtil.ParsedContentAmount parsedContentAmount
        = OffContentAmountUtil.parse(args.getOffQuantity());
    if (parsedContentAmount != null) {
      quickContentAmountLive = new MutableLiveData<>(
          NumUtil.trimAmount(parsedContentAmount.amount, maxDecimalPlacesAmount)
      );
      quickContentUnitLive = new MutableLiveData<>(parsedContentAmount.unitName);
    } else {
      quickContentAmountLive = new MutableLiveData<>();
      quickContentUnitLive = new MutableLiveData<>();
    }
    quickPackagingQuMissingLive = new MutableLiveData<>(false);
    quickContentQuMissingLive = new MutableLiveData<>(false);
    // The content amount field is two-way data-bound directly to the EditText (no dedicated
    // setter method to hook into like setQuickPackaging()/setQuickContentUnit() below), so this
    // is the only way to also re-sync the product's quantity units whenever it changes -
    // observeForever is safe here: both this LiveData and the observer are owned by this same
    // ViewModel instance (never an external/longer-lived LiveData), and the observer is removed
    // in onCleared() regardless.
    quickContentAmountLive.observeForever(quickContentAmountObserver);

    if (isActionEdit()) {
      if (args.getProduct() != null) {
        setCurrentProduct(args.getProduct());
      } else {
        assert args.getProductId() != null;
        int productId = Integer.parseInt(args.getProductId());
        extraQueueItem = ProductDetails.getProductDetails(dlHelper, productId, productDetails -> {
          extraQueueItem = null;
          formData.getProductNamesLive().setValue(
              getProductNames(products, productDetails.getProduct().getName())
          );
          setCurrentProduct(productDetails.getProduct());
        });
      }
    } else if (args.getProduct() != null || NumUtil.isStringInt(args.getProductId())) {  // on clone
      if (args.getProduct() != null) {
        Product product = args.getProduct();
        formData.getMessageCopiedFromLive()
            .setValue(getString(R.string.msg_data_copied_from_product, product.getName()));
        if (args.getProductName() != null) {
          product.setName(args.getProductName());
        } else {
          product.setName(null);
          sendEvent(Event.FOCUS_INVALID_VIEWS);
        }
        setCurrentProduct(product);
      } else {
        assert args.getProductId() != null;
        int productId = Integer.parseInt(args.getProductId());
        extraQueueItem = ProductDetails.getProductDetails(dlHelper, productId, productDetails -> {
          extraQueueItem = null;
          Product product = productDetails.getProduct();
          formData.getMessageCopiedFromLive()
              .setValue(getString(R.string.msg_data_copied_from_product, product.getName()));
          if (args.getProductName() != null) {
            product.setName(args.getProductName());
          } else {
            product.setName(null);
            sendEvent(Event.FOCUS_INVALID_VIEWS);
          }
          setCurrentProduct(product);
        });
      }
    } else {
      Product product = new Product(sharedPrefs);
      if (args.getProductName() != null) {
        product.setName(args.getProductName());
      } else {
        sendEvent(Event.FOCUS_INVALID_VIEWS);
      }
      // Captured here, before anything - quick card or user - could have touched these fields:
      // the product's own ambient quantity unit preset (e.g. a "default new-product quantity
      // unit" from Settings, or -1 if none configured). See QuickPackagingSyncUtil#isQuickOwned().
      initialPresetStockQuId = product.getQuIdStockInt();
      initialPresetPurchaseQuId = product.getQuIdPurchaseInt();
      initialPresetPriceQuId = product.getQuIdPriceInt();
      setCurrentProduct(product);
    }
  }

  public boolean isActionEdit() {
    assert actionEditLive.getValue() != null;
    return actionEditLive.getValue();
  }

  public MutableLiveData<Boolean> getActionEditLive() {
    return actionEditLive;
  }

  public String getAction() {
    return isActionEdit() ? ACTION.EDIT : ACTION.CREATE;
  }

  public FormDataMasterProduct getFormData() {
    return formData;
  }

  public void setCurrentProduct(Product product) {
    formData.getProductLive().setValue(product);
    formData.isFormValid();
  }

  public Product getFilledProduct() {
    return formData.fillProduct(formData.getProductLive().getValue());
  }

  /**
   * Whether this ViewModel was given a barcode to link when it was created (i.e. reached via
   * ChooseProductFragment#createNewProduct, not e.g. its "copy existing product" flow, which
   * never passes one). Exposed so MasterProductFragment can tell the screen below not to forward
   * the same barcode again once saved (see linkScannedBarcodeAndUploadPending). This reflects
   * the ViewModel's own stable, construction-time field, not the fragment's own arguments: those
   * get cleared (.setBarcode(null) in MasterProductFragment#onViewCreated) right after being
   * read once, so re-reading them on a later onViewCreated pass - e.g. after the user visits the
   * quantity unit screen, required for a new product, and comes back to save - would wrongly
   * look like no barcode was ever given, even though this same ViewModel instance still holds it
   * and already attempted to link it.
   */
  public boolean hasScannedBarcode() {
    return !isBlank(scannedBarcode);
  }

  /**
   * Keeps the quick packaging/content entry card and the REAL product quantity unit fields
   * (quIdStock/quIdPurchase/quIdConsume/quIdPrice) as one single source of truth, instead of two
   * parallel configurations that could contradict each other (the quick card merely "learning"
   * something while the classic quantity unit screen still shows "nothing selected", and - worse
   * - {@link FormDataMasterProduct#isWholeFormValid()} still refusing to save because it only
   * ever looks at these same product fields). Called whenever anything the quick card's decision
   * depends on changes: quantityUnits finishing loading, a chip (de)selected, the content amount
   * typed, or a missing quantity unit created via {@link #createQuickQuantityUnit}.
   * <p>
   * Applies only:
   * - for a genuinely new, non-cloned product with the quick card actually showing (see
   *   {@link #getShowQuickPackagingEntryLive()}) - never for editing/cloning, which already have
   *   deliberately chosen units that must never be touched automatically,
   * - the PURCHASE and PRICE unit are set to the confirmed PACKAGING unit (e.g. "Flasche") -
   *   never the content unit: what you buy/pay for is one bottle, not "250 ml" (see task docs,
   *   "keine doppelte Mengeneingabe"),
   * - the STOCK unit is set to the confirmed CONTENT unit if a valid content amount is also
   *   present (e.g. "ml"), otherwise it falls back to the packaging unit too - see
   *   {@link QuickPackagingSyncUtil#resolveEffectiveStockQuId},
   * - CONSUME is set to the same as stock, but only once, while it is still completely unset
   *   (id -1) - mirroring the existing manual cascade in
   *   {@link FormDataMasterProductCatQuantityUnit#selectQuantityUnit(QuantityUnit, Bundle)},
   * - a field already holding something other than what the quick card itself last applied there
   *   (or, before it applied anything yet, other than the product's own original ambient preset -
   *   see {@link QuickPackagingSyncUtil#isQuickOwned}) is NEVER overwritten - i.e. the user picked
   *   something different on the classic quantity unit screen - so a manual choice is never
   *   silently reverted by a later quick-card change.
   * Never creates a new Grocy quantity unit itself and never guesses: only unit ids already
   * resolved from the user's current, confirmed chip/text selections (see resolveQuickQuId())
   * are ever applied.
   */
  private void syncQuickPackagingToProduct() {
    if (isActionEdit() || quantityUnits == null) {
      return;
    }
    Boolean showQuickCard = showQuickPackagingEntryLive.getValue();
    if (showQuickCard == null || !showQuickCard) {
      return;
    }
    Product product = formData.getProductLive().getValue();
    if (product == null) {
      return;
    }
    Integer packagingQuId = resolveQuickQuId(quickPackagingLive.getValue());
    // Only Grocy servers >= 4.0 support a per-product QuantityUnitConversion (see
    // applyQuickPackagingAndContent()'s own docs) - on older servers there is only a single
    // GLOBAL purchase-to-stock factor (qu_factor_purchase_to_stock, left at its default of 1
    // here), so setting the stock unit to the content unit there, without a matching conversion,
    // would silently misrepresent "1 Flasche" purchased as "1 ml" in stock. The content unit is
    // therefore never even resolved on those servers - stock simply stays the packaging unit too,
    // exactly like before this quick-card feature existed for them.
    Integer contentQuId = VersionUtil.isGrocyServerMin400(sharedPrefs)
        ? resolveQuickQuId(quickContentUnitLive.getValue()) : null;
    Integer effectiveStockQuId = QuickPackagingSyncUtil.resolveEffectiveStockQuId(
        packagingQuId, contentQuId, quickContentAmountLive.getValue()
    );

    boolean changed = false;
    if (packagingQuId != null) {
      if (QuickPackagingSyncUtil.isQuickOwned(
          product.getQuIdPurchaseInt(), initialPresetPurchaseQuId, lastQuickAppliedPurchaseQuId
      ) && product.getQuIdPurchaseInt() != packagingQuId) {
        product.setQuIdPurchase(packagingQuId);
        changed = true;
      }
      if (QuickPackagingSyncUtil.isQuickOwned(
          product.getQuIdPriceInt(), initialPresetPriceQuId, lastQuickAppliedPriceQuId
      ) && product.getQuIdPriceInt() != packagingQuId) {
        product.setQuIdPrice(packagingQuId);
        changed = true;
      }
      lastQuickAppliedPurchaseQuId = packagingQuId;
      lastQuickAppliedPriceQuId = packagingQuId;
    }
    if (effectiveStockQuId != null) {
      if (QuickPackagingSyncUtil.isQuickOwned(
          product.getQuIdStockInt(), initialPresetStockQuId, lastQuickAppliedStockQuId
      ) && product.getQuIdStockInt() != effectiveStockQuId) {
        product.setQuIdStock(effectiveStockQuId);
        changed = true;
      }
      if (product.getQuIdConsumeInt() == -1) {
        product.setQuIdConsume(effectiveStockQuId);
        changed = true;
      }
      lastQuickAppliedStockQuId = effectiveStockQuId;
    }
    if (!changed) {
      return;
    }
    // Sync the name field INTO the product before re-emitting, instead of only conditionally
    // re-emitting when it already matches: formData.getNameLive() is itself derived FROM
    // productLive via Transformations.map (see FormDataMasterProduct) and two-way bound to the
    // name EditText, so re-emitting without this would reset whatever the user already typed
    // back to the product's own (possibly still empty) name. But skipping the emission instead
    // whenever the two happen to differ - as an earlier version of this method did - would leave
    // catQuErrorLive/isWholeFormValid() stale forever the moment the user has typed anything
    // (e.g. right after OFF returned no name and FOCUS_INVALID_VIEWS opened the keyboard there),
    // silently reintroducing the exact "can't save without visiting the classic quantity unit
    // screen" bug this method exists to fix. Syncing first keeps both correct together.
    String currentName = formData.getNameLive().getValue();
    if (currentName != null) {
      product.setName(currentName);
    }
    formData.getProductLive().setValue(product);
  }

  @Nullable
  private QuantityUnit findUniqueQuantityUnitByName(String name) {
    QuantityUnit match = null;
    for (QuantityUnit quantityUnit : quantityUnits) {
      if (quantityUnit.getName() != null && quantityUnit.getName().trim().equalsIgnoreCase(name)) {
        if (match != null) {
          return null; // more than one match -> not unique, don't guess
        }
        match = quantityUnit;
      }
    }
    return match;
  }

  public LiveData<String> getOffProductNameLive() {
    return offProductNameLive;
  }

  public LiveData<String> getOffBrandLive() {
    return offBrandLive;
  }

  public LiveData<String> getOffBrandFullLive() {
    return offBrandFullLive;
  }

  public LiveData<String> getOffQuantityLive() {
    return offQuantityLive;
  }

  public LiveData<String> getOffImageUrlLive() {
    return offImageUrlLive;
  }

  public LiveData<String> getOffEnergyPer100gLive() {
    return offEnergyPer100gLive;
  }

  public LiveData<Boolean> getHasOffPreviewLive() {
    return hasOffPreviewLive;
  }

  public LiveData<String> getOffIngredientsLive() {
    return offIngredientsLive;
  }

  public LiveData<String> getOffAllergensLive() {
    return offAllergensLive;
  }

  public LiveData<String> getOffNutriscoreLive() {
    return offNutriscoreLive;
  }

  public LiveData<String> getOffOriginLive() {
    return offOriginLive;
  }

  public LiveData<String> getOffPackagingTypeLive() {
    return offPackagingTypeLive;
  }

  public LiveData<String> getOffNutrientsLive() {
    return offNutrientsLive;
  }

  public LiveData<Boolean> getHasOffExtraInfoLive() {
    return hasOffExtraInfoLive;
  }

  public LiveData<Boolean> getOffExtraInfoExpandedLive() {
    return offExtraInfoExpandedLive;
  }

  public void toggleOffExtraInfoExpanded() {
    Boolean expanded = offExtraInfoExpandedLive.getValue();
    offExtraInfoExpandedLive.setValue(expanded == null || !expanded);
  }

  public LiveData<Boolean> getShowQuickPackagingEntryLive() {
    return showQuickPackagingEntryLive;
  }

  public MutableLiveData<String> getQuickPackagingLive() {
    return quickPackagingLive;
  }

  public MutableLiveData<String> getQuickContentAmountLive() {
    return quickContentAmountLive;
  }

  public MutableLiveData<String> getQuickContentUnitLive() {
    return quickContentUnitLive;
  }

  public LiveData<Boolean> getQuickPackagingQuMissingLive() {
    return quickPackagingQuMissingLive;
  }

  public LiveData<Boolean> getQuickContentQuMissingLive() {
    return quickContentQuMissingLive;
  }

  /**
   * Called by the fragment when the user (de)selects a chip in the "Verpackung" quick-pick.
   * Also refreshes the "not yet a Grocy quantity unit" hint for the new selection.
   */
  public void setQuickPackaging(@Nullable String name) {
    quickPackagingLive.setValue(name);
    updateQuickQuMissingFlags();
    syncQuickPackagingToProduct();
  }

  /**
   * Called by the fragment when the user (de)selects a chip in the "Inhaltseinheit" quick-pick.
   * Also refreshes the "not yet a Grocy quantity unit" hint for the new selection.
   */
  public void setQuickContentUnit(@Nullable String name) {
    quickContentUnitLive.setValue(name);
    updateQuickQuMissingFlags();
    syncQuickPackagingToProduct();
  }

  private void updateQuickQuMissingFlags() {
    quickPackagingQuMissingLive.setValue(isQuickQuMissing(quickPackagingLive.getValue()));
    quickContentQuMissingLive.setValue(isQuickQuMissing(quickContentUnitLive.getValue()));
  }

  private boolean isQuickQuMissing(@Nullable String name) {
    if (isBlank(name) || quantityUnits == null) {
      return false;
    }
    return findUniqueQuantityUnitByName(name.trim()) == null;
  }

  @Nullable
  private Integer resolveQuickQuId(@Nullable String name) {
    if (isBlank(name) || quantityUnits == null) {
      return null;
    }
    QuantityUnit match = findUniqueQuantityUnitByName(name.trim());
    return match != null ? match.getId() : null;
  }

  /**
   * Quick, minimal quantity unit creation for the quick packaging/content entry card only -
   * completely separate from and never touching the existing QuantityUnitsBottomSheet/
   * MasterQuantityUnitFragment full master-data QU creation flow. Adds the created unit to the
   * in-memory list this ViewModel already tracks so it is immediately treated as available/
   * selected (the already-checked chip with the same name simply stops showing the "missing"
   * hint). On failure, falls back to the existing generic network error path.
   */
  public void createQuickQuantityUnit(@Nullable String name, @Nullable Consumer<QuantityUnit> onCreated) {
    if (isBlank(name)) {
      return;
    }
    String trimmedName = name.trim();
    JSONObject body = new JSONObject();
    try {
      body.put("name", trimmedName);
      body.put("name_plural", trimmedName);
    } catch (JSONException e) {
      if (debug) {
        Log.e(TAG, "createQuickQuantityUnit: " + e);
      }
      return;
    }
    dlHelper.post(
        grocyApi.getObjects(GrocyApi.ENTITY.QUANTITY_UNITS),
        body,
        response -> {
          int objectId = -1;
          try {
            objectId = response.getInt("created_object_id");
          } catch (JSONException e) {
            if (debug) {
              Log.e(TAG, "createQuickQuantityUnit: " + e);
            }
          }
          if (objectId == -1) {
            return;
          }
          QuantityUnit created = new QuantityUnit(objectId, trimmedName);
          if (quantityUnits == null) {
            quantityUnits = new ArrayList<>();
          }
          quantityUnits.add(created);
          updateQuickQuMissingFlags();
          // The newly created unit must be usable immediately: no "refresh master data" step,
          // no re-navigation - the product's real quantity unit fields (and with them, the
          // classic quantity unit screen and this form's validity) reflect it right away.
          syncQuickPackagingToProduct();
          if (onCreated != null) {
            onCreated.accept(created);
          }
        },
        error -> {
          showNetworkErrorMessage(error);
          if (debug) {
            Log.e(TAG, "createQuickQuantityUnit: " + error);
          }
        }
    );
  }

  private static boolean isBlank(@Nullable String value) {
    return value == null || value.isBlank();
  }

  public void loadFromDatabase(boolean downloadAfterLoading) {
    repository.loadFromDatabase(data -> {
      this.products = data.getProducts();
      this.productBarcodes = data.getBarcodes();
      this.pendingProductBarcodes = data.getPendingProductBarcodes();
      this.quantityUnits = data.getQuantityUnits();
      updateQuickQuMissingFlags();
      syncQuickPackagingToProduct();
      formData.getProductNamesLive().setValue(getProductNames(this.products, null));

      if (downloadAfterLoading) {
        downloadData(false);
      } else {
        onQueueEmpty();
      }
    }, error -> onError(error, TAG));
  }

  public void downloadData(boolean forceUpdate) {
    dlHelper.updateData(
        updated -> {
          if (updated) {
            loadFromDatabase(false);
          } else {
            onQueueEmpty();
          }
        }, error -> onError(error, TAG),
        null,
        forceUpdate,
        false,
        extraQueueItem,
        Product.class,
        ProductBarcode.class
    );
  }

  private void onQueueEmpty() {
    if (args.getPendingProductBarcodes() != null) {
      ArrayList<PendingProductBarcode> filteredBarcodes = new ArrayList<>();
      String[] barcodeIds = args.getPendingProductBarcodes().split(",");
      for (PendingProductBarcode barcode : pendingProductBarcodes) {
        if (ArrayUtil.contains(barcodeIds, String.valueOf(barcode.getId()))) {
          filteredBarcodes.add(barcode);
        }
      }
      if (!filteredBarcodes.isEmpty()) {
        this.pendingProductBarcodesLive.setValue(filteredBarcodes);
      }
      removeBarcodesWhichExistOnline(productBarcodes);
    }
  }

  private ArrayList<String> getProductNames(List<Product> products, @Nullable String nameToRemove) {
    ArrayList<String> names = new ArrayList<>();
    for (Product product : products) {
      names.add(product.getName());
    }
    if (isActionEdit() && (formData.getProductLive().getValue() != null || nameToRemove != null)) {
      names.remove(nameToRemove != null
          ? nameToRemove
          : formData.getProductLive().getValue().getName());
    }
    return names;
  }

  public void saveProduct(boolean withClosing) {
    if (!formData.isWholeFormValid()) {
      showMessage(getString(R.string.error_missing_information));
      return;
    }
    if (saveInProgress) {
      // Guards against a double-tap on the save button firing two POSTs/PUTs: without this,
      // creating a product twice from the same barcode scan would now also link the same
      // scannedBarcode to two different products, making the barcode permanently ambiguous
      // (not just an annoying duplicate product like before this feature existed).
      return;
    }
    saveInProgress = true;

    Product product = getFilledProduct();
    JSONObject jsonObject = product.getJsonFromProduct(sharedPrefs, debug, TAG);

    if (isActionEdit()) {
      dlHelper.put(
          grocyApi.getObject(GrocyApi.ENTITY.PRODUCTS, product.getId()),
          jsonObject,
          response -> {
            saveInProgress = false;
            Bundle bundle = new Bundle();
            bundle.putInt(Constants.ARGUMENT.PRODUCT_ID, product.getId());
            sendEvent(Event.SET_PRODUCT_ID, bundle);
            sendEvent(Event.NAVIGATE_UP);
          },
          error -> {
            saveInProgress = false;
            showNetworkErrorMessage(error);
            if (debug) {
              Log.e(TAG, "saveProduct: " + error);
            }
          }
      );
    } else {
      dlHelper.post(
          grocyApi.getObjects(GrocyApi.ENTITY.PRODUCTS),
          jsonObject,
          response -> {
            saveInProgress = false;
            int objectId = -1;
            try {
              objectId = response.getInt("created_object_id");
              Log.i(TAG, "saveProduct: " + objectId);
            } catch (JSONException e) {
              if (debug) {
                Log.e(TAG, "saveProduct: " + e);
              }
            }
            handleOffPictureUploadIfNecessary(objectId, product.getPictureFileName());
            // Resolve the quick packaging/content entry card's confirmed selections (if any)
            // exactly once here, at save time - only what is checked/filled in the form right
            // now counts, never raw OFF data on its own (see getShowQuickPackagingEntryLive()/
            // applyQuickPackagingAndContent() docs).
            Integer packagingQuId = null;
            if (objectId != -1 && hasScannedBarcode()) {
              product.setId(objectId);
              packagingQuId = resolveQuickQuId(quickPackagingLive.getValue());
            }
            Integer finalPackagingQuId = packagingQuId;
            int finalObjectId = objectId;
            // Both the barcode link AND the quick packaging/content follow-up writes must finish
            // BEFORE navigating away: dlHelper.destroy() (ViewModel#onCleared, e.g. once
            // NAVIGATE_UP pops this fragment) cancels any still-in-flight request tagged with
            // this ViewModel's uuid, so firing NAVIGATE_UP while these writes are still pending
            // could silently drop them, leaving the product half-configured (e.g. a stock unit
            // change with no matching conversion, or vice versa).
            Runnable proceedWithBarcodeLinkAndNavigation = () -> {
              if (withClosing) {
                if (finalObjectId != -1) {
                  Bundle bundle = new Bundle();
                  bundle.putInt(Constants.ARGUMENT.PRODUCT_ID, finalObjectId);
                  sendEvent(Event.SET_PRODUCT_ID, bundle);
                }
                linkScannedBarcodeAndUploadPending(
                    finalObjectId, finalPackagingQuId, () -> sendEvent(Event.NAVIGATE_UP)
                );
              } else {
                linkScannedBarcodeAndUploadPending(finalObjectId, finalPackagingQuId, () -> {
                  actionEditLive.setValue(true);
                  product.setId(finalObjectId);
                  setCurrentProduct(product);
                  sendEvent(Event.TRANSACTION_SUCCESS);
                });
              }
            };
            if (packagingQuId != null) {
              applyQuickPackagingAndContent(
                  objectId, product, packagingQuId, proceedWithBarcodeLinkAndNavigation
              );
            } else {
              proceedWithBarcodeLinkAndNavigation.run();
            }
          },
          error -> {
            saveInProgress = false;
            showNetworkErrorMessage(error);
            if (debug) {
              Log.e(TAG, "saveProduct: " + error);
            }
          }
      );
    }
  }

  /**
   * Creates the Grocy {@link QuantityUnitConversion} row (purchase unit -> stock unit, factor =
   * content amount) for the user's CONFIRMED quick packaging/content selections (from the quick
   * packaging entry card, visible only for a new, non-cloned product with a scanned barcode).
   * Never invents anything from raw OFF data alone - only what is checked/filled in the form at
   * save time counts, exactly as if the user had typed it themselves (see task docs for
   * getShowQuickPackagingEntryLive()).
   * <p>
   * Unlike the very first version of this method, the product's stock unit itself no longer needs
   * a follow-up PUT here: {@link #syncQuickPackagingToProduct()} already kept it in sync with the
   * SAME confirmed selections live, in-form, before this product was even created - see that
   * method's docs - so it is already part of the initial POST body. This only needs to create the
   * conversion row once the product (and therefore a real product id for it) exists.
   * <p>
   * Only Grocy servers >= 4.0 are touched: on older servers the "resolved" conversion lookup
   * this app uses to turn a purchase amount into a stock amount does not consult a
   * product-specific conversion the way >= 4.0 does (see QuantityUnitConversionUtil), so creating
   * one there would silently do nothing useful - the packaging/barcode part (amount + qu_id on
   * the ProductBarcode) still applies regardless of server version, only the conversion itself is
   * skipped.
   * <p>
   * If the product's stock unit is not actually the confirmed content unit when this runs - e.g.
   * the user manually picked something else on the (mandatory, for a new product) quantity unit
   * screen after syncQuickPackagingToProduct() already applied it - that explicit manual choice
   * is never overridden, and no conversion pointing at a unit that is not the actual stock unit is
   * created either (it would never be used for the purchase-to-stock math and would just be
   * confusing leftover data).
   * <p>
   * Always calls {@code onFinished} exactly once, on every exit path (nothing to apply, server
   * too old, manual override detected, success, or failure) - the caller uses this to sequence
   * navigation after this write, since it runs on this ViewModel's own DownloadHelper, whose
   * requests get cancelled by onCleared() once the fragment is popped (see saveProduct()).
   */
  private void applyQuickPackagingAndContent(
      int productId, Product createdProduct, int packagingQuId, Runnable onFinished
  ) {
    if (!VersionUtil.isGrocyServerMin400(sharedPrefs)) {
      onFinished.run();
      return;
    }
    Integer contentQuId = resolveQuickQuId(quickContentUnitLive.getValue());
    String contentAmountStr = quickContentAmountLive.getValue();
    boolean validAmount = NumUtil.isStringDouble(contentAmountStr)
        && NumUtil.toDouble(contentAmountStr) > 0;
    if (contentQuId == null || !validAmount || packagingQuId == contentQuId) {
      onFinished.run();
      return;
    }
    if (createdProduct.getQuIdStockInt() != contentQuId) {
      // Not (or no longer) the confirmed content unit - either syncQuickPackagingToProduct()
      // never resolved it this way, or the user manually overrode it - either way, never create
      // a conversion pointing at a unit that isn't actually the stock unit.
      onFinished.run();
      return;
    }
    double contentAmount = NumUtil.toDouble(contentAmountStr);
    createQuickQuantityUnitConversion(productId, packagingQuId, contentQuId, contentAmount, onFinished);
  }

  private void createQuickQuantityUnitConversion(
      int productId, int fromQuId, int toQuId, double factor, Runnable onFinished
  ) {
    QuantityUnitConversion conversion = new QuantityUnitConversion();
    conversion.setProductId(String.valueOf(productId));
    conversion.setFromQuId(fromQuId);
    conversion.setToQuId(toQuId);
    conversion.setFactor(factor);
    dlHelper.post(
        grocyApi.getObjects(GrocyApi.ENTITY.QUANTITY_UNIT_CONVERSIONS),
        conversion.getJsonFromConversion(debug, TAG),
        response -> onFinished.run(),
        error -> {
          if (debug) {
            Log.w(TAG, "createQuickQuantityUnitConversion: failed for product " + productId
                + ": " + describeVolleyError(error));
          }
          showMessage(R.string.msg_quick_packaging_content_failed);
          onFinished.run();
        }
    );
  }

  /**
   * Links the barcode this product was created from (if any) to the newly created product,
   * before uploading any pending barcodes. Without this, a scanned-but-unknown barcode is never
   * actually attached to the product created for it, so a repeat scan of the same barcode could
   * never resolve to it and Grocy's own per-barcode fields (qu_id/amount/shopping_location_id/
   * last_price/note) would never get a row to be reused from. Only the bare barcode string is
   * sent, plus - now - amount/qu_id, but ONLY when {@code packagingQuId} was explicitly resolved
   * from the user's confirmed selection in the quick packaging/content entry card; otherwise
   * those fields stay null and get filled in later, by the user, through the normal barcode-edit
   * form or purchase flow, exactly as before this feature existed.
   */
  private void linkScannedBarcodeAndUploadPending(
      int productId, @Nullable Integer packagingQuId, Runnable onFinished
  ) {
    if (productId < 0 || isBlank(scannedBarcode) || isAlreadyCoveredByPendingBarcode()) {
      uploadBarcodesIfNecessary(productId, onFinished);
      return;
    }
    // Never blindly create a second row for a barcode Grocy already knows about (it enforces
    // barcode uniqueness): check the already-loaded barcode list first. If it already belongs to
    // this exact product, the work is already done. If it belongs to a DIFFERENT product, that
    // existing assignment is left untouched - it is not ours to reassign or overwrite.
    ProductBarcode existing = ProductBarcode.getFromBarcode(productBarcodes, scannedBarcode);
    if (existing != null) {
      if (debug && existing.getProductIdInt() != productId) {
        Log.w(TAG, "linkScannedBarcodeAndUploadPending: barcode " + scannedBarcode
            + " already belongs to product " + existing.getProductIdInt()
            + ", not linking it to product " + productId);
      }
      uploadBarcodesIfNecessary(productId, onFinished);
      return;
    }
    ProductBarcode productBarcode = new ProductBarcode();
    productBarcode.setProductIdInt(productId);
    productBarcode.setBarcode(scannedBarcode);
    if (packagingQuId != null) {
      productBarcode.setAmount("1");
      productBarcode.setQuId(String.valueOf(packagingQuId));
    }
    dlHelper.post(
        grocyApi.getObjects(GrocyApi.ENTITY.PRODUCT_BARCODES),
        productBarcode.getJsonFromProductBarcode(debug, TAG),
        response -> uploadBarcodesIfNecessary(productId, onFinished),
        error -> {
          // Never blocks the already-successful product save: the product just keeps no
          // linked barcode, exactly like before this feature existed.
          if (debug) {
            Log.w(TAG, "linkScannedBarcodeAndUploadPending: failed to link barcode "
                + scannedBarcode + " to product " + productId + ": " + describeVolleyError(error));
          }
          uploadBarcodesIfNecessary(productId, onFinished);
        }
    );
  }

  private boolean isAlreadyCoveredByPendingBarcode() {
    List<PendingProductBarcode> pending = pendingProductBarcodesLive.getValue();
    if (pending == null || scannedBarcode == null) {
      return false;
    }
    for (PendingProductBarcode pendingProductBarcode : pending) {
      if (scannedBarcode.equals(pendingProductBarcode.getBarcode())) {
        return true;
      }
    }
    return false;
  }

  private void uploadBarcodesIfNecessary(int productId, Runnable onFinished) {
    List<PendingProductBarcode> pendingProductBarcodes = pendingProductBarcodesLive.getValue();
    if (pendingProductBarcodes == null || pendingProductBarcodes.isEmpty() || productId < 0) {
      onFinished.run();
      return;
    }
    NetworkQueue queue = dlHelper.newQueue(
        updated -> {
          pendingProductBarcodesLive.setValue(null);
          onFinished.run();
        }, error -> onFinished.run()
    );
    for (PendingProductBarcode pendingProductBarcode : pendingProductBarcodes) {
      pendingProductBarcode.setPendingProductId(productId);
      queue.append(ProductBarcode.addProductBarcode(
          dlHelper,
          pendingProductBarcode.getJsonFromProductBarcode(debug, TAG),
          null, null
      ));
    }
    if (queue.getSize() == 0) {
      onFinished.run();
      return;
    }
    queue.start();
  }

  /**
   * Downloads the previously looked-up Open Food Facts picture (if any) and stores it as the
   * real Grocy product picture, but only after the product itself was already created
   * successfully. This must never block or affect saving the product: it runs fire-and-forget
   * and any failure (download or upload) is only logged, leaving the product without a picture.
   * Never guesses a picture: only a validated OFF image URL is used, no search, no fallback
   * provider. Never overwrites a picture the user already picked manually (camera/clipboard) in
   * the Optional category before saving.
   */
  private void handleOffPictureUploadIfNecessary(int productId, @Nullable String existingPictureFileName) {
    String imageUrl = offImageUrlLive.getValue();
    if (productId < 0 || !isBlank(existingPictureFileName) || isDemoInstance()
        || !isValidOffImageUrl(imageUrl)) {
      return;
    }
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executor.execute(() -> downloadAndUploadOffPicture(executor, productId, imageUrl));
  }

  private static boolean isValidOffImageUrl(@Nullable String imageUrl) {
    if (isBlank(imageUrl) || !imageUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
      return false;
    }
    Uri uri = Uri.parse(imageUrl);
    return OFF_IMAGE_HOST.equals(uri.getHost());
  }

  // Runs on a background thread (the caller's single-thread executor). Uses Glide's submit()/
  // FutureTarget instead of a Target/into(): that keeps the download itself off the main thread
  // (no Handler hand-off needed) and, unlike a Target callback, guarantees the returned Bitmap
  // stays valid/unrecycled until we explicitly clear() it below - avoiding a race with Glide's
  // bitmap pool while it is still being scaled/compressed.
  private void downloadAndUploadOffPicture(ExecutorService executor, int productId, String imageUrl) {
    FutureTarget<Bitmap> future = Glide.with(getApplication()).asBitmap().load(imageUrl).submit();
    byte[] imageArray = null;
    try {
      imageArray = PictureUtil.convertBitmapToByteArray(PictureUtil.scaleBitmap(future.get()));
    } catch (Throwable t) {
      // Deliberately broad: the remote image is fully attacker/server-controlled (dimensions,
      // corrupt data, OOM on huge images) and must never be able to crash the app or leave the
      // executor thread un-shut-down. Product creation already succeeded before this runs, so
      // the product simply keeps no picture.
      if (debug) {
        Log.i(TAG, "downloadAndUploadOffPicture: OFF picture download/scale failed for product "
            + productId + ": " + t);
      }
    } finally {
      Glide.with(getApplication()).clear(future);
      executor.shutdown();
    }
    if (imageArray != null) {
      byte[] finalImageArray = imageArray;
      new Handler(Looper.getMainLooper()).post(() -> uploadOffPicture(productId, finalImageArray));
    }
  }

  private void uploadOffPicture(int productId, byte[] imageArray) {
    // Dedicated, short-lived helper instead of the field dlHelper: its requests must not share
    // the uuid tag that gets cancelled in onCleared() when this ViewModel is destroyed right
    // after "save & close" navigates away.
    DownloadHelper pictureDlHelper = new DownloadHelper(getApplication(), TAG, null, null);
    String filename = PictureUtil.createImageFilename();
    pictureDlHelper.putFile(
        grocyApi.getProductPicture(filename),
        imageArray,
        () -> linkOffPictureToProduct(pictureDlHelper, productId, filename),
        error -> {
          if (debug) {
            Log.w(TAG, "uploadOffPicture: OFF picture upload failed: " + error);
          }
        }
    );
  }

  private void linkOffPictureToProduct(DownloadHelper pictureDlHelper, int productId,
      String filename) {
    JSONObject jsonObject = new JSONObject();
    try {
      jsonObject.put("picture_file_name", filename);
    } catch (JSONException e) {
      if (debug) {
        Log.w(TAG, "linkOffPictureToProduct: building request failed, deleting orphaned"
            + " picture " + filename + ": " + e);
      }
      deleteOrphanedOffPicture(pictureDlHelper, filename);
      return;
    }
    pictureDlHelper.put(
        grocyApi.getObject(GrocyApi.ENTITY.PRODUCTS, productId),
        jsonObject,
        response -> {
          if (debug) {
            Log.i(TAG, "linkOffPictureToProduct: linked OFF picture to product " + productId);
          }
          // Keep the in-memory product consistent if this ViewModel/session is still alive and
          // still looking at the same product (e.g. "save" without closing switched to edit
          // mode) - otherwise a save triggered from the stale local copy would blank the
          // picture_file_name out again server-side.
          Product currentProduct = formData.getProductLive().getValue();
          if (currentProduct != null && currentProduct.getId() == productId) {
            currentProduct.setPictureFileName(filename);
          }
        },
        error -> {
          if (debug) {
            Log.w(TAG, "linkOffPictureToProduct: failed to link OFF picture to product "
                + productId + ", deleting orphaned picture " + filename + ": " + error);
          }
          deleteOrphanedOffPicture(pictureDlHelper, filename);
        }
    );
  }

  // Best-effort cleanup so a failed link step doesn't leave an unreferenced file behind on the
  // Grocy server forever. Its own failure is only logged: we already logged the original error
  // that triggered this cleanup, and there is nothing more useful this app can do about it.
  private void deleteOrphanedOffPicture(DownloadHelper pictureDlHelper, String filename) {
    pictureDlHelper.delete(
        grocyApi.getProductPicture(filename),
        response -> {},
        error -> {
          if (debug) {
            Log.w(TAG, "deleteOrphanedOffPicture: failed to delete orphaned picture "
                + filename + ": " + error);
          }
        }
    );
  }

  public void deleteProduct(int productId) {
    dlHelper.delete(
        grocyApi.getObject(GrocyApi.ENTITY.PRODUCTS, productId),
        response -> sendEvent(Event.NAVIGATE_UP),
        error -> showMessage(getString(R.string.error_undefined))
    );
  }

  public MutableLiveData<List<PendingProductBarcode>> getPendingProductBarcodesLive() {
    return pendingProductBarcodesLive;
  }

  private void removeBarcodesWhichExistOnline(List<ProductBarcode> productBarcodes) {
    if (pendingProductBarcodesLive.getValue() == null) return;
    ArrayList<String> barcodeStrings = new ArrayList<>();
    for (ProductBarcode barcode : productBarcodes) {
      barcodeStrings.add(barcode.getBarcode());
    }
    ArrayList<PendingProductBarcode> filteredBarcodes = new ArrayList<>();
    for (PendingProductBarcode pendingProductBarcode : pendingProductBarcodesLive.getValue()) {
      if (barcodeStrings.contains(pendingProductBarcode.getBarcode())) continue;
      filteredBarcodes.add(pendingProductBarcode);
    }
    if (filteredBarcodes.isEmpty()) {
      pendingProductBarcodesLive.setValue(null);
    } else {
      pendingProductBarcodesLive.setValue(filteredBarcodes);
    }
  }

  public boolean isForceSaveWithClose() {
    return forceSaveWithClose;
  }

  @NonNull
  public MutableLiveData<Boolean> getIsLoadingLive() {
    return isLoadingLive;
  }

  @NonNull
  public MutableLiveData<InfoFullscreen> getInfoFullscreenLive() {
    return infoFullscreenLive;
  }

  @Override
  protected void onCleared() {
    quickContentAmountLive.removeObserver(quickContentAmountObserver);
    dlHelper.destroy();
    super.onCleared();
  }

  public static class MasterProductViewModelFactory implements ViewModelProvider.Factory {

    private final Application application;
    private final MasterProductFragmentArgs args;

    public MasterProductViewModelFactory(
        Application application,
        MasterProductFragmentArgs args
    ) {
      this.application = application;
      this.args = args;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      return (T) new MasterProductViewModel(application, args);
    }
  }
}
