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

import java.util.Collections;
import java.util.List;
import org.junit.Test;
import xyz.zedler.patrick.grocy.model.ProductGroup;

public class OffProductGroupUtilTest {

  @Test
  public void languagePrefixStripped_matchesProductGroupName() {
    List<ProductGroup> groups = List.of(new ProductGroup(1, "Öle"), new ProductGroup(2, "Milch"));
    Integer id = OffProductGroupUtil.detectProductGroupId(List.of("de:Öle"), groups);
    assertEquals(Integer.valueOf(1), id);
  }

  @Test
  public void hyphenatedTag_matchesSpaceSeparatedGroupName() {
    List<ProductGroup> groups = List.of(new ProductGroup(1, "Milk Products"));
    Integer id = OffProductGroupUtil.detectProductGroupId(
        List.of("en:milk-products"), groups);
    assertEquals(Integer.valueOf(1), id);
  }

  @Test
  public void caseInsensitiveMatch_returnsId() {
    List<ProductGroup> groups = List.of(new ProductGroup(1, "Getränke"));
    Integer id = OffProductGroupUtil.detectProductGroupId(
        List.of("en:GETRÄNKE"), groups);
    assertEquals(Integer.valueOf(1), id);
  }

  @Test
  public void ambiguousMultipleDistinctMatches_returnsNull() {
    List<ProductGroup> groups = List.of(new ProductGroup(1, "Öle"), new ProductGroup(2, "Milch"));
    Integer id = OffProductGroupUtil.detectProductGroupId(
        List.of("de:Öle", "de:Milch"), groups);
    assertNull(id);
  }

  @Test
  public void noMatch_returnsNull() {
    List<ProductGroup> groups = List.of(new ProductGroup(1, "Öle"));
    Integer id = OffProductGroupUtil.detectProductGroupId(
        List.of("en:beverages"), groups);
    assertNull(id);
  }

  @Test
  public void sameGroupMatchedByMultipleTags_isNotAmbiguous() {
    List<ProductGroup> groups = List.of(new ProductGroup(1, "Öle"));
    Integer id = OffProductGroupUtil.detectProductGroupId(
        List.of("de:Öle", "fr:Öle"), groups);
    assertEquals(Integer.valueOf(1), id);
  }

  @Test
  public void emptyTagsOrGroups_returnsNull() {
    assertNull(OffProductGroupUtil.detectProductGroupId(
        Collections.emptyList(), List.of(new ProductGroup(1, "Öle"))));
    assertNull(OffProductGroupUtil.detectProductGroupId(
        List.of("de:Öle"), Collections.emptyList()));
  }

  // Real-world OFF categories_tags shape (task docs section 10/23-F/G) - OFF returns the FULL
  // hierarchy flattened into one array, most general first, most specific last (verified against
  // a real Develey burger sauce OFF entry: ["en:condiments","en:sauces","en:hot-sauces",
  // "en:burger-sauces"]). Since every ancestor is already its own array entry, an exact match
  // against any household product group name at ANY hierarchy level is found without any
  // separate "walk up the parents" logic being needed.

  @Test
  public void realWorldOffHierarchy_matchesGroupAtAnyLevel_notOnlyTheLeaf() {
    List<ProductGroup> groups = List.of(new ProductGroup(1, "Sauces"));
    Integer id = OffProductGroupUtil.detectProductGroupId(
        List.of("en:condiments", "en:sauces", "en:hot-sauces", "en:burger-sauces"), groups);
    assertEquals(Integer.valueOf(1), id);
  }

  @Test
  public void realWorldOffHierarchy_englishOnly_noGermanGroupMatch_returnsNull() {
    // The same real-world tag set, but the household's Grocy product group is German ("Saucen")
    // while OFF has no German-language tag for this particular product (categories_lc "en") -
    // never guessed/translated, must stay empty rather than matching nothing and picking wrong.
    List<ProductGroup> groups = List.of(new ProductGroup(1, "Saucen"));
    Integer id = OffProductGroupUtil.detectProductGroupId(
        List.of("en:condiments", "en:sauces", "en:hot-sauces", "en:burger-sauces"), groups);
    assertNull(id);
  }
}
