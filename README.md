# News und Podfang

Zwei Android-Apps (ab Android 8.0) aus einer Codebasis, geschrieben in Kotlin mit Jetpack Compose:

- **News**: RSS-/Atom-Reader
- **Podfang**: Podcast-App

Beide lassen sich nebeneinander installieren und haben getrennte Daten.

## Module

| Modul | Inhalt |
|---|---|
| `core` | Gemeinsamer Kern: Feeds, Datenbank, Nextcloud-Sync, OPML, Bildschirme, Wiedergabe |
| `app` | **News**: Paket `io.github.mohnstollen.news`, ohne Podcast-Funktionen, mit Widget |
| `podfang` | **Podfang**: Paket `io.github.mohnstollen.podfang`, eigenes Symbol, Player-Widget |

Was die Apps unterscheidet, steht in `core/…/AppFeatures.kt`: ob Podcast-Funktionen da sind,
einige Bezeichnungen („Alle Artikel“ / „Alle Folgen“) und der Nextcloud-Ordner. Die
App-Module wählen ihre Variante in ihrer Application-Klasse und entfernen in ihrem Manifest, was
sie nicht brauchen.

## Funktionen beider Apps

- **Feeds** hinzufügen (mit Prüfung der Adresse; bei einer normalen Website wird der Feed
  gesucht), bearbeiten, in Ordner sortieren und löschen
- **Anzeige**: alle Einträge, ein einzelner Feed oder die gemerkten; Sortierung nach
  Datum (neueste oder älteste zuerst); Schnellfilter für Ungelesene, Einträge der letzten
  drei Stunden und einen Ordner
- **Gelesen-Status**: automatisch beim Vorbeiscrollen (abschaltbar), per langem Druck,
  oder alle Einträge der aktuellen Ansicht auf einmal
- **Aufgeräumte Liste**: gelesene Einträge verschwinden nach einstellbarer Zeit aus den Listen
  (Standard: ein Tag) und bleiben über die Suche auffindbar
- **Archiv**: in der Liste nach links wischen (oder im Eintrag oben archivieren), damit er aus allen
  Listen verschwindet. Mit „Rückgängig“, über das Archiv im Menü und über die Suche zurückzuholen
- **Merken** (Stern), **Notiz** (Stift) und **Teilen**
- **Offline**: alles liegt in einer lokalen Datenbank. Der **Volltext** der verlinkten Seite
  lässt sich pro Eintrag laden oder pro Feed automatisch bei jedem Update
- **Aktualisierung** im Hintergrund in einstellbarem Intervall (Standard 60 Minuten, auf Wunsch
  nur im WLAN) oder nur manuell
- **Große Feeds** (langjährige Podcasts) werden bis 10 MB gelesen; reicht das nicht, fehlen die
  ältesten Einträge
- **Suche** über Titel, Text, Volltext und Notizen
- **Benachrichtigung** bei Neuem nach einem Hintergrund-Update (abschaltbar, auch pro Feed)
- **OPML** Import und Export
- **Nextcloud** (WebDAV): gleicht Feeds, gemerkte Einträge und Notizen zwischen Geräten ab. News
  nutzt standardmäßig den Ordner „News“, Podfang „Podfang“, damit sich die Abos nicht vermischen

## Nur News

- **Widget** für den Startbildschirm mit den neuesten ungelesenen Artikeln aller Feeds, eines
  Ordners oder eines einzelnen Feeds (pro Widget wählbar, auch mehrere nebeneinander) und mit
  eigenem, einstellbarem Aktualisierungsintervall

## Nur Podfang

- **Ablauf einer Folge**: Neue Folgen landen unter **Neu** (dem Eingang). Nach rechts wischen,
  herunterladen oder abspielen bringt sie in die **Warteschlange**, nach links wischen ins **Archiv**
  („nicht interessiert“). Eingereihte Folgen werden im WLAN geladen; heruntergeladene werden
  eingereiht (beides abschaltbar). Zu Ende gehört verlässt eine Folge die Warteschlange, die Datei wird
  auf Wunsch gelöscht
- **Tabs** unten: Neu, Warteschlange, Podcasts (Cover-Raster der Abos) und „Mehr“ (Einstellungen,
  Statistik, Podcasts entdecken, Gemerkt, Archiv). Kein Seitenmenü
