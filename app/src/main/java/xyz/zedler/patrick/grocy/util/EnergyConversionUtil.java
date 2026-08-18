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
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Converts OFF's {@code energy-kcal_100g} value (kcal per 100 units of the SAME dimension - grams
 * or milliliters - as the product's content amount, see {@link NutrientBasisUtil}) into a total
 * calorie count for one full package/stock unit. Only ever converts within the same dimension
 * (weight&lt;-&gt;weight, e.g. kg to g, or volume&lt;-&gt;volume, e.g. l to ml) - never converts
 * weight to volume or vice versa, since no density is known ("unknown stays unknown").
 */
public final class EnergyConversionUtil {

  private EnergyConversionUtil() {
  }

  /**
   * @param energyKcalPer100 OFF {@code energy-kcal_100g} value (kcal per 100 g OR 100 ml,
   *     depending on the product's actual basis - the caller must ensure this value actually
   *     belongs to the same dimension (weight/volume) as contentAmount/contentUnit; this method
   *     itself does not separately determine/check that).
   * @param contentAmount content amount of one package (e.g. 500 for "500 ml").
   * @param contentUnit canonical unit token of the content amount ("g", "kg", "mg", "ml", "l",
   *     "cl").
   * @return calories per package, rounded to the nearest whole kcal (half-up), or null if
   *     {@code energyKcalPer100 <= 0}, {@code contentAmount <= 0}, or {@code contentUnit} is not
   *     recognized.
   */
  @Nullable
  public static Integer caloriesPerPackage(
      double energyKcalPer100, double contentAmount, @Nullable String contentUnit) {
    if (energyKcalPer100 <= 0 || contentAmount <= 0 || contentUnit == null) {
      return null;
    }
    double contentAmountInBaseUnit;
    switch (contentUnit) {
      case "g":
      case "ml":
        contentAmountInBaseUnit = contentAmount;
        break;
      case "kg":
        contentAmountInBaseUnit = contentAmount * 1000;
        break;
      case "mg":
        contentAmountInBaseUnit = contentAmount / 1000;
        break;
      case "l":
        contentAmountInBaseUnit = contentAmount * 1000;
        break;
      case "cl":
        contentAmountInBaseUnit = contentAmount * 10;
        break;
      default:
        return null; // unrecognized unit -> never guess
    }
    double calories = energyKcalPer100 * contentAmountInBaseUnit / 100.0;
    return BigDecimal.valueOf(calories).setScale(0, RoundingMode.HALF_UP).intValue();
  }
}
