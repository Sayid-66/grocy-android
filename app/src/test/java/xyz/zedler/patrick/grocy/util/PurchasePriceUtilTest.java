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

package xyz.zedler.patrick.grocy.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PurchasePriceUtilTest {

  @Test
  public void pricePerPackage_notTotal_returnsSameValue() {
    assertEquals(
        Double.valueOf(2.5),
        PurchasePriceUtil.computePricePerStockUnit("2.5", "2", false)
    );
  }

  @Test
  public void totalPrice_dividedByAmount_returnsPricePerPackage() {
    // 2 Flaschen, Gesamtpreis 5,00 EUR -> 2,50 EUR pro Flasche
    assertEquals(
        Double.valueOf(2.5),
        PurchasePriceUtil.computePricePerStockUnit("5.0", "2", true)
    );
  }

  @Test
  public void singlePackageTotalPrice_equalsPricePerPackage() {
    assertEquals(
        Double.valueOf(4.99),
        PurchasePriceUtil.computePricePerStockUnit("4.99", "1", true)
    );
  }

  @Test
  public void blankPrice_returnsNull() {
    assertNull(PurchasePriceUtil.computePricePerStockUnit("", "1", false));
    assertNull(PurchasePriceUtil.computePricePerStockUnit(null, "1", false));
  }

  @Test
  public void nonNumericPrice_returnsNull() {
    assertNull(PurchasePriceUtil.computePricePerStockUnit("abc", "1", false));
  }

  @Test
  public void blankOrZeroAmount_returnsNull() {
    assertNull(PurchasePriceUtil.computePricePerStockUnit("5", "", true));
    assertNull(PurchasePriceUtil.computePricePerStockUnit("5", "0", true));
    assertNull(PurchasePriceUtil.computePricePerStockUnit("5", "-1", true));
  }

  @Test
  public void notTotalPrice_amountIrrelevantAsLongAsValid() {
    // Not a total price -> the entered value already IS the per-package price, regardless of how
    // many packages are being purchased.
    assertEquals(
        Double.valueOf(1.29),
        PurchasePriceUtil.computePricePerStockUnit("1.29", "5", false)
    );
  }
}
