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

public class OffPackagingUtilTest {

  @Test
  public void glassJarAlone_returnsGlas() {
    assertEquals("Glas", OffPackagingUtil.detectPrimaryPackaging(
        List.of("en:jar"), Collections.emptyList()));
  }

  @Test
  public void jarWithSecondaryParts_returnsGlas() {
    // Real Nutella (3017620422003) response.
    assertEquals("Glas", OffPackagingUtil.detectPrimaryPackaging(
        List.of("en:jar", "en:plaque", "en:screw-cap", "en:seal"), Collections.emptyList()));
  }

  @Test
  public void bottle_returnsFlasche() {
    assertEquals("Flasche", OffPackagingUtil.detectPrimaryPackaging(
        List.of("en:bottle", "en:screw-cap"), Collections.emptyList()));
  }

  @Test
  public void canAndDrinkCan_dedupeToDose() {
    assertEquals("Dose", OffPackagingUtil.detectPrimaryPackaging(
        List.of("en:can", "en:drink-can"), Collections.emptyList()));
  }

  @Test
  public void brick_returnsKarton() {
    assertEquals("Karton", OffPackagingUtil.detectPrimaryPackaging(
        List.of("en:brick"), Collections.emptyList()));
  }

  @Test
  public void bagWithUnrecognizedEnvelope_returnsBeutel() {
    // Real biscuits product response; "en:envelope" is unrecognized/ignored.
    assertEquals("Beutel", OffPackagingUtil.detectPrimaryPackaging(
        List.of("en:bag", "en:envelope"), Collections.emptyList()));
  }

  @Test
  public void boxWithLidAndSeal_returnsSchachtel() {
    // Real NESQUIK Cacao response.
    assertEquals("Schachtel", OffPackagingUtil.detectPrimaryPackaging(
        List.of("en:box", "en:lid", "en:seal"), Collections.emptyList()));
  }

  @Test
  public void trayWithFilm_returnsSchale() {
    assertEquals("Schale", OffPackagingUtil.detectPrimaryPackaging(
        List.of("en:tray", "en:film"), Collections.emptyList()));
  }

  @Test
  public void tube_returnsTube() {
    assertEquals("Tube", OffPackagingUtil.detectPrimaryPackaging(
        List.of("en:tube"), Collections.emptyList()));
  }

  @Test
  public void contradictoryMultiplePrimaryTags_returnsNull() {
    assertNull(OffPackagingUtil.detectPrimaryPackaging(
        List.of("en:jar", "en:bottle"), Collections.emptyList()));
  }

  @Test
  public void onlySecondaryOrUnrecognizedTags_returnsNull() {
    assertNull(OffPackagingUtil.detectPrimaryPackaging(
        List.of("en:cap", "en:label"), Collections.emptyList()));
  }

  @Test
  public void emptyShapesTags_fallsBackToPackagingTags() {
    assertEquals("Schachtel", OffPackagingUtil.detectPrimaryPackaging(
        Collections.emptyList(), List.of("en:box")));
  }

  @Test
  public void bothListsEmpty_returnsNull() {
    assertNull(OffPackagingUtil.detectPrimaryPackaging(
        Collections.emptyList(), Collections.emptyList()));
  }

  @Test
  public void shapesTagsPresent_packagingTagsCompletelyIgnored() {
    // Real Nutella (3017620422003) response: packaging_shapes_tags is clean ("en:jar" + secondary
    // parts), but the legacy packaging_tags field for the SAME product is ["en:plastic",
    // "fr:pot-en-verre"] - a material tag and an untranslated French shape word. If both lists
    // were ever merged/consulted together, "fr:pot-en-verre" and "en:plastic" would either be
    // ignored (fine) or, after a careless future change, misread. Pin that shapes_tags alone
    // decides the result whenever it is non-empty.
    assertEquals("Glas", OffPackagingUtil.detectPrimaryPackaging(
        List.of("en:jar"), List.of("en:plastic", "fr:pot-en-verre")));
  }

  @Test
  public void nonEnPrefixedTags_areIgnoredNotInterpreted() {
    assertNull(OffPackagingUtil.detectPrimaryPackaging(
        Collections.emptyList(), List.of("fr:pot-en-verre", "en:plastic")));
  }

  @Test
  public void literalEnPrefixWithNoId_isIgnored() {
    assertNull(OffPackagingUtil.detectPrimaryPackaging(List.of("en:"), Collections.emptyList()));
  }

  @Test
  public void tagWhitespaceAndMixedCase_stillRecognized() {
    assertEquals("Glas", OffPackagingUtil.detectPrimaryPackaging(
        List.of(" EN:JAR "), Collections.emptyList()));
  }

  @Test
  public void nullEntryInTagList_isSkippedNotThrown() {
    List<String> tags = new java.util.ArrayList<>();
    tags.add("en:jar");
    tags.add(null);
    assertEquals("Glas", OffPackagingUtil.detectPrimaryPackaging(tags, Collections.emptyList()));
  }

  @Test
  public void pumpBottle_returnsFlasche() {
    // OFF "pump-bottle" = "pump bottle, spray, spray bottle, applicator bottle" - a bottle
    // variant/container, verified against the real taxonomy, not a closure.
    assertEquals("Flasche", OffPackagingUtil.detectPrimaryPackaging(
        List.of("en:pump-bottle"), Collections.emptyList()));
  }
}
