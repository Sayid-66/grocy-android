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
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;
import xyz.zedler.patrick.grocy.Constants;
import xyz.zedler.patrick.grocy.Constants.ARGUMENT;
import xyz.zedler.patrick.grocy.Constants.PREF;
import xyz.zedler.patrick.grocy.Constants.SETTINGS.BEHAVIOR;
import xyz.zedler.patrick.grocy.Constants.SETTINGS.STOCK;
import xyz.zedler.patrick.grocy.Constants.SETTINGS_DEFAULT;
import xyz.zedler.patrick.grocy.R;
import xyz.zedler.patrick.grocy.api.GrocyApi;
import xyz.zedler.patrick.grocy.form.FormDataPurchase;
import xyz.zedler.patrick.grocy.fragment.PurchaseFragmentArgs;
import xyz.zedler.patrick.grocy.fragment.bottomSheetDialog.DateBottomSheet;
import xyz.zedler.patrick.grocy.fragment.bottomSheetDialog.InputProductBottomSheet;
import xyz.zedler.patrick.grocy.fragment.bottomSheetDialog.LocationsBottomSheet;
import xyz.zedler.patrick.grocy.fragment.bottomSheetDialog.ProductsBottomSheet;
import xyz.zedler.patrick.grocy.fragment.bottomSheetDialog.QuantityUnitsBottomSheet;
import xyz.zedler.patrick.grocy.fragment.bottomSheetDialog.QuickModeConfirmBottomSheet;
import xyz.zedler.patrick.grocy.fragment.bottomSheetDialog.StoresBottomSheet;
import xyz.zedler.patrick.grocy.helper.DownloadHelper;
import xyz.zedler.patrick.grocy.helper.DownloadHelper.OnJSONArrayResponseListener;
import xyz.zedler.patrick.grocy.helper.DownloadHelper.OnObjectResponseListener;
import xyz.zedler.patrick.grocy.model.Event;
import xyz.zedler.patrick.grocy.model.InfoFullscreen;
import xyz.zedler.patrick.grocy.model.Location;
import xyz.zedler.patrick.grocy.model.PendingProduct;
import xyz.zedler.patrick.grocy.model.PendingProductBarcode;
import xyz.zedler.patrick.grocy.model.Product;
import xyz.zedler.patrick.grocy.model.ProductBarcode;
import xyz.zedler.patrick.grocy.model.ProductDetails;
import xyz.zedler.patrick.grocy.model.QuantityUnit;
import xyz.zedler.patrick.grocy.model.QuantityUnitConversionResolved;
import xyz.zedler.patrick.grocy.model.ShoppingListItem;
import xyz.zedler.patrick.grocy.model.SnackbarMessage;
import xyz.zedler.patrick.grocy.model.Store;
import xyz.zedler.patrick.grocy.model.StoredPurchase;
import xyz.zedler.patrick.grocy.repository.PurchaseRepository;
import xyz.zedler.patrick.grocy.util.AmountUtil;
import xyz.zedler.patrick.grocy.util.ArrayUtil;
import xyz.zedler.patrick.grocy.util.DateUtil;
import xyz.zedler.patrick.grocy.util.GrocycodeUtil;
import xyz.zedler.patrick.grocy.util.GrocycodeUtil.Grocycode;
import xyz.zedler.patrick.grocy.util.NumUtil;
import xyz.zedler.patrick.grocy.util.PrefsUtil;
import xyz.zedler.patrick.grocy.util.QuantityUnitConversionUtil;
import xyz.zedler.patrick.grocy.util.VersionUtil;

public class PurchaseViewModel extends BaseViewModel {

  private static final String TAG = PurchaseViewModel.class.getSimpleName();
  private final SharedPreferences sharedPrefs;
  private final boolean debug;

  private final DownloadHelper dlHelper;
  private final GrocyApi grocyApi;
  private final PurchaseRepository repository;
  private final FormDataPurchase formData;

  private List<Product> products;
  private HashMap<Integer, Product> productHashMap;
  private List<PendingProduct> pendingProducts;
  private List<QuantityUnit> quantityUnits;
  private HashMap<Integer, QuantityUnit> quantityUnitHashMap;
  private List<QuantityUnitConversionResolved> unitConversions;
  private HashMap<Integer, Double> shoppingListItemAmountsHashMap;
  private List<ProductBarcode> barcodes;
  private List<PendingProductBarcode> pendingProductBarcodes;
  private List<Store> stores;
  private List<Location> locations;
  private List<ShoppingListItem> shoppingListItems;
  private HashMap<Integer, ShoppingListItem> shoppingListItemHashMap;
  private ArrayList<Integer> batchShoppingListItemIds;

  private final MutableLiveData<Boolean> isLoadingLive;
  private final MutableLiveData<InfoFullscreen> infoFullscreenLive;
  private final MutableLiveData<Boolean> quickModeEnabled;

  private Integer storedPurchaseId;
  private StoredPurchase storedPurchase;
  private Runnable queueEmptyAction;
  private boolean productWillBeFilled;
  private final int maxDecimalPlacesAmount;
  private final int decimalPlacesPriceInput;

  // "Just this purchase" vs "save as new default" (see purchaseProduct/barcodeDefaultChanged):
  // the exact ProductBarcode row that was used to prefill the form, plus the amount/qu_id that
  // were ACTUALLY applied to the form from it (not just whatever the row happens to hold - tare
  // weight handling or a quantity unit with no conversion for this product can both make
  // setProduct() skip the barcode's value, in which case there is nothing to compare against).
  @Nullable
  private ProductBarcode learnedBarcode;
  @Nullable
  private Double learnedBarcodeAppliedAmount;
  @Nullable
  private Integer learnedBarcodeAppliedQuId;
  private boolean updateBarcodeDefaultPending;
  // Independent from the pre-existing CONFIRM_FREEZING "confirmed" re-entry flag: both dialogs
  // must be able to show in sequence when both conditions apply for the same purchase attempt,
  // never let confirming one silently also skip the other. Reset only at the true start of a
  // fresh attempt (purchaseProduct(), the no-arg entry point) - all re-entries keep it as-is.
  private boolean barcodeDefaultDialogConfirmed;

  public PurchaseViewModel(@NonNull Application application, PurchaseFragmentArgs args) {
    super(application);

    sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplication());
    debug = PrefsUtil.isDebuggingEnabled(sharedPrefs);
    maxDecimalPlacesAmount = sharedPrefs.getInt(
        STOCK.DECIMAL_PLACES_AMOUNT,
        SETTINGS_DEFAULT.STOCK.DECIMAL_PLACES_AMOUNT
    );
    decimalPlacesPriceInput = sharedPrefs.getInt(
        STOCK.DECIMAL_PLACES_PRICES_INPUT,
        SETTINGS_DEFAULT.STOCK.DECIMAL_PLACES_PRICES_INPUT
    );

