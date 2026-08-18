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
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.FutureTarget;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;
import xyz.zedler.patrick.grocy.Constants;
import xyz.zedler.patrick.grocy.Constants.ACTION;
import xyz.zedler.patrick.grocy.Constants.PREF;
import xyz.zedler.patrick.grocy.Constants.SETTINGS.STOCK;
import xyz.zedler.patrick.grocy.Constants.SETTINGS_DEFAULT;
import xyz.zedler.patrick.grocy.R;
import xyz.zedler.patrick.grocy.api.GrocyApi;
import xyz.zedler.patrick.grocy.form.FormDataMasterProduct;
import xyz.zedler.patrick.grocy.form.FormDataMasterProductCatQuantityUnit;
import xyz.zedler.patrick.grocy.fragment.MasterProductFragmentArgs;
import xyz.zedler.patrick.grocy.fragment.bottomSheetDialog.DateBottomSheet;
import xyz.zedler.patrick.grocy.helper.DownloadHelper;
import xyz.zedler.patrick.grocy.model.Event;
import xyz.zedler.patrick.grocy.model.InfoFullscreen;
import xyz.zedler.patrick.grocy.model.Location;
import xyz.zedler.patrick.grocy.model.PendingProductBarcode;
import xyz.zedler.patrick.grocy.model.Product;
import xyz.zedler.patrick.grocy.model.ProductBarcode;
import xyz.zedler.patrick.grocy.model.ProductDetails;
import xyz.zedler.patrick.grocy.model.ProductGroup;
import xyz.zedler.patrick.grocy.model.QuantityUnit;
import xyz.zedler.patrick.grocy.model.QuantityUnitConversion;
import xyz.zedler.patrick.grocy.model.Store;
import xyz.zedler.patrick.grocy.repository.MasterProductRepository;
import xyz.zedler.patrick.grocy.util.ArrayUtil;
import xyz.zedler.patrick.grocy.util.DateUtil;
import xyz.zedler.patrick.grocy.util.EnergyConversionUtil;
import xyz.zedler.patrick.grocy.util.NumUtil;
import xyz.zedler.patrick.grocy.util.NutrientBasisUtil;
import xyz.zedler.patrick.grocy.util.OffProductGroupUtil;
import xyz.zedler.patrick.grocy.util.PictureUtil;
import xyz.zedler.patrick.grocy.util.PrefsUtil;
import xyz.zedler.patrick.grocy.util.PurchaseDueDateUtil;
import xyz.zedler.patrick.grocy.util.PurchasePriceUtil;
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
  private final DateUtil dateUtil;
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
  // "Weitere Einstellungen" - the classic sub-screens (Optionale Eigenschaften, Standort,
  // Fälligkeitsdatum, Mengeneinheiten, Menge, Barcodes, Umrechnungen), collapsed by default so
  // the main quick flow only shows the ONE primary inline field for each of these (task docs
  // section 17/18) - same collapse pattern as offExtraInfoExpandedLive above, just a second,
  // independent toggle.
  private final MutableLiveData<Boolean> advancedSettingsExpandedLive;
  private final MutableLiveData<String> offPackagingTypeLive;
  private final MutableLiveData<String> offPackagingMaterialLive;
  private final String scannedBarcode;

  // Origin + data passed through from ChooseProductViewModel's OFF lookup (see
  // ChooseProductFragment#createNewProduct) - only ever used for the genuinely-new-scanned-
  // barcode branch below (never edit/clone), exactly like the other quick-card OFF fields above.
  private final boolean fromPurchase;
  @Nullable
  private final List<String> offCategoriesTags;
  private final boolean offNutritionUnreliable;
  private List<ProductGroup> productGroups;

  // "Dieser Einkauf" - merged first-purchase section, shown only when this product creation was
  // reached from the Purchase flow's unknown-barcode scan (fromPurchase) for a genuinely new,
  // non-cloned product (same guard as showQuickPackagingEntryLive below) - see task docs section
  // J/L/O. Booking itself (bookPurchase()) reuses the exact same Grocy "add to stock" endpoint
  // (GrocyApi#purchaseProduct) the classic PurchaseFragment/PurchaseViewModel/FormDataPurchase use,
  // but is intentionally a separate, independent code path: it never touches FormDataPurchase or
  // PurchaseViewModel, so the classic Purchase screen (and Consume/Transfer/Inventory, which never
  // set fromPurchase) are entirely unaffected by this feature.
  private final MutableLiveData<Boolean> showPurchaseSectionLive;
  private final MutableLiveData<String> purchaseAmountLive;
  private final MutableLiveData<String> purchaseDueDateLive;
  private final MutableLiveData<String> purchasePriceLive;
  private final MutableLiveData<Boolean> purchaseIsTotalPriceLive;
  private final MutableLiveData<String> purchaseNoteLive;
  // Non-null exactly once the product itself was successfully created - lets a failed purchase
  // booking be retried (retryPurchase()) without ever creating the product a second time.
  @Nullable
  private Integer createdProductIdForPurchase;
  private final MutableLiveData<Boolean> purchaseFailedLive;
  private boolean purchaseBooked;
  private boolean purchaseInProgress;

  // Derived, read-only LiveData bound directly in fragment_master_product.xml. Each of these is
  // constructed EXACTLY ONCE, here as a field (assigned in the constructor, once their sources
  // below are ready) and never re-created by its getter - returning a freshly-built
  // Transformations.map()/MediatorLiveData instance on every getter call instead would make data
  // binding's LiveData auto-registration see a "new" object on every single re-evaluation (it
  // compares object identity), tearing down and re-creating the observation - and since these
  // sources typically already hold a value the moment a NEW product's fields are set up, that
  // resubscription re-fires synchronously each time too, in a loop that never settles. This is
  // exactly what made opening "new product" crash: this constructor-time, one-time assignment is
  // the fix (see the root-cause report for this crash).
  private final LiveData<String> offNutrientBasisLabelLive;
  private final LiveData<String> offEnergyBasisLive;
  private final LiveData<Integer> dueDateTypeLive;
  private final LiveData<String> purchaseDueDateTextLive;
  private final LiveData<String> productGroupNameLive;
  private final LiveData<String> locationNameLive;
  private final LiveData<String> storeNameLive;
  private final LiveData<String> summaryAmountContentLive;
  private final LiveData<String> summaryDueDateLive;
  private final LiveData<String> purchaseSummaryPriceLive;

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
  private final int decimalPlacesPriceDisplay;
  private final String currency;

  private List<Product> products;
  private List<ProductBarcode> productBarcodes;
  private List<PendingProductBarcode> pendingProductBarcodes;
  private List<QuantityUnit> quantityUnits;
  // Loaded for the inline "Produktzuordnung"/"Dieser Einkauf" pickers on the main page (task
  // docs section L/N) - these write directly into the SAME shared Product object as everything
  // else (see setProductGroup()/setLocation()/setStore() below), never a second data holder.
  private List<Location> locations;
  private List<Store> stores;

  // Tracks which quantity unit the quick packaging/content entry card itself last applied to
  // each of these product fields (null = never applied by the quick card yet), so a later
  // manual choice on the classic quantity unit screen is never silently overridden again - see
  // syncQuickPackagingToProduct()/QuickPackagingSyncUtil#isQuickOwned().
  private Integer lastQuickAppliedStockQuId;
  private Integer lastQuickAppliedPurchaseQuId;
  private Integer lastQuickAppliedPriceQuId;
  private Integer lastQuickAppliedConsumeQuId;
  // The product's own ambient quantity unit preset (e.g. a "default new-product quantity unit"
  // from Settings) at the exact moment it was constructed, before anything - quick card or user -
  // could have touched it. Captured once, only for the genuinely-new-product branch these fields
  // are ever relevant for; see QuickPackagingSyncUtil#isQuickOwned() for why this is needed.
  private Integer initialPresetStockQuId;
  private Integer initialPresetPurchaseQuId;
  private Integer initialPresetPriceQuId;
  private Integer initialPresetConsumeQuId;
  // Same "quick owned" principle as the quantity unit fields above (see
  // QuickPackagingSyncUtil#isQuickOwned), applied to Product.calories: the OFF-derived energy
  // value (see applyOffEnergyIfPossible()) is only ever auto-(re)applied while the field still
  // holds exactly what this feature itself last put there (or its untouched "0" construction
  // default) - never once the user has typed their own value in the classic "Optionale
  // Eigenschaften" energy field.
  private String lastAppliedCaloriesValue;
  // Same principle again, for Product.productGroupId (see applyOffProductGroupIfPossible()):
  // captured once at construction (the ambient "default product group" preset from Settings, or
  // null), an OFF-detected product group is only ever auto-applied while the field still holds
  // exactly this untouched value - never once the user picked something themselves.
  private String initialPresetProductGroupId;
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
    dateUtil = new DateUtil(application);
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
    offPackagingMaterialLive = new MutableLiveData<>(args.getOffPackagingMaterial());
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
    advancedSettingsExpandedLive = new MutableLiveData<>(false);
    scannedBarcode = args.getBarcode();
    fromPurchase = args.getFromPurchase();
    offNutritionUnreliable = args.getOffNutritionUnreliable();
    offCategoriesTags = !isBlank(args.getOffCategoriesTagsJoined())
        ? Arrays.asList(args.getOffCategoriesTagsJoined().split(",")) : null;
    purchaseFailedLive = new MutableLiveData<>(false);
    purchaseAmountLive = new MutableLiveData<>();
    purchaseDueDateLive = new MutableLiveData<>();
    purchasePriceLive = new MutableLiveData<>();
    purchaseIsTotalPriceLive = new MutableLiveData<>(false);
    purchaseNoteLive = new MutableLiveData<>();

    maxDecimalPlacesAmount = sharedPrefs.getInt(
        STOCK.DECIMAL_PLACES_AMOUNT,
        SETTINGS_DEFAULT.STOCK.DECIMAL_PLACES_AMOUNT
    );
    decimalPlacesPriceDisplay = sharedPrefs.getInt(
        STOCK.DECIMAL_PLACES_PRICES_DISPLAY,
        SETTINGS_DEFAULT.STOCK.DECIMAL_PLACES_PRICES_DISPLAY
    );
    currency = sharedPrefs.getString(Constants.PREF.CURRENCY, "");
    // Explicitly excludes the "clone" case (args.getProduct()!=null or a valid args.getProductId())
    // even though hasScannedBarcode() is already false there today (the copy-existing-product
    // flow never passes a barcode) - the exported grocy:// deep link technically accepts
    // productId and barcode together, so this must not rely on that only being true in practice.
    boolean isClone = args.getProduct() != null || NumUtil.isStringInt(args.getProductId());
    boolean isGenuinelyNewScannedProduct = !isActionEdit() && !isClone && hasScannedBarcode();
    showQuickPackagingEntryLive = new MutableLiveData<>(isGenuinelyNewScannedProduct);
    // The merged "Dieser Einkauf" section is a strict subset of the quick packaging card's own
    // guard: it must never show for edit/clone either, AND only when this creation actually came
    // from the Purchase flow's unknown-barcode scan (fromPurchase) - Consume/Transfer/Inventory/
    // classic master-data creation never set that flag, so they are entirely unaffected (task
    // docs section P).
    showPurchaseSectionLive = new MutableLiveData<>(isGenuinelyNewScannedProduct && fromPurchase);
    if (isGenuinelyNewScannedProduct && fromPurchase) {
      purchaseAmountLive.setValue(NumUtil.trimAmount(1, maxDecimalPlacesAmount));
    }
    String detectedPackagingLabel = args.getOffPackagingType();
    quickPackagingLive = new MutableLiveData<>(
        detectedPackagingLabel != null && QUICK_PACKAGING_LABELS.contains(detectedPackagingLabel)
            ? detectedPackagingLabel : null
    );
    // Already parsed/resolved once upstream, in ChooseProductViewModel (see
    // OpenFoodFactsProduct#getContentAmount(), which itself prefers OFF's structured
    // product_quantity/product_quantity_unit fields over the free-text quantity field) - the
    // canonical unit token (g/kg/mg/ml/l/cl) travels through as-is, never re-parsed from raw text
    // here.
    if (NumUtil.isStringDouble(args.getOffContentAmount()) && args.getOffContentUnit() != null) {
      quickContentAmountLive = new MutableLiveData<>(
          NumUtil.trimAmount(NumUtil.toDouble(args.getOffContentAmount()), maxDecimalPlacesAmount)
      );
      quickContentUnitLive = new MutableLiveData<>(args.getOffContentUnit());
    } else {
      quickContentAmountLive = new MutableLiveData<>();
      quickContentUnitLive = new MutableLiveData<>();
    }
    quickPackagingQuMissingLive = new MutableLiveData<>(false);
    quickContentQuMissingLive = new MutableLiveData<>(false);

    // Built ONCE here, right after everything they read from is already assigned above - see the
    // field-level comment for why this must never happen inside the getters themselves.
    offNutrientBasisLabelLive = Transformations.map(quickContentUnitLive, unit -> {
      NutrientBasisUtil.Basis basis = NutrientBasisUtil.resolve(unit);
      if (basis == null) {
        return null;
      }
      return basis == NutrientBasisUtil.Basis.PER_100_ML
          ? getString(R.string.subtitle_off_nutrients_per_100ml)
          : getString(R.string.subtitle_off_nutrients_per_100g);
    });
    // Same NutrientBasisUtil resolution as offNutrientBasisLabelLive above, just formatted as a
    // plain "100 g"/"100 ml" label for the energy preview row - so both rows can never disagree
    // on liquid vs. solid again (this was the reported bug: the energy preview used to hardcode
    // "100 g" via a fixed string template regardless of this basis).
    offEnergyBasisLive = Transformations.map(quickContentUnitLive, unit -> {
      NutrientBasisUtil.Basis basis = NutrientBasisUtil.resolve(unit);
      if (basis == null) {
        return null;
      }
      return basis == NutrientBasisUtil.Basis.PER_100_ML
          ? getString(R.string.label_off_basis_100ml)
          : getString(R.string.label_off_basis_100g);
    });
    // Mirrors FormDataPurchase#dueDateTextLive exactly: an unset date reads "Nichts ausgewählt"
    // (never a real date, and never defaulted to "today" - that would resurrect the bug where an
    // empty MHD silently became today's date), the "never overdue" sentinel reads "Nie überfällig",
    // and any real date is localized. The raw purchaseDueDateLive value itself is left untouched
    // (still what buildPurchaseJson()/PurchaseDueDateUtil read) - this is a display-only mapping.
    purchaseDueDateTextLive = Transformations.map(purchaseDueDateLive, date -> {
      if (isBlank(date)) {
        return getString(R.string.subtitle_none_selected);
      } else if (date.equals(Constants.DATE.NEVER_OVERDUE)) {
        return getString(R.string.subtitle_never_overdue);
      } else {
        return dateUtil.getLocalizedDate(date, DateUtil.FORMAT_MEDIUM);
      }
    });
    dueDateTypeLive = Transformations.map(
        formData.getProductLive(), p -> p != null ? p.getDueDateTypeInt() : 1
    );
    productGroupNameLive = Transformations.map(formData.getProductLive(), p -> {
      if (p == null || productGroups == null || !NumUtil.isStringInt(p.getProductGroupId())) {
        return null;
      }
      int id = Integer.parseInt(p.getProductGroupId());
      for (ProductGroup productGroup : productGroups) {
        if (productGroup.getId() == id) {
          return productGroup.getName();
        }
      }
      return null;
    });
    locationNameLive = Transformations.map(formData.getProductLive(), p -> {
      if (p == null || locations == null || !NumUtil.isStringInt(p.getLocationId())) {
        return null;
      }
      int id = Integer.parseInt(p.getLocationId());
      for (Location location : locations) {
        if (location.getId() == id) {
          return location.getName();
        }
      }
      return null;
    });
    storeNameLive = Transformations.map(formData.getProductLive(), p -> {
      if (p == null || stores == null || !NumUtil.isStringInt(p.getStoreId())) {
        return null;
      }
      int id = Integer.parseInt(p.getStoreId());
      for (Store store : stores) {
        if (store.getId() == id) {
          return store.getName();
        }
      }
      return null;
    });
    MediatorLiveData<String> summaryAmountContent = new MediatorLiveData<>();
    Observer<Object> updateSummaryAmountContent =
        ignored -> summaryAmountContent.setValue(formatSummaryAmountContent());
    summaryAmountContent.addSource(purchaseAmountLive, updateSummaryAmountContent);
    summaryAmountContent.addSource(quickPackagingLive, updateSummaryAmountContent);
    summaryAmountContent.addSource(quickContentAmountLive, updateSummaryAmountContent);
    summaryAmountContent.addSource(quickContentUnitLive, updateSummaryAmountContent);
    summaryAmountContentLive = summaryAmountContent;
    MediatorLiveData<String> summaryDueDate = new MediatorLiveData<>();
    Observer<Object> updateSummaryDueDate =
        ignored -> summaryDueDate.setValue(formatSummaryDueDate());
    summaryDueDate.addSource(formData.getProductLive(), updateSummaryDueDate);
    summaryDueDate.addSource(purchaseDueDateLive, updateSummaryDueDate);
    summaryDueDateLive = summaryDueDate;
    // Display-only German-locale price formatting for the Summary row (task: "2.25" -> "2,25 €",
    // or "2,25 € pro Flasche" for a per-package price) - purchasePriceLive itself (the two-way
    // bound EditText value, always "."-separated per NumUtil's app-wide convention) and everything
    // that reads/saves it (buildPurchaseJson(), computePurchasePricePerStockUnit()) stay untouched.
    MediatorLiveData<String> purchaseSummaryPrice = new MediatorLiveData<>();
    Observer<Object> updateSummaryPrice =
        ignored -> purchaseSummaryPrice.setValue(formatSummaryPrice());
    purchaseSummaryPrice.addSource(purchasePriceLive, updateSummaryPrice);
    purchaseSummaryPrice.addSource(purchaseIsTotalPriceLive, updateSummaryPrice);
    purchaseSummaryPrice.addSource(quickPackagingLive, updateSummaryPrice);
    purchaseSummaryPriceLive = purchaseSummaryPrice;

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
      initialPresetConsumeQuId = product.getQuIdConsumeInt();
      initialPresetProductGroupId = product.getProductGroupId();
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
   * Household stock model (see task docs section B): the PACKAGING unit (e.g. "Flasche") - never
   * the content unit (e.g. "ml") - is the single quantity unit for STOCK, PURCHASE, CONSUME and
   * PRICE alike. A household is never forced to book stock/purchases/consumption in individual
   * millilitres or grams; "1 Flasche" bought is "1 Flasche" in stock is "1 Flasche" consumed once
   * empty. The confirmed content amount/unit is preserved separately, purely as a
   * {@link xyz.zedler.patrick.grocy.model.QuantityUnitConversion} from packaging to content (see
   * {@link #applyQuickPackagingAndContent}) - it never influences which unit stock is tracked in.
   * <p>
   * Applies only:
   * - for a genuinely new, non-cloned product with the quick card actually showing (see
   *   {@link #getShowQuickPackagingEntryLive()}) - never for editing/cloning, which already have
   *   deliberately chosen units that must never be touched automatically,
   * - all four fields (stock/purchase/consume/price) are set to the confirmed PACKAGING unit,
   * - a field already holding something other than what the quick card itself last applied there
   *   (or, before it applied anything yet, other than the product's own original ambient preset -
   *   see {@link QuickPackagingSyncUtil#isQuickOwned}) is NEVER overwritten - i.e. the user picked
   *   something different on the classic quantity unit screen - so a manual choice is never
   *   silently reverted by a later quick-card change.
   * Never creates a new Grocy quantity unit itself and never guesses: only unit ids already
   * resolved from the user's current, confirmed chip/text selections (see resolveQuickQuId())
   * are ever applied. Also (re)applies the OFF-derived energy value and product group whenever
   * called - see {@link #applyOffEnergyIfPossible()}/{@link #applyOffProductGroupIfPossible()} -
   * since both depend on some of the same inputs (content amount/unit) this method already reacts
   * to, and neither needs quantityUnits to be loaded, unlike the quantity unit sync below.
   */
  private void syncQuickPackagingToProduct() {
    applyOffEnergyIfPossible();
    applyOffProductGroupIfPossible();
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
      if (QuickPackagingSyncUtil.isQuickOwned(
          product.getQuIdStockInt(), initialPresetStockQuId, lastQuickAppliedStockQuId
      ) && product.getQuIdStockInt() != packagingQuId) {
        product.setQuIdStock(packagingQuId);
        changed = true;
      }
      if (QuickPackagingSyncUtil.isQuickOwned(
          product.getQuIdConsumeInt(), initialPresetConsumeQuId, lastQuickAppliedConsumeQuId
      ) && product.getQuIdConsumeInt() != packagingQuId) {
        product.setQuIdConsume(packagingQuId);
        changed = true;
      }
      lastQuickAppliedPurchaseQuId = packagingQuId;
      lastQuickAppliedPriceQuId = packagingQuId;
      lastQuickAppliedStockQuId = packagingQuId;
      lastQuickAppliedConsumeQuId = packagingQuId;
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

  /**
   * Converts OFF's energy-per-100(g/ml) value into calories per package/stock unit (see
   * {@link EnergyConversionUtil}) and writes it LIVE into the real {@code Product.calories} field
   * whenever this is possible with mathematical certainty - never a guess (task docs section G):
   * only for a genuinely new, non-cloned, scanned-barcode product; only while OFF itself does not
   * flag a nutrition-related data quality problem for this product (see
   * OpenFoodFactsProduct#hasNutritionDataQualityWarning, threaded through as
   * {@code offNutritionUnreliable}); only while both a valid OFF energy value AND a valid,
   * dimensionally-recognized content amount/unit are present; and only while the calories field
   * still holds exactly what THIS method itself last put there (or its untouched "0" construction
   * default) - a value the user already typed manually in the classic "Optionale Eigenschaften"
   * energy field is never silently overwritten (same ownership principle as the quantity unit
   * fields, see {@link QuickPackagingSyncUtil#isQuickOwned}). Recomputed every time the content
   * amount/unit changes, so editing "0,5 l" to "0,75 l" on the quick card live-updates the energy
   * value too (task docs section U) - if it later becomes uncomputable again (e.g. content amount
   * cleared), the field is simply left at its last computed value rather than reset to "0".
   */
  private void applyOffEnergyIfPossible() {
    if (isActionEdit()) {
      return;
    }
    Boolean showQuickCard = showQuickPackagingEntryLive.getValue();
    if (showQuickCard == null || !showQuickCard || offNutritionUnreliable) {
      return;
    }
    String energyStr = offEnergyPer100gLive.getValue();
    String contentAmountStr = quickContentAmountLive.getValue();
    String contentUnit = quickContentUnitLive.getValue();
    if (!NumUtil.isStringDouble(energyStr) || !NumUtil.isStringDouble(contentAmountStr)) {
      return;
    }
    Integer calories = EnergyConversionUtil.caloriesPerPackage(
        NumUtil.toDouble(energyStr), NumUtil.toDouble(contentAmountStr), contentUnit
    );
    if (calories == null) {
      return;
    }
    Product product = formData.getProductLive().getValue();
    if (product == null) {
      return;
    }
    String current = product.getCalories();
    boolean ownedByUs = lastAppliedCaloriesValue != null
        ? Objects.equals(current, lastAppliedCaloriesValue)
        : current == null || current.isBlank() || "0".equals(current);
    if (!ownedByUs) {
      return; // user already edited the energy field manually -> never override
    }
    String newValue = String.valueOf(calories);
    lastAppliedCaloriesValue = newValue;
    if (!newValue.equals(current)) {
      product.setCalories(newValue);
      formData.getProductLive().setValue(product);
    }
  }

  /**
   * Prefills the real {@code Product.productGroupId} field from OFF's categories, but only while
   * an exact, unambiguous match to an EXISTING Grocy product group was found (see
   * {@link OffProductGroupUtil#detectProductGroupId}) - never a fuzzy guess, never creates a new
   * product group (task docs section H). Applies only for a genuinely new, non-cloned product
   * (never edit), only once the user's product groups are actually loaded, and only while the
   * field still holds exactly its untouched ambient preset from construction (same ownership
   * principle as the quantity unit fields) - a product group the user already picked themselves
   * on the classic "Optionale Eigenschaften" screen is never silently overridden.
   */
  private void applyOffProductGroupIfPossible() {
    if (isActionEdit() || productGroups == null || offCategoriesTags == null
        || offCategoriesTags.isEmpty()) {
      return;
    }
    Product product = formData.getProductLive().getValue();
    if (product == null || !Objects.equals(product.getProductGroupId(), initialPresetProductGroupId)) {
      return;
    }
    Integer groupId = OffProductGroupUtil.detectProductGroupId(offCategoriesTags, productGroups);
    if (groupId == null) {
      return;
    }
    product.setProductGroupId(String.valueOf(groupId));
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

  public LiveData<String> getOffEnergyBasisLive() {
    return offEnergyBasisLive;
  }

  public LiveData<String> getPurchaseDueDateTextLive() {
    return purchaseDueDateTextLive;
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

  public LiveData<String> getOffPackagingMaterialLive() {
    return offPackagingMaterialLive;
  }

  /**
   * "pro 100 g" or "pro 100 ml", correctly determined from the resolved content unit's own
   * dimension (task docs section F) - never hardcoded to "100 g" - or null while that dimension
   * isn't known.
   */
  public LiveData<String> getOffNutrientBasisLabelLive() {
    return offNutrientBasisLabelLive;
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

  public LiveData<Boolean> getAdvancedSettingsExpandedLive() {
    return advancedSettingsExpandedLive;
  }

  public void toggleAdvancedSettingsExpanded() {
    Boolean expanded = advancedSettingsExpandedLive.getValue();
    advancedSettingsExpandedLive.setValue(expanded == null || !expanded);
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

  // "Dieser Einkauf" - see field docs above.

  public LiveData<Boolean> getShowPurchaseSectionLive() {
    return showPurchaseSectionLive;
  }

  public MutableLiveData<String> getPurchaseAmountLive() {
    return purchaseAmountLive;
  }

  public MutableLiveData<String> getPurchaseDueDateLive() {
    return purchaseDueDateLive;
  }

  public MutableLiveData<String> getPurchasePriceLive() {
    return purchasePriceLive;
  }

  public MutableLiveData<Boolean> getPurchaseIsTotalPriceLive() {
    return purchaseIsTotalPriceLive;
  }

  /**
   * Plain setter for the "pro Verpackung"/"Gesamtpreis" radio toggle - needed because a binding
   * lambda cannot call {@code MutableLiveData#setValue} directly on {@code purchaseIsTotalPriceLive}
   * (data binding auto-unwraps the observable LiveData field to its value there), same reason the
   * classic Purchase screen's {@code FormDataPurchase#setIsTotalPriceLive(boolean)} exists.
   */
  public void setPurchaseIsTotalPrice(boolean isTotalPrice) {
    purchaseIsTotalPriceLive.setValue(isTotalPrice);
  }

  public MutableLiveData<String> getPurchaseNoteLive() {
    return purchaseNoteLive;
  }

  public LiveData<Boolean> getPurchaseFailedLive() {
    return purchaseFailedLive;
  }

  /**
   * "MHD" (best-before) or "Verbrauchsdatum" (expiration) - reflects the SAME
   * {@code Product.dueDateType} field the classic Fälligkeitsdatum sub-screen edits (never a
   * second, parallel value): defaults to "1"/MHD for a genuinely new product (see
   * Product(SharedPreferences)), but a product that already has a saved type (edit/clone) keeps
   * exactly that - never guessed from an OFF category (task docs section I).
   */
  public LiveData<Integer> getDueDateTypeLive() {
    return dueDateTypeLive;
  }

  public void setPurchaseDueDateType(int type) {
    Product product = formData.getProductLive().getValue();
    if (product == null || (type != 1 && type != 2)) {
      return;
    }
    product.setDueDateTypeInt(type);
    formData.getProductLive().setValue(product);
  }

  // "Produktzuordnung" (Produktgruppe/Lagerstandort) and "Geschäft" as directly editable fields
  // on the main page (task docs section L/N) - all write straight into the SAME shared Product
  // object as everything else on this screen (never a second data holder), exactly like
  // setPurchaseDueDateType() above. The classic "Optionale Eigenschaften"/"Standort" sub-screens
  // keep working on this same field too (see FormDataMasterProductCatOptional/CatLocation) -
  // whichever was edited last simply wins, there is only ever one Product instance in play.

  @Nullable
  public List<ProductGroup> getProductGroups() {
    return productGroups;
  }

  @Nullable
  public List<Location> getLocations() {
    return locations;
  }

  @Nullable
  public List<Store> getStores() {
    return stores;
  }

  /** Name of the product's currently set product group, or null if none is set/loaded yet. */
  public LiveData<String> getProductGroupNameLive() {
    return productGroupNameLive;
  }

  public void setProductGroup(@Nullable ProductGroup productGroup) {
    Product product = formData.getProductLive().getValue();
    if (product == null) {
      return;
    }
    product.setProductGroupId(
        productGroup != null && productGroup.getId() != -1
            ? String.valueOf(productGroup.getId()) : null
    );
    formData.getProductLive().setValue(product);
  }

  /** Name of the product's currently set storage location, or null if none is set/loaded yet. */
  public LiveData<String> getLocationNameLive() {
    return locationNameLive;
  }

  public void setLocation(@Nullable Location location) {
    Product product = formData.getProductLive().getValue();
    if (product == null) {
      return;
    }
    product.setLocationId(
        location != null && location.getId() != -1 ? String.valueOf(location.getId()) : null
    );
    formData.getProductLive().setValue(product);
  }

  /** Name of the product's currently set (shopping) store, or null if none is set/loaded yet. */
  public LiveData<String> getStoreNameLive() {
    return storeNameLive;
  }

  public void setStore(@Nullable Store store) {
    Product product = formData.getProductLive().getValue();
    if (product == null) {
      return;
    }
    product.setStoreId(store != null && store.getId() != -1 ? String.valueOf(store.getId()) : null);
    formData.getProductLive().setValue(product);
  }

  // Compact "Zusammenfassung" card (task docs section N) - pure display formatting of values
  // that are already entered elsewhere on this same page, never a second source of truth and
  // never any new decision logic. Both are re-derived live whenever any of their inputs change,
  // so the card always reflects the current form state.

  /** "1 Flasche × 0,5 l" - null while no packaging is confirmed yet. */
  public LiveData<String> getSummaryAmountContentLive() {
    return summaryAmountContentLive;
  }

  @Nullable
  private String formatSummaryAmountContent() {
    String packaging = quickPackagingLive.getValue();
    if (isBlank(packaging)) {
      return null;
    }
    String amount = NumUtil.isStringDouble(purchaseAmountLive.getValue())
        ? purchaseAmountLive.getValue() : "1";
    StringBuilder text = new StringBuilder(amount).append(" ").append(packaging);
    String contentAmount = quickContentAmountLive.getValue();
    String contentUnit = quickContentUnitLive.getValue();
    if (NumUtil.isStringDouble(contentAmount) && !isBlank(contentUnit)) {
      text.append(" × ").append(contentAmount).append(" ").append(contentUnit);
    }
    return text.toString();
  }

  /** "MHD: 23.01.2031" / "Verbrauchsdatum: –" - the type mirrors {@link #getDueDateTypeLive()}. */
  public LiveData<String> getSummaryDueDateLive() {
    return summaryDueDateLive;
  }

  /** "2,25 €" / "2,25 € pro Flasche" - see {@link #formatSummaryPrice()}. */
  public LiveData<String> getPurchaseSummaryPriceLive() {
    return purchaseSummaryPriceLive;
  }

  private String formatSummaryDueDate() {
    Product product = formData.getProductLive().getValue();
    String typeLabel = product != null && product.getDueDateTypeInt() == 2
        ? getString(R.string.label_due_date_type_expiration)
        : getString(R.string.label_due_date_type_best_before);
    String date = purchaseDueDateLive.getValue();
    return typeLabel + ": " + (isBlank(date) ? getString(R.string.subtitle_none_selected) : date);
  }

  /**
   * "2,25 €" / "2,25 € pro Flasche" for the Summary row's price display - German-locale comma
   * instead of NumUtil's internal "."-separated convention, and the packaging name appended when
   * the price is per package rather than a total. Display-only: purchasePriceLive itself (what
   * gets saved) is never touched here.
   */
  private String formatSummaryPrice() {
    String priceStr = purchasePriceLive.getValue();
    if (!NumUtil.isStringDouble(priceStr)) {
      return null;
    }
    String priceWithCurrency = NumUtil.trimPrice(NumUtil.toDouble(priceStr), decimalPlacesPriceDisplay)
        .replace(".", ",");
    if (!isBlank(currency)) {
      priceWithCurrency += " " + currency;
    }
    boolean isTotalPrice = Boolean.TRUE.equals(purchaseIsTotalPriceLive.getValue());
    String packaging = quickPackagingLive.getValue();
    if (!isTotalPrice && !isBlank(packaging)) {
      return getString(R.string.property_price_unit_insert, priceWithCurrency, packaging);
    }
    return priceWithCurrency;
  }

  public void increasePurchaseAmount() {
    double current = NumUtil.isStringDouble(purchaseAmountLive.getValue())
        ? NumUtil.toDouble(purchaseAmountLive.getValue()) : 0;
    purchaseAmountLive.setValue(NumUtil.trimAmount(current + 1, maxDecimalPlacesAmount));
  }

  public void decreasePurchaseAmount() {
    if (!NumUtil.isStringDouble(purchaseAmountLive.getValue())) {
      return;
    }
    double current = NumUtil.toDouble(purchaseAmountLive.getValue());
    if (current > 1) {
      purchaseAmountLive.setValue(NumUtil.trimAmount(current - 1, maxDecimalPlacesAmount));
    }
  }

  /**
   * Opens the same {@link xyz.zedler.patrick.grocy.fragment.bottomSheetDialog.DateBottomSheet}
   * the classic Purchase screen uses for its due date field - result comes back via
   * {@link xyz.zedler.patrick.grocy.fragment.MasterProductFragment#selectDueDate(String)}, same
   * as {@code PurchaseFragment#selectDueDate}. No individual concrete date is ever preset here
   * (task docs section I: a scanned barcode never implies a specific real MHD) - the sheet simply
   * opens empty/on today unless the user already picked something on this exact screen before.
   */
  public void showPurchaseDueDateBottomSheet(boolean hasFocus) {
    if (!hasFocus) {
      return;
    }
    Bundle bundle = new Bundle();
    bundle.putString(Constants.ARGUMENT.DEFAULT_DAYS_FROM_NOW, String.valueOf(0));
    bundle.putString(Constants.ARGUMENT.SELECTED_DATE, purchaseDueDateLive.getValue());
    bundle.putInt(DateBottomSheet.DATE_TYPE, DateBottomSheet.DUE_DATE);
    showBottomSheet(new DateBottomSheet(), bundle);
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
      this.productGroups = data.getProductGroups();
      this.locations = data.getLocations();
      this.stores = data.getStores();
      updateQuickQuMissingFlags();
      syncQuickPackagingToProduct();
      formData.getProductNamesLive().setValue(getProductNames(this.products, null));
      // The inline "Produktzuordnung"/"Dieser Einkauf" name displays (product group/location/
      // store) are derived FROM productLive but look up names in these lists - re-emit once the
      // lists have actually arrived so they resolve correctly even when syncQuickPackagingToProduct()
      // above didn't itself need to change anything (e.g. no OFF data at all).
      if (formData.getProductLive().getValue() != null) {
        formData.getProductLive().setValue(formData.getProductLive().getValue());
      }

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
        ProductBarcode.class,
        // Needed for the inline "Produktzuordnung"/"Geschäft" fields on this main page (see
        // getProductGroupNameLive()/getLocationNameLive()/getStoreNameLive()) - without this,
        // this screen relied on whatever these three tables already happened to hold in the
        // local Room cache (e.g. from a previous visit to the classic sub-screens, which DO
        // sync them - see MasterProductCatLocationViewModel/CatOptionalViewModel), which could
        // be empty or stale on first use and made an existing Grocy default (e.g. a configured
        // default storage location) silently fail to show here even though it was already set.
        ProductGroup.class,
        Location.class,
        Store.class
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
    if (saveInProgress || purchaseInProgress) {
      // Guards against a double-tap on the save button firing two POSTs/PUTs: without this,
      // creating a product twice from the same barcode scan would now also link the same
      // scannedBarcode to two different products, making the barcode permanently ambiguous
      // (not just an annoying duplicate product like before this feature existed).
      return;
    }
    if (!isActionEdit() && createdProductIdForPurchase != null
        && Boolean.TRUE.equals(showPurchaseSectionLive.getValue())) {
      // The product itself was already created successfully by a PREVIOUS tap of this exact
      // button - only its purchase booking failed and is still pending (see bookPurchase()/
      // purchaseFailedLive). Tapping "Fertig" again here must never create a second product
      // (task docs section O: "keine doppelten Produkte") - it just retries the one thing that
      // actually still needs to happen, exactly like the dedicated retry action does. Scoped to
      // showPurchaseSectionLive so this can never misfire for a product that was never part of
      // the purchase flow to begin with (createdProductIdForPurchase is set for every new
      // product, not just this one).
      updateProductThenRetryPurchase();
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
            // Deliberately NOT reset here (unlike the edit-product PUT branch above): the
            // product was created, but barcode-linking, the packaging/content conversion and -
            // if applicable - the purchase booking are all still in flight below, and
            // saveInProgress must keep blocking a re-tap until THAT whole chain reaches a
            // terminal state too (see finishSave/bookPurchase's error path further down) -
            // otherwise a fast double-tap during that window could still re-enter this branch
            // and create a second product before createdProductIdForPurchase is even set.
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
            createdProductIdForPurchase = finalObjectId != -1 ? finalObjectId : null;
            // Both the barcode link AND the quick packaging/content follow-up writes must finish
            // BEFORE navigating away: dlHelper.destroy() (ViewModel#onCleared, e.g. once
            // NAVIGATE_UP pops this fragment) cancels any still-in-flight request tagged with
            // this ViewModel's uuid, so firing NAVIGATE_UP while these writes are still pending
            // could silently drop them, leaving the product half-configured (e.g. a stock unit
            // change with no matching conversion, or vice versa).
            Runnable finishSave = withClosing ? () -> {
              saveInProgress = false;
              if (finalObjectId != -1) {
                Bundle bundle = new Bundle();
                bundle.putInt(Constants.ARGUMENT.PRODUCT_ID, finalObjectId);
                sendEvent(Event.SET_PRODUCT_ID, bundle);
              }
              sendEvent(Event.NAVIGATE_UP);
            } : () -> {
              saveInProgress = false;
              actionEditLive.setValue(true);
              product.setId(finalObjectId);
              setCurrentProduct(product);
              sendEvent(Event.TRANSACTION_SUCCESS);
            };
            // One single save from the user's point of view (task docs section O): if this
            // product creation came from the Purchase flow's unknown-barcode scan and a "Dieser
            // Einkauf" amount is confirmed, the first purchase is booked directly here, as the
            // very last step, AFTER the product/barcode/packaging-conversion all already
            // succeeded - never before. If it fails, bookPurchase() shows its own message and
            // deliberately withholds finishSave (no navigation) so the user stays on this screen
            // with a working retry action (retryPurchase()) instead of a silently half-done save.
            Runnable proceedWithBarcodeLinkAndNavigation = () -> linkScannedBarcodeAndUploadPending(
                finalObjectId, finalPackagingQuId, () -> {
                  if (Boolean.TRUE.equals(showPurchaseSectionLive.getValue())
                      && finalObjectId != -1 && isPurchaseAmountValid()) {
                    bookPurchase(finalObjectId, finishSave);
                  } else {
                    finishSave.run();
                  }
                }
            );
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
   * Creates the Grocy {@link QuantityUnitConversion} row (packaging unit -> content unit, factor
   * = content amount, e.g. "1 Flasche = 500 ml") for the user's CONFIRMED quick packaging/content
   * selections (from the quick packaging entry card, visible only for a new, non-cloned product
   * with a scanned barcode). Never invents anything from raw OFF data alone - only what is
   * checked/filled in the form at save time counts, exactly as if the user had typed it
   * themselves (see task docs for getShowQuickPackagingEntryLive()).
   * <p>
   * Household stock model (task docs section B/D): the stock/purchase/consume/price unit is
   * always the PACKAGING unit itself (see syncQuickPackagingToProduct()) - this conversion exists
   * purely so recipes/energy calculations/future features still know how much a package actually
   * contains, never to redirect stock tracking to the content unit.
   * <p>
   * Only Grocy servers >= 4.0 are touched: on older servers the "resolved" conversion lookup this
   * app uses does not consult a product-specific conversion the way >= 4.0 does (see
   * QuantityUnitConversionUtil), so creating one there would silently do nothing useful - the
   * packaging/barcode part (amount + qu_id on the ProductBarcode) still applies regardless of
   * server version, only the conversion itself is skipped.
   * <p>
   * If the product's stock unit is not actually the confirmed packaging unit when this runs - e.g.
   * the user manually picked something else on the (mandatory, for a new product) quantity unit
   * screen after syncQuickPackagingToProduct() already applied it - that explicit manual choice
   * is never overridden, and no conversion anchored to a unit that is no longer the actual stock
   * unit is created either (it would just be confusing leftover data).
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
    if (createdProduct.getQuIdStockInt() != packagingQuId) {
      // Not (or no longer) the confirmed packaging unit - either syncQuickPackagingToProduct()
      // never resolved it this way, or the user manually overrode it - either way, never create
      // a conversion anchored to a unit that isn't actually the stock unit.
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

  private boolean isPurchaseAmountValid() {
    return NumUtil.isStringDouble(purchaseAmountLive.getValue())
        && NumUtil.toDouble(purchaseAmountLive.getValue()) > 0;
  }

  /**
   * Price per stock unit (i.e. per packaging, e.g. "per Flasche") from the "Dieser Einkauf"
   * section's confirmed price, resolving "Preis pro Verpackung" vs. "Gesamtpreis" exactly like
   * the classic Purchase screen's {@code QuantityUnitConversionUtil#getPriceStock}. Unlike there,
   * no separate quantity-unit-factor conversion is applied: the purchase unit IS the stock unit
   * by construction in this household model (see QuickPackagingSyncUtil), so the factor is always
   * 1. Returns null if no valid price/amount is available - never invents a price.
   */
  @Nullable
  private Double computePurchasePricePerStockUnit() {
    return PurchasePriceUtil.computePricePerStockUnit(
        purchasePriceLive.getValue(),
        purchaseAmountLive.getValue(),
        Boolean.TRUE.equals(purchaseIsTotalPriceLive.getValue())
    );
  }

  /**
   * Books the confirmed "Dieser Einkauf" section directly as the product's first stock entry,
   * reusing the exact same Grocy "add to stock" endpoint the classic PurchaseFragment/
   * PurchaseViewModel/FormDataPurchase already use ({@link GrocyApi#purchaseProduct}) - but as an
   * entirely separate, independent code path that never touches FormDataPurchase/
   * PurchaseViewModel, so the classic Purchase screen (and Consume/Transfer/Inventory, which
   * never set fromPurchase) stay completely unaffected by this feature (task docs section P).
   * Only ever reached for a genuinely new, non-cloned, scanned-barcode product coming from the
   * Purchase flow (see {@link #getShowPurchaseSectionLive()}), and only after the product itself,
   * its barcode link and its packaging/content conversion have already succeeded - so a failed
   * purchase booking never means the product has to be created again (task docs section O): only
   * {@link #retryPurchase()} needs to run again, using the SAME already-created product id.
   * <p>
   * On success: marks the purchase as booked (see {@link #isPurchaseBooked()}, read by
   * MasterProductFragment to forward {@code ARGUMENT.PURCHASE_ALREADY_BOOKED} so the classic
   * Purchase screen never offers this exact delivery a second time) and runs {@code onFinished}.
   * On failure: tells the user the product itself WAS saved but the purchase was not, sets
   * {@link #getPurchaseFailedLive()} so the fragment can show a retry action, and deliberately
   * does NOT run {@code onFinished} - navigating away with a purchase that was never actually
   * booked, and no visible way to retry it, would be a silent data loss.
   */
  private void bookPurchase(int productId, Runnable onFinished) {
    if (purchaseInProgress) {
      return;
    }
    purchaseInProgress = true;
    dlHelper.postWithArray(
        grocyApi.purchaseProduct(productId),
        buildPurchaseJson(),
        response -> {
          purchaseInProgress = false;
          purchaseBooked = true;
          purchaseFailedLive.setValue(false);
          onFinished.run();
        },
        error -> {
          purchaseInProgress = false;
          // The product itself is safely saved by now (this only ever runs after that already
          // succeeded) - release the save-button guard too, so the user is never stuck unable to
          // do anything on this screen: a further "Fertig" tap is redirected straight back to
          // retryPurchase() (see the top of saveProduct()), never re-creating the product.
          saveInProgress = false;
          purchaseFailedLive.setValue(true);
          showMessage(R.string.msg_product_saved_purchase_failed);
          if (debug) {
            Log.w(TAG, "bookPurchase: failed for product " + productId + ": "
                + describeVolleyError(error));
          }
        }
    );
  }

  /**
   * Entry point for a "Fertig" re-tap after a failed purchase booking (see the guard at the top
   * of {@link #saveProduct}) - unlike the dedicated retry action, the user may well have kept
   * editing product fields (name, product group, location, ...) on this same screen while fixing
   * whatever caused the purchase to fail, so those changes are saved with a PUT first, and only
   * once that succeeds is the purchase itself retried. Never re-creates the product (still the
   * same {@code createdProductIdForPurchase}) and never touches the barcode link or the
   * packaging/content conversion again - only the two things that can actually still be wrong
   * (product fields, purchase booking) are retried, each exactly once.
   */
  private void updateProductThenRetryPurchase() {
    Integer productId = createdProductIdForPurchase;
    if (productId == null) {
      return;
    }
    saveInProgress = true;
    Product product = getFilledProduct();
    product.setId(productId);
    dlHelper.put(
        grocyApi.getObject(GrocyApi.ENTITY.PRODUCTS, productId),
        product.getJsonFromProduct(sharedPrefs, debug, TAG),
        response -> {
          saveInProgress = false;
          retryPurchase();
        },
        error -> {
          saveInProgress = false;
          showNetworkErrorMessage(error);
          if (debug) {
            Log.e(TAG, "updateProductThenRetryPurchase: " + error);
          }
        }
    );
  }

  /**
   * Retries booking ONLY the purchase after {@link #bookPurchase} previously failed - never
   * re-creates the product, never re-links the barcode, never re-creates the packaging/content
   * conversion (task docs section O: "keine doppelten Produkte"). Navigates up on success, exactly
   * like a first-time successful save+purchase would have. Also reachable directly from the
   * dedicated retry action (nothing was edited since the failure, so there is nothing to update).
   */
  public void retryPurchase() {
    Integer productId = createdProductIdForPurchase;
    if (productId == null) {
      return;
    }
    bookPurchase(productId, () -> {
      Bundle bundle = new Bundle();
      bundle.putInt(Constants.ARGUMENT.PRODUCT_ID, productId);
      sendEvent(Event.SET_PRODUCT_ID, bundle);
      sendEvent(Event.NAVIGATE_UP);
    });
  }

  public boolean isPurchaseBooked() {
    return purchaseBooked;
  }

  /**
   * Builds the purchase POST body from the "Dieser Einkauf" section's confirmed fields, in the
   * same JSON shape {@code FormDataPurchase#getFilledJSONObject()} sends to the same endpoint:
   * amount is the only required field (defaults to "1" if somehow blank - isPurchaseAmountValid()
   * already gates whether this is called at all), everything else is only sent if actually filled
   * in (task docs section T - price/due date/note are always optional; the very first purchase of
   * a brand new product never has a real price to fall back on, so an empty price field is simply
   * left out, never invented). Price supports "pro Verpackung" vs. "Gesamtpreis" exactly like the
   * classic Purchase screen; unlike there, no separate quantity-unit-factor conversion is needed
   * here, since the purchase unit IS the stock unit by construction in this household model (see
   * QuickPackagingSyncUtil) - factor 1 always. Location and store are read directly from the
   * product's own location_id/shopping_location_id fields (the SAME fields the
   * "Produktzuordnung"/classic Standort screen edits) instead of duplicating a second, separate
   * location/store selection just for this one purchase (task docs: "keine doppelte Eingabe
   * derselben Information").
   */
  private JSONObject buildPurchaseJson() {
    Product product = formData.getProductLive().getValue();
    String amount = isPurchaseAmountValid() ? purchaseAmountLive.getValue() : "1";
    JSONObject json = new JSONObject();
    try {
      json.put("amount", amount);
      if (isFeatureEnabled(PREF.FEATURE_STOCK_PRICE_TRACKING)) {
        Double priceStock = computePurchasePricePerStockUnit();
        if (priceStock != null) {
          json.put("price", String.valueOf(priceStock));
        }
        if (product != null && product.getStoreId() != null) {
          json.put("shopping_location_id", product.getStoreId());
        }
      }
      // Grocy's own API (see /stock/products/{id}/add) explicitly defaults an OMITTED
      // best_before_date to TODAY server-side - so this is always sent explicitly, never
      // omitted, exactly like the classic Purchase screen's own
      // FormDataPurchase#getFilledJSONObject() already does for the same reason. A blank
      // "Dieser Einkauf" date field must never silently become "expires today" (task docs
      // section 13) - see PurchaseDueDateUtil for the resolution rule. Whether the BBD field is
      // even shown/editable in THIS app's UI (the feature flag) is a separate, purely local
      // concern from what Grocy's server needs in the request body.
      json.put("best_before_date", isFeatureEnabled(PREF.FEATURE_STOCK_BBD_TRACKING)
          ? PurchaseDueDateUtil.resolveBestBeforeDate(purchaseDueDateLive.getValue())
          : Constants.DATE.NEVER_OVERDUE);
      if (isFeatureEnabled(PREF.FEATURE_STOCK_LOCATION_TRACKING) && product != null
          && product.getLocationId() != null) {
        json.put("location_id", product.getLocationId());
      }
      if (!isBlank(purchaseNoteLive.getValue())) {
        json.put("note", purchaseNoteLive.getValue());
      }
    } catch (JSONException e) {
      if (debug) {
        Log.e(TAG, "buildPurchaseJson: " + e);
      }
    }
    return json;
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
    // Learn the store/price from THIS first purchase too, exactly like a later purchase of the
    // same barcode would learn them (task docs section Q) - never invented, only what the user
    // actually confirmed in the "Dieser Einkauf" section.
    if (fromPurchase && isPurchaseAmountValid()) {
      Product currentProduct = formData.getProductLive().getValue();
      if (currentProduct != null && currentProduct.getStoreId() != null) {
        productBarcode.setStoreId(currentProduct.getStoreId());
      }
      Double pricePerStockUnit = computePurchasePricePerStockUnit();
      if (pricePerStockUnit != null) {
        productBarcode.setLastPrice(String.valueOf(pricePerStockUnit));
      }
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
