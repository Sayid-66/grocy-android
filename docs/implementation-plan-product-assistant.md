# Product Assistant — Implementation Plan (Schritt 1 + 2)

> **Für Codex:** Dies ist ein direkter Arbeitsauftrag. Beide Schritte sind additiv und minimal-invasiv angelegt. Vor jeder Änderung die referenzierten Dateien/Zeilen im aktuellen Stand gegenlesen (Zeilennummern können durch zwischenzeitliche Commits leicht abweichen) — Verhalten darf sich nur an den explizit benannten Stellen ändern.

**Ziel:** Schritt 1 vereinheitlicht die dupliziert vorhandene Barcode/Produkt-Lookup-Logik auf das tatsächlich nötige Minimum. Schritt 2 schafft eine gemeinsame, additive Vorbefüll-("Assistant"-)Logik für Produkt-Stammdaten, die beim erstmaligen Anlernen (aus einem Pending-Product/Barcode-Scan heraus) **und** im Kauf-Flow nutzbar ist.

**Spec:** [`PROJECT.md`](../PROJECT.md) (verbindlich) — insbesondere: "Clearly detected values may be prefilled automatically", "Uncertain values stay empty", "The same assistant logic should be used during first product setup and later purchase/stock workflows".

## Global Constraints

- Kein Packaging-Konzept einführen (Packaging bleibt Teil einer künftigen, hier nicht geplanten Änderung).
- Keine OpenFoodFacts-Erweiterung über den bestehenden Namens-Lookup hinaus.
- Bestehende Grocy-Server-API-Verträge (Feldnamen, JSON-Struktur, Endpunkte) unverändert lassen.
- Kein Refactoring nur aus Gründen der Sauberkeit — jede Änderung an bestehendem, funktionierendem Code muss für das Ziel des jeweiligen Tasks zwingend nötig sein.
- Neue Funktionalität additiv (neue Klassen/Methoden) neben bestehenden Upstream-Strukturen; bestehende Methoden nur an den explizit benannten Stellen ändern.
- Nichts aus Schritt 3–5 (siehe unten) vorwegnehmen.

## Wichtiger Scoping-Hinweis (Ergebnis der Analyse)

Schritt 2 hängt **nicht zwingend** von Schritt 1 ab: Der tragfähigste, risikoärmste Integrationspunkt für die gemeinsame Assistant-Logik (Task 2.1/2.2 unten) nutzt Daten, die bereits unabhängig von der Barcode-Lookup-Duplizierung vorliegen (`MasterProductViewModel`s bereits bestehende `pendingProductBarcodes`-Argument-Verarbeitung). Schritt 1 wird trotzdem wie gefordert umgesetzt, aber bewusst auf die tatsächlich identische, duplizierte Kernlogik reduziert (Grocycode-Parsing + ProductBarcode-Auflösung in `onBarcodeRecognized()`), nicht auf eine vollständige Vereinheitlichung der vier ViewModels (die sich in Pending-Product-Handling, Rückgabetypen und Folgeverhalten unterscheiden und deren Vereinheitlichung reines Sauberkeits-Refactoring wäre).

---

## Schritt 1: Barcode-/Produkt-Lookup-Duplizierung minimal auflösen

### Bestandsaufnahme (verifiziert)

Identischer Code-Block existiert in `onBarcodeRecognized(String barcode)` in:
- `app/src/main/java/xyz/zedler/patrick/grocy/viewmodel/PurchaseViewModel.java:498-540`
- `app/src/main/java/xyz/zedler/patrick/grocy/viewmodel/ConsumeViewModel.java:315-351`
- `app/src/main/java/xyz/zedler/patrick/grocy/viewmodel/InventoryViewModel.java:276-310`
- `app/src/main/java/xyz/zedler/patrick/grocy/viewmodel/TransferViewModel.java:287-323`

Gemeinsames Muster (Grocycode parsen → Produkt auflösen → ProductBarcode auflösen → Produkt oder "nicht gefunden"):

```java
Product product = null;
Grocycode grocycode = GrocycodeUtil.getGrocycode(barcode);
if (grocycode != null && grocycode.isProduct()) {
  product = /* productHashMap.get(...) ODER Product.getProductFromId(products, ...) */;
  if (product == null) {
    showMessageAndContinueScanning(R.string.msg_not_found);
    return;
  }
  // Inventory/Transfer zusätzlich: stockEntryId = grocycode.getProductStockEntryId();
} else if (grocycode != null) {
  showMessageAndContinueScanning(R.string.error_wrong_grocycode_type);
  return;
}
ProductBarcode productBarcode = null;
if (product == null) {
  productBarcode = ProductBarcode.getFromBarcode(barcodes, barcode);
  // Nur Purchase: if (productBarcode instanceof PendingProductBarcode) { ... return; }
  product = /* Auflösung wie oben */;
}
```