    isLoadingLive = new MutableLiveData<>(false);
    dlHelper = new DownloadHelper(getApplication(), TAG, isLoadingLive::setValue, getOfflineLive());
    grocyApi = new GrocyApi(getApplication());
    repository = new PurchaseRepository(application);
    formData = new FormDataPurchase(application, sharedPrefs, args);

    if (args.getShoppingListItems() != null) {
      batchShoppingListItemIds = new ArrayList<>(args.getShoppingListItems().length);
      for (int i : args.getShoppingListItems()) {
        batchShoppingListItemIds.add(i);
      }
    }
    if (NumUtil.isStringInt(args.getStoredPurchaseId())) {
      storedPurchaseId = Integer.parseInt(args.getStoredPurchaseId());
    }

    infoFullscreenLive = new MutableLiveData<>();
    boolean quickModeStart;
    if (args.getStartWithScanner()) {
      quickModeStart = isTurnOnQuickModeEnabled();
    } else if (!args.getCloseWhenFinished()) {
      quickModeStart = sharedPrefs.getBoolean(
          Constants.PREF.QUICK_MODE_ACTIVE_PURCHASE,
          false
      );
    } else {
      quickModeStart = false;
    }
    if (hasStoredPurchase()) {
      quickModeStart = false;
    }
    quickModeEnabled = new MutableLiveData<>(quickModeStart);