- **Podcast-Übersicht**: Cover, Erscheinungsrhythmus und alle Folgen mit Abspielen, Herunterladen und
  Einreihen; Einstellungen des Podcasts und Abbestellen. Aus jeder Folge erreichbar
- **Cover**: in den Listen als Vorschaubild, groß im Player und klein im Mini-Player; zwischengespeichert,
  also auch offline sichtbar
- **Kapitel** im Player: aus dem Feed (Podlove Simple Chapters), aus einer verlinkten Kapiteldatei
  (Podcasting 2.0) oder aus der MP3-Datei (ID3); aktuelles Kapitel, Liste zum Springen, vorheriges
  und nächstes Kapitel
- **Wiedergabe** im Hintergrund mit Mini-Player, Player mit Positionsregler und Geschwindigkeit
  (0,8× bis 2×, auch direkt in der Folge umschaltbar), 10 s zurück / 30 s vor, Steuerung über
  Benachrichtigung, Sperrbildschirm, Kopfhörer und Bluetooth. Auf dem Sperrbildschirm und in der
  Benachrichtigung: vorherige/nächste Folge (aus der Warteschlange), 10 s zurück und 30 s vor; welches
  Paar neben Play liegt, ist einstellbar (manche Handys zeigen dort nur zwei Knöpfe). Jede Folge merkt
  sich, wo sie aufgehört hat; zu Ende gehörte Folgen gelten als gelesen
- **Widget** für den Startbildschirm, ein Widget für alle Größen: ab 2×1 Cover, Folge und Play/Pause,
  ab 4×1 zusätzlich 10 s zurück, 30 s vor und nächste Folge, ab 4×2 die nächsten drei Folgen der
  Warteschlange. Zeigt die laufende Folge mit verbleibender Zeit (läuft beim Abspielen mit) oder, wenn
  nichts geladen ist, die erste der Warteschlange; Play startet sie. Cover oder Titel öffnen den Player,
  eine Folge der Liste ihre Seite
- **Downloads**: Folgen pro Stück herunterladen (Knopf in der Folge) oder pro Podcast automatisch nach
  jedem Update, einstellbar schon beim Abonnieren oder später unter Podcasts verwalten → Podcast
  antippen. Standardmäßig nur im WLAN; unterbrochene Downloads laufen weiter, wo sie aufgehört haben.
  Gehörte Folgen werden auf Wunsch gelöscht, auf Wunsch schon kurz vor dem Ende („Abspann
  überspringen“: die letzten 10 bis 120 Sekunden zählen als gehört); Belegung und „Alle löschen“
  unter Einstellungen → Podcasts
- **Warteschlange**: Länge jeder Folge und verbleibende Gesamtzeit; nach rechts wischen schiebt ans Ende
  („später“), nach links wischen nimmt heraus (mit Rückgängig); Reihenfolge mit Pfeilen ändern.
  Die laufende Folge bleibt drin, bis sie zu Ende gehört ist.
  Längen, die der Feed nicht nennt, lernt die App beim Abspielen
- **Downloads**: Knopf an jeder Folge. Übersicht aller heruntergeladenen Folgen mit Gesamtlänge und
  Speicherbedarf unter Einstellungen → Podcasts → „Ansehen“
- **Sleep-Timer** im Player: 5 bis 90 Minuten oder bis zum Ende der Folge, mit sanftem Ausblenden
- **Statistik**: Hörzeit dieser Woche, dieses Monats, dieses Jahres und insgesamt, Lieblingspodcast,
  meistgehörte Podcasts, die letzten 7 Tage, zu Ende gehörte Folgen und durch Geschwindigkeit
  gesparte Zeit. Bleibt erhalten, wenn ein Podcast abbestellt wird
- **Podcasts entdecken**: Suche im öffentlichen Podcast-Katalog von Apple und Vorschläge passend zu den
  abonnierten Podcasts („Weil du … hörst“): gleiche Kategorie, Themen aus den Folgentiteln, für alle
  oder einen ausgewählten Podcast. Ein Tipp auf einen Podcast zeigt die letzten Folgen und wie oft er
  erscheint. Fragt den Katalog nur auf Knopfdruck; dabei gehen Podcast-Namen und Stichwörter an Apple,
  ohne Konto
