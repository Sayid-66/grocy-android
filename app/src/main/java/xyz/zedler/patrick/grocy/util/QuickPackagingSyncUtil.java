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

  /**
   * The Grocy quantity unit id the product's STOCK unit should be set to, given the quick card's
   * current (resolved, i.e. already matched to a real Grocy quantity unit - never a raw label)
   * selections: the confirmed content unit if a PACKAGING unit is ALSO confirmed and a positive
   * numeric content amount is present (e.g. "250 ml" stock-tracked separately from "1 Flasche"
   * purchased/consumed), otherwise just the packaging unit itself (which may itself be null, i.e.
   * nothing confirmed yet at all).
   * <p>
   * The stock unit is deliberately never set to the content unit ALONE, without a packaging unit
   * also confirmed: the whole point of splitting stock from purchase/price is the
   * {@link xyz.zedler.patrick.grocy.model.QuantityUnitConversion} between them (1 packaging unit
   * = &lt;content amount&gt; content units) that MasterProductViewModel#applyQuickPackagingAndContent
   * creates right after - and that conversion itself needs a packaging unit as its "from" side.
   * Without one, the packaging/purchase/price fields elsewhere would keep pointing at an
   * unrelated ambient default (or stay unset) while stock silently switched to the content unit
   * with NO conversion between them at all - i.e. exactly the "keine stille Umrechnung aus
   * unsicheren Daten" rule this whole feature must never violate. Never guesses: returns null if
   * nothing is resolved at all.
   */
  @Nullable
  public static Integer resolveEffectiveStockQuId(
      @Nullable Integer packagingQuId,
      @Nullable Integer contentQuId,
      @Nullable String contentAmount
  ) {
    boolean validAmount = NumUtil.isStringDouble(contentAmount)
        && NumUtil.toDouble(contentAmount) > 0;
    if (packagingQuId != null && contentQuId != null && validAmount) {
      return contentQuId;
    }
    return packagingQuId;
  }
}
