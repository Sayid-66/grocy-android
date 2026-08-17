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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps Open Food Facts' {@code packaging_shapes} taxonomy (verified against the real taxonomy at
 * github.com/openfoodfacts/openfoodfacts-server, taxonomies/packaging_shapes.txt) to canonical
 * German packaging names, generically for any product - never brand/category/product specific.
 * <p>
 * Only tags that unambiguously denote ONE physical primary container are mapped; closures,
 * labels and films are recognized as secondary and excluded from consideration. Nothing is ever
 * guessed: if OFF's data does not allow a unique determination, detection returns null
 * ("unknown stays unknown").
 */
public final class OffPackagingUtil {

  // OFF packaging_shapes taxonomy id (without "en:" prefix) -> canonical German packaging name.
  // Every key here is a verified canonical id from the real OFF packaging_shapes taxonomy.
  // Multiple OFF ids intentionally map to the same German name where they denote the same
  // real-world container at different taxonomy granularity (e.g. "can"/"drink-can"/"food-can"
  // are all "Dose" in German) - this does NOT introduce ambiguity because detection dedupes by
  // the resulting *name*, not by raw tag.
  private static final Map<String, String> PRIMARY_SHAPE_TO_NAME;

  static {
    Map<String, String> map = new HashMap<>();
    map.put("jar", "Glas");
    map.put("bottle", "Flasche");
    map.put("can", "Dose");
    map.put("drink-can", "Dose");
    map.put("food-can", "Dose");
    map.put("aerosol-can", "Sprühdose");
    map.put("box", "Schachtel");
    map.put("pizza-box", "Schachtel");
    map.put("brick", "Karton");        // OFF "brick" = brick-shaped carton, e.g. Tetra Pak milk/juice carton
    map.put("small-brick", "Karton");
    map.put("bag", "Beutel");
    map.put("individual-bag", "Beutel");
    map.put("packet", "Packung");
    map.put("tube", "Tube");
    map.put("tray", "Schale");
    map.put("pot", "Becher");          // OFF taxonomy: "pot" and "cup" are the SAME canonical tag/id (en: pot, cup, pots, cups) - OFF does not distinguish them, so we don't invent a separate "Cup" mapping
    map.put("individual-pot", "Becher");
    map.put("wrapper", "Wrapper");     // distinct OFF id from "film" - a wrapper can legitimately be the sole/primary packaging of a wrapped item (e.g. a chocolate bar); "film" (below) is treated as secondary shrink/cling wrap instead
    map.put("pouch-flask", "Standbeutel"); // OFF synonyms include "doypack"/"pouch" = stand-up pouch
    map.put("bucket", "Eimer");
    map.put("small-bucket", "Eimer");
    map.put("canister", "Kanister");
    map.put("jug", "Krug");
    // OFF "pump-bottle" = "pump bottle, spray, spray bottle, applicator bottle" - verified
    // against the real taxonomy it is a bottle variant (a container), not a closure, so it
    // resolves to the same German name as a plain bottle rather than being excluded.
    map.put("pump-bottle", "Flasche");
    PRIMARY_SHAPE_TO_NAME = Collections.unmodifiableMap(map);
  }

  // OFF packaging_shapes ids that are secondary components (closures, labels, films, etc.) of a
  // packaging, never the primary sales/stock container by themselves. Verified individually
  // against the real taxonomy before being added here (never guessed). Actively consulted by
  // detectPrimaryPackaging() below - not just documentation - and asserted disjoint from
  // PRIMARY_SHAPE_TO_NAME at class-load time so the two lists can never silently contradict
  // each other as either one grows.
  private static final Set<String> SECONDARY_SHAPES = Collections.unmodifiableSet(
      new HashSet<>(java.util.Arrays.asList(
          "cap", "screw-cap", "lid", "seal", "label", "film", "tethered-cap", "flip-top",
          "crown-cork", "bottle-cap", "pouring-cap", "grinder-cap", "overcap", "special-cap",
          "neck-seal", "clamping-ring", "clipband", "bread-clip", "hanger", "tie", "string",
          "staple", "fastener", "wire-cage", "handle", "spout", "valve",
          "applicator", "wine-cork", "champagne-cork", "inlay", "filling", "card", "brochure",
          "sheet", "backing", "protection-cover", "mold", "lid-or-cap"
      ))
  );

  static {
    for (String secondary : SECONDARY_SHAPES) {
      if (PRIMARY_SHAPE_TO_NAME.containsKey(secondary)) {
        throw new AssertionError(
            "OffPackagingUtil: \"" + secondary + "\" is listed as both primary and secondary"
        );
      }
    }
  }

  private OffPackagingUtil() {
  }

  /**
   * Determines the single, unambiguous primary packaging type from OFF's structured packaging
   * tags, or returns null if none, several different, or only unrecognized/secondary tags are
   * present ("unknown stays unknown"). Never guesses from product name, brand, weight, category
   * or free text - only from taxonomized shape tags.
   *
   * @param packagingShapesTags preferred structured field (OFF "packaging_shapes_tags"); pass
   *     an empty list if absent.
   * @param packagingTags legacy/fallback flat field (OFF "packaging_tags"), only consulted when
   *     packagingShapesTags is empty; pass an empty list if absent.
   * @return the canonical German packaging name (e.g. "Glas"), or null if not uniquely
   *     determinable.
   */
  @Nullable
  public static String detectPrimaryPackaging(
      @NonNull List<String> packagingShapesTags,
      @NonNull List<String> packagingTags
  ) {
    List<String> tags = !packagingShapesTags.isEmpty() ? packagingShapesTags : packagingTags;
    Set<String> primaryNamesFound = new HashSet<>();
    for (String rawTag : tags) {
      String id = stripEnPrefix(rawTag);
      if (id == null) {
        continue; // not an "en:"-prefixed tag we can interpret -> ignore, never guess
      }
      if (SECONDARY_SHAPES.contains(id)) {
        continue; // recognized secondary component (closure/label/film/...) -> never a primary
      }
      String name = PRIMARY_SHAPE_TO_NAME.get(id);
      if (name != null) {
        primaryNamesFound.add(name);
      }
      // any other unrecognized id is silently ignored too: it must never block detection just
      // by being present alongside a recognized primary tag, and is never guessed into a type.
    }
    return primaryNamesFound.size() == 1 ? primaryNamesFound.iterator().next() : null;
  }

  @Nullable
  private static String stripEnPrefix(@Nullable String tag) {
    if (tag == null) {
      return null;
    }
    String trimmed = tag.trim();
    if (trimmed.length() <= 3 || !trimmed.regionMatches(true, 0, "en:", 0, 3)) {
      return null;
    }
    return trimmed.substring(3).toLowerCase(Locale.ROOT);
  }
}