- Spricht von Folgen und Podcasts statt von Artikeln und Feeds; kein Volltext, kein Artikel-Widget

## Bauen

Benötigt JDK 17 oder neuer und das Android SDK (Plattform 35).

```bash
./gradlew :app:assembleDebug :podfang:assembleDebug
```

Die APKs liegen danach unter `app/build/outputs/apk/debug/app-debug.apk` (News) und
`podfang/build/outputs/apk/debug/podfang-debug.apk` (Podfang).

```bash
./gradlew :core:testDebugUnitTest
```

## Nextcloud-Synchronisation

In der App unter **Einstellungen → Nextcloud-Synchronisation** einer von drei Wegen:

- **„Mit Nextcloud anmelden“** (am einfachsten): Server-Adresse eintragen, antippen, auf der
  Anmeldeseite der Nextcloud anmelden und „Zugriff gewähren“ (Login Flow v2). Die App erhält ein
  eigenes App-Passwort „News (Android)“ bzw. „Podfang (Android)“, speichert alles und synchronisiert. Die Seite öffnet sich
  als Custom Tab über der App; nach dem Schließen ist man zurück.
- **„Mit QR-Code anmelden“**: In Nextcloud unter **Persönliche Einstellungen → Sicherheit** ein
  App-Passwort erstellen und **„QR-Code für mobile Apps anzeigen“**, dann den Code scannen.
- **Von Hand**: Server-Adresse, Benutzername und App-Passwort eintragen.

Bei den ersten beiden Wegen werden Server, Benutzer und Passwort übernommen und gleich
synchronisiert. Der Text des QR-Codes (`nc://login/…`) lässt sich alternativ ins Feld
„Server-Adresse“ einfügen.

Die Kamera wird nur für den Scan gebraucht (CameraX und ZXing, ohne Google-Dienste). Meldet man
sich mit einer E-Mail-Adresse an, ermittelt die App beim ersten Sync die Nextcloud-Benutzer-ID
für den WebDAV-Pfad.

Die App legt im gewählten Ordner zwei Dateien an:

- `news-sync.json`: Feeds (inklusive gelöschter, damit sie nicht zurückkommen), gemerkte
  Artikel und Notizen. Bei Konflikten gewinnt pro Feed bzw. Artikel die jüngste Änderung.
- `feeds.opml`: Kopie der Abonnements für andere Programme. Wird nur geschrieben, nie gelesen.

Der Gelesen-Status wird nicht synchronisiert. Das Passwort wird verschlüsselt im Android
Keystore abgelegt.

## Code im Kern

Pfade relativ zu `core/src/main/java/io/github/mohnstollen/news/`.

| Pfad | Inhalt |
| --- | --- |
| `AppFeatures.kt` | Was News und Podfang unterscheidet |
| `data/Database.kt` | Room-Datenbank: Feeds, Artikel, Warteschlange, Hör-Protokoll, Löschmarker, Suchindex |
| `data/Chapters.kt` | Kapitel aus Feed, Kapiteldatei und ID3 |
| `data/Recommendations.kt` | Podcast-Suche und Vorschläge über den Katalog von Apple |
| `playback/` | Wiedergabedienst, Downloads, Sleep-Timer (nur Podfang) |
| `data/FeedParser.kt` | RSS 2.0, RSS 1.0 (RDF) und Atom, Datumsformate |
| `data/Network.kt` | Abruf, Feed-Suche auf Webseiten, Volltext-Extraktion |
| `data/Opml.kt` | OPML lesen und schreiben |
| `data/Sync.kt` | WebDAV-Client und Abgleich |
| `data/NextcloudLogin.kt` | Anmeldung: Login Flow v2, Anmelde-QR-Code lesen und dekodieren |
| `data/Repository.kt` | Aktualisieren, Abonnements, Artikelstatus |
| `work/RefreshWorker.kt` | Hintergrund-Aktualisierung mit WorkManager |
| `work/Notifier.kt` | Benachrichtigung über neue Artikel |
| `widget/` | Startbildschirm-Widgets: Artikel mit Auswahl pro Widget (News), Player (Podfang) |
| `ui/QrScanner.kt` | Kamera-Ansicht für den QR-Code |
| `ui/` | Oberfläche: Artikelliste, Leseansicht, Feeds, Einstellungen |
