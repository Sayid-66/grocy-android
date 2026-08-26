/*
 * This file is part of Grocy Android.
 *
 * Grocy Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package xyz.zedler.patrick.grocy.fragment;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import xyz.zedler.patrick.grocy.model.ProductBarcode;
import xyz.zedler.patrick.grocy.viewmodel.PurchaseViewModel;

@RunWith(RobolectricTestRunner.class)
public class PurchaseFragmentBarcodeReturnTest {

  @Test
  public void purchaseReturnDirectionMarksChooseProductFlowAsPurchase() {
    PurchaseFragmentDirections.ActionPurchaseFragmentToChooseProductFragment direction =
        PurchaseFragment.buildChooseProductDirection("4000000000001", false);

    assertTrue(direction.getFromPurchase());
  }

  @Test
  public void alreadyBookedReturnDoesNotOfferSameDeliveryAgain() {
    PurchaseViewModel viewModel = mock(PurchaseViewModel.class);

    PurchaseFragment.applyReturnedProduct(
        viewModel, "4000000000001", true, true, 42
    );

    verifyNoInteractions(viewModel);
  }

  @Test
  public void handledBarcodeReturnCallsJustLinkedBarcodePath() {
    PurchaseViewModel viewModel = mock(PurchaseViewModel.class);
    ArgumentCaptor<Runnable> actionCaptor = ArgumentCaptor.forClass(Runnable.class);

    PurchaseFragment.applyReturnedProduct(
        viewModel, "4000000000001", true, false, 42
    );
    verify(viewModel).setQueueEmptyAction(actionCaptor.capture());
    actionCaptor.getValue().run();

    verify(viewModel).setProductFromJustLinkedBarcode(42, "4000000000001");
  }

  @Test
  public void normalBarcodeReturnLinksBarcodeAndUsesNormalProductPath() {
    PurchaseViewModel viewModel = mock(PurchaseViewModel.class);
    ArgumentCaptor<Runnable> actionCaptor = ArgumentCaptor.forClass(Runnable.class);

    PurchaseFragment.applyReturnedProduct(
        viewModel, "4000000000001", false, false, 42
    );
    verify(viewModel).addBarcodeToExistingProduct("4000000000001");
    verify(viewModel).setQueueEmptyAction(actionCaptor.capture());
    actionCaptor.getValue().run();

    verify(viewModel).setProduct(42, null, null);
  }

  @Test
  public void laterScanUsesJustLinkedBarcodeDefaultsWithoutRelinking() throws Exception {
    ProductBarcode linked = new ProductBarcode();
    linked.setProductIdInt(42);
    linked.setBarcode("4000000000001");
    linked.setAmount("1");
    linked.setQuId("10");

    PurchaseViewModel viewModel = mock(PurchaseViewModel.class, CALLS_REAL_METHODS);
    setField(viewModel, "barcodes", List.of(linked));
    doNothing().when(viewModel).setProduct(anyInt(), any(), isNull());

    viewModel.setProductFromJustLinkedBarcode(42, "4000000000001");

    ArgumentCaptor<ProductBarcode> barcodeCaptor = ArgumentCaptor.forClass(ProductBarcode.class);
    verify(viewModel).setProduct(anyInt(), barcodeCaptor.capture(), isNull());
    ProductBarcode selected = barcodeCaptor.getValue();
    assertTrue(selected == linked);
    assertTrue("1".equals(selected.getAmount()));
    assertTrue("10".equals(selected.getQuId()));
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = PurchaseViewModel.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
