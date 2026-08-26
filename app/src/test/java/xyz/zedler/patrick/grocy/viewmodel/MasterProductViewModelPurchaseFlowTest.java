/*
 * This file is part of Grocy Android.
 *
 * Grocy Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package xyz.zedler.patrick.grocy.viewmodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import android.app.Application;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import com.android.volley.VolleyError;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.invocation.InvocationOnMock;
import org.robolectric.RobolectricTestRunner;
import xyz.zedler.patrick.grocy.Constants;
import xyz.zedler.patrick.grocy.api.GrocyApi;
import xyz.zedler.patrick.grocy.form.FormDataMasterProduct;
import xyz.zedler.patrick.grocy.fragment.MasterProductFragmentArgs;
import xyz.zedler.patrick.grocy.helper.DownloadHelper;
import xyz.zedler.patrick.grocy.model.Event;
import xyz.zedler.patrick.grocy.model.Product;
import xyz.zedler.patrick.grocy.model.QuantityUnit;

/**
 * Etappe 8: MasterProductViewModel's saveProduct() -> barcode link -> conversion -> bookPurchase()
 * chain, retry/error paths and double-booking guards.
 *
 * <p>{@code GrocyApi}'s and {@code DownloadHelper}'s real constructors are not exercised here -
 * both are intercepted via {@link org.mockito.Mockito#mockConstruction}. Their real construction
 * (through {@link MasterProductViewModel}'s untouched, already-integrated constructor) reads app
 * resources (e.g. {@code R.raw.locales} via {@code LocaleUtil}) and opens a real Volley
 * {@code RequestQueue}, neither of which is available/needed for a Robolectric unit test that
 * only exercises the purchase-orchestration logic added in this stage. This keeps the test fully
 * isolated from network/resource infrastructure without changing any production code.
 */
@RunWith(RobolectricTestRunner.class)
public class MasterProductViewModelPurchaseFlowTest {

  @Rule public final InstantTaskExecutorRule instantTaskExecutorRule =
      new InstantTaskExecutorRule();

  private MasterProductViewModel viewModel;
  private RequestRecorder requests;
  private final List<Integer> events = new ArrayList<>();
  private MockedConstruction<GrocyApi> grocyApiConstruction;
  private MockedConstruction<DownloadHelper> downloadHelperConstruction;

  @Before
  public void setUp() throws Exception {
    Application application = ApplicationProvider.getApplicationContext();
    PreferenceManager.getDefaultSharedPreferences(application).edit()
        .putString(Constants.PREF.SERVER_URL, "https://example.test/api/")
        .putString(Constants.PREF.GROCY_VERSION, "4.0.0")
        .putBoolean(Constants.PREF.FEATURE_STOCK_BBD_TRACKING, true)
        .apply();

    requests = new RequestRecorder();
    // Only the URL-building methods MasterProductViewModel actually calls are stubbed; nothing
    // about the real GrocyApi class is invoked.
    grocyApiConstruction = mockConstruction(GrocyApi.class, (apiMock, context) -> {
      when(apiMock.getObjects(anyString()))
          .thenAnswer(inv -> "/objects/" + (String) inv.getArgument(0));
      when(apiMock.getObject(anyString(), anyInt())).thenAnswer(inv ->
          "/objects/" + (String) inv.getArgument(0) + "/" + inv.<Integer>getArgument(1));
      when(apiMock.purchaseProduct(anyInt()))
          .thenAnswer(inv -> "/stock/products/" + inv.<Integer>getArgument(0) + "/add");
    });
    downloadHelperConstruction =
        mockConstruction(DownloadHelper.class, (helperMock, context) -> requests.attachTo(helperMock));

    MasterProductFragmentArgs args = new MasterProductFragmentArgs.Builder(Constants.ACTION.CREATE)
        .setProductName("Test milk")
        .setBarcode("4000000000001")
        .setFromPurchase(true)
        .setOffNutritionUnreliable(false)
        .build();
    viewModel = new MasterProductViewModel(application, args);

    Product product = new Product(PreferenceManager.getDefaultSharedPreferences(application));
    product.setName("Test milk");
    product.setQuIdStock(10);
    product.setQuIdPurchase(10);
    product.setQuIdConsume(10);
    product.setQuIdPrice(10);
    MutableLiveData<Product> productLive = new MutableLiveData<>(product);
    FormDataMasterProduct formData = mock(FormDataMasterProduct.class);
    when(formData.isWholeFormValid()).thenReturn(true);
    when(formData.getProductLive()).thenReturn(productLive);
    when(formData.fillProduct(any(Product.class))).thenReturn(product);
    setField(viewModel, "formData", formData);

    setField(viewModel, "quantityUnits", List.of(
        new QuantityUnit(10, "Flasche"), new QuantityUnit(11, "ml")
    ));
    setField(viewModel, "productBarcodes", Collections.emptyList());
    viewModel.getQuickPackagingLive().setValue("Flasche");
    viewModel.getQuickContentAmountLive().setValue("500");
    viewModel.getQuickContentUnitLive().setValue("ml");
    viewModel.getPurchaseAmountLive().setValue("2");

    viewModel.getEventHandler().observeForever(event -> events.add(event.getType()));
  }

  @After
  public void tearDown() {
    downloadHelperConstruction.close();
    grocyApiConstruction.close();
  }