    if (hasStoredPurchase()) {
      setQueueEmptyAction(() -> setStoredPurchase(storedPurchase));
    }
  }

  public FormDataPurchase getFormData() {
    return formData;
  }

  public void loadFromDatabase(boolean downloadAfterLoading) {
    repository.loadFromDatabase(data -> {
      this.products = data.getProducts();
      this.pendingProducts = data.getPendingProducts();
      formData.getProductsLive().setValue(
              appendPendingProducts(Product.getActiveProductsOnly(products), pendingProducts)
      );
      productHashMap = ArrayUtil.getProductsHashMap(products);
      this.pendingProductBarcodes = data.getPendingProductBarcodes();
      this.barcodes = appendPendingProductBarcodes(data.getBarcodes(), pendingProductBarcodes);
      this.quantityUnits = data.getQuantityUnits();
      quantityUnitHashMap = ArrayUtil.getQuantityUnitsHashMap(quantityUnits);
      this.unitConversions = data.getQuantityUnitConversionsResolved();
      this.stores = data.getStores();
      this.locations = data.getLocations();
      this.shoppingListItems = data.getShoppingListItems();
      shoppingListItemHashMap = ArrayUtil.getShoppingListItemHashMap(shoppingListItems);
      fillShoppingListItemAmountsHashMap();
      if (storedPurchaseId != null) {
        storedPurchase = StoredPurchase.getFromId(data.getStoredPurchases(), storedPurchaseId);
      }
      if (downloadAfterLoading) {
        downloadData(false);
      } else {
        if (queueEmptyAction != null) {
          queueEmptyAction.run();
          queueEmptyAction = null;
        }
        if (batchShoppingListItemIds != null) {
          fillWithShoppingListItem();
        }
      }
    }, error -> onError(error, TAG));
  }

  public void downloadData(boolean forceUpdate) {
    dlHelper.updateData(
        updated -> {
          if (updated) {
            loadFromDatabase(false);
          } else {
            if (queueEmptyAction != null) {
              queueEmptyAction.run();
              queueEmptyAction = null;
            }
            if (batchShoppingListItemIds != null) {
              fillWithShoppingListItem();
            }
          }
        },
        error -> onError(error, TAG),
        forceUpdate,
        false,
        Product.class,
        ProductBarcode.class,
        QuantityUnit.class,
        QuantityUnitConversionResolved.class,
        Store.class,
        Location.class,
        batchShoppingListItemIds != null ? ShoppingListItem.class : null
    );
  }

  public void setProduct(
      @Nullable Integer productId,
      @Nullable ProductBarcode barcode,
      @Nullable ShoppingListItem shoppingListItem
  ) {
    if (productId == null && barcode == null && shoppingListItem == null) {
      return;
    }
    if (productId == null && barcode != null) {
      return;
    }

    // Reset the "learned default" baseline on every call (different/null barcode or a different
    // product): a stale comparison must never survive to be persisted against the wrong barcode
    // row later in purchaseProduct(). Never a PendingProductBarcode: its id is a local Room
    // autoincrement id for a pending product, not a real server ProductBarcode id - PUTting
    // against it later (updateLearnedBarcodeDefault) could hit an unrelated real barcode row if
    // the ids happen to collide (both are small autoincrement ints). onBarcodeRecognized already
    // routes pending-product barcodes to setPendingProduct() instead, but checkProductInput()
    // does not, so this must guard here too.
    learnedBarcode = barcode != null && !(barcode instanceof PendingProductBarcode)
        && (barcode.hasAmount() || barcode.hasQuId()) ? barcode : null;
    // Set below, inside the listener, only for whichever of amount/qu_id actually gets applied
    // to the form from this barcode - tare weight handling or a quantity unit with no conversion
    // for this product can both make the prefill below skip it despite the barcode having it.
    learnedBarcodeAppliedAmount = null;
    learnedBarcodeAppliedQuId = null;

    OnObjectResponseListener<ProductDetails> listener = productDetails -> {
      Product updatedProduct = productDetails.getProduct();

      if (updatedProduct.getNoOwnStockBoolean()) {
        showProductChildrenBottomSheet(updatedProduct);
        return;
      }

      formData.getProductDetailsLive().setValue(productDetails);
      formData.getProductNameLive().setValue(updatedProduct.getName());

      // quantity unit
      Integer forcedQuId = null;
      if (barcode != null && barcode.hasQuId()) {
        forcedQuId = barcode.getQuIdInt();
      } else if (shoppingListItem != null && shoppingListItem.hasQuId()) {
        forcedQuId = shoppingListItem.getQuIdInt();
      }
      HashMap<QuantityUnit, Double> unitFactors = QuantityUnitConversionUtil.getUnitFactors(
          quantityUnitHashMap,
          unitConversions,
          updatedProduct,
          VersionUtil.isGrocyServerMin400(sharedPrefs)
      );
      formData.getQuantityUnitsFactorsLive().setValue(unitFactors);
      formData.getQuantityUnitStockLive().setValue(
          quantityUnitHashMap.get(updatedProduct.getQuIdStockInt())
      );
      QuantityUnit forcedUnit = null;
      if (forcedQuId != null) {
        forcedUnit = quantityUnitHashMap.get(forcedQuId);
      }
      Double factor;
      if (forcedUnit != null && unitFactors.containsKey(forcedUnit)) {
        formData.getQuantityUnitLive().setValue(forcedUnit);
        factor = unitFactors.get(forcedUnit);
        if (barcode != null && barcode.hasQuId() && forcedUnit.getId() == barcode.getQuIdInt()) {
          learnedBarcodeAppliedQuId = forcedUnit.getId();
        }
      } else {
        QuantityUnit purchase = quantityUnitHashMap.get(updatedProduct.getQuIdPurchaseInt());
        formData.getQuantityUnitLive().setValue(purchase);
        factor = unitFactors.get(purchase);
      }
      double initialUnitFactor = factor != null ? factor : 1;

      // amount
      boolean isTareWeightEnabled = formData.isTareWeightEnabled();
      if (!isTareWeightEnabled && barcode != null && barcode.hasAmount()) {
        // if barcode contains amount, take this (with tare weight handling off)
        // quick mode status doesn't matter
        formData.getAmountLive().setValue(NumUtil.trimAmount(barcode.getAmountDouble(), maxDecimalPlacesAmount));
        learnedBarcodeAppliedAmount = barcode.getAmountDouble();
      } else if (!isTareWeightEnabled && shoppingListItem != null) {
        Double amountInUnit = AmountUtil.getShoppingListItemAmount(
            shoppingListItem, productHashMap, quantityUnitHashMap, unitConversions,
            VersionUtil.isGrocyServerMin400(sharedPrefs)
        );
        formData.getAmountLive().setValue(
            NumUtil.trimAmount(
                Objects.requireNonNullElseGet(amountInUnit, shoppingListItem::getAmountDouble),
                maxDecimalPlacesAmount
            )
        );
      } else if (!isTareWeightEnabled && !isQuickModeEnabled()) {
        String defaultAmount = sharedPrefs.getString(
            Constants.SETTINGS.STOCK.DEFAULT_PURCHASE_AMOUNT,
            Constants.SETTINGS_DEFAULT.STOCK.DEFAULT_PURCHASE_AMOUNT
        );
        if (NumUtil.isStringDouble(defaultAmount)) {
          defaultAmount = NumUtil.trimAmount(NumUtil.toDouble(defaultAmount), maxDecimalPlacesAmount);
        }
        if (NumUtil.isStringDouble(defaultAmount)
            && NumUtil.toDouble(defaultAmount) > 0) {
          formData.getAmountLive().setValue(defaultAmount);
        }
      } else if (!isTareWeightEnabled) {
        // if quick mode enabled, always fill with amount 1
        formData.getAmountLive().setValue(NumUtil.trimAmount(1, maxDecimalPlacesAmount));
      }

      // purchased date
      if (formData.getPurchasedDateEnabled()) {
        formData.getPurchasedDateLive().setValue(DateUtil.getDateStringToday());
      }

      // due days
      if (isFeatureEnabled(PREF.FEATURE_STOCK_BBD_TRACKING)) {
        int dueDays = productDetails.getProduct().getDefaultDueDaysInt();
        if (dueDays < 0) {
          formData.getDueDateLive().setValue(Constants.DATE.NEVER_OVERDUE);
        } else if (dueDays == 0) {
          formData.getDueDateLive().setValue(null);
        } else {
          formData.getDueDateLive()
              .setValue(DateUtil.getTodayWithDaysAdded(dueDays));
        }
      }

      // price
      if (isFeatureEnabled(PREF.FEATURE_STOCK_PRICE_TRACKING)) {
        String lastPrice;
        if (barcode != null && barcode.hasLastPrice()) {
          // if barcode contains last price, take this
          lastPrice = barcode.getLastPrice();
        } else {
          lastPrice = productDetails.getLastPrice();
          if (lastPrice != null && !lastPrice.isEmpty()) {
            lastPrice = NumUtil.trimPrice(NumUtil.toDouble(lastPrice) / initialUnitFactor, decimalPlacesPriceInput);
          }
        }
        formData.getPriceLive().setValue(lastPrice);
      }

      // store
      String storeId;
      if (formData.getPinnedStoreIdLive().getValue() != null) {
        storeId = String.valueOf(formData.getPinnedStoreIdLive().getValue());
      } else if (barcode != null && barcode.hasStoreId()) {
        // if barcode contains store, take this
        storeId = barcode.getStoreId();
      } else {
        storeId = productDetails.getLastShoppingLocationId();
      }
      if (!NumUtil.isStringInt(storeId)) {
        storeId = productDetails.getDefaultShoppingLocationId();
      }
      Store store = NumUtil.isStringInt(storeId)
          ? getStore(Integer.parseInt(storeId)) : null;
      formData.getStoreLive().setValue(store);
      formData.getShowStoreSection().setValue(store != null || !stores.isEmpty());

      // location
      if (isFeatureEnabled(PREF.FEATURE_STOCK_LOCATION_TRACKING)) {
        formData.getLocationLive().setValue(productDetails.getLocation());
      }

      // stock label type
      if (isFeatureEnabled(PREF.FEATURE_LABEL_PRINTER)) {
        formData.getPrintLabelTypeLive()
            .setValue(productDetails.getProduct().getDefaultStockLabelTypeInt());
      }

      // note
      if (barcode != null && barcode.getNote() != null) {
        formData.getNoteLive().setValue(barcode.getNote());
      }

      formData.isFormValid();
      if (isQuickModeEnabled()) {
        sendEvent(Event.FOCUS_INVALID_VIEWS);
      }
    };

    if (productId == null && shoppingListItem.hasProduct()) {
      productId = shoppingListItem.getProductIdInt();
    } else if (productId == null) {
      formData.getAmountLive().setValue(NumUtil.trimAmount(shoppingListItem.getAmountDouble(), maxDecimalPlacesAmount));
      return;
    }
    ProductDetails.getProductDetails(
        dlHelper,
        productId,
        listener,
        error -> showMessageAndContinueScanning(getString(R.string.error_no_product_details))
    ).perform(dlHelper.getUuid());
  }

  /**
   * Like {@link #setProduct(Integer, ProductBarcode, ShoppingListItem)}, but resolves the
   * {@link ProductBarcode} from an already-known barcode STRING first, looked up in the
   * already-loaded {@link #barcodes} list - used when returning here right after a product was
   * newly created FROM a scanned barcode (see
   * MasterProductViewModel#linkScannedBarcodeAndUploadPending): that barcode was already linked
   * server-side there, with its confirmed amount/quantity unit if any (e.g. "1 Flasche"), so THIS
   * call must never attempt to (re)link it again - it only uses it, read-only, to prefill the
   * purchase form with that already-learned default, exactly like scanning any other
   * already-known barcode would (see {@link #onBarcodeRecognized}). Without this, the very first
   * purchase right after creating a product from a scanned barcode would have to re-enter the
   * amount/quantity unit that was just confirmed a moment ago on the product creation screen.
   */
  public void setProductFromJustLinkedBarcode(int productId, @Nullable String barcode) {
    ProductBarcode productBarcode = barcode != null && barcodes != null
        ? ProductBarcode.getFromBarcode(barcodes, barcode) : null;
    setProduct(productId, productBarcode, null);
  }

  public void setPendingProduct(int pendingProductId, PendingProductBarcode barcode) {
    PendingProduct pendingProduct = PendingProduct.getFromId(pendingProducts, pendingProductId);
    if (pendingProduct == null) return;
    formData.getPendingProductLive().setValue(pendingProduct);
    formData.getProductNameLive().setValue(pendingProduct.getName());

    // amount
    formData.getAmountLive().setValue(
        barcode == null || barcode.getAmount() == null
            ? NumUtil.trimAmount(1, maxDecimalPlacesAmount) : barcode.getAmount()
    );

    // purchased date
    if (formData.getPurchasedDateEnabled()) {
      formData.getPurchasedDateLive().setValue(DateUtil.getDateStringToday());
    }

    // price
    if (isFeatureEnabled(PREF.FEATURE_STOCK_PRICE_TRACKING)) {
      String lastPrice = null;
      if (barcode != null && barcode.hasLastPrice()) {
        // if barcode contains last price, take this
        lastPrice = barcode.getLastPrice();
      }
      if (lastPrice != null && !lastPrice.isEmpty()) {
        lastPrice = NumUtil.trimPrice(NumUtil.toDouble(lastPrice), decimalPlacesPriceInput);
      }
      formData.getPriceLive().setValue(lastPrice);
    }

    // store
    String storeId = null;
    if (formData.getPinnedStoreIdLive().getValue() != null) {
      storeId = String.valueOf(formData.getPinnedStoreIdLive().getValue());
    } else if (barcode != null && barcode.hasStoreId()) {
      // if barcode contains store, take this
      storeId = barcode.getStoreId();
    }
    Store store = NumUtil.isStringInt(storeId) ? getStore(Integer.parseInt(storeId)) : null;
    formData.getStoreLive().setValue(store);
    formData.getShowStoreSection().setValue(store != null || !stores.isEmpty());

    formData.isFormValid();
    sendEvent(Event.FOCUS_INVALID_VIEWS);
  }

  private void setStoredPurchase(StoredPurchase storedPurchase) {
    PendingProduct pendingProduct = PendingProduct
        .getFromId(pendingProducts, storedPurchase.getPendingProductId());
    if (pendingProduct == null) return;

    formData.getPendingProductLive().setValue(pendingProduct);
    formData.getProductNameLive().setValue(pendingProduct.getName());

    // amount
    formData.getAmountLive().setValue(storedPurchase.getAmount());

    // purchased date
    if (formData.getPurchasedDateEnabled()) {
      formData.getPurchasedDateLive().setValue(storedPurchase.getPurchasedDate());
    }

    // due date
    if (isFeatureEnabled(PREF.FEATURE_STOCK_BBD_TRACKING)) {
      formData.getDueDateLive().setValue(storedPurchase.getBestBeforeDate());
    }

    // price
    if (isFeatureEnabled(PREF.FEATURE_STOCK_PRICE_TRACKING)) {
      formData.getPriceLive().setValue(storedPurchase.getPrice());
    }

    // store
    String storeId = storedPurchase.getStoreId();
    Store store = NumUtil.isStringInt(storeId) ? getStore(Integer.parseInt(storeId)) : null;
    formData.getStoreLive().setValue(store);
    formData.getShowStoreSection().setValue(store != null || !stores.isEmpty());

    formData.isFormValid();
  }

  public void onBarcodeRecognized(String barcode) {
    if (barcodes == null) {
      loadFromDatabase(true);
      return;
    }
    if (formData.getProductDetailsLive().getValue() != null) {
      if (ProductBarcode.getFromBarcode(barcodes, barcode) == null) {
        formData.getBarcodeLive().setValue(barcode);
      } else {
        showMessage(R.string.msg_clear_form_first);
      }
      return;
    }
    Product product = null;
    Grocycode grocycode = GrocycodeUtil.getGrocycode(barcode);
    if (grocycode != null && grocycode.isProduct()) {
      product = productHashMap.get(grocycode.getObjectId());
      if (product == null) {
        showMessageAndContinueScanning(R.string.msg_not_found);
        return;
      }
    } else if (grocycode != null) {
      showMessageAndContinueScanning(R.string.error_wrong_grocycode_type);
      return;
    }
    ProductBarcode productBarcode = null;
    if (product == null) {
      productBarcode = ProductBarcode.getFromBarcode(barcodes, barcode);
      if (productBarcode instanceof PendingProductBarcode) {
        setPendingProduct(productBarcode.getProductIdInt(), (PendingProductBarcode) productBarcode);
        return;
      } else if (productBarcode != null) {
        product = productHashMap.get(productBarcode.getProductIdInt());
      }
    }
    if (product != null) {
      setProduct(product.getId(), productBarcode, null);
    } else {
      Bundle bundle = new Bundle();
      bundle.putString(ARGUMENT.BARCODE, barcode);
      sendEvent(Event.CHOOSE_PRODUCT, bundle);
    }
  }

  public void checkProductInput() {
    formData.isProductNameValid();
    String input = formData.getProductNameLive().getValue();
    if (input == null || input.isEmpty()) {
      return;
    }
    Product product = Product.getProductFromName(products, input);

    Grocycode grocycode = GrocycodeUtil.getGrocycode(input.trim());
    if (grocycode != null && grocycode.isProduct()) {
      product = productHashMap.get(grocycode.getObjectId());
      if (product == null) {
        showMessageAndContinueScanning(R.string.msg_not_found);
        return;
      }
    } else if (grocycode != null) {
      showMessageAndContinueScanning(R.string.error_wrong_grocycode_type);
      return;
    }
    if (product == null) {
      ProductBarcode productBarcode = null;
      for (ProductBarcode code : barcodes) {
        if (code.getBarcode().equals(input.trim())) {
          productBarcode = code;
          if (code instanceof PendingProductBarcode) {
            product = PendingProduct.getFromId(pendingProducts, code.getProductIdInt());
          } else {
            product = productHashMap.get(code.getProductIdInt());
          }
        }
      }
      if (product != null) {
        setProduct(product.getId(), productBarcode, null);
        return;
      }
    }

    ProductDetails currentProductDetails = formData.getProductDetailsLive().getValue();
    Product currentProduct = currentProductDetails != null
        ? currentProductDetails.getProduct() : null;
    if (currentProduct != null && product != null && currentProduct.getId() == product.getId()) {
      return;
    }

    if (product != null) {
      setProduct(product.getId(), null, null);
    } else {
      showInputProductBottomSheet(input);
    }
  }

  public void addBarcodeToExistingProduct(String barcode) {
    formData.getBarcodeLive().setValue(barcode);
    formData.getProductNameLive().setValue(null);
  }

  public void fillWithShoppingListItem() {
    if (batchShoppingListItemIds == null) {
      return;
    }
    if (formData.getBatchModeItemIndexLive().getValue() == null) {
      return;
    }
    int index = formData.getBatchModeItemIndexLive().getValue();
    if (index >= batchShoppingListItemIds.size()) {
      return;
    }
    int currentItemId = batchShoppingListItemIds.get(index);
    if (shoppingListItemHashMap == null) {
      return;
    }
    ShoppingListItem currentItem = shoppingListItemHashMap.get(currentItemId);
    if (currentItem == null) {
      return;
    }
    formData.getShoppingListItemLive().setValue(currentItem);
    setProduct(null, null, currentItem);
  }

  public boolean batchModeNextItem() {  // also returns whether there was a next item
    if (batchShoppingListItemIds == null) {
      return false;
    }
    if (formData.getBatchModeItemIndexLive().getValue() == null) {
      return false;
    }
    int index = formData.getBatchModeItemIndexLive().getValue();
    if (index >= batchShoppingListItemIds.size() - 1) {
      return false;
    }
    formData.getBatchModeItemIndexLive().setValue(index + 1);
    fillWithShoppingListItem();
    return true;
  }

  public void purchaseProduct() {
    // A genuinely fresh attempt (the only entry point reachable other than the confirm-dialog
    // re-entries below) - reset both the barcode-default-dialog gate (so a decision made on a
    // previous, unrelated purchase can never suppress this dialog here) and the "save as new
    // default" intent (so, if the user previously chose that but then cancelled a later
    // CONFIRM_FREEZING prompt without ever completing that purchase, it can't silently attach
    // itself to this different, later purchase instead).
    barcodeDefaultDialogConfirmed = false;
    updateBarcodeDefaultPending = false;
    purchaseProduct(false);
  }

  /**
   * "Nur für diesen Einkauf verwenden": proceeds with the purchase exactly as if the learned
   * barcode default had matched, without touching the remembered ProductBarcode row. Re-enters
   * with confirmed=false (not true) so the SEPARATE, pre-existing CONFIRM_FREEZING safety prompt
   * - if it also applies to this same purchase - still gets its own chance to fire, instead of
   * being silently skipped by a confirmation that was only ever about the barcode default.
   */
  public void purchaseOnceWithoutSavingDefault() {
    barcodeDefaultDialogConfirmed = true;
    purchaseProduct(false);
  }

  /**
   * "Als neuen Standard speichern": proceeds with the purchase and, only once it itself
   * succeeds, PUTs the matched ProductBarcode with the current form's amount/quantity unit so
   * future scans of this exact barcode prefill the new values. See
   * purchaseOnceWithoutSavingDefault() for why this re-enters with confirmed=false.
   */
  public void purchaseAndSaveAsNewDefault() {
    barcodeDefaultDialogConfirmed = true;
    updateBarcodeDefaultPending = true;
    purchaseProduct(false);
  }

  /**
   * Whether the current form's amount or quantity unit differs from what was ACTUALLY applied to
   * the form from the ProductBarcode that prefilled it (see setProduct()/
   * learnedBarcodeAppliedAmount/learnedBarcodeAppliedQuId - NOT just whatever the row happens to
   * hold, since e.g. tare weight handling or a quantity unit with no conversion for this product
   * can both make setProduct() skip applying it, in which case there is nothing to compare
   * against and this must never fire). Never true if no barcode was learned yet (brand new
   * barcode) - that case is exactly what saveProduct/linkScannedBarcodeAndUploadPending in
   * MasterProductViewModel is for, not this dialog.
   */
  private boolean barcodeDefaultChanged() {
    if (learnedBarcode == null) {
      return false;
    }
    String currentAmount = formData.getAmountLive().getValue();
    boolean amountChanged = learnedBarcodeAppliedAmount != null && (
        !NumUtil.isStringDouble(currentAmount)
            || Math.abs(NumUtil.toDouble(currentAmount) - learnedBarcodeAppliedAmount) > 0.0001
    );
    QuantityUnit currentQu = formData.getQuantityUnitLive().getValue();
    boolean quChanged = learnedBarcodeAppliedQuId != null
        && (currentQu == null || currentQu.getId() != learnedBarcodeAppliedQuId);
    return amountChanged || quChanged;
  }

  private void updateLearnedBarcodeDefault() {
    if (learnedBarcode == null) {
      return;
    }
    // Partial body (amount + qu_id only, like linkOffPictureToProduct's picture_file_name-only
    // PUT elsewhere in this app) - never resend shopping_location_id/note/last_price from this
    // possibly-stale cached copy, which could last-write-wins overwrite a change made elsewhere
    // (e.g. the Grocy web UI) since this list was last synced.
    JSONObject body = new JSONObject();
    String amount = formData.getAmountLive().getValue();
    QuantityUnit currentQu = formData.getQuantityUnitLive().getValue();
    try {
      body.put("amount", amount != null ? amount : JSONObject.NULL);
      body.put("qu_id", currentQu != null ? currentQu.getId() : JSONObject.NULL);
    } catch (JSONException e) {
      if (debug) {
        Log.e(TAG, "updateLearnedBarcodeDefault: " + e);
      }
      return;
    }
    int barcodeId = learnedBarcode.getId();
    String barcodeString = learnedBarcode.getBarcode();
    dlHelper.put(
        grocyApi.getObject(GrocyApi.ENTITY.PRODUCT_BARCODES, barcodeId),
        body,
        response -> {
          // Only mutate the shared cached object (from the `barcodes` list loaded from Room)
          // once the write actually succeeded - never before, or a failed PUT would leave the
          // in-memory list claiming a default that was never actually saved server-side.
          learnedBarcode.setAmount(amount);
          if (currentQu != null) {
            learnedBarcode.setQuId(String.valueOf(currentQu.getId()));
          }
          if (debug) {
            Log.i(TAG, "updateLearnedBarcodeDefault: updated barcode " + barcodeString);
          }
        },
        error -> {
          if (debug) {
            Log.w(TAG, "updateLearnedBarcodeDefault: failed to update barcode "
                + barcodeString + ": " + describeVolleyError(error));
          }
        }
    );
  }

  public void purchaseProduct(boolean confirmed) {
    if (!formData.isFormValid()) {
      showMessage(R.string.error_missing_information);
      return;
    }
    if (storedPurchase != null) {
      overwriteStoredPurchase();
      return;
    }
    if (formData.getBarcodeLive().getValue() != null) {
      uploadProductBarcode(this::purchaseProduct);
      return;
    }
    if (formData.getPendingProductLive().getValue() != null) {
      purchasePendingProduct();
      return;
    }

    assert formData.getProductDetailsLive().getValue() != null;
    Product product = formData.getProductDetailsLive().getValue().getProduct();
    JSONObject body = formData.getFilledJSONObject();

    // Zero extra clicks in the common case: only asked about when a learned barcode default
    // actually exists AND the user changed the amount/unit away from it. Gated on its own
    // dedicated flag, NOT the shared `confirmed` parameter below - otherwise confirming this
    // dialog would also silently skip the separate CONFIRM_FREEZING safety prompt if both apply
    // to the same purchase.
    if (!barcodeDefaultDialogConfirmed && barcodeDefaultChanged()) {
      sendEvent(Event.CONFIRM_BARCODE_DEFAULT_CHANGE);
      return;
    }

    if (!confirmed && product.getShouldNotBeFrozenBoolean()
        && formData.getLocationLive().getValue() != null
        && formData.getLocationLive().getValue().getIsFreezerInt() == 1) {
      sendEvent(Event.CONFIRM_FREEZING);
      return;
    }

    OnJSONArrayResponseListener onResponse = response -> {
      // UNDO OPTION
      String transactionId = null;
      double amountPurchased = 0;
      try {
        transactionId = response.getJSONObject(0)
            .getString("transaction_id");
        for (int i = 0; i < response.length(); i++) {
          amountPurchased += response.getJSONObject(i).getDouble("amount");
        }
      } catch (JSONException e) {
        if (debug) {
          Log.e(TAG, "purchaseProduct: " + e);
        }
      }
      if (debug) {
        Log.i(TAG, "purchaseProduct: transaction successful");
      }

      SnackbarMessage snackbarMessage = new SnackbarMessage(
          formData.getTransactionSuccessMsg(amountPurchased)
      );
      if (transactionId != null) {
        String transId = transactionId;
        ShoppingListItem shoppingListItem = formData.getShoppingListItemLive().getValue();
        snackbarMessage.setAction(
            getString(R.string.action_undo),
            v -> undoTransaction(transId, shoppingListItem)
        );
        snackbarMessage.setDurationSecs(sharedPrefs.getInt(
                Constants.SETTINGS.BEHAVIOR.MESSAGE_DURATION,
                Constants.SETTINGS_DEFAULT.BEHAVIOR.MESSAGE_DURATION));
      }
      showSnackbar(snackbarMessage);
      sendEvent(Event.TRANSACTION_SUCCESS);
      if (updateBarcodeDefaultPending) {
        updateBarcodeDefaultPending = false;
        updateLearnedBarcodeDefault();
      }
    };

    dlHelper.postWithArray(
        grocyApi.purchaseProduct(product.getId()),
        body,
        response -> {
          ShoppingListItem shoppingListItem = formData.getShoppingListItemLive().getValue();
          if (batchShoppingListItemIds != null && shoppingListItem != null) {
            deleteShoppingListItem(shoppingListItem.getId(), () -> onResponse.onResponse(response));
          } else {
            onResponse.onResponse(response);
          }
        },
        error -> {
          // Never leave this armed for a later, unrelated purchase attempt: without clearing it
          // here, a failed purchase after "save as new default" was chosen would silently update
          // the barcode default on whatever purchase happens to succeed next - even one the user
          // explicitly chose "only use for this purchase" for.
          updateBarcodeDefaultPending = false;
          showNetworkErrorMessage(error);
          if (debug) {
            Log.i(TAG, "purchaseProduct: " + error);
          }
        }
    );
  }

  private void undoTransaction(String transactionId, @Nullable ShoppingListItem shoppingListItem) {
    dlHelper.post(
        grocyApi.undoStockTransaction(transactionId),
        success -> {
          showMessage(getString(R.string.msg_undone_transaction));
          if (shoppingListItem != null) undoDeleteShoppingListItem(shoppingListItem);
          if (debug) {
            Log.i(TAG, "undoTransaction: undone");
          }
        },
        this::showNetworkErrorMessage
    );
  }

  private void uploadProductBarcode(Runnable onSuccess) {
    if (formData.getPendingProductLive().getValue() != null) {
      storePendingProductBarcode(onSuccess);
      return;
    }
    ProductBarcode productBarcode = formData.fillProductBarcode();
    if (productBarcode == null) {
      // Defensive only: purchaseProduct() already checks formData.isFormValid() before calling
      // this, and fillProductBarcode() returns null exactly when the form is invalid, so this
      // should be unreachable in practice.
      if (debug) {
        Log.w(TAG, "uploadProductBarcode: form invalid, nothing to upload");
      }
      return;
    }
    // Never blindly create a second row for a barcode Grocy already knows about (it enforces
    // barcode uniqueness) - e.g. it may already have just been linked automatically when the
    // product was created from this exact scanned barcode (see
    // MasterProductViewModel#linkScannedBarcodeAndUploadPending). Re-posting the same barcode
    // would fail as a server-side duplicate and show a spurious error even though it is already
    // correctly linked.
    ProductBarcode existing = ProductBarcode.getFromBarcode(barcodes, productBarcode.getBarcode());
    if (existing != null && existing.getProductIdInt() == productBarcode.getProductIdInt()) {
      // Already linked to this exact product - nothing to do, safe to proceed.
      formData.getBarcodeLive().setValue(null);
      if (onSuccess != null) {
        onSuccess.run();
      }
      return;
    }
    if (existing != null) {
      // Belongs to a DIFFERENT product - that existing assignment is left untouched, never
      // overwritten or reassigned here. Matches this method's existing behavior for any other
      // barcode-link problem below (network/server error): inform the user and do NOT proceed,
      // rather than silently completing a purchase whose scanned barcode did not actually get
      // linked. Keeping this outcome the same regardless of whether the conflict was caught here
      // (via the locally cached barcode list) or only later by the server rejecting the POST
      // below as a duplicate (e.g. if the list was stale) avoids the same barcode conflict
      // producing two different results depending on cache freshness.
      if (debug) {
        Log.w(TAG, "uploadProductBarcode: barcode " + productBarcode.getBarcode()
            + " already belongs to product " + existing.getProductIdInt()
            + ", not linking it to product " + productBarcode.getProductIdInt());
      }
      showMessage(R.string.msg_barcode_duplicate);
      return;
    }
    JSONObject body = productBarcode.getJsonFromProductBarcode(debug, TAG);
    ProductBarcode.addProductBarcode(dlHelper, body, () -> {
      formData.getBarcodeLive().setValue(null);
      barcodes.add(productBarcode); // add to list so it will be found on next scan without reload
      if (onSuccess != null) {
        onSuccess.run();
      }
    }, error -> showMessage(R.string.error_failed_barcode_upload)).perform(dlHelper.getUuid());
  }

  private void purchasePendingProduct() {
    StoredPurchase productPurchase = formData.fillStoredPurchase(null);
    repository.insertStoredPurchase(productPurchase, id -> {
      SnackbarMessage snackbarMessage = new SnackbarMessage(
          formData.getTransactionSuccessMsg(NumUtil.isStringDouble(productPurchase.getAmount())
              ? NumUtil.toDouble(productPurchase.getAmount()) : 0)
      );
      snackbarMessage.setAction(
          getString(R.string.action_undo),
          v -> repository.deleteStoredPurchase(
              id,
              () -> showMessage(getString(R.string.msg_undone_transaction)),
              this::showErrorMessage
          )
      );
      snackbarMessage.setDurationSecs(sharedPrefs.getInt(
          Constants.SETTINGS.BEHAVIOR.MESSAGE_DURATION,
          Constants.SETTINGS_DEFAULT.BEHAVIOR.MESSAGE_DURATION)
      );
      showSnackbar(snackbarMessage);
      sendEvent(Event.TRANSACTION_SUCCESS);
    }, this::showErrorMessage);
  }

  private void storePendingProductBarcode(Runnable onSuccess) {
    PendingProductBarcode productBarcode = formData.fillPendingProductBarcode();
    formData.getBarcodeLive().setValue(null);
    barcodes.add(productBarcode); // add to list so it will be found on next scan without reload
    pendingProductBarcodes.add(productBarcode);
    repository.insertPendingProductBarcode(productBarcode, onSuccess);
  }

  private void overwriteStoredPurchase() {
    StoredPurchase storedPurchase = formData.fillStoredPurchase(this.storedPurchase);
    repository.insertStoredPurchase(
        storedPurchase,
        id -> sendEvent(Event.TRANSACTION_SUCCESS),
        this::showErrorMessage
    );
  }

  private void deleteShoppingListItem(int itemId, @NonNull Runnable onFinish) {
    dlHelper.delete(
        grocyApi.getObject(GrocyApi.ENTITY.SHOPPING_LIST, itemId),
        response -> onFinish.run(),
        error -> {
          if (debug) {
            Log.e(TAG, "deleteShoppingListItem: " + error);
          }
          onFinish.run();
        }
    );
  }

  private void undoDeleteShoppingListItem(@NonNull ShoppingListItem item) {
    dlHelper.post(
        grocyApi.getObjects(GrocyApi.ENTITY.SHOPPING_LIST),
        item.getJsonFromShoppingListItem(true, debug, TAG),
        response -> {},
        error -> {
          if (debug) {
            Log.e(TAG, "undoDeleteShoppingListItem: " + error);
          }
        }
    );
  }

  public HashMap<Integer, Product> getProductHashMap() {
    return productHashMap;
  }

  public HashMap<Integer, QuantityUnit> getQuantityUnitHashMap() {
    return quantityUnitHashMap;
  }

  private Store getStore(int id) {
    for (Store store : stores) {
      if (store.getId() == id) {
        return store;
      }
    }
    return null;
  }

  private void fillShoppingListItemAmountsHashMap() {
    shoppingListItemAmountsHashMap = new HashMap<>();
    if (shoppingListItems == null) {
      return;
    }
    boolean isGrocyServerMin400 = VersionUtil.isGrocyServerMin400(sharedPrefs);
    for (ShoppingListItem item : shoppingListItems) {
      Double amount = AmountUtil.getShoppingListItemAmount(
          item, productHashMap, quantityUnitHashMap, unitConversions, isGrocyServerMin400
      );
      if (amount != null) {
        shoppingListItemAmountsHashMap.put(item.getId(), amount);
      }
    }
  }

  public HashMap<Integer, Double> getShoppingListItemAmountsHashMap() {
    return shoppingListItemAmountsHashMap;
  }

  public void showInputProductBottomSheet(@NonNull String input) {
    Bundle bundle = new Bundle();
    bundle.putString(Constants.ARGUMENT.PRODUCT_INPUT, input);
    showBottomSheet(new InputProductBottomSheet(), bundle);
  }

  public void showQuantityUnitsBottomSheet(boolean hasFocus) {
    if (!hasFocus) {
      return;
    }
    HashMap<QuantityUnit, Double> unitsFactors = getFormData()
        .getQuantityUnitsFactorsLive().getValue();
    Bundle bundle = new Bundle();
    bundle.putParcelableArrayList(
        Constants.ARGUMENT.QUANTITY_UNITS,
        unitsFactors != null ? new ArrayList<>(unitsFactors.keySet()) : null
    );
    QuantityUnit quantityUnit = formData.getQuantityUnitLive().getValue();
    bundle.putInt(ARGUMENT.SELECTED_ID, quantityUnit != null ? quantityUnit.getId() : -1);
    showBottomSheet(new QuantityUnitsBottomSheet(), bundle);
  }

  public void showPurchasedDateBottomSheet() {
    if (!formData.isProductNameValid()) {
      return;
    }
    Bundle bundle = new Bundle();
    bundle.putString(Constants.ARGUMENT.DEFAULT_DAYS_FROM_NOW, String.valueOf(0));
    bundle.putString(
        Constants.ARGUMENT.SELECTED_DATE,
        formData.getPurchasedDateLive().getValue()
    );
    bundle.putInt(DateBottomSheet.DATE_TYPE, DateBottomSheet.PURCHASED_DATE);
    showBottomSheet(new DateBottomSheet(), bundle);
  }

  public void showDueDateBottomSheet(boolean hasFocus) {
    ProductDetails productDetails = formData.getProductDetailsLive().getValue();
    if (!hasFocus || !formData.isProductNameValid()) {
      return;
    }
    Bundle bundle = new Bundle();
    bundle.putString(
        Constants.ARGUMENT.DEFAULT_DAYS_FROM_NOW,
        productDetails != null
                ? String.valueOf(productDetails.getProduct().getDefaultDueDaysInt())
                : String.valueOf(0)
    );
    bundle.putString(
        Constants.ARGUMENT.SELECTED_DATE,
        formData.getDueDateLive().getValue()
    );
    bundle.putInt(DateBottomSheet.DATE_TYPE, DateBottomSheet.DUE_DATE);
    showBottomSheet(new DateBottomSheet(), bundle);
  }

  public void showStoresBottomSheet() {
    if (stores == null || stores.isEmpty()) {
      return;
    }
    Bundle bundle = new Bundle();
    bundle.putParcelableArrayList(Constants.ARGUMENT.STORES, new ArrayList<>(stores));
    bundle.putInt(
        Constants.ARGUMENT.SELECTED_ID,
        formData.getStoreLive().getValue() != null
            ? formData.getStoreLive().getValue().getId()
            : -1
    );
    bundle.putBoolean(ARGUMENT.DISPLAY_EMPTY_OPTION, true);
    bundle.putBoolean(ARGUMENT.NONE_SELECTABLE, !formData.isProductNameValid(false));
    bundle.putBoolean(ARGUMENT.DISPLAY_PIN_BUTTONS, true);
    Integer pinId = formData.getPinnedStoreIdLive().getValue();
    bundle.putInt(ARGUMENT.CURRENT_PIN_ID, pinId != null ? pinId : -1);
    showBottomSheet(new StoresBottomSheet(), bundle);
  }

  public void showLocationsBottomSheet() {
    if (!formData.isProductNameValid()) {
      return;
    }
    Bundle bundle = new Bundle();
    bundle.putParcelableArrayList(Constants.ARGUMENT.LOCATIONS, new ArrayList<>(locations));
    bundle.putInt(
        Constants.ARGUMENT.SELECTED_ID,
        formData.getLocationLive().getValue() != null
            ? formData.getLocationLive().getValue().getId()
            : -1
    );
    showBottomSheet(new LocationsBottomSheet(), bundle);
  }

  public void showProductChildrenBottomSheet(Product parentProduct) {
    ArrayList<Product> childrenProducts = Product.getProductChildren(products, parentProduct.getId());
    if (childrenProducts.isEmpty()) {
      showMessage(R.string.error_no_children);
      return;
    }
    Bundle bundle = new Bundle();
    bundle.putParcelableArrayList(ARGUMENT.PRODUCTS, childrenProducts);
    bundle.putInt(Constants.ARGUMENT.SELECTED_ID, -1);
    showBottomSheet(new ProductsBottomSheet(), bundle);
  }

  public void showConfirmationBottomSheet() {
    Bundle bundle = new Bundle();
    bundle.putString(Constants.ARGUMENT.TEXT, formData.getConfirmationText());
    showBottomSheet(new QuickModeConfirmBottomSheet(), bundle);
  }

  private void showMessageAndContinueScanning(String msg) {
    formData.clearForm();
    showMessage(msg);
    sendEvent(Event.CONTINUE_SCANNING);
  }

  private void showMessageAndContinueScanning(@StringRes int msg) {
    showMessageAndContinueScanning(getString(msg));
  }

  @NonNull
  public MutableLiveData<Boolean> getIsLoadingLive() {
    return isLoadingLive;
  }

  @NonNull
  public MutableLiveData<InfoFullscreen> getInfoFullscreenLive() {
    return infoFullscreenLive;
  }

  public void setQueueEmptyAction(Runnable queueEmptyAction) {
    this.queueEmptyAction = queueEmptyAction;
  }

  public void setProductWillBeFilled(boolean productWillBeFilled) {
    this.productWillBeFilled = productWillBeFilled;
  }

  public boolean isProductWillBeFilled() {
    return productWillBeFilled;
  }

  private ArrayList<Product> appendPendingProducts(
          ArrayList<Product> products,
          List<PendingProduct> pendingProducts
  ) {
    ArrayList<String> productStrings = new ArrayList<>();
    for (Product product : products) {
      productStrings.add(product.getName());
    }
    ArrayList<Product> newList = new ArrayList<>(products);
    if (pendingProducts != null) {
      for (PendingProduct pendingProduct : pendingProducts) {
        if (productStrings.contains(pendingProduct.getName())) continue;
        newList.add(pendingProduct);
      }
    }
    return newList;
  }

  private ArrayList<ProductBarcode> appendPendingProductBarcodes(
      List<ProductBarcode> productBarcodes,
      List<PendingProductBarcode> pendingProductBarcodes
  ) {
    ArrayList<String> barcodeStrings = new ArrayList<>();
    for (ProductBarcode productBarcode : productBarcodes) {
      barcodeStrings.add(productBarcode.getBarcode());
    }
    ArrayList<ProductBarcode> newList = new ArrayList<>(productBarcodes);
    for (PendingProductBarcode pendingProductBarcode : pendingProductBarcodes) {
      if (barcodeStrings.contains(pendingProductBarcode.getBarcode())) continue;
      newList.add(pendingProductBarcode);
    }
    return newList;
  }

  public boolean hasStoredPurchase() {
    return storedPurchaseId != null;
  }

  public int getMaxDecimalPlacesAmount() {
    return maxDecimalPlacesAmount;
  }

  public boolean isQuickModeEnabled() {
    if (quickModeEnabled.getValue() == null) {
      return false;
    }
    return quickModeEnabled.getValue();
  }

  public MutableLiveData<Boolean> getQuickModeEnabled() {
    return quickModeEnabled;
  }

  public boolean toggleQuickModeEnabled() {
    if (hasStoredPurchase()) return false;
    quickModeEnabled.setValue(!isQuickModeEnabled());
    sendEvent(isQuickModeEnabled() ? Event.QUICK_MODE_ENABLED : Event.QUICK_MODE_DISABLED);
    sharedPrefs.edit()
        .putBoolean(Constants.PREF.QUICK_MODE_ACTIVE_PURCHASE, isQuickModeEnabled())
        .apply();
    return true;
  }

  public boolean isTurnOnQuickModeEnabled() {
    return sharedPrefs.getBoolean(
        BEHAVIOR.TURN_ON_QUICK_MODE,
        SETTINGS_DEFAULT.BEHAVIOR.TURN_ON_QUICK_MODE
    );
  }

  public boolean isQuickModeReturnEnabled() {
    return sharedPrefs.getBoolean(
        BEHAVIOR.QUICK_MODE_RETURN,
        Constants.SETTINGS_DEFAULT.BEHAVIOR.QUICK_MODE_RETURN
    );
  }

  @Override
  protected void onCleared() {
    dlHelper.destroy();
    super.onCleared();
  }

  public static class PurchaseViewModelFactory implements ViewModelProvider.Factory {

    private final Application application;
    private final PurchaseFragmentArgs args;

    public PurchaseViewModelFactory(Application application, PurchaseFragmentArgs args) {
      this.application = application;
      this.args = args;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      return (T) new PurchaseViewModel(application, args);
    }
  }
}
