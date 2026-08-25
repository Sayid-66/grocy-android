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

import androidx.annotation.Nullable;

/**
 * Resolves "Preis pro Verpackung" vs. "Gesamtpreis" for the merged "Dieser Einkauf" section (see
 * MasterProductViewModel) into a single price per stock unit, exactly like the classic Purchase
 * screen's {@link QuantityUnitConversionUtil#getPriceStock} does for a scanned/selected product -
 * but deliberately without any quantity-unit-factor conversion: in this household stock model
 * (see QuickPackagingSyncUtil) the purchase unit IS the stock unit by construction, so that factor
 * is always 1 and does not need to be looked up here. Never invents a price: null in, null out.
 */
public final class PurchasePriceUtil {

  private PurchasePriceUtil() {
  }

  /**
   * @param price the entered price (either per package or total, depending on {@code
   *     isTotalPrice}), as a plain decimal string, or null/blank/non-numeric if not entered.
   * @param amount the entered purchase amount (number of packages), as a plain decimal string.
   * @param isTotalPrice whether {@code price} is the total for {@code amount} packages (true) or
   *     already the price of a single package (false).
   * @return the price per stock unit (= per package), or null if {@code price} or {@code amount}
   *     is missing/not a valid positive number - the user simply left the price empty, which must
   *     never be turned into an invented "0".
   */
  @Nullable
  public static Double computePricePerStockUnit(
      @Nullable String price, @Nullable String amount, boolean isTotalPrice
  ) {
    if (!NumUtil.isStringDouble(price) || !NumUtil.isStringDouble(amount)) {
      return null;
    }
    double amountValue = NumUtil.toDouble(amount);
    if (amountValue <= 0) {
      return null;
    }
    double priceValue = NumUtil.toDouble(price);
    // NumUtil#toDouble() itself falls back to -1 for genuinely unparseable text (e.g. "abc") -
    // never propagating a NumberFormatException - so NumUtil#isStringDouble() alone cannot
    // reliably reject non-numeric garbage above. A real price is never negative, so this is also
    // the correct, defensive rejection for that case, not just a workaround.
    if (priceValue < 0) {
      return null;
    }
    return isTotalPrice ? priceValue / amountValue : priceValue;
  }
}