**Unterschiede, die erhalten bleiben müssen:**
- Purchase behandelt `PendingProductBarcode` explizit (`setPendingProduct(...)`) — Consume/Inventory/Transfer tun das nicht (dort ist Produkt-Neuanlage verboten).
- Purchase nutzt `productHashMap` (HashMap), Consume/Inventory/Transfer nutzen `Product.getProductFromId(products, id)` (lineare Liste) — funktional äquivalent, aber unterschiedliche Datenquelle.
- Inventory/Transfer extrahieren zusätzlich `stockEntryId` aus dem Grocycode; Purchase/Consume nicht.
- Nachgelagerter Aufruf `setProduct(...)` hat pro ViewModel eine andere Signatur (2–3 Parameter) — **bleibt unverändert**, nur die Werte-Ermittlung davor wird geteilt.

### Task 1.1: Gemeinsame Lookup-Utility erstellen

**Ziel:** Die oben markierte, identische Auflösungslogik (Grocycode → Produkt/ProductBarcode) in eine einzige, testbare, seiteneffektfreie Utility-Klasse extrahieren.

**Dateien:**
- Create: `app/src/main/java/xyz/zedler/patrick/grocy/util/ProductBarcodeLookupUtil.java`
- Test: `app/src/test/java/xyz/zedler/patrick/grocy/util/ProductBarcodeLookupUtilTest.java`

**Bestehende Klassen/Methoden (werden konsumiert, nicht verändert):**
- `xyz.zedler.patrick.grocy.util.GrocycodeUtil.getGrocycode(String)` → `GrocycodeUtil.Grocycode`
- `Grocycode.isProduct()`, `Grocycode.getObjectId()`, `Grocycode.getProductStockEntryId()`
- `xyz.zedler.patrick.grocy.model.ProductBarcode.getFromBarcode(List<ProductBarcode>, String)`
- `xyz.zedler.patrick.grocy.model.PendingProductBarcode` (Subtyp von `ProductBarcode`)
- `xyz.zedler.patrick.grocy.model.Product` (Feld `id` via `getId()`)

**Neue Schnittstelle/Signatur:**

```java
package xyz.zedler.patrick.grocy.util;

public final class ProductBarcodeLookupUtil {

  private ProductBarcodeLookupUtil() {}

  public enum Status { PRODUCT_FOUND, PENDING_PRODUCT_FOUND, WRONG_GROCYCODE_TYPE, NOT_FOUND }

  public static class LookupResult {
    public final Status status;
    @Nullable public final Product product;             // gesetzt bei PRODUCT_FOUND
    @Nullable public final ProductBarcode productBarcode; // gesetzt, wenn über Barcode aufgelöst
    @Nullable public final String stockEntryId;          // aus Grocycode, falls vorhanden
    // package-private Konstruktor, Erzeugung nur über die statischen Resolver-Methoden unten
  }

  /**
   * Löst einen Barcode auf Produktebene auf (Grocycode zuerst, danach ProductBarcode-Tabelle).
   * Verhält sich 1:1 wie der bisherige Code in Purchase/Consume/Inventory/Transfer-ViewModel,
   * OHNE Pending-Product-Sonderfall (siehe resolveIncludingPending für Purchase).
   */
  public static LookupResult resolve(
      String barcode,
      Map<Integer, Product> productMap,
      List<ProductBarcode> barcodes
  ) { ... }

  /**
   * Wie resolve(), zusätzlich mit Pending-Product-Auflösung (nur für Purchase-Flow benötigt).
   * Gibt bei Treffer auf eine PendingProductBarcode Status.PENDING_PRODUCT_FOUND zurück;
   * productBarcode enthält dann die PendingProductBarcode-Instanz, product bleibt null.
   */
  public static LookupResult resolveIncludingPending(
      String barcode,
      Map<Integer, Product> productMap,
      List<ProductBarcode> barcodes
  ) { ... }
}
```

**Genaue Änderung (neue Datei, Logik 1:1 aus den vier Fundstellen übernommen):**
1. `resolve()` implementiert exakt den in "Bestandsaufnahme" gezeigten Ablauf ohne Pending-Zweig.
2. `resolveIncludingPending()` ruft intern dieselbe Grocycode-/ProductBarcode-Auflösung auf, prüft zusätzlich `productBarcode instanceof PendingProductBarcode` und liefert `Status.PENDING_PRODUCT_FOUND` statt den Aufrufer selbst prüfen zu lassen.
3. Keine Android-/LiveData-/ViewModel-Abhängigkeiten in der neuen Klasse (reines POJO-Utility, dadurch mit JUnit ohne Robolectric testbar).

