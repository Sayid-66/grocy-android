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

import org.junit.Test;
import xyz.zedler.patrick.grocy.Constants;

public class PurchaseDueDateUtilTest {

  @Test
  public void enteredDate_isUsedAsIs() {
    assertEquals("2027-02-12", PurchaseDueDateUtil.resolveBestBeforeDate("2027-02-12"));
  }

  @Test
  public void nullDate_neverBecomesToday_resolvesToNeverOverdueSentinel() {
    // This is the exact regression this test guards against: Grocy's own API defaults an
    // OMITTED best_before_date to today server-side - a blank "Dieser Einkauf" date field must
    // never be sent as if it meant "expires today".
    assertEquals(Constants.DATE.NEVER_OVERDUE, PurchaseDueDateUtil.resolveBestBeforeDate(null));
  }

  @Test
  public void blankDate_resolvesToNeverOverdueSentinel() {
    assertEquals(Constants.DATE.NEVER_OVERDUE, PurchaseDueDateUtil.resolveBestBeforeDate(""));
    assertEquals(Constants.DATE.NEVER_OVERDUE, PurchaseDueDateUtil.resolveBestBeforeDate("   "));
  }

  @Test
  public void neverOverdueSentinel_isNeverATodayLookingDate() {
    // Sanity check on the sentinel itself: it must not accidentally be today's date or an empty
    // string - it's the same far-future "never overdue" convention already used elsewhere in
    // this app (e.g. FormDataPurchase) for "no real due date to report".
    assertEquals("2999-12-31", Constants.DATE.NEVER_OVERDUE);
  }
}
