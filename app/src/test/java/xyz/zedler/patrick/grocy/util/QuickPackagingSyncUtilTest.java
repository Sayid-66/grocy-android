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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QuickPackagingSyncUtilTest {

  // isQuickOwned

  @Test
  public void unsetField_isQuickOwned() {
    assertTrue(QuickPackagingSyncUtil.isQuickOwned(-1, null, null));
    assertTrue(QuickPackagingSyncUtil.isQuickOwned(-1, 2, 5));
  }

  @Test
  public void fieldStillMatchingLastQuickValue_isQuickOwned() {
    assertTrue(QuickPackagingSyncUtil.isQuickOwned(5, null, 5));
  }

  @Test
  public void fieldStillMatchingUntouchedOriginalPreset_isQuickOwned() {
    // Nothing (neither quick card nor the user) has touched this field yet - it's still exactly
    // the ambient "default new-product quantity unit" from Settings the product was constructed
    // with, so the quick card's first resolved selection may still apply here.
    assertTrue(QuickPackagingSyncUtil.isQuickOwned(3, 3, null));
  }

  @Test
  public void fieldNeverTouchedByQuickCardAndNoOriginalPresetRecorded_isNotQuickOwned() {
    assertFalse(QuickPackagingSyncUtil.isQuickOwned(3, null, null));
  }

  @Test
  public void fieldChangedAwayFromOriginalPresetBeforeQuickCardEverApplied_isNotQuickOwned() {
    // The user picked a different unit (7) on the classic quantity unit screen before the quick
    // card ever got to apply anything (original ambient preset was 3) - must not be overridden.
    assertFalse(QuickPackagingSyncUtil.isQuickOwned(7, 3, null));
  }

  @Test
  public void manuallyChangedFieldOnClassicScreen_isNotQuickOwned() {
    // Quick card previously applied id 5, but the user then picked a different unit (7) on the
    // classic quantity unit screen - that manual choice must never be overridden again, even if
    // it happens to match the original ambient preset (3).
    assertFalse(QuickPackagingSyncUtil.isQuickOwned(7, 3, 5));
  }

  // resolveEffectiveStockQuId

  @Test
  public void packagingAndValidContent_returnsContentUnit() {
    assertEquals(Integer.valueOf(9), QuickPackagingSyncUtil.resolveEffectiveStockQuId(3, 9, "250"));
  }

  @Test
  public void onlyPackaging_returnsPackagingUnit() {
    assertEquals(Integer.valueOf(3), QuickPackagingSyncUtil.resolveEffectiveStockQuId(3, null, null));
  }

  @Test
  public void contentUnitWithoutAmount_fallsBackToPackagingUnit() {
    assertEquals(Integer.valueOf(3), QuickPackagingSyncUtil.resolveEffectiveStockQuId(3, 9, null));
  }

  @Test
  public void contentUnitWithZeroAmount_fallsBackToPackagingUnit() {
    assertEquals(Integer.valueOf(3), QuickPackagingSyncUtil.resolveEffectiveStockQuId(3, 9, "0"));
  }

  @Test
  public void contentUnitWithNegativeAmount_fallsBackToPackagingUnit() {
    assertEquals(Integer.valueOf(3), QuickPackagingSyncUtil.resolveEffectiveStockQuId(3, 9, "-5"));
  }

  @Test
  public void contentUnitWithNonNumericAmount_fallsBackToPackagingUnit() {
    assertEquals(Integer.valueOf(3), QuickPackagingSyncUtil.resolveEffectiveStockQuId(3, 9, "abc"));
  }

  @Test
  public void nothingResolved_returnsNull() {
    assertNull(QuickPackagingSyncUtil.resolveEffectiveStockQuId(null, null, null));
  }

  @Test
  public void onlyContentUnitResolvedNoPackaging_returnsNullWithoutValidAmount() {
    assertNull(QuickPackagingSyncUtil.resolveEffectiveStockQuId(null, 9, null));
  }

  @Test
  public void onlyContentUnitResolvedWithValidAmountButNoPackaging_returnsNull() {
    // The stock unit must never diverge to the content unit without a packaging unit also
    // confirmed: without one there is no "from" side for the QuantityUnitConversion that would
    // normally tie stock back to purchase/price, which would otherwise leave those pointing at
    // an unrelated unit with no conversion between them at all ("keine stille Umrechnung").
    assertNull(QuickPackagingSyncUtil.resolveEffectiveStockQuId(null, 9, "250"));
  }
}
