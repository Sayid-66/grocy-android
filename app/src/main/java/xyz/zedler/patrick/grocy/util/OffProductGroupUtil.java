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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import xyz.zedler.patrick.grocy.model.ProductGroup;

/**
 * Maps Open Food Facts' {@code categories_tags} to an already existing Grocy product group, but
 * only via an exact, case-insensitive string match - never fuzzy/similarity matching, and never
 * via an invented dictionary between OFF's English taxonomy and the user's own, possibly custom,
 * Grocy product group names. Nothing is ever guessed: ambiguous or non-matching input results in
 * null ("unknown stays unknown").
 */
public final class OffProductGroupUtil {

  private OffProductGroupUtil() {
  }

  /**
   * Detects a Grocy product group id from OFF's {@code categories_tags}, but ONLY via an exact,
   * case-insensitive string match: for every OFF category tag, strip any "xx:" language prefix
   * and replace '-'/'_' with spaces, then compare case-insensitively against every existing
   * Grocy product group's name. If exactly one DISTINCT product group id matches across all tags,
   * its id is returned - otherwise (no match, or more than one distinct group matched) null is
   * returned. Never guesses, never creates a new product group.
   *
   * @param categoriesTags OFF's structured "categories_tags" field; pass an empty list if absent.
   * @param productGroups the user's existing Grocy product groups.
   * @return the single unambiguous matching product group's id, or null.
   */
  @Nullable
  public static Integer detectProductGroupId(
      @NonNull List<String> categoriesTags, @NonNull List<ProductGroup> productGroups) {
    Set<Integer> matchedIds = new HashSet<>();
    for (String rawTag : categoriesTags) {
      String normalizedTag = normalize(rawTag);
      if (normalizedTag == null) {
        continue;
      }
      for (ProductGroup productGroup : productGroups) {
        String normalizedName = normalize(productGroup.getName());
        if (normalizedName != null && normalizedName.equals(normalizedTag)) {
          matchedIds.add(productGroup.getId());
        }
      }
    }
    return matchedIds.size() == 1 ? matchedIds.iterator().next() : null;
  }

  @Nullable
  private static String normalize(@Nullable String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    int colonIndex = trimmed.indexOf(':');
    // OFF language-prefixes tags as "xx:tag" (e.g. "en:oils", "de:Öle"); a 2-letter prefix
    // followed by a colon is stripped, mirroring OffPackagingUtil's stripEnPrefix approach but
    // generalized to any language code since categories_tags is not "en:"-only.
    if (colonIndex == 2) {
      trimmed = trimmed.substring(colonIndex + 1);
    }
    trimmed = trimmed.replace('-', ' ').replace('_', ' ').trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }
}