**Was ausdrücklich unverändert bleiben muss:**
- Die Strings/String-Resource-IDs für Fehlermeldungen (`R.string.msg_not_found`, `R.string.error_wrong_grocycode_type`) bleiben in den ViewModels — die Utility liefert nur den `Status`, das Mapping auf `showMessageAndContinueScanning(...)` bleibt Aufgabe des jeweiligen ViewModels.
- `setProduct(...)`-Signaturen und -Verhalten in allen vier ViewModels.
- `stockEntryId`-Handling bleibt nur dort angebunden, wo es bisher verwendet wird (Inventory/Transfer).
- `checkProductInput()` (Freitext-Suche) bleibt unangetastet — nutzt teils andere Logik (`Product.getProductFromName`) und ist nicht Teil dieses Tasks.

**Akzeptanzkriterien:**
- Neue Klasse kompiliert ohne Android-Framework-Abhängigkeit (reines Java/androidx.annotation).
- Für alle vier bisherigen Code-Pfade liefert die Utility bei identischen Eingaben (Barcode, Produktliste, Barcode-Liste) exakt das gleiche Ergebnis wie der bisherige Inline-Code (per Unit-Test nachgewiesen, siehe unten).

**Tests/Verifikation:**
- Neue Unit-Tests in `ProductBarcodeLookupUtilTest.java`, mindestens:
  - Grocycode mit gültiger Produkt-ID → `PRODUCT_FOUND`.
  - Grocycode mit unbekannter Produkt-ID → `NOT_FOUND`.
  - Grocycode falschen Typs (z. B. `grcy:b:1`) → `WRONG_GROCYCODE_TYPE`.
  - Klartext-Barcode, der in `barcodes` auf ein reales `Product` zeigt → `PRODUCT_FOUND`.
  - Klartext-Barcode, der in `barcodes` auf eine `PendingProductBarcode` zeigt → bei `resolve()` `NOT_FOUND`/kein Produkt (da Pending nicht behandelt), bei `resolveIncludingPending()` → `PENDING_PRODUCT_FOUND`.
  - Unbekannter Barcode ohne Treffer → `NOT_FOUND`.
  - Grocycode mit `stockEntryId`-Zusatzdaten (`grcy:p:12:34`) → `stockEntryId == "34"`.

**Risiken:**
- Gering. Reine Extraktion ohne Verhaltensänderung; Risiko liegt in Copy-Paste-Fehlern beim Übertragen der Bedingungen — durch die Unit-Tests abgesichert.

**Upstream-Merge-Risiko:** **Niedrig** — neue Datei, keine Änderung an bestehenden Dateien in diesem Task.

**Abhängigkeiten:** Keine (kann unabhängig von Task 1.2 und Schritt 2 umgesetzt werden).

---

### Task 1.2: Bestehende ViewModels auf die Utility umstellen

**Ziel:** Den in Task 1.1 extrahierten Block in den vier ViewModels durch einen Aufruf der neuen Utility ersetzen, ohne das äußere Verhalten zu verändern.

**Dateien (Modify):**
- `app/src/main/java/xyz/zedler/patrick/grocy/viewmodel/PurchaseViewModel.java:511-532` (innerhalb `onBarcodeRecognized`)
- `app/src/main/java/xyz/zedler/patrick/grocy/viewmodel/ConsumeViewModel.java:324-343`
- `app/src/main/java/xyz/zedler/patrick/grocy/viewmodel/InventoryViewModel.java:285-302`
- `app/src/main/java/xyz/zedler/patrick/grocy/viewmodel/TransferViewModel.java:296-315`

**Bestehende Klassen/Methoden (Kontext, unverändert genutzt):**
- `productHashMap` (Purchase) bzw. `products`-Liste (Consume/Inventory/Transfer) — für Consume/Inventory/Transfer muss vor dem Utility-Aufruf einmalig eine `Map<Integer, Product>` gebildet werden (z. B. via vorhandenes `ArrayUtil.getProductsHashMap(products)`, das laut `PurchaseViewModel.java:185` bereits existiert und in `loadFromDatabase(...)` aufgerufen werden kann — dort wird die Map dann wie in Purchase als Feld gehalten statt bei jedem Aufruf neu gebaut).
- `showMessageAndContinueScanning(int)` — bleibt wie bisher, wird jetzt anhand von `LookupResult.status` aufgerufen statt anhand direkter If-Verzweigung.
- `setPendingProduct(int, PendingProductBarcode)` (nur PurchaseViewModel) — Aufruf bleibt, nur die Erkennung von "ist Pending" kommt jetzt aus `LookupResult.status == PENDING_PRODUCT_FOUND`.

