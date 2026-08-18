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
 * Determines whether OFF's {@code *_100g} nutrient fields are to be labeled "per 100 g" (solid)
 * or "per 100 ml" (liquid) for a given product, based on a single already-canonicalized content
 * unit. Mirrors {@link OffPackagingUtil}'s "never guess" philosophy: if the unit is unknown or
 * not present, the basis is left undetermined (null) rather than assumed.
 */
public final class NutrientBasisUtil {

  private NutrientBasisUtil() {
  }

  public enum Basis {
    PER_100_G,
    PER_100_ML
  }

  /**
   * @param canonicalContentUnit an already canonicalized unit token as delivered by
   *     {@link OffContentAmountUtil}/{@link xyz.zedler.patrick.grocy.model.OpenFoodFactsProduct
   *     #getContentAmount()} ("g", "kg", "mg", "ml", "l", "cl"), or null if unknown.
   * @return {@link Basis#PER_100_ML} if the unit is unambiguously a volume unit (ml/l/cl),
   *     {@link Basis#PER_100_G} if unambiguously a weight unit (g/kg/mg), otherwise null - never
   *     guessed.
   */
  @Nullable
  public static Basis resolve(@Nullable String canonicalContentUnit) {
    if (canonicalContentUnit == null) {
      return null;
    }
    switch (canonicalContentUnit) {
      case "g":
      case "kg":
      case "mg":
        return Basis.PER_100_G;
      case "ml":
      case "l":
      case "cl":
        return Basis.PER_100_ML;
      default:
        return null; // unrecognized unit -> never guess
    }
  }
}
