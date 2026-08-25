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
import xyz.zedler.patrick.grocy.util.NutrientBasisUtil.Basis;

public class NutrientBasisUtilTest {

  @Test
  public void milliliters_returnsPer100Ml() {
    assertEquals(Basis.PER_100_ML, NutrientBasisUtil.resolve("ml"));
  }

  @Test
  public void liters_returnsPer100Ml() {
    assertEquals(Basis.PER_100_ML, NutrientBasisUtil.resolve("l"));
  }

  @Test
  public void centiliters_returnsPer100Ml() {
    assertEquals(Basis.PER_100_ML, NutrientBasisUtil.resolve("cl"));
  }

  @Test
  public void grams_returnsPer100G() {
    assertEquals(Basis.PER_100_G, NutrientBasisUtil.resolve("g"));
  }

  @Test
  public void kilograms_returnsPer100G() {
    assertEquals(Basis.PER_100_G, NutrientBasisUtil.resolve("kg"));
  }

  @Test
  public void milligrams_returnsPer100G() {
    assertEquals(Basis.PER_100_G, NutrientBasisUtil.resolve("mg"));
  }

  @Test
  public void nullUnit_returnsNull() {
    assertNull(NutrientBasisUtil.resolve(null));
  }

  @Test
  public void unknownUnit_returnsNull() {
    assertNull(NutrientBasisUtil.resolve("oz"));
  }
}