**Genaue Änderung (Beispiel PurchaseViewModel, analog für die anderen drei):**

Vorher (Ausschnitt `PurchaseViewModel.java:511-532`):
```java
Product product = null;
Grocycode grocycode = GrocycodeUtil.getGrocycode(barcode);
if (grocycode != null && grocycode.isProduct()) {
  product = productHashMap.get(grocycode.getObjectId());
  if (product == null) {
    showMessageAndContinueScanning(R.string.msg_not_found);
    return;
  }
} else if (grocycode != null) {
  showMessageAndContinueScanning(R.string.error_wrong_grocycode_type);
  return;
}
ProductBarcode productBarcode = null;
if (product == null) {
  productBarcode = ProductBarcode.getFromBarcode(barcodes, barcode);
  if (productBarcode instanceof PendingProductBarcode) {
    setPendingProduct(productBarcode.getProductIdInt(), (PendingProductBarcode) productBarcode);
    return;
  } else if (productBarcode != null) {
    product = productHashMap.get(productBarcode.getProductIdInt());
  }
}
```

Nachher:
```java
ProductBarcodeLookupUtil.LookupResult result =
    ProductBarcodeLookupUtil.resolveIncludingPending(barcode, productHashMap, barcodes);
switch (result.status) {
  case NOT_FOUND:
    showMessageAndContinueScanning(R.string.msg_not_found);
    return;
  case WRONG_GROCYCODE_TYPE:
    showMessageAndContinueScanning(R.string.error_wrong_grocycode_type);
    return;
  case PENDING_PRODUCT_FOUND:
    setPendingProduct(
        result.productBarcode.getProductIdInt(),
        (PendingProductBarcode) result.productBarcode
    );
    return;
}
Product product = result.product;
```

Für Consume/Inventory/Transfer: identisches Muster mit `ProductBarcodeLookupUtil.resolve(...)` (ohne Pending-Fall, daher kein `PENDING_PRODUCT_FOUND`-Zweig) und — bei Inventory/Transfer — zusätzlich `String stockEntryId = result.stockEntryId;` statt der bisherigen separaten Grocycode-Abfrage.

**Was ausdrücklich unverändert bleiben muss:**
- Alle Zeilen vor und nach dem markierten Block (insbesondere die vorgelagerte Prüfung `if (formData.getProductDetailsLive().getValue() != null) { ... }` und der abschließende `setProduct(...)`/`sendEvent(Event.CHOOSE_PRODUCT, ...)`-Aufruf).
- `checkProductInput()` in allen vier Dateien.
- Feldnamen `productHashMap`, `products`, `barcodes` selbst (nur ihre Nutzung an dieser einen Stelle ändert sich).

**Akzeptanzkriterien:**
- Bestehendes Verhalten aller vier Flows bleibt bei jedem der in Task 1.1 getesteten Fälle identisch (gleiche Snackbar-Message, gleiche Navigation, gleicher `setProduct`-Aufruf mit gleichen Parametern).
- Kein neues Feld/keine neue Methode wird öffentlich exponiert, die nicht in Task 1.1 spezifiziert ist.

**Tests/Verifikation:**
- Bestehende (falls vorhandene) ViewModel-/Instrumentation-Tests für Purchase/Consume/Inventory/Transfer weiter grün.
- Manuelle Verifikation (da UI-Tests in diesem Projekt laut bisheriger Analyse nicht flächendeckend vorhanden sind): je Flow einmal Scan eines bekannten Produkt-Barcodes, eines unbekannten Barcodes, eines Grocycodes und (nur Purchase) eines Pending-Product-Barcodes durchspielen.

**Risiken:**
- Mittel-gering: Fehler hier wirken sich direkt auf den produktiven Scan-Flow aus (hohe Nutzungsfrequenz). Durch 1:1-Extraktion und die Unit-Tests aus Task 1.1 abgesichert.

**Upstream-Merge-Risiko:** **Mittel** — vier bestehende, upstream-gepflegte Dateien werden an derselben Stelle geändert, an der Upstream (patzly/grocy-android) ebenfalls Fixes einpflegen könnte (z. B. neue Barcode-Formate). Konflikt ist wahrscheinlich ein einfacher Zeilen-Konflikt (kein struktureller), da nur der Blockinhalt ersetzt wird, nicht die Methode umbenannt/verschoben wird.

