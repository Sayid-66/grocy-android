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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservatively parses Open Food Facts' free-text {@code quantity} field (e.g. "400 g",
 * "1,5l", "6 x 330ml") into a single number + unit, mirroring {@link OffPackagingUtil}'s
 * "never guess" philosophy: only a strict single NUMBER+UNIT pattern is accepted. Multipacks,
 * ranges, more than one number, unrecognized units or any leftover text all result in null
 * ("unknown stays unknown") rather than a best-effort guess.
 */
public final class OffContentAmountUtil {

  // Whitespace-tolerant, comma-or-period decimal separator, single number followed by a unit
  // token, nothing else allowed before/after (anchored on the whole trimmed input).
  private static final Pattern SINGLE_AMOUNT_PATTERN = Pattern.compile(
      "^\\s*(\\d+(?:[.,]\\d+)?)\\s*([a-zA-Z]+)\\s*$"
  );

  // Matches a bare "digits.digits" or "digits,digits" number whose fractional part is exactly
  // 3 digits long, e.g. "1.000" or "2,500" - see the comment where this is used.
  private static final Pattern AMBIGUOUS_THOUSANDS_GROUPING_PATTERN = Pattern.compile(
      "^\\d+[.,]\\d{3}$"
  );

  private OffContentAmountUtil() {
  }

  /**
   * @param offQuantity OFF's raw free-text "quantity" field, or null.
   * @return the parsed amount+unit, or null if not uniquely/reliably determinable.
   */
  @Nullable
  public static ParsedContentAmount parse(@Nullable String offQuantity) {
    if (offQuantity == null || offQuantity.isBlank()) {
      return null;
    }
    String trimmed = offQuantity.trim();
    Matcher matcher = SINGLE_AMOUNT_PATTERN.matcher(trimmed);
    if (!matcher.matches()) {
      // Covers multipacks ("6 x 330ml", "3x100g"), ranges, more than one number, missing
      // number, missing unit, or any other unmatched text - all left as "unknown".
      return null;
    }
    String rawNumber = matcher.group(1);
    // A single separator followed by exactly 3 digits is ambiguous: it's far more often a
    // thousands grouping (OFF quantities like "1.000 g"/"1,000 g" for 1000 g, or "2.500 g" for
    // 2500 g, are common in European product data) than a genuine 3-decimal-place amount - never
    // guess which one it is, reject instead of silently parsing it as e.g. 1.0 g instead of
    // 1000 g.
    if (AMBIGUOUS_THOUSANDS_GROUPING_PATTERN.matcher(rawNumber).matches()) {
      return null;
    }
    String numberString = rawNumber.replace(",", ".");
    if (!NumUtil.isStringDouble(numberString)) {
      return null;
    }
    double amount = NumUtil.toDouble(numberString);
    String unitToken = matcher.group(2).toLowerCase(Locale.ROOT);
    String canonicalUnit;
    switch (unitToken) {
      case "g":
        canonicalUnit = "g";
        break;
      case "kg":
        canonicalUnit = "kg";
        break;
      case "mg":
        canonicalUnit = "mg";
        break;
      case "ml":
        canonicalUnit = "ml";
        break;
      case "l":
        canonicalUnit = "l";
        break;
      case "cl":
        canonicalUnit = "cl";
        break;
      default:
        return null; // unknown unit token -> never guess (e.g. "oz", "pcs", "stück")
    }
    return new ParsedContentAmount(amount, canonicalUnit);
  }

  public static final class ParsedContentAmount {

    public final double amount;
    @NonNull
    public final String unitName;

    ParsedContentAmount(double amount, @NonNull String unitName) {
      this.amount = amount;
      this.unitName = unitName;
    }
  }
}
