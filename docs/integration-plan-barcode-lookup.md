# Integration Plan: `origin/feature/barcode-lookup` → `master`

> **Für agentische Bearbeiter:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development` (empfohlen) oder `superpowers:executing-plans` für die tatsächliche Umsetzung — **noch nicht jetzt**. Dieser Plan ist ein Arbeitsauftrag für später, kein Ausführungsbefehl. Vor jeder Etappe die referenzierten Dateien im aktuellen `master`- UND `origin/feature/barcode-lookup`-Stand gegenlesen (beide können sich zwischenzeitlich weiterentwickelt haben).

**Ziel:** Die auf `origin/feature/barcode-lookup` bereits entwickelte Funktionalität (Barcode-Lern-Standards, OFF-Datenmodell, Packaging/Content-Trennung, Produkt-Assistant, Kauf-Integration) schrittweise, in kleinen überprüfbaren Einheiten nach `master` übernehmen — Risiken zuerst absichern, spätere Upstream-Merges möglichst konfliktarm halten.

**Architektur:** Acht sequenzielle Integrationsetappen, jede als eigener Pull Request/Commit-Satz auf `master`, mit dem risikoärmsten (neue, isolierte Utility-Dateien) zuerst und der komplexesten, zustandsbehaftetsten Logik (Kauf-Buchungs-Orchestrierung in `MasterProductViewModel`) zuletzt — und dort erst, nachdem die dafür fehlenden Tests nachgezogen wurden.

**Tech Stack:** Android/Java, JUnit (Unit-Tests der reinen Util-Klassen bereits vorhanden auf dem Feature-Branch), Room, Grocy-REST-API.

**Spec:** [`PROJECT.md`](../PROJECT.md) (verbindlich). Ersetzt/löst ab: [`docs/implementation-plan-product-assistant.md`](implementation-plan-product-assistant.md) — dieser gilt als **überholt** (nicht gelöscht, aber nicht mehr verfolgt), da `feature/barcode-lookup` die dort geplante Funktionalität bereits in fortgeschrittener Form enthält. Grundlage dieses Plans sind die beiden vorangegangenen Audit-Berichte in diesem Chat (Barcode-Ist-Analyse auf `master`, vollständige Diff-Analyse `origin/master..origin/feature/barcode-lookup`) — **diese Analysen werden hier nicht wiederholt**, nur referenziert.

## Global Constraints

- Kein Merge, kein Cherry-Pick, keine Branch-Änderung in diesem Schritt — nur Planung.
- Jede Etappe muss für sich allein `master` in einem kompilier- und testbaren Zustand belassen (kein "halb integriertes Feature").
- `Constants.SETTINGS.BEHAVIOR.FOOD_FACTS`-Default bleibt `false` (upstream-Wert) — die auf dem Feature-Branch vorgenommene Änderung auf `true` wird **nicht automatisch übernommen** (siehe Etappe 3, expliziter Entscheidungspunkt).
- Keine bereits vorhandene Funktionalität (Grocycode-Auflösung, `ProductBarcode`-Modell, bestehende `onBarcodeRecognized`-Ketten) wird neu entwickelt — nur das, was auf dem Feature-Branch zusätzlich existiert, wird integriert.
- Grocy-Server-API-Verträge unverändert (bereits im Diff-Audit als unauffällig bewertet — hier nicht erneut geprüft, nur pro Etappe referenziert, wo relevant).
- Reihenfolge der Etappen ist bindend: eine spätere Etappe darf nicht vor ihren Abhängigkeiten begonnen werden.

---

## Cherry-Pick vs. datei-/feature-weise Integration — Entscheidung

**Entscheidung: Keine Cherry-Picks der drei Commits. Kontrollierte, etappen-/dateiweise Integration aus dem Endstand des Feature-Branch.**

Begründung:
- Die drei Commits sind **nicht** entlang der hier gewünschten Etappen-Grenzen geschnitten. Commit 1 (`f12c691cf`) fasst bereits OFF-Datenübernahme, erste Quick-Packaging-Ansätze und einen Großteil des `MasterProductFragment`-Layouts in einem Commit zusammen; Commit 3 (`597c7c0d4`) bündelt Kauf-Buchung, Duplicate-Schutz, OFF-Bild/Energie/Produktgruppen-Automatik und Test-Erweiterungen gemeinsam. Ein `git cherry-pick` von Commit 1 würde also unweigerlich Teile von Etappe 3, 4 und 6 gleichzeitig einbringen.
- Mehrere Dateien wurden über die drei Commits hinweg **mehrfach verändert und einmal auch wieder verkleinert** (`QuickPackagingSyncUtil`: 99 Zeilen in Commit 2, −43 Zeilen in Commit 3 → Endstand 76 Zeilen). Ein Cherry-Pick von Commit 2 allein würde eine Zwischenversion einbringen, die Commit 3 anschließend ohnehin wieder verwirft — unnötiger Umweg.
- Da die Etappen 1–8 gezielt **einzelne Dateien im Endstand** des Feature-Branch übernehmen sollen (nicht "wie sie zu einem bestimmten Commit-Zeitpunkt aussahen"), ist die richtige Technik pro Etappe:
  ```
  git show origin/feature/barcode-lookup:<pfad> > <pfad>   # neue Datei
  # oder für gezielte Teil-Übernahmen in eine bestehende Datei:
  git diff origin/master origin/feature/barcode-lookup -- <pfad>   # als Grundlage für eine manuelle, geprüfte Übernahme
  ```
  d. h. **Endstand-Extraktion pro Datei**, nicht `git cherry-pick <commit>`.
- Vorteil dieses Vorgehens: jede Etappe erzeugt einen sauberen, in sich review-baren Diff gegen `master`, unabhängig davon, über wie viele Commits die jeweilige Funktion auf dem Feature-Branch tatsächlich gewachsen ist. Nachteil: mehr manuelle Sorgfalt beim Aufteilen einzelner Dateien (z. B. `MasterProductViewModel.java`) auf mehrere Etappen — dafür sind die Etappen unten so geschnitten, dass jede Datei möglichst genau einer Haupt-Etappe zugeordnet ist.

---

## Etappe 1: Additive Util-Klassen + vorhandene Unit-Tests

**Commits/Dateien (Endstand von `feature/barcode-lookup`):**
- Neu: `util/OffContentAmountUtil.java`, `util/OffPackagingUtil.java`, `util/OffProductGroupUtil.java`, `util/QuickPackagingSyncUtil.java`, `util/PurchaseDueDateUtil.java`, `util/PurchasePriceUtil.java`, `util/EnergyConversionUtil.java`, `util/NutrientBasisUtil.java`
- Erweitert (rein additiv, keine Zeile entfernt laut Diff-Stat): `util/PictureUtil.java` (neue Methode `loadExternalPicture`)
- Neu: alle zugehörigen Tests unter `app/src/test/java/xyz/zedler/patrick/grocy/util/*Test.java` (8 Dateien, 862 Zeilen)

**Übernommene Funktion:** Sämtliche reine, seiteneffektfreie Entscheidungs-/Parsing-Logik — OFF-Mengenparsing, Packaging-Taxonomie-Mapping, Produktgruppen-Matching, Preis-/Datums-Auflösung, Energie-Umrechnung, Nährwert-Basis-Erkennung, Ownership-Entscheidung für die Quick-Packaging-Einheiten.

**Abhängigkeiten:** Keine. Reinstes Java, keine Android-/LiveData-/ViewModel-Bezüge, keine Abhängigkeit zu anderen Etappen.

**Tests vor Integration:** Bereits vorhanden auf dem Feature-Branch — vor Übernahme lokal ausführen (`./gradlew testDebugUnitTest --tests "xyz.zedler.patrick.grocy.util.*"`) und grün bestätigen, bevor die Dateien nach `master` kopiert werden.

**Tests nach Integration:** Dieselben Tests laufen unverändert auf `master` grün. Keine neuen Tests nötig (Abdeckung bereits vorhanden und geprüft als vorbildlich).

**Akzeptanzkriterien:** Alle 8 Testklassen grün auf `master`; keine bestehende Datei/Klasse auf `master` wird berührt; Projekt kompiliert unverändert (diese Klassen werden noch von niemandem aufgerufen).

**Upstream-Merge-Risiko:** **Sehr niedrig** — ausschließlich neue Dateien, keine Berührung mit upstream-gepflegtem Code.

**Rückfallstrategie:** Dateien einfach wieder entfernen (kein Aufrufer existiert nach dieser Etappe) — keine Migrationsschritte nötig.

**Kann Codex das isoliert umsetzen?** **Ja, uneingeschränkt.** Reine Datei-Kopie + Testlauf, keine Designentscheidung nötig. Bestgeeigneter erster Auftrag.

---

## Etappe 2: Duplicate-Barcode-Schutz

**Commits/Dateien:**
- `viewmodel/PurchaseViewModel.java` — Methode `uploadProductBarcode(...)`: Prüfung, ob der zu verlinkende Barcode bereits einem *anderen* Produkt gehört (`ProductBarcode.getFromBarcode(barcodes, ...)`-Vergleich vor dem `POST`).
- Neue String-Ressource `msg_barcode_duplicate` (`strings.xml`, ggf. `values-de/strings.xml`).
- **Zu verifizieren bei Umsetzung** (im bisherigen Audit nicht einzeln bestätigt): ob `ConsumeViewModel`/`InventoryViewModel`/`TransferViewModel` auf dem Feature-Branch eine analoge Prüfung erhalten haben oder ob dort weiterhin ungeschützt hochgeladen wird — vor Integration per gezieltem `git diff` dieser drei Dateien klären.

**Übernommene Funktion:** Verhindert, dass ein Barcode, der bereits an ein anderes Produkt gebunden ist, versehentlich erneut verlinkt wird (client-seitige Prüfung vor dem Server-Request, mit Nutzer-Meldung statt stillem Fehlschlag/Server-Duplicate-Error).

**Abhängigkeiten:** Keine (baut nur auf bereits vorhandenem `master`-Code auf — `ProductBarcode.getFromBarcode` existiert bereits).

**Tests vor Integration:** Neuer Unit-/Robolectric-Test für `PurchaseViewModel.uploadProductBarcode`: Barcode gehört bereits Produkt A, aktuelle Transaktion ist Produkt B → `showMessage(R.string.msg_barcode_duplicate)` wird aufgerufen, kein `POST` ausgelöst. (Auf dem Feature-Branch nicht vorhanden — hier nachzuziehen, da isoliert und klein genug für einen Test.)

**Tests nach Integration:** Obiger Test grün; bestehendes Kaufverhalten (Barcode gehört noch niemandem / gehört bereits demselben Produkt) unverändert per manueller Prüfung bestätigt.

**Akzeptanzkriterien:** Barcode-Duplicate-Fall zeigt Nutzer-Meldung statt Server-Fehler; alle anderen Barcode-Upload-Fälle unverändert.

**Upstream-Merge-Risiko:** **Niedrig-mittel** — kleine, lokal begrenzte Änderung an einer bestehenden, von Upstream gepflegten Methode.

**Rückfallstrategie:** Einzelner, klar abgegrenzter Hunk — per Revert der einen Methode rückgängig machbar, ohne andere Etappen zu berühren.

**Kann Codex das isoliert umsetzen?** **Ja.** Klar abgegrenzte, testbare Änderung an einer Methode. Zweitbester Auftrag nach Etappe 1.

---

## Etappe 3: OFF-Datenmodell und konservatives Parsing

**Commits/Dateien:**
- `model/OpenFoodFactsProduct.java` — alle neuen Felder/Getter (Brand, Quantity, Bild-URL, Energie/Nährwerte, Packaging-Tags, Kategorien, Data-Quality-Flags, `getContentAmount()`, `getDetectedPackagingType()/Material()`, `hasNutritionDataQualityWarning()`).
- `viewmodel/ChooseProductViewModel.java` — die 160 Zeilen Diff: neue `off*`-Felder/Getter, `buildOffNutrientsSummary()`/`addNutrientPart()`, `offLookupInProgress`-Schutz gegen doppelte parallele OFF-Abfragen.
- `fragment/ChooseProductFragment.java` — 44-Zeilen-Diff (Weiterreichen der neuen OFF-Felder an `MasterProductFragmentArgs`, siehe Etappe 6 für den Empfänger).

**Übernommene Funktion:** Deutlich erweiterte, aber weiterhin konservative ("unknown stays unknown") Auswertung der OFF-Antwort — nutzt die in Etappe 1 gelieferten `Off*Util`-Klassen zum Parsen/Erkennen.

**Abhängigkeiten:** Etappe 1 (`OffContentAmountUtil`, `OffPackagingUtil` werden von `OpenFoodFactsProduct` intern genutzt).

**⚠️ Separater Entscheidungspunkt — NICHT automatisch übernehmen:**
`Constants.java`: `SETTINGS.BEHAVIOR.FOOD_FACTS`-Default wurde auf dem Feature-Branch von `false` auf `true` geändert. **Diese Änderung wird in dieser Etappe explizit ausgelassen** — der Default bleibt `false` (Opt-in, wie upstream). Grund: Privacy-Trade-off (automatischer Barcode-Versand an Drittanbieter-Server), keine rein technische Frage — erfordert eine bewusste, separate Entscheidung außerhalb dieses Integrationsplans.

**Tests vor Integration:** Keine dedizierten `OpenFoodFactsProduct`-Tests auf dem Feature-Branch vorhanden (nur indirekt über die `Off*Util`-Tests aus Etappe 1 abgedeckt) — vor Integration prüfen, ob ein kurzer Parsing-Test für 1–2 reale OFF-JSON-Antworten (Fixture) sinnvoll ergänzt wird, da dies die einzige Stelle ist, an der externe, nicht kontrollierte Rohdaten in die App eintreten.

**Tests nach Integration:** Etappe-1-Tests weiterhin grün; manuelle Prüfung: Barcode-Scan eines bekannten OFF-Produkts zeigt Marke/Bild/Nährwerte in `ChooseProductFragment`-Vorschau (falls dort schon sichtbar) bzw. Daten kommen unverändert im Fragment an.

**Akzeptanzkriterien:** OFF-Lookup liefert die erweiterten Felder; `FOOD_FACTS`-Default bleibt `false`; kein automatischer Datenversand ohne bestehende Nutzer-Opt-in-Einstellung.

**Upstream-Merge-Risiko:** **Niedrig** (neues Modell-Wachstum, additive Felder) bis **mittel** für `ChooseProductViewModel.java` (bestehende, von Upstream gepflegte Datei, aber die Änderung ist additiv/lokal begrenzt).

**Rückfallstrategie:** `OpenFoodFactsProduct`-Erweiterungen sind additiv und ungefährlich stehen zu lassen, auch falls spätere Etappen verworfen werden; `ChooseProductViewModel`-Änderungen sind klar als ein zusammenhängender Block revertierbar.

**Kann Codex das isoliert umsetzen?** **Ja, mit einer Einschränkung:** Die `FOOD_FACTS`-Default-Auslassung muss als expliziter Hinweis im Auftrag stehen (sonst übernimmt Codex sie versehentlich beim Datei-Diff-Import mit).

---

## Etappe 4: Packaging / Content Amount / Content Unit (household stock model)

**Commits/Dateien:**
- `viewmodel/MasterProductViewModel.java` — nur der Teilbereich: Quick-Packaging-Felder (`quickPackagingLive`, `quickContentAmountLive`, `quickContentUnitLive`, `quickPackagingQuMissingLive`, `quickContentQuMissingLive`), `showQuickPackagingEntryLive`, `syncQuickPackagingToProduct()`, `findUniqueQuantityUnitByName()`, `resolveQuickQuId()`, `isQuickQuMissing()`, `updateQuickQuMissingFlags()`, `createQuickQuantityUnit()`, `lastQuickApplied*QuId`/`initialPreset*QuId`-Felder, `setQuickPackaging()`/`setQuickContentUnit()`.
- **Explizit NICHT Teil dieser Etappe** (kommt erst in Etappe 6/8): OFF-Bild-Upload, Kalorien-/Produktgruppen-Automatik, Kauf-Buchung.
- `form/FormDataMasterProductCatQuantityUnit.java` (neuer Import in `MasterProductViewModel` — prüfen, ob dort Änderungen nötig sind, damit die klassische Mengeneinheiten-Unterseite die vom Quick-Card gesetzten Werte korrekt widerspiegelt).
- Layout-Ergänzungen in `fragment_master_product.xml` für die Verpackungs-/Inhalts-Chips (Teilmenge des großen Layout-Diffs).

**Übernommene Funktion:** Das "household stock model" aus PROJECT.md — Packaging-Einheit ist die alleinige Stock-/Purchase-/Consume-/Price-Einheit; Content-Menge/-Einheit wird nur als `QuantityUnitConversion` gespeichert. Enthält auch `applyQuickPackagingAndContent()`/`createQuickQuantityUnitConversion()` (Erstellung der Conversion beim Speichern) — **aber ohne** die Kauf-Buchungs-Verkettung (die kommt in Etappe 8; hier endet der Speicherpfad weiterhin mit direktem `NAVIGATE_UP`/`TRANSACTION_SUCCESS` wie auf `master` bereits vorhanden).

**Abhängigkeiten:** Etappe 1 (`QuickPackagingSyncUtil`). Unabhängig von Etappe 3 (auch ohne OFF-Vorschlag nutzbar — die Chips können ja auch ohne OFF-Vorbefüllung leer starten), aber sinnvoller Zusammenhang: die initiale Chip-Vorbelegung (`detectedPackagingLabel`, `quickContentAmountLive`/`quickContentUnitLive` aus `args.getOffContentAmount()/getOffContentUnit()`) setzt die in Etappe 6 hinzukommenden `MasterProductFragmentArgs`-Felder voraus — bis Etappe 6 integriert ist, starten die Chips also leer (kein Fehler, nur noch ohne OFF-Vorschlag).

**Tests vor Integration:** `QuickPackagingSyncUtilTest` (aus Etappe 1) bereits grün. Neu zu schreiben (auf dem Feature-Branch fehlend): Test für `syncQuickPackagingToProduct()`/`resolveQuickQuId()`/`createQuickQuantityUnit()` als ViewModel-Verhalten (Robolectric) — mindestens: (a) Chip-Auswahl setzt alle vier QU-Felder, wenn noch "quick-owned"; (b) manuelle Nutzeränderung auf der klassischen Mengeneinheiten-Seite wird bei nachfolgender Chip-Änderung nicht überschrieben; (c) `createQuickQuantityUnit()` legt fehlende Einheit an und wendet sie sofort an.

**Tests nach Integration:** Obige neue Tests grün; bestehende `MasterProductViewModel`-Nutzung (Edit/Clone-Flow) unverändert per manueller Prüfung.

**Akzeptanzkriterien:** Für ein neu angelegtes, gescanntes Produkt setzt eine Verpackungs-Chip-Auswahl `quIdPurchase/Stock/Consume/Price` konsistent; eine bestätigte Inhaltsmenge erzeugt beim Speichern eine `QuantityUnitConversion`; manuelle Änderungen auf der klassischen Mengeneinheiten-Unterseite werden nie stillschweigend zurückgesetzt; Edit-/Clone-Modus vollständig unberührt.

**Upstream-Merge-Risiko:** **Mittel-hoch** — `MasterProductViewModel.java` ist die Datei mit dem größten Änderungsvolumen auf dem Feature-Branch; auch als Teilmenge ist der Eingriff strukturell (neue Felder, neue Konstruktor-Logik, neue LiveData-Verkettungen).

**Rückfallstrategie:** Da diese Etappe den Speicherpfad bewusst noch nicht mit der Kauf-Buchung verkettet, ist ein Revert dieser Etappe ohne Auswirkung auf Etappe 5 (unabhängig) möglich; Etappe 6/8 müssten dann pausiert werden, bis das Problem behoben ist.

**Kann Codex das isoliert umsetzen?** **Bedingt.** Die Kernlogik (Felder, Sync-Methode, Chip-Setter) kann Codex direkt aus dem Feature-Branch-Diff übernehmen. Die Abgrenzung "was gehört zu Etappe 4 vs. 6 vs. 8 im selben `MasterProductViewModel.java`-Diff" sollte vorab von uns (Claude) präzise als Hunk-Liste vorgegeben werden, damit Codex nicht versehentlich zu viel auf einmal integriert.

---

## Etappe 5: Gelernte Barcode-Standards

**Commits/Dateien:**
- `viewmodel/PurchaseViewModel.java` — `learnedBarcode`/`learnedBarcodeAppliedAmount`/`learnedBarcodeAppliedQuId`, `barcodeDefaultDialogConfirmed`, `updateBarcodeDefaultPending`, `barcodeDefaultChanged()`, `updateLearnedBarcodeDefault()`, `purchaseOnceWithoutSavingDefault()`/`purchaseAndSaveAsNewDefault()`, `setProductFromJustLinkedBarcode()` (Methode selbst additiv integrierbar, ihr eigentlicher Aufrufer entsteht erst in Etappe 8 — bis dahin unbenutzt, aber unschädlich).
- `model/Event.java` — `CONFIRM_BARCODE_DEFAULT_CHANGE`.
- `Constants.java` — `ARGUMENT.BARCODE_ALREADY_HANDLED` (der zweite neue Key, `PURCHASE_ALREADY_BOOKED`, gehört zu Etappe 8).
- `fragment/PurchaseFragment.java` — Dialog-Handling für `CONFIRM_BARCODE_DEFAULT_CHANGE`, Konsum von `barcodeAlreadyHandled` (der `purchaseAlreadyBooked`-Teil gehört zu Etappe 8).
- Neue String-Ressourcen (`title_barcode_default_changed`, `msg_barcode_default_changed`, `action_save_as_default`, `action_purchase_once`).

**Übernommene Funktion:** "Nur für diesen Einkauf" vs. "als neuen Standard speichern"-Mechanismus — direkte Umsetzung von PROJECT.md's "Confirmed values are stored and reused later".

**Abhängigkeiten:** Keine Abhängigkeit zu Etappe 3/4 (reiner `PurchaseViewModel`/`PurchaseFragment`-Kreis, unabhängig von `MasterProductViewModel`). Kann parallel zu Etappe 3/4 integriert werden.

**Tests vor Integration:** Keine dedizierten Tests auf dem Feature-Branch vorhanden. Neu zu schreiben: Robolectric-/Unit-Test für `barcodeDefaultChanged()` (reine Vergleichslogik, leicht isolierbar) — Fälle: kein gelernter Barcode → `false`; Menge geändert → `true`; Einheit geändert → `true`; unverändert → `false`; Barcode ist `PendingProductBarcode` → nie als `learnedBarcode` gesetzt (Regressionsschutz für den im Code dokumentierten ID-Kollisions-Fall).

**Tests nach Integration:** Obige Tests grün; manueller Durchlauf: Barcode mit gespeicherter Menge scannen → Menge ändern → Dialog erscheint → beide Optionen ("nur diesmal"/"als Standard") führen zum Kaufabschluss, nur letztere aktualisiert den Barcode-Datensatz.

**Akzeptanzkriterien:** Dialog erscheint nur, wenn tatsächlich ein gelernter Wert existiert UND abweicht; `CONFIRM_FREEZING`-Dialog (bestehend) funktioniert weiterhin unabhängig und kann in Folge auftreten; kein PendingProductBarcode wird jemals als `learnedBarcode` verwendet (ID-Kollisionsschutz).

**Upstream-Merge-Risiko:** **Mittel** — `PurchaseViewModel.java`/`PurchaseFragment.java` sind stark frequentierte, von Upstream gepflegte Dateien; Änderung ist aber additiv (neue Felder/Methoden, ein neuer Event-Zweig).

**Rückfallstrategie:** In sich geschlossener Feature-Block; Revert betrifft ausschließlich diese Dateien, keine Rückwirkung auf Etappe 3/4/6.

**Kann Codex das isoliert umsetzen?** **Ja.** Klar abgegrenzter Kreis (zwei Dateien + Event/Constants), gute Testbarkeit der Kernlogik (`barcodeDefaultChanged`). Guter dritter/vierter Auftrag, kann parallel zu Etappe 3/4 laufen.

---

## Etappe 6: Produkt-Anlern-/Assistant-UI

**Commits/Dateien:**
- `viewmodel/MasterProductViewModel.java` — OFF-Vorschau-Felder/-Getter (`offBrandLive`, `offImageUrlLive`, `offNutrientsLive`, `hasOffPreviewLive`, `hasOffExtraInfoLive`, `offExtraInfoExpandedLive`/`toggleOffExtraInfoExpanded()`, `advancedSettingsExpandedLive`/`toggleAdvancedSettingsExpanded()`), OFF-Bild-Handling (`handleOffPictureUploadIfNecessary`, `downloadAndUploadOffPicture`, `uploadOffPicture`, `linkOffPictureToProduct`, `deleteOrphanedOffPicture`, `isValidOffImageUrl`, `OFF_IMAGE_HOST`-Konstante), Auto-Fill (`applyOffEnergyIfPossible()`, `applyOffProductGroupIfPossible()`, `lastAppliedCaloriesValue`, `initialPresetProductGroupId`), inline Produktgruppe/Lagerort/Store-Zuweisung (`setProductGroup/setLocation/setStore` + zugehörige `*NameLive`).
- `fragment/MasterProductFragmentArgs` / `navigation_main.xml` — neue Argumente (`offBrand`, `offBrandFull`, `offQuantity`, `offImageUrl`, `offEnergyPer100g`, `offIngredients`, `offAllergens`, `offNutriscore`, `offOrigin`, `offNutrients`, `offPackagingType`, `offPackagingMaterial`, `offCategoriesTagsJoined`, `offNutritionUnreliable`, `barcode`, `fromPurchase`).
- `fragment/ChooseProductFragment.java` — Restlicher Teil des 44-Zeilen-Diffs (Befüllung der obigen neuen Nav-Argumente beim Übergang zu `MasterProductFragment`).
- `fragment/MasterProductFragment.java`, `fragment_master_product.xml` — OFF-Vorschau-Karte, aufklappbare Bereiche, inline Produktzuordnung/Store-Auswahl (großer Layout-Anteil — vor Integration im Detail gegenlesen, in diesem Plan nicht Zeile für Zeile vorab geprüft).
- `fragment/bottomSheetDialog/ProductOverviewBottomSheet.java` (201-Zeilen-Erweiterung — Zweck vor Integration klären, im bisherigen Audit nur oberflächlich gesichtet).
- `util/PictureUtil.java`s `loadExternalPicture()` (aus Etappe 1 bereits vorhanden, hier erstmals genutzt).

**Übernommene Funktion:** Sichtbare OFF-Vorschau im Anlege-Screen plus automatische, eng gefasste Übernahme von Kalorien/Produktgruppe/Bild — jeweils nur bei eindeutiger Erkennung und nur solange das Feld noch unberührt ist ("ownership"-Prinzip wie in Etappe 4).

**Abhängigkeiten:** Etappe 3 (liefert die `off*`-Rohdaten), Etappe 4 (Kalorien-Berechnung hängt an `quickContentAmountLive`/`quickContentUnitLive`).

**Tests vor Integration:** Keine dedizierten Tests auf dem Feature-Branch. Neu zu schreiben: `applyOffEnergyIfPossible()`/`applyOffProductGroupIfPossible()` als Verhaltenstest (Robolectric) — insbesondere die "ownership"-Fälle (Feld bereits manuell geändert → keine Überschreibung) und der `offNutritionUnreliable`-Ausschluss. Für den Bild-Upload-Pfad: mindestens ein Test für `isValidOffImageUrl()` (Host-Allowlist — sicherheitsrelevant, siehe unten) als reine, leicht isolierbare Funktion.

**Tests nach Integration:** Obige Tests grün; manueller Durchlauf: neues Produkt aus Barcode mit vollständigen OFF-Daten anlegen → Vorschau zeigt Marke/Bild/Nährwerte; Kalorien-/Produktgruppenfeld sind vorbefüllt, aber änderbar; manuelle Änderung vor dem Speichern bleibt erhalten.

**Akzeptanzkriterien:** Vorschau erscheint nur für neu angelegte, gescannte (nicht editierte/geklonte) Produkte; `isValidOffImageUrl()` lehnt jede URL ab, deren Host nicht exakt `images.openfoodfacts.org` ist (Regressionsschutz gegen SSRF/Credential-Leak über den exportierten `grocy://`-Deep-Link — im Quellcode explizit als Sicherheitsmaßnahme dokumentiert); kein Feld wird nach manueller Nutzeränderung automatisch zurücküberschrieben.

**Upstream-Merge-Risiko:** **Hoch** — größter Layout-Diff (`fragment_master_product.xml`, ~1000 Zeilen über alle Commits) und umfangreichste `MasterProductViewModel`-Erweiterung. Diese Etappe hat das höchste Konfliktpotenzial mit künftigen Upstream-Änderungen an diesen beiden Dateien.

**Rückfallstrategie:** Die OFF-Vorschau kann als Ganzes deaktiviert werden (z. B. Feature-Flag oder Revert dieser Etappe), ohne Etappe 4 (Packaging-Mechanik) zu beeinträchtigen, solange Etappe 8 (die den ganzen Speicherpfad verkettet) noch nicht integriert ist.

**Kann Codex das isoliert umsetzen?** **Nein, nicht vollständig isoliert.** Der Layout-Anteil (`fragment_master_product.xml`, `ProductOverviewBottomSheet.java`) sollte vorab von uns gesichtet werden (im bisherigen Audit nur oberflächlich geprüft); die reine ViewModel-Logik (Auto-Fill, Bild-Validierung) kann Codex separat und mit klaren Testvorgaben umsetzen. Empfehlung: diese Etappe in zwei Teilaufträge splitten (ViewModel-Logik zuerst, Layout/Fragment danach nach zusätzlicher Sichtung).

---

## Etappe 7: Purchase-Integration ("Dieser Einkauf"-Sektion — Daten & Anzeige, ohne Buchungs-Verkettung)

**Commits/Dateien:**
- `viewmodel/MasterProductViewModel.java` — `showPurchaseSectionLive`, `purchaseAmountLive`/`purchaseDueDateLive`/`purchasePriceLive`/`purchaseIsTotalPriceLive`/`purchaseNoteLive`, `increasePurchaseAmount()`/`decreasePurchaseAmount()`, `showPurchaseDueDateBottomSheet()`, `computePurchasePricePerStockUnit()` (nutzt `PurchasePriceUtil` aus Etappe 1), `formatSummaryAmountContent()`/`formatSummaryDueDate()`/`formatSummaryPrice()` + zugehörige `summary*Live`/`purchaseDueDateTextLive`/`dueDateTypeLive`, `setPurchaseDueDateType()`.
- **Explizit NICHT Teil dieser Etappe** (kommt in Etappe 8): `bookPurchase()`, `buildPurchaseJson()`, `retryPurchase()`, `updateProductThenRetryPurchase()`, `linkScannedBarcodeAndUploadPending()`, `isAlreadyCoveredByPendingBarcode()`, `createdProductIdForPurchase`, `purchaseBooked`/`purchaseInProgress`/`purchaseFailedLive`.
- Layout-Anteil für die "Dieser Einkauf"-Zusammenfassungskarte in `fragment_master_product.xml`.

**Übernommene Funktion:** Die Eingabe- und Anzeigefelder für den Erstkauf werden sichtbar und bedienbar (Menge erhöhen/verringern, Fälligkeitsdatum wählen, Preis pro Verpackung/gesamt), inklusive Live-Zusammenfassung — **aber** ein Tap auf "Fertig" bucht in dieser Etappe noch keinen Kauf; das Verhalten von `master` (Produkt anlegen, danach ggf. wie bisher zu `PurchaseFragment` zurückkehren) bleibt unverändert, bis Etappe 8 die Verkettung ergänzt.

**Abhängigkeiten:** Etappe 4 (nutzt `quickPackagingLive` für die Zusammenfassung "1 Flasche × 0,5 l"), Etappe 6 (`fromPurchase`-Flag aus den Nav-Argumenten steuert `showPurchaseSectionLive`).

**Tests vor Integration:** Auf dem Feature-Branch vorhanden: `PurchasePriceUtilTest`, `PurchaseDueDateUtilTest` (Etappe 1). Neu zu schreiben: Tests für die drei `formatSummary*()`-Methoden (reine String-Formatierung, leicht isolierbar) und für `isPurchaseAmountValid()`.

**Tests nach Integration:** Obige Tests grün; manueller Durchlauf: Sektion erscheint nur bei `fromPurchase=true` und neu angelegtem Produkt; Zusammenfassung aktualisiert sich live bei jeder Eingabe; "Fertig" verhält sich exakt wie vor dieser Etappe (nur Produktanlage, keine Buchung).

**Akzeptanzkriterien:** Sektion sichtbar/unsichtbar exakt gemäß `showPurchaseSectionLive`-Bedingung; keine Netzwerk-Seiteneffekte durch diese Etappe allein.

**Upstream-Merge-Risiko:** **Mittel-hoch** (Teil desselben großen `MasterProductViewModel.java`/Layout-Diffs wie Etappe 6).

**Rückfallstrategie:** Sektion kann ausgeblendet werden (`showPurchaseSectionLive` fest auf `false`), ohne Etappe 4/6 zu beeinträchtigen.

**Kann Codex das isoliert umsetzen?** **Ja, mit vorgegebener Methodenliste** — die Abgrenzung zu Etappe 8 (welche Methoden noch NICHT integriert werden) muss explizit im Auftrag stehen, da beide Etappen dieselbe Datei betreffen.

---

## Etappe 8: Komplexe MasterProductViewModel-Orchestrierung (zuletzt)

**Commits/Dateien:**
- `viewmodel/MasterProductViewModel.java` — `saveProduct()`-Erweiterung um die vollständige Kette: `applyQuickPackagingAndContent()` → `linkScannedBarcodeAndUploadPending()` → `bookPurchase()` → `finishSave`; dazu `retryPurchase()`, `updateProductThenRetryPurchase()`, `isAlreadyCoveredByPendingBarcode()`, `createQuickQuantityUnitConversion()` (falls nicht schon in Etappe 4 gezogen — siehe dortige Abgrenzung), Guard-Felder `saveInProgress`/`purchaseInProgress`/`purchaseBooked`/`purchaseFailedLive`/`createdProductIdForPurchase`.
- `viewmodel/PurchaseViewModel.java` — `setProductFromJustLinkedBarcode()` bekommt hier erstmals einen echten Aufrufer.
- `fragment/PurchaseFragment.java` — Konsum von `ARGUMENT.PURCHASE_ALREADY_BOOKED` (der `BARCODE_ALREADY_HANDLED`-Teil kam bereits in Etappe 5), `focusNextInvalidView()`-Fix (`binding.executePendingBindings()` vor `requestFocus()` — behebt eine dokumentierte Timing-Lücke).
- `Constants.java` — `ARGUMENT.PURCHASE_ALREADY_BOOKED`.

**Übernommene Funktion:** Direkte Erstkauf-Buchung aus dem Produkt-Anlege-Screen heraus, vollständig verkettet und mit Fehler-/Retry-Behandlung sowie Doppelbuchungsschutz.

**Abhängigkeiten:** Etappe 4, 5, 6, 7 (alle vorherigen Etappen liefern die Bausteine, die hier verkettet werden).

**Vor dieser Etappe zwingend nachzuziehende Tests** (wie explizit gefordert):
1. **Happy Path End-to-End** (Robolectric): Produkt anlegen (mit gescanntem Barcode + bestätigter Verpackung/Menge + ausgefüllter "Dieser Einkauf"-Sektion) → erwartete Aufrufreihenfolge: `POST /objects/products` → `POST /objects/product_barcodes` → `POST /objects/quantity_unit_conversions` → `POST /stock/products/{id}/add` → `NAVIGATE_UP`-Event. Test verifiziert Reihenfolge und dass jeder Schritt erst nach Erfolg des vorherigen ausgelöst wird.
2. **Retry-/Fehlerpfade:** (a) Kauf-Buchung schlägt fehl → `purchaseFailedLive=true`, Produkt bleibt gespeichert, kein zweites Produkt bei erneutem "Fertig"-Tap (`updateProductThenRetryPurchase()` statt Neuanlage). (b) `retryPurchase()` direkt nach Fehlschlag → bucht nur den Kauf nach, ohne Barcode/Conversion erneut anzulegen.
3. **Schutz vor Doppelbuchungen:** Zwei schnelle Taps auf "Fertig" (`saveInProgress`/`purchaseInProgress` bereits `true`) → zweiter Tap wird ignoriert, kein zweiter `POST`.
4. **Zusammenspiel mit gelernten Barcode-Defaults (Etappe 5):** Rückkehr zu `PurchaseFragment` nach `PURCHASE_ALREADY_BOOKED`/`BARCODE_ALREADY_HANDLED` → `PurchaseFragment` bietet diese Lieferung nicht erneut zum Kauf an; ein späterer, zweiter Scan desselben Barcodes in `PurchaseFragment` nutzt `setProductFromJustLinkedBarcode()` und übernimmt die beim Anlegen gelernte Menge/Einheit korrekt (nicht erneut als "neuer" Barcode behandelt).

**Tests nach Integration:** Alle vier obigen Testgruppen grün; zusätzlich manueller End-to-End-Durchlauf auf einer echten/Demo-Grocy-Instanz (Netzwerk-Reihenfolge lässt sich nur begrenzt in Robolectric simulieren).

**Akzeptanzkriterien:** Kein Pfad erzeugt ein doppeltes Produkt oder eine doppelte Buchung; jeder Fehlerfall lässt dem Nutzer eine funktionierende Weiterarbeitsmöglichkeit (Retry), nie einen Sackgassen-Zustand; `PurchaseFragment` verhält sich nach Rückkehr korrekt für alle drei Fälle (normaler Barcode, bereits verlinkter Barcode, bereits gebuchter Kauf).

**Upstream-Merge-Risiko:** **Hoch** — größte, am stärksten zustandsbehaftete Änderung an der bereits durch Etappe 4/6/7 modifizierten `MasterProductViewModel.java`; zusätzlich Änderungen an `PurchaseFragment.java`/`PurchaseViewModel.java`, die mit Etappe 5 zusammenspielen.

**Rückfallstrategie:** Da alle vorherigen Etappen den Speicherpfad bewusst *nicht* verketten, ist ein Revert dieser einen Etappe möglich, ohne Etappe 1–7 rückgängig machen zu müssen — der Anlege-Screen fällt dann auf "Produkt anlegen ohne automatische Kaufbuchung" zurück (= Zustand nach Etappe 7).

**Kann Codex das isoliert umsetzen?** **Nur nach Testvorgabe, nicht komplett eigenständig.** Die vier oben geforderten Testgruppen sollten von uns (Claude) als konkrete Testfälle/Gerüst vorgegeben werden (ggf. sogar zuerst geschrieben, siehe TDD-Prinzip), bevor Codex die Verkettungslogik selbst umsetzt oder aus dem Feature-Branch-Diff übernimmt. Diese Etappe sollte **nicht** der erste Codex-Auftrag zu diesem Integrationsvorhaben sein.

---

## Zusammenfassung für Codex (Reihenfolge der Beauftragung)

1. Etappe 1 (Util-Klassen + Tests) — sofort beauftragbar, keine Vorbedingungen.
2. Etappe 2 (Duplicate-Barcode-Schutz) — sofort beauftragbar, parallel zu 1 möglich.
3. Etappe 5 (Gelernte Barcode-Standards) — parallel zu 3/4 möglich, da unabhängiger Dateikreis.
4. Etappe 3 (OFF-Datenmodell) — nach Etappe 1, **mit explizitem Hinweis, den `FOOD_FACTS`-Default NICHT zu übernehmen**.
5. Etappe 4 (Packaging/Content) — nach Etappe 1, mit genauer Hunk-Abgrenzung gegen Etappe 6/7/8 in derselben Datei.
6. Etappe 6 (Assistant-UI) — nach 3+4, Layout-Anteil vorab von uns sichten.
7. Etappe 7 (Purchase-Daten/Anzeige) — nach 4+6.
8. Etappe 8 (Orchestrierung) — zuletzt, erst nach Testvorgabe durch uns.

---

## Referenz zu bestehenden Analysen (nicht wiederholt)

- Ist-Zustand der vier `onBarcodeRecognized()`-Implementierungen auf `master`: siehe vorheriger Audit-Bericht in diesem Chat.
- Vollständige Diff-Analyse `origin/master..origin/feature/barcode-lookup` (Funktionsgruppen, Codequalität, Testabdeckung, Commit-Historie): siehe vorheriger Bericht in diesem Chat.
- `docs/implementation-plan-product-assistant.md`: **überholt**, nicht weiterverfolgen, nicht löschen (Referenzwert für die dort dokumentierte Ist-Analyse von `master` vor Kenntnis des Feature-Branch).