**Abhängigkeiten:** Benötigt Task 1.1.

---

## Schritt 2: Gemeinsame Assistant-/Vorbefüllungslogik

### Bestandsaufnahme (verifiziert)

- `PendingProductBarcode` (Modell: `app/src/main/java/xyz/zedler/patrick/grocy/model/PendingProductBarcode.java`) trägt bereits `qu_id`, `amount`, `store_id`, `last_price` (geerbt/analog zu `ProductBarcode`).
- Beim Umwandeln eines Pending-Products in ein echtes Produkt navigiert `StoredPurchasesFragment.java:183-188` zu `MasterProductFragment` mit `ACTION.CREATE`, `productName`, `pendingProductId` **und `pendingProductBarcodes`** (kommagetrennte IDs).
- `MasterProductViewModel.onQueueEmpty()` (`MasterProductViewModel.java:215-229`) lädt die zu `pendingProductBarcodes` passenden `PendingProductBarcode`-Objekte bereits in `pendingProductBarcodesLive` — **aber ausschließlich**, um sie nach dem Speichern erneut hochzuladen (`uploadBarcodesIfNecessary()`, `MasterProductViewModel.java:311-336`). Die darin enthaltenen Werte (`qu_id`, `store_id`, `amount`, `last_price`) werden **nicht** zur Vorbefüllung der neuen `Product`-Stammdaten (`quIdPurchase`, `quIdStock`, `storeId`) verwendet.
- Im Kauf-Flow (`PurchaseViewModel.setProduct(...)`, `PurchaseViewModel.java:241-380`) existiert bereits exakt diese Art von Vorbefüll-Logik für die *Transaktion* (nicht Produktstammdaten): `barcode.hasQuId()` → bevorzugte Einheit, `barcode.hasStoreId()` → bevorzugter Store, `barcode.hasLastPrice()` → Preisvorschlag.
- Auf Produkt-Stammdatenebene sind nur zwei Felder aus Barcode-Metadaten sinnvoll ableitbar, weil nur diese sowohl auf `Product` als auch auf `ProductBarcode`/`PendingProductBarcode` existieren: **`quIdPurchase`/`quIdStock`** (aus `qu_id`) und **`storeId`** (aus `store_id`). `amount` und `last_price` sind Transaktionswerte (Kaufmenge/-preis), keine Produktstammdaten, und bleiben entsprechend außen vor.

### Task 2.1: Gemeinsame Vorbefüll-Utility erstellen

**Ziel:** Eine einzige, seiteneffektfreie Funktion, die aus einer `ProductBarcode`/`PendingProductBarcode` klar erkennbare Produkt-Stammdaten-Vorschläge ableitet — nach der Regel "nur eindeutig bekannte Werte vorschlagen, sonst leer lassen" (PROJECT.md).

**Dateien:**
- Create: `app/src/main/java/xyz/zedler/patrick/grocy/util/ProductPrefillAssistant.java`
- Test: `app/src/test/java/xyz/zedler/patrick/grocy/util/ProductPrefillAssistantTest.java`

**Bestehende Klassen/Methoden (konsumiert, unverändert):**
- `ProductBarcode.hasQuId()`, `getQuIdInt()` (`ProductBarcode.java:170-182`)
- `ProductBarcode.hasStoreId()`, `getStoreId()` (`ProductBarcode.java:202-214`)
- `Product.setQuIdPurchase(int)`, `Product.setQuIdStock(int)`, `Product.setStoreId(String)` (`Product.java:441-477`)

**Neue Schnittstelle/Signatur:**

```java
package xyz.zedler.patrick.grocy.util;

public final class ProductPrefillAssistant {

  private ProductPrefillAssistant() {}

  public static class MasterDataSuggestion {
    @Nullable public final Integer quId;     // eindeutig erkannte Einheit, sonst null
    @Nullable public final String storeId;   // eindeutig erkannter Store, sonst null
  }

  /**
   * Leitet aus einer (Pending-)ProductBarcode klar erkennbare Produkt-Stammdaten-Vorschläge ab.
   * Liefert für jedes Feld null, wenn der Barcode dazu keine Angabe trägt (PROJECT.md:
   * "uncertain values stay empty").
   */
  public static MasterDataSuggestion suggestMasterData(@Nullable ProductBarcode barcode) {
    if (barcode == null) return new MasterDataSuggestion(null, null);
    Integer quId = barcode.hasQuId() ? barcode.getQuIdInt() : null;
    String storeId = barcode.hasStoreId() ? barcode.getStoreId() : null;
    return new MasterDataSuggestion(quId, storeId);
  }
}
```