  @Test
  public void happyPathWaitsForProductBarcodeConversionAndPurchaseInThatOrder() throws Exception {
    viewModel.saveProduct(true);

    assertPaths("/objects/products");
    requests.last().succeed(new JSONObject().put("created_object_id", 42));
    assertPaths("/objects/products", "/objects/product_barcodes");

    requests.last().succeed(new JSONObject());
    assertPaths("/objects/products", "/objects/product_barcodes",
        "/objects/quantity_unit_conversions");

    requests.last().succeed(new JSONObject());
    assertPaths("/objects/products", "/objects/product_barcodes",
        "/objects/quantity_unit_conversions", "/stock/products/42/add");
    assertEquals("2", requests.last().body.getString("amount"));
    assertEquals(Constants.DATE.NEVER_OVERDUE,
        requests.last().body.getString("best_before_date"));
    assertFalse(events.contains(Event.NAVIGATE_UP));

    requests.last().succeed(new JSONArray());
    assertTrue(events.contains(Event.NAVIGATE_UP));
    assertTrue(viewModel.isPurchaseBooked());
  }

  @Test
  public void finishTapAfterPurchaseFailureUpdatesExistingProductThenRetriesOnlyPurchase()
      throws Exception {
    reachPurchaseRequest();
    requests.last().fail();

    assertTrue(Boolean.TRUE.equals(viewModel.getPurchaseFailedLive().getValue()));
    assertFalse(viewModel.isPurchaseBooked());
    int requestCountAfterFailure = requests.all.size();

    viewModel.saveProduct(true);
    assertEquals(requestCountAfterFailure + 1, requests.all.size());
    assertTrue(requests.last().url.contains("/objects/products/42"));

    requests.last().succeed(new JSONObject());
    assertEquals(requestCountAfterFailure + 2, requests.all.size());
    assertTrue(requests.last().url.contains("/stock/products/42/add"));
    assertEquals(1, requests.count("/objects/products"));
    assertEquals(1, requests.count("/objects/product_barcodes"));
    assertEquals(1, requests.count("/objects/quantity_unit_conversions"));
  }

  @Test
  public void retryActionAfterPurchaseFailureRetriesOnlyPurchase() throws Exception {
    reachPurchaseRequest();
    requests.last().fail();
    int requestCountAfterFailure = requests.all.size();

    viewModel.retryPurchase();

    assertEquals(requestCountAfterFailure + 1, requests.all.size());
    assertTrue(requests.last().url.contains("/stock/products/42/add"));
    assertEquals(1, requests.count("/objects/products"));
    assertEquals(1, requests.count("/objects/product_barcodes"));
    assertEquals(1, requests.count("/objects/quantity_unit_conversions"));
  }

  @Test
  public void rapidFinishTapsNeverStartASecondPost() throws Exception {
    viewModel.saveProduct(true);
    viewModel.saveProduct(true);
    assertEquals(1, requests.all.size());

    requests.last().succeed(new JSONObject().put("created_object_id", 42));
    viewModel.saveProduct(true);
    assertEquals(2, requests.all.size());
    assertEquals(1, requests.count("/objects/products"));
  }

  private void reachPurchaseRequest() throws Exception {
    viewModel.saveProduct(true);
    requests.last().succeed(new JSONObject().put("created_object_id", 42));
    requests.last().succeed(new JSONObject());
    requests.last().succeed(new JSONObject());
    assertTrue(requests.last().url.contains("/stock/products/42/add"));
  }

  private void assertPaths(String... expected) {
    assertEquals(expected.length, requests.all.size());
    for (int index = 0; index < expected.length; index++) {
      assertTrue(requests.all.get(index).url, requests.all.get(index).url.contains(expected[index]));
    }
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static final class RequestRecorder {
    private final List<PendingRequest> all = new ArrayList<>();

    private void attachTo(DownloadHelper downloadHelper) {
      doAnswer(invocation -> recordObject(invocation, false)).when(downloadHelper).post(
          anyString(), any(JSONObject.class),
          any(DownloadHelper.OnJSONResponseListener.class),
          any(DownloadHelper.OnErrorListener.class)
      );
      doAnswer(invocation -> recordObject(invocation, false)).when(downloadHelper).put(
          anyString(), any(JSONObject.class),
          any(DownloadHelper.OnJSONResponseListener.class),
          any(DownloadHelper.OnErrorListener.class)
      );
      doAnswer(invocation -> recordObject(invocation, true)).when(downloadHelper).postWithArray(
          anyString(), any(JSONObject.class),
          any(DownloadHelper.OnJSONArrayResponseListener.class),
          any(DownloadHelper.OnErrorListener.class)
      );
    }

    private Object recordObject(InvocationOnMock invocation, boolean arrayResponse) {
      all.add(new PendingRequest(
          invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
          invocation.getArgument(3), arrayResponse
      ));
      return null;
    }

    private PendingRequest last() {
      return all.get(all.size() - 1);
    }

    private int count(String path) {
      int count = 0;
      for (PendingRequest request : all) {
        if (request.url.endsWith(path)) {
          count++;
        }
      }
      return count;
    }
  }

  private static final class PendingRequest {
    private final String url;
    private final JSONObject body;
    private final Object success;
    private final DownloadHelper.OnErrorListener error;
    private final boolean arrayResponse;

    private PendingRequest(
        String url, JSONObject body, Object success, Object error, boolean arrayResponse
    ) {
      this.url = url;
      this.body = body;
      this.success = success;
      this.error = (DownloadHelper.OnErrorListener) error;
      this.arrayResponse = arrayResponse;
    }

    private void succeed(Object response) {
      if (arrayResponse) {
        ((DownloadHelper.OnJSONArrayResponseListener) success).onResponse((JSONArray) response);
      } else {
        ((DownloadHelper.OnJSONResponseListener) success).onResponse((JSONObject) response);
      }
    }

    private void fail() {
      error.onError(new VolleyError("expected test failure"));
    }
  }
}
