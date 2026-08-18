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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import xyz.zedler.patrick.grocy.Constants;

/**
 * Resolves the {@code best_before_date} value to send with a stock purchase POST for the merged
 * "Dieser Einkauf" section (see MasterProductViewModel#buildPurchaseJson), so a household that
 * simply hasn't entered a real MHD/Verbrauchsdatum yet never has one silently fabricated.
 * <p>
 * Grocy's own API ({@code /stock/products/{id}/add}) explicitly documents that omitting
 * {@code best_before_date} makes the SERVER default it to today's date - never what we want when
 * the field was deliberately left blank (task docs: "kein künstlicher Ablauf heute"). This never
 * omits the key: it always resolves to either the user's own entered date, or the same
 * {@link Constants.DATE#NEVER_OVERDUE} sentinel this app already sends elsewhere (see
 * {@code FormDataPurchase#getFilledJSONObject()}) when BBD tracking itself is off - not a
 * perfect stand-in for "unknown", but strictly less wrong than asserting an already-expiring
 * date nobody ever entered.
 */
public final class PurchaseDueDateUtil {

  private PurchaseDueDateUtil() {
  }

  /**
   * @param enteredDueDate the date the user actually typed/picked on the "Dieser Einkauf"
   *     section, or null/blank if they left it empty.
   * @return {@code enteredDueDate} itself if non-blank, otherwise the "never overdue" sentinel -
   *     never null, so the caller can always send this value explicitly rather than omitting the
   *     key (which Grocy's API would otherwise silently interpret as "today").
   */
  @NonNull
  public static String resolveBestBeforeDate(@Nullable String enteredDueDate) {
    return enteredDueDate != null && !enteredDueDate.isBlank()
        ? enteredDueDate : Constants.DATE.NEVER_OVERDUE;
  }
}