**Genaue Änderung:** Neue, kleine, reine Utility-Klasse (kein Android-Framework-Bezug), 1:1 auf Basis der bereits im Kauf-Flow verwendeten `has*()`-Methoden von `ProductBarcode`.

**Was ausdrücklich unverändert bleiben muss:**
- `ProductBarcode`/`PendingProductBarcode` selbst (keine neuen Felder, keine neue Methode dort).
- Die bestehende Vorbefüll-Logik in `PurchaseViewModel.setProduct()` für **Transaktionswerte** (Menge, Preis) — diese ist bewusst nicht Teil dieses Tasks (siehe "Bestandsaufnahme").

**Akzeptanzkriterien:**
- `suggestMasterData(null)` liefert `{null, null}`.
- `suggestMasterData(barcode)` liefert `quId`/`storeId` **nur**, wenn `hasQuId()`/`hasStoreId()` `true` ist — nie geraten, nie ein Default eingesetzt.

**Tests/Verifikation:**
- Unit-Test: Barcode ohne jegliche Zusatzdaten → beide Felder `null`.
- Unit-Test: Barcode mit `qu_id`, ohne `store_id` → nur `quId` gesetzt.
- Unit-Test: Barcode mit `store_id`, ohne `qu_id` → nur `storeId` gesetzt.
- Unit-Test: Barcode mit beidem → beide gesetzt.

**Risiken:** Sehr gering — neue, isolierte Klasse ohne Seiteneffekte.

**Upstream-Merge-Risiko:** **Niedrig** — neue Datei.

**Abhängigkeiten:** Keine.

---

### Task 2.2: Anbindung an Produkt-Anlernen (Pending → echtes Produkt)

**Ziel:** Beim Umwandeln eines Pending-Products in ein echtes Produkt (Flow: `StoredPurchasesFragment` → `MasterProductFragment`, `ACTION.CREATE` mit `pendingProductId` + `pendingProductBarcodes`) die per Task 2.1 ermittelten, eindeutigen Werte in die neue Produkt-Stammdaten übernehmen — additiv, ohne bestehende Konstruktions-Logik zu verändern.

**Dateien (Modify):**
- `app/src/main/java/xyz/zedler/patrick/grocy/viewmodel/MasterProductViewModel.java:215-229` (`onQueueEmpty()`)

**Bestehende Klassen/Methoden (Kontext):**
- `MasterProductViewModel.onQueueEmpty()` — lädt bereits `pendingProductBarcodesLive` (siehe Bestandsaufnahme).
- `FormDataMasterProduct` / `formData.getProductLive()` — hält das aktuell bearbeitete `Product`-Objekt (verifiziert über `MasterProductViewModel.getFilledProduct()`, `MasterProductViewModel.java:178-180`, das intern `formData.getProductLive().getValue()` liest).
- `Product.setQuIdPurchase(int)`, `Product.setQuIdStock(int)`, `Product.setStoreId(String)`.

> **Hinweis für Codex:** Den exakten Setter/Getter-Namen für das aktuell im Formular gehaltene `Product`-Objekt in `FormDataMasterProduct` (z. B. `getProductLive()`/`setProduct(...)` oder abweichend benannt) vor der Änderung verifizieren — die Feldnamen wurden nur indirekt über `getFilledProduct()`/`setCurrentProduct(...)` bestätigt, nicht per Volltext von `FormDataMasterProduct.java`.

**Neue/geänderte Schnittstelle:** Keine neue öffentliche Methode nötig — reine interne Ergänzung in `onQueueEmpty()`.

**Genaue Änderung:**

In `onQueueEmpty()`, **nach** dem bestehenden Block, der `filteredBarcodes`/`pendingProductBarcodesLive` befüllt (`MasterProductViewModel.java:216-227`), zusätzlich:

```java
if (!isActionEdit() && filteredBarcodes.size() == 1) {
  // Nur eindeutiger Fall: genau ein Pending-Barcode für dieses Pending-Product.
  // Bei mehreren Barcodes mit ggf. widersprüchlichen qu_id/store_id-Werten
  // KEINE automatische Wahl treffen (PROJECT.md: "never guess uncertain values").
  ProductPrefillAssistant.MasterDataSuggestion suggestion =
      ProductPrefillAssistant.suggestMasterData(filteredBarcodes.get(0));
  Product current = formData.getProductLive().getValue();
  if (current != null) {
    if (suggestion.quId != null && !NumUtil.isStringInt(current.getQuIdPurchase())) {
      current.setQuIdPurchase(suggestion.quId);
    }
    if (suggestion.quId != null && !NumUtil.isStringInt(current.getQuIdStock())) {
      current.setQuIdStock(suggestion.quId);
    }
    if (suggestion.storeId != null && !NumUtil.isStringInt(current.getStoreId())) {
      current.setStoreId(suggestion.storeId);
    }
    formData.getProductLive().setValue(current);
  }
}
```

