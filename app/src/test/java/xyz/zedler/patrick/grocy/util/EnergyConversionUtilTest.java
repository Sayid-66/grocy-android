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

public class EnergyConversionUtilTest {

  @Test
  public void rapeseedOil_828kcalPer100ml_500ml_returns4140() {
    // Real-world rapeseed oil (Rapsöl) testcase from the task specification.
    Integer calories = EnergyConversionUtil.caloriesPerPackage(828, 500, "ml");
    assertEquals(Integer.valueOf(4140), calories);
  }

  @Test
  public void kilogramContentUnit_convertedToGrams() {
    // 500 kcal / 100 g * (2 kg = 2000 g) = 10000 kcal
    Integer calories = EnergyConversionUtil.caloriesPerPackage(500, 2, "kg");
    assertEquals(Integer.valueOf(10000), calories);
  }

  @Test
  public void milligramContentUnit_convertedToGrams() {
    // 500 kcal / 100 g * (500 mg = 0.5 g) = 2.5 kcal -> rounds to 3
    Integer calories = EnergyConversionUtil.caloriesPerPackage(500, 500, "mg");
    assertEquals(Integer.valueOf(3), calories);
  }

  @Test
  public void literContentUnit_convertedToMilliliters() {
    // 100 kcal / 100 ml * (1 l = 1000 ml) = 1000 kcal
    Integer calories = EnergyConversionUtil.caloriesPerPackage(100, 1, "l");
    assertEquals(Integer.valueOf(1000), calories);
  }

  @Test
  public void centiliterContentUnit_convertedToMilliliters() {
    // 100 kcal / 100 ml * (70 cl = 700 ml) = 700 kcal
    Integer calories = EnergyConversionUtil.caloriesPerPackage(100, 70, "cl");
    assertEquals(Integer.valueOf(700), calories);
  }

  @Test
  public void gramContentUnit_staysAsIs() {
    Integer calories = EnergyConversionUtil.caloriesPerPackage(200, 400, "g");
    assertEquals(Integer.valueOf(800), calories);
  }

  @Test
  public void zeroOrNegativeEnergy_returnsNull() {
    assertNull(EnergyConversionUtil.caloriesPerPackage(0, 500, "ml"));
    assertNull(EnergyConversionUtil.caloriesPerPackage(-10, 500, "ml"));
  }

  @Test
  public void zeroOrNegativeContentAmount_returnsNull() {
    assertNull(EnergyConversionUtil.caloriesPerPackage(500, 0, "g"));
    assertNull(EnergyConversionUtil.caloriesPerPackage(500, -1, "g"));
  }

  @Test
  public void nullOrUnrecognizedContentUnit_returnsNull() {
    assertNull(EnergyConversionUtil.caloriesPerPackage(500, 500, null));
    assertNull(EnergyConversionUtil.caloriesPerPackage(500, 500, "oz"));
  }
}
