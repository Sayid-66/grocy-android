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
import xyz.zedler.patrick.grocy.util.OffContentAmountUtil.ParsedContentAmount;

public class OffContentAmountUtilTest {

  @Test
  public void plainGrams_returnsParsed() {
    ParsedContentAmount result = OffContentAmountUtil.parse("400 g");
    assertEquals(400.0, result.amount, 0.0001);
    assertEquals("g", result.unitName);
  }

  @Test
  public void commaDecimalLiters_returnsParsed() {
    ParsedContentAmount result = OffContentAmountUtil.parse("1,5l");
    assertEquals(1.5, result.amount, 0.0001);
    assertEquals("l", result.unitName);
  }

  @Test
  public void periodDecimalUppercaseLiters_caseInsensitive() {
    ParsedContentAmount result = OffContentAmountUtil.parse("1.5 L");
    assertEquals(1.5, result.amount, 0.0001);
    assertEquals("l", result.unitName);
  }

  @Test
  public void noSpaceMilliliters_returnsParsed() {
    ParsedContentAmount result = OffContentAmountUtil.parse("250ml");
    assertEquals(250.0, result.amount, 0.0001);
    assertEquals("ml", result.unitName);
  }

  @Test
  public void kilograms_returnsParsed() {
    ParsedContentAmount result = OffContentAmountUtil.parse("1.5 kg");
    assertEquals(1.5, result.amount, 0.0001);
    assertEquals("kg", result.unitName);
  }

  @Test
  public void milligrams_returnsParsed() {
    ParsedContentAmount result = OffContentAmountUtil.parse("500 mg");
    assertEquals(500.0, result.amount, 0.0001);
    assertEquals("mg", result.unitName);
  }

  @Test
  public void centiliters_returnsParsed() {
    ParsedContentAmount result = OffContentAmountUtil.parse("70 cl");
    assertEquals(70.0, result.amount, 0.0001);
    assertEquals("cl", result.unitName);
  }

  @Test
  public void multipackWithSpaces_returnsNull() {
    assertNull(OffContentAmountUtil.parse("6 x 330ml"));
  }

  @Test
  public void multipackWithoutSpaces_returnsNull() {
    assertNull(OffContentAmountUtil.parse("3x100g"));
  }

  @Test
  public void nullInput_returnsNull() {
    assertNull(OffContentAmountUtil.parse(null));
  }

  @Test
  public void blankInput_returnsNull() {
    assertNull(OffContentAmountUtil.parse(""));
    assertNull(OffContentAmountUtil.parse("   "));
  }

  @Test
  public void unknownUnit_returnsNull() {
    assertNull(OffContentAmountUtil.parse("12 oz"));
  }

  @Test
  public void numberWithoutUnit_returnsNull() {
    assertNull(OffContentAmountUtil.parse("400"));
  }

  @Test
  public void unitWithoutNumber_returnsNull() {
    assertNull(OffContentAmountUtil.parse("g"));
  }

  @Test
  public void twoNumbers_returnsNull() {
    assertNull(OffContentAmountUtil.parse("400 g 500 g"));
  }

  @Test
  public void periodThousandsGrouping_returnsNull() {
    // "1.000 g" is common European notation for 1000 g, not 1.0 g - ambiguous, must not guess.
    assertNull(OffContentAmountUtil.parse("1.000 g"));
  }

  @Test
  public void commaThousandsGrouping_returnsNull() {
    // "2,500 g" is common English notation for 2500 g, not 2.5 g - ambiguous, must not guess.
    assertNull(OffContentAmountUtil.parse("2,500 g"));
  }

  @Test
  public void twoDecimalDigits_stillParses() {
    // Only exactly-3-digit fractional parts are treated as ambiguous thousands grouping.
    assertEquals(1.25, OffContentAmountUtil.parse("1.25 kg").amount, 0.0001);
  }

  @Test
  public void canonicalizeUnit_recognizedUnits_returnCanonical() {
    assertEquals("g", OffContentAmountUtil.canonicalizeUnit("g"));
    assertEquals("kg", OffContentAmountUtil.canonicalizeUnit("kg"));
    assertEquals("mg", OffContentAmountUtil.canonicalizeUnit("mg"));
    assertEquals("ml", OffContentAmountUtil.canonicalizeUnit("ml"));
    assertEquals("l", OffContentAmountUtil.canonicalizeUnit("l"));
    assertEquals("cl", OffContentAmountUtil.canonicalizeUnit("cl"));
  }

  @Test
  public void canonicalizeUnit_caseInsensitive() {
    assertEquals("ml", OffContentAmountUtil.canonicalizeUnit("ML"));
  }

  @Test
  public void canonicalizeUnit_unknownUnit_returnsNull() {
    assertNull(OffContentAmountUtil.canonicalizeUnit("oz"));
  }

  @Test
  public void canonicalizeUnit_null_returnsNull() {
    assertNull(OffContentAmountUtil.canonicalizeUnit(null));
  }
}