Wichtige Designentscheidungen (bewusst konservativ, im Sinne von PROJECT.md):
- Vorbefüllung nur bei **genau einem** zugeordneten Pending-Barcode — bei mehreren mit potenziell widersprüchlichen Werten wird nichts automatisch gesetzt (kein Raten).
- Vorbefüllung überschreibt **nie** bereits vorhandene Werte (Prüfung `!NumUtil.isStringInt(current.getQuIdPurchase())` etc.) — falls `new Product(sharedPrefs)` bereits einen Default aus den Einstellungen gesetzt hat, hat der explizite Nutzer-Default Vorrang vor dem Barcode-Vorschlag (bestehendes Verhalten aus `MasterProductViewModel.java:146` bleibt unangetastet).
- Nur `ACTION.CREATE`-Fall (`!isActionEdit()`), niemals beim Bearbeiten eines bestehenden Produkts.

**Was ausdrücklich unverändert bleiben muss:**
- Der EDIT- und CLONE-Zweig des Konstruktors (`MasterProductViewModel.java:80-154`) — Änderung betrifft ausschließlich `onQueueEmpty()`.
- `uploadBarcodesIfNecessary()` und der restliche Speicher-/Upload-Flow (`MasterProductViewModel.java:244-336`).
- Verhalten, wenn `args.getPendingProductBarcodes()` `null` ist (normale Neuanlage ohne Pending-Product) — bleibt exakt wie bisher, da der neue Block nur greift, wenn `filteredBarcodes` genau ein Element hat.
- Alle Felder in `MasterProductCatQuantityUnitFragment`/`MasterProductCatLocationFragment`, die der Nutzer weiterhin manuell ändern kann — die Vorbefüllung ist nur ein Startwert, keine Sperre.

**Akzeptanzkriterien:**
- Beim Öffnen von "Pending-Product zu echtem Produkt machen" aus `StoredPurchasesFragment`, wenn genau ein Barcode mit bekannter `qu_id` verknüpft ist: `MasterProductCatQuantityUnitFragment` zeigt diese Einheit bereits vorausgewählt, ist aber änderbar.
- Wenn kein Barcode oder mehrere Barcodes mit unterschiedlichen `qu_id`/`store_id`-Werten verknüpft sind: Felder bleiben leer/Default wie bisher (keine Regression).
- Normale Produktneuanlage ohne Pending-Product-Kontext: Verhalten 100 % unverändert.

**Tests/Verifikation:**
- Neuer Unit-/Robolectric-Test für `MasterProductViewModel.onQueueEmpty()` (bzw. für die extrahierte Vorbefüll-Bedingung, falls zwecks Testbarkeit als kleine private Methode ausgelagert): ein Pending-Barcode mit `qu_id` → Produkt-Objekt erhält `quIdPurchase`/`quIdStock`; zwei Pending-Barcodes mit unterschiedlicher `qu_id` → keine Änderung.
- Manuelle Verifikation: Kauf-Flow mit unbekanntem Barcode → "neues Produkt anlegen" → Barcode-Editor eine Einheit setzen (simuliert späteren Scan mit `qu_id`) → Pending-Product-Liste → "zu echtem Produkt machen" → Einheit ist vorausgefüllt.

**Risiken:**
- Mittel: Änderung an einer bestehenden, zentralen Methode (`onQueueEmpty()`). Risiko wird durch die enge Bedingung (nur bei genau einem Barcode, nur bei leeren Zielfeldern, nur bei CREATE) begrenzt.
- Randfall: Falls `qu_id` im Barcode auf eine inzwischen gelöschte/serverseitig andere `QuantityUnit`-ID verweist, würde ein ungültiger Wert vorbefüllt. Mitigation: Codex sollte prüfen, ob `quantityUnitHashMap`/vergleichbare geladene Einheiten-Map in `MasterProductViewModel` verfügbar ist, und den Vorschlag nur übernehmen, wenn die ID dort existiert (analog zur Absicherung, die `PurchaseViewModel.setProduct()` bereits über `unitFactors.containsKey(forcedUnit)` vornimmt).

