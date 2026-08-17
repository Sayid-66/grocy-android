# CLAUDE.md

Diese Datei gibt Claude verbindliche Regeln für die Arbeit an diesem Repository.
Es handelt sich um einen **privaten Fork von Grocy Android** (Original: https://github.com/patzly/grocy-android),
ausschließlich für den **persönlichen Gebrauch** des Repo-Besitzers. Es gibt kein externes Nutzerpublikum,
keinen Play-Store-Release-Prozess und keine Pull-Request-Reviewer, auf die Rücksicht genommen werden muss –
trotzdem gilt: sauberer, nachvollziehbarer Code, keine Abkürzungen bei Datensicherheit oder Stabilität.

## Projektüberblick

- **App:** Android-Client für [Grocy](https://grocy.info/) (self-hosted Haushalts-/Vorratsverwaltung).
  Die App ist ein reiner **Companion-Client** über die Grocy-HTTP-API – sie kann nicht standalone laufen.
- **Sprache/Stack:** Der komplette bestehende Code in `app/src/main/java` ist **Java** (kein Kotlin,
  trotz `kotlinx-serialization-json` als Abhängigkeit). `minSdk 23`, `targetSdk`/`compileSdk 37`.
  Architekturmuster: **MVVM** – `Fragment` (View) → `ViewModel` → `Repository` → `helper/DownloadHelper`
  (Volley-basierte HTTP-Requests mit Callback-Listenern `OnObjectResponseListener` / `OnErrorListener`),
  Datenhaltung über **Room** (`database/`, `dao/`), Datenmodelle in `model/`, Navigation über das
  Android Navigation Framework mit Safe Args.
- Wichtige Pakete: `activity/`, `fragment/`, `viewmodel/`, `repository/`, `model/`, `dao/`, `database/`,
  `api/` (URL-Builder je Backend, z. B. `GrocyApi`, `OpenFoodFactsApi`, `OpenBeautyFactsApi`), `scanner/`
  (ZXing-Barcode-Scanning), `helper/`, `util/`, `web/` (Volley-Requests/SSL).
- Home-Assistant-Kompatibilität (Grocy als HA-Add-on) ist ein bestehendes, zu erhaltendes Feature
  (siehe FAQ.md) – Netzwerk-/Auth-Code darf diese Konstellation nicht brechen.
- Es existiert **kein bestehendes Test-Verzeichnis** (`app/src/test`, `app/src/androidTest`) im Projekt.
  Neue Logik soll trotzdem nach Möglichkeit mit Tests abgesichert werden (siehe unten).

## Grundhaltung

- Dies ist **mein persönlicher Fork**. Optimiere für *meinen* Workflow, nicht für Upstream-Kompatibilität
  oder generische Nutzerfreundlichkeit – aber ändere nichts leichtfertig, das andere Teile der App
  beeinflusst.
- **Bestehende Architektur und Muster respektieren.** Neuer Code folgt dem vorhandenen MVVM-Aufbau,
  dem Java-Stil, den Namenskonventionen und dem Callback-/Listener-Stil (`DownloadHelper`,
  `OnObjectResponseListener`, `OnErrorListener`) dieses Projekts – keine neuen Architektur- oder
  Nebenläufigkeits-Paradigmen (z. B. Kotlin Coroutines, RxJava-Ketten wo bisher Callbacks verwendet
  werden, neue DI-Frameworks) einführen, ohne dass das explizit gewünscht ist.
- **Änderungen klein, gezielt und wartbar halten.** Nur ändern, was für die konkrete Aufgabe nötig ist.
- **Keine unnötigen Refactorings** oder Änderungen außerhalb des aktuellen Auftrags – auch nicht
  "aus Prinzip" oder um Code moderner/eleganter zu machen. Wenn während der Arbeit größere Probleme
  auffallen, die nicht Teil der Aufgabe sind: kurz melden statt ungefragt mit zu ändern.
- **Vor jeder Änderung den relevanten bestehenden Code lesen und verstehen** (betroffene Fragment/
  ViewModel/Repository/API-Klassen, verwandte Model-Klassen, Aufrufer), bevor etwas angepasst wird.
- **Grocy-API- und Home-Assistant-Kompatibilität nicht beschädigen.** Änderungen an `api/GrocyApi.java`,
  Auth-Handling, SSL/`ssl/`-Paket oder Request-Verhalten (`web/`, `helper/DownloadHelper`) besonders
  vorsichtig behandeln und gegen bestehende Nutzung prüfen.
- **Nichts erfinden.** Keine APIs, Klassen, Methoden, Felder oder Bibliotheksfunktionen annehmen, die
  nicht nachweislich existieren. Bei Unsicherheit: zuerst im Code (Grep/Read) oder in offizieller
  Dokumentation (Grocy-API-Doku, Open Food Facts API, verwendete Android/Gradle-Libraries laut
  `gradle/libs.versions.toml`) nachsehen, nicht raten.

## Code-Qualität

- Kotlin-/Android-Best-Practices einhalten, soweit sie zum bestehenden Java-Code passen. Da das
  Projekt aktuell durchgehend Java ist: neuen Code standardmäßig ebenfalls in Java schreiben, im
  gleichen Stil (License-Header, Formatierung, Sichtbarkeiten, Naming) wie die umgebenden Dateien.
  Nur auf ausdrücklichen Wunsch neue Kotlin-Dateien einführen.
- **Null-Safety**: `@Nullable`/`@NonNull`-Annotationen konsequent verwenden bzw. beachten, defensive
  Null-Checks bei API-/JSON-Daten (siehe bestehendes Muster in `model/OpenFoodFactsProduct.java` mit
  `JSONException`-Handling und Default-Werten).
- **Lifecycle**: `ViewModel`/`LiveData`-Bindungen an den Fragment-Lifecycle beachten, keine Leaks über
  Context-Referenzen in Callbacks, laufende Requests bei Fragment-Zerstörung sauber behandeln
  (bestehende Muster in den `viewmodel/*ViewModel.java`-Klassen als Vorbild nehmen).
- **Nebenläufigkeit**: Netzwerkaufrufe laufen aktuell über Volley/`DownloadHelper` mit Callback-Listenern
  auf dem Main-Thread nach Response. Dieses Muster fortführen; auf Race Conditions bei parallelen/
  verketteten Requests (z. B. mehrere Barcode-Lookups nacheinander) besonders achten.
  Bei Room-Zugriffen bestehendes Async-Muster (RxJava3, siehe `room-rxjava3`-Dependency) respektieren.
  Fehlerhafte oder fehlende Netzwerkantworten immer über `OnErrorListener`/vorhandene Fehlerpfade
  behandeln, nie stillschweigend ignorieren.
- **Fehlerbehandlung**: Netzwerkfehler, leere/unerwartete API-Antworten und Timeouts explizit abfangen
  und sinnvoll behandeln (Fallback, Nutzerhinweis, Logging über bestehendes `Log`/Debug-Muster) –
  nie ungeprüfte Annahmen über Response-Struktur externer APIs treffen.
- **Eingaben und externe Daten defensiv behandeln**: Barcode-Strings, Nutzereingaben und Antworten von
  Grocy-API sowie externen Produktdatenbanken (Open Food Facts u. ä.) immer validieren/prüfen, bevor
  sie weiterverarbeitet oder in Room persistiert werden.
- **Keine Secrets im Code oder in Git**: keine API-Keys, Tokens, Passwörter, Server-URLs mit
  eingebetteten Zugangsdaten o. Ä. hart kodieren oder committen. Konfiguration bleibt nutzerseitig
  (Settings/Preferences), nicht im Repository.

## Barcode-/Produkt-Workflow (Projektziel)

Ziel ist ein möglichst reibungsloser Barcode-Scan-Workflow für den privaten Haushalt:

1. Bekannter Barcode → direkt vorhandenes Grocy-Produkt verwenden (bestehender Grocy-API-Weg, nicht
   umgehen oder duplizieren).
2. Unbekannter Barcode → automatische Suche in kostenlosen externen Produktdatenbanken, primär
   **Open Food Facts** (bereits integriert über `api/OpenFoodFactsApi.java` /
   `model/OpenFoodFactsProduct.java`, siehe auch `OpenBeautyFactsApi`/`OpenBeautyFactsProduct` als
   analoges bestehendes Muster für einen zweiten Datenanbieter).
3. Gefundene Produktdaten möglichst automatisch in das Neuanlage-Formular übernehmen, sodass der
   Nutzer nur noch die nötigen/fehlenden Angaben bestätigt (Ansatzpunkt: `ChooseProductViewModel`/
   zugehörige Fragmente, die den bestehenden OpenFoodFacts-Abruf bereits nutzen).
4. Weitere externe Datenquellen als Fallback sind für später vorgesehen – neue Provider nach dem
   bestehenden `api/`+`model/`-Muster (eigene `*Api`-Klasse + eigenes Produkt-Model) integrieren,
   nicht in bestehende Klassen wie `OpenFoodFactsProduct` hineinmischen.

Bei Arbeiten an diesem Bereich: bestehenden Ablauf in `ChooseProductViewModel`/`ChooseProductRepository`
und den zugehörigen Fragmenten zuerst nachvollziehen, bevor Automatisierung ergänzt wird.

## Arbeitsablauf bei Änderungen

1. Aufgabe/relevanten Code verstehen, bevor etwas geändert wird.
2. Kleinstmögliche, gezielte Änderung umsetzen, die zur bestehenden Architektur passt.
3. **Nach relevanten Änderungen das Projekt kompilieren** (`./gradlew assembleDebug` bzw. passendes
   Gradle-Target) und **vorhandene Tests ausführen** (`./gradlew test` – aktuell existiert keine
   Test-Suite, das ist also primär relevant, sobald Tests ergänzt wurden).
4. **Android Lint** und weitere vorhandene statische Prüfungen laufen lassen (`./gradlew lint`;
   Projekt-Lint-Konfiguration in `app/build.gradle` beachtet bereits `abortOnError = false` und
   deaktiviertes `MissingTranslation` – neue Lint-Warnungen trotzdem ernst nehmen, nicht pauschal
   unterdrücken).
5. Neue nicht-triviale Logik nach Möglichkeit mit Tests absichern (JUnit; für Android-Komponenten
   ggf. Instrumented Tests) – dafür bei Bedarf `app/src/test`/`app/src/androidTest` neu anlegen.
6. Bei Fehlern/Testfehlschlägen: erst die **Ursache** analysieren (Root Cause), nicht nur das
   Symptom umgehen (z. B. keine Try/Catch-Verschleierung ohne Verständnis des eigentlichen Problems).
7. Nach jeder größeren Änderung eigenen Code noch einmal kritisch prüfen: Bugs, Seiteneffekte auf
   andere Features (insbesondere Grocy-API- und Home-Assistant-Kompatibilität), Sicherheitsprobleme
   (z. B. Umgang mit Zugangsdaten, SSL/Zertifikatsprüfung im `ssl/`-Paket, Eingabevalidierung).
8. **Nichts committen oder pushen**, sofern nicht ausdrücklich verlangt. Änderungen bleiben im
   Arbeitsverzeichnis, bis explizit ein Commit/Push gewünscht wird.

## Nicht tun

- Keine großflächigen Refactorings, Umbenennungen, Formatierungs- oder Architektur-Änderungen "nebenbei".
- Keine neuen Abhängigkeiten/Libraries hinzufügen, ohne dass das für die Aufgabe nötig ist und ohne
  kurze Begründung.
- Keine Vermutungen über Grocy-API-Endpunkte, Open-Food-Facts-Response-Felder oder Android-/Library-
  APIs als Tatsache behandeln – im Zweifel nachschlagen oder nachfragen.
- Keine Commits/Pushes, keine Versionsnummern-Bumps, keine Release-/Changelog-Änderungen ohne
  ausdrückliche Anweisung.
