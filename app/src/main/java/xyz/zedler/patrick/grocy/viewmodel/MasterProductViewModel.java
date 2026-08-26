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
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import xyz.zedler.patrick.grocy.util.PrefsUtil;
import xyz.zedler.patrick.grocy.util.QuickPackagingSyncUtil;
import xyz.zedler.patrick.grocy.util.VersionUtil;
import xyz.zedler.patrick.grocy.web.NetworkQueue;

public class MasterProductViewModel extends BaseViewModel {

  private static final String TAG = MasterProductViewModel.class.getSimpleName();

  private final SharedPreferences sharedPrefs;
  private final DownloadHelper dlHelper;
  private final GrocyApi grocyApi;
  private final MasterProductRepository repository;
  private final FormDataMasterProduct formData;

  private final MutableLiveData<List<PendingProductBarcode>> pendingProductBarcodesLive;
  private final MutableLiveData<Boolean> isLoadingLive;
  private final MutableLiveData<InfoFullscreen> infoFullscreenLive;

  private static final List<String> QUICK_PACKAGING_LABELS = Arrays.asList(
      "Flasche", "Glas", "Dose", "Packung", "Beutel", "Karton", "Schachtel", "Schale",
      "Becher", "Tube", "Stueck"
  );
  private final MutableLiveData<String> quickPackagingLive;
  private final MutableLiveData<String> quickContentAmountLive;
  private final MutableLiveData<String> quickContentUnitLive;
  private final MutableLiveData<Boolean> quickPackagingQuMissingLive;
  private final MutableLiveData<Boolean> quickContentQuMissingLive;
  private final MutableLiveData<Boolean> showQuickPackagingEntryLive;
  private final int maxDecimalPlacesAmount;

  private List<Product> products;
  private List<ProductBarcode> productBarcodes;
  private List<PendingProductBarcode> pendingProductBarcodes;
  private List<QuantityUnit> quantityUnits;

  private Integer lastQuickAppliedStockQuId;
  private Integer lastQuickAppliedPurchaseQuId;
  private Integer lastQuickAppliedPriceQuId;
  private Integer lastQuickAppliedConsumeQuId;
  private Integer initialPresetStockQuId;
  private Integer initialPresetPurchaseQuId;
  private Integer initialPresetPriceQuId;
  private Integer initialPresetConsumeQuId;
  private final Observer<String> quickContentAmountObserver =
      amount -> syncQuickPackagingToProduct();

  private NetworkQueue.QueueItem extraQueueItem;
  private final boolean debug;
  private final MutableLiveData<Boolean> actionEditLive;
  private final MasterProductFragmentArgs args;
  private final boolean forceSaveWithClose;

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

    boolean isClone = args.getProduct() != null || NumUtil.isStringInt(args.getProductId());
    boolean isGenuinelyNewScannedProduct = !isActionEdit() && !isClone
        && args.getPendingProductBarcodes() != null;
    // Interim substitute for hasScannedBarcode()/the Etappe-6 flag; revisit when Etappe 6 lands.
    showQuickPackagingEntryLive = new MutableLiveData<>(isGenuinelyNewScannedProduct);
    maxDecimalPlacesAmount = sharedPrefs.getInt(
        STOCK.DECIMAL_PLACES_AMOUNT,
        SETTINGS_DEFAULT.STOCK.DECIMAL_PLACES_AMOUNT
    );
    quickPackagingLive = new MutableLiveData<>();
    quickContentAmountLive = new MutableLiveData<>();
    quickContentUnitLive = new MutableLiveData<>();
    quickPackagingQuMissingLive = new MutableLiveData<>(false);
    quickContentQuMissingLive = new MutableLiveData<>(false);
    quickContentAmountLive.observeForever(quickContentAmountObserver);

    pendingProductBarcodesLive = new MutableLiveData<>();
    infoFullscreenLive = new MutableLiveData<>();