**Upstream-Merge-Risiko:** **Mittel** — `onQueueEmpty()` ist eine bestehende, von Upstream gepflegte Methode; Änderung ist aber additiv (neuer Block am Ende der Methode), reduziert Konfliktwahrscheinlichkeit auf einfache Kontext-Zeilen-Konflikte.

**Abhängigkeiten:** Benötigt Task 2.1. Ist **unabhängig von Schritt 1** (siehe Scoping-Hinweis oben).

---

### Task 2.3 (optional, nur falls Zeit/Risiko-Budget vorhanden): Anbindung an Kauf-Flow

**Ziel:** Die in Task 2.1 geschaffene Utility zusätzlich in `PurchaseViewModel.setProduct()` für die dortige Einheiten-/Store-Vorauswahl einsetzen, damit die Utility **tatsächlich** an beiden vom PROJECT.md geforderten Stellen verwendet wird (nicht nur "könnte verwendet werden").

**Dateien (Modify):**
- `app/src/main/java/xyz/zedler/patrick/grocy/viewmodel/PurchaseViewModel.java:264-270` (Ermittlung `forcedQuId`) und `:363-370` (Ermittlung `storeId`)

**Genaue Änderung:** Ersetze die beiden Inline-Bedingungen
```java
Integer forcedQuId = null;
if (barcode != null && barcode.hasQuId()) {
  forcedQuId = barcode.getQuIdInt();
} else if (shoppingListItem != null && shoppingListItem.hasQuId()) {
  forcedQuId = shoppingListItem.getQuIdInt();
}
```
und
```java
String storeId;
if (formData.getPinnedStoreIdLive().getValue() != null) {
  storeId = String.valueOf(formData.getPinnedStoreIdLive().getValue());
} else if (barcode != null && barcode.hasStoreId()) {
  storeId = barcode.getStoreId();
} else { ... }
```
durch Aufrufe von `ProductPrefillAssistant.suggestMasterData(barcode)` für den barcode-abhängigen Teil, **ohne** die `shoppingListItem`- bzw. `pinnedStoreId`-Fallbacks zu verändern (diese bleiben vorrangig wie bisher).

**Was ausdrücklich unverändert bleiben muss:** Reihenfolge der Priorität (ShoppingListItem/PinnedStore vor Barcode), Amount- und Preis-Logik, alles andere in `setProduct()`.

**Akzeptanzkriterien:** Kein Verhaltensunterschied bei bestehenden Tests/manueller Prüfung des Kauf-Flows (reine 1:1-Substitution).

**Risiken:** Gering-mittel — Änderung an bereits produktivem, funktionierendem Code ohne funktionalen Zugewinn (nur Konsolidierung). **Da dies laut Vorgabe "kein Refactoring nur aus Gründen der Sauberkeit" ist, ist dieser Task ausdrücklich optional** und sollte nur umgesetzt werden, wenn die wörtliche Anforderung "in beiden Flows verwendet" höher gewichtet wird als das Prinzip der minimalen Änderung. Empfehlung: zurückstellen, bis ein echter dritter Verwendungsort (z. B. künftiges Packaging-Feature) die Konsolidierung ohnehin nötig macht.

**Upstream-Merge-Risiko:** **Mittel** (bestehende, viel genutzte Methode).

**Abhängigkeiten:** Task 2.1. Nicht erforderlich für die Kernfunktion von Schritt 2.

---

## Spätere Schritte (nur als Abhängigkeit erwähnt, hier nicht geplant)

- **Schritt 3:** Erweiterte OFF-Datennutzung (über den Produktnamen hinaus) — abhängig von Task 2.1 (Assistant-Utility als Erweiterungspunkt).
- **Schritt 4:** Eigenständiges Packaging-Konzept getrennt von Content-Quantity/-Unit — größte, invasivste Änderung; sollte `ProductPrefillAssistant` (Task 2.1) als Erweiterungsstelle nutzen, erfordert aber eigene, separate Planung und vermutlich Rücksprache mit deep-thinker wegen Server-Kompatibilität.
- **Schritt 5:** UI-Konsolidierung MasterProduct-Wizard ↔ Purchase-Flow auf Basis der in Schritt 2 geschaffenen Assistant-Utility.

---

## Zusammenfassung für Codex

1. Task 1.1 → Task 1.2 (Schritt 1, in sich abgeschlossen).
2. Task 2.1 → Task 2.2 (Kernlieferung Schritt 2; unabhängig von Schritt 1 startbar).
3. Task 2.3 nur nach expliziter Freigabe, da er bewusst funktionierenden Code ohne funktionalen Zugewinn anfasst.
