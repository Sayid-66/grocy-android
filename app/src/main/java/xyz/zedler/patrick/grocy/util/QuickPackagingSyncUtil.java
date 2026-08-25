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
 * Pure decision logic for keeping the "quick packaging/content entry" card (see
 * MasterProductViewModel) and the real Grocy {@code Product} quantity unit fields (quIdStock/
 * quIdPurchase/quIdConsume/quIdPrice) as ONE single source of truth, instead of two parallel,
 * possibly-contradicting configurations (quick card vs. classic quantity unit screen). Split out
 * from MasterProductViewModel so this decision logic - which never touches Android/LiveData - can
 * be unit-tested directly.
 * <p>
 * Household stock model: for a normal packaged household product, the PACKAGING (e.g. "Flasche")
 * is the single quantity unit for stock, purchase, consume AND price alike - never a separate
 * "content" unit (e.g. "ml") split out for stock tracking. A household is not meant to book stock
 * changes in individual millilitres/grams; "1 Flasche" bought is "1 Flasche" in stock is "1
 * Flasche" consumed once empty. The confirmed content amount/unit (e.g. "0,5 l") is preserved
 * separately, only as a {@link xyz.zedler.patrick.grocy.model.QuantityUnitConversion} from the
 * packaging to the content unit - never as the stock unit itself - so recipes/energy
 * calculations/future features still know "1 Flasche = 500 ml" without forcing the household to
 * track stock in millilitres day to day.
 */
public final class QuickPackagingSyncUtil {

  private QuickPackagingSyncUtil() {
  }

  /**
   * Whether a product quantity unit field the quick card wants to (re)apply a new id to may
   * safely be overwritten right now:
   * - always, if it was never set at all (-1),
   * - once the quick card itself has applied something there before ({@code lastQuickAppliedQuId}
   *   non-null), only if it still holds exactly that value - anything else means the user picked
   *   something different on the classic quantity unit screen since, and that manual choice must
   *   never be silently reverted by a later quick-card change,
   * - before the quick card has ever applied anything there yet, only if it still holds exactly
   *   the product's original, untouched ambient preset ({@code initialPresetQuId} - e.g. a
   *   "default new-product quantity unit" from Settings, captured once at construction, before
   *   the quick card could apply anything) - this is what lets the quick card's FIRST resolved
   *   selection (e.g. once a missing quantity unit is created a moment after the product was
   *   constructed - see MasterProductViewModel#createQuickQuantityUnit) still win over a generic
   *   ambient default, while still never touching a value the user already changed elsewhere in
   *   the meantime.
   */
  public static boolean isQuickOwned(
      int currentQuId, @Nullable Integer initialPresetQuId, @Nullable Integer lastQuickAppliedQuId
  ) {
    if (currentQuId == -1) {
      return true;
    }
    if (lastQuickAppliedQuId != null) {
      return currentQuId == lastQuickAppliedQuId;
    }
    return initialPresetQuId != null && currentQuId == initialPresetQuId;
  }
}