    if (isActionEdit()) {
      if (args.getProduct() != null) {
        Product product = args.getProduct();
        setCurrentProduct(product);
        captureInitialQuantityUnits(product);
      } else {
        assert args.getProductId() != null;
        int productId = Integer.parseInt(args.getProductId());
        extraQueueItem = ProductDetails.getProductDetails(dlHelper, productId, productDetails -> {
          extraQueueItem = null;
          formData.getProductNamesLive().setValue(
              getProductNames(products, productDetails.getProduct().getName())
          );
          Product product = productDetails.getProduct();
          setCurrentProduct(product);
          captureInitialQuantityUnits(product);
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
        captureInitialQuantityUnits(product);
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
          captureInitialQuantityUnits(product);
        });
      }
    } else {
      Product product = new Product(sharedPrefs);
      if (args.getProductName() != null) {
        product.setName(args.getProductName());
      } else {
        sendEvent(Event.FOCUS_INVALID_VIEWS);
      }
      setCurrentProduct(product);
      captureInitialQuantityUnits(product);
    }
  }

  private void captureInitialQuantityUnits(Product product) {
    initialPresetStockQuId = product.getQuIdStockInt();
    initialPresetPurchaseQuId = product.getQuIdPurchaseInt();
    initialPresetPriceQuId = product.getQuIdPriceInt();
    initialPresetConsumeQuId = product.getQuIdConsumeInt();
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
          return null;
        }
        match = quantityUnit;
      }
    }
    return match;
  }

  public void setQuickPackaging(@Nullable String name) {
    quickPackagingLive.setValue(name);
    updateQuickQuMissingFlags();
    syncQuickPackagingToProduct();
  }

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

  public void createQuickQuantityUnit(
      @Nullable String name,
      @Nullable Consumer<QuantityUnit> onCreated
  ) {
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
        ProductBarcode.class,
        QuantityUnit.class
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

    Product product = getFilledProduct();
    JSONObject jsonObject = product.getJsonFromProduct(sharedPrefs, debug, TAG);

    if (isActionEdit()) {
      dlHelper.put(
          grocyApi.getObject(GrocyApi.ENTITY.PRODUCTS, product.getId()),
          jsonObject,
          response -> {
            Bundle bundle = new Bundle();
            bundle.putInt(Constants.ARGUMENT.PRODUCT_ID, product.getId());
            sendEvent(Event.SET_PRODUCT_ID, bundle);
            sendEvent(Event.NAVIGATE_UP);
          },
          error -> {
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
            int objectId = -1;
            try {
              objectId = response.getInt("created_object_id");
              Log.i(TAG, "saveProduct: " + objectId);
            } catch (JSONException e) {
              if (debug) {
                Log.e(TAG, "saveProduct: " + e);
              }
            }
            Integer packagingQuId = null;
            if (objectId != -1
                && Boolean.TRUE.equals(showQuickPackagingEntryLive.getValue())) {
              product.setId(objectId);
              packagingQuId = resolveQuickQuId(quickPackagingLive.getValue());
            }
            int finalObjectId = objectId;
            Integer finalPackagingQuId = packagingQuId;
            if (withClosing) {
              if (objectId != -1) {
                Bundle bundle = new Bundle();
                bundle.putInt(Constants.ARGUMENT.PRODUCT_ID, objectId);
                sendEvent(Event.SET_PRODUCT_ID, bundle);
              }
              Runnable proceed =
                  () -> uploadBarcodesIfNecessary(finalObjectId, () -> sendEvent(Event.NAVIGATE_UP));
              if (finalPackagingQuId != null) {
                applyQuickPackagingAndContent(
                    finalObjectId, product, finalPackagingQuId, proceed
                );
              } else {
                proceed.run();
              }
            } else {
              Runnable proceed = () -> uploadBarcodesIfNecessary(finalObjectId, () -> {
                actionEditLive.setValue(true);
                product.setId(finalObjectId);
                setCurrentProduct(product);
                sendEvent(Event.TRANSACTION_SUCCESS);
              });
              if (finalPackagingQuId != null) {
                applyQuickPackagingAndContent(
                    finalObjectId, product, finalPackagingQuId, proceed
                );
              } else {
                proceed.run();
              }
            }
          },
          error -> {
            showNetworkErrorMessage(error);
            if (debug) {
              Log.e(TAG, "saveProduct: " + error);
            }
          }
      );
    }
  }

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
