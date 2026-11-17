# News

RSS-/Atom-Reader für Android (ab Android 8.0), geschrieben in Kotlin mit Jetpack Compose.

## Funktionen

- **Feeds** hinzufügen (mit Prüfung der Adresse; bei einer normalen Website wird der Feed
  gesucht), bearbeiten, in Ordner sortieren und löschen
- **Anzeige**: alle Artikel, ein einzelner Feed oder die gemerkten Artikel; Sortierung nach
  Datum (neueste oder älteste zuerst); Schnellfilter für Ungelesene, Artikel der letzten
  drei Stunden und einen Ordner
- **Gelesen-Status**: automatisch beim Vorbeiscrollen (abschaltbar), per langem Druck auf einen
  Artikel, oder alle Artikel der aktuellen Ansicht auf einmal
- **Aufgeräumte Liste**: gelesene Artikel verschwinden nach einstellbarer Zeit aus den Listen
  (Standard: ein Tag) und bleiben über die Suche auffindbar
- **Merken** (Stern), **Notiz** (Stift) und **Teilen** für jeden Artikel
- **Offline**: Artikel liegen in einer lokalen Datenbank. Der **Volltext** der verlinkten Seite
  lässt sich pro Artikel laden oder pro Feed automatisch bei jedem Update
- **Aktualisierung** im Hintergrund in einstellbarem Intervall (Standard 60 Minuten, auf Wunsch
  nur im WLAN) oder nur manuell
- **Suche** über Titel, Text, Volltext und Notizen, in allen Artikeln oder im gewählten Feed
- **Benachrichtigung** bei neuen Artikeln nach einem Hintergrund-Update (abschaltbar, auch pro Feed)
- **Widget** für den Startbildschirm mit den neuesten ungelesenen Artikeln und eigenem,
  einstellbarem Aktualisierungsintervall
- **OPML** Import und Export
- **Nextcloud** (WebDAV): gleicht Feeds, gemerkte Artikel und Notizen zwischen Geräten ab

## Bauen

Benötigt JDK 17 oder neuer und das Android SDK (Plattform 35).

```bash
./gradlew :app:assembleDebug
```

Die APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.

```bash
./gradlew :app:testDebugUnitTest
```

## Nextcloud-Synchronisation

Am einfachsten: In Nextcloud unter **Persönliche Einstellungen → Sicherheit** ein App-Passwort
erstellen und **„QR-Code für mobile Apps anzeigen“**. In der App unter **Einstellungen →
Nextcloud-Synchronisation → „Mit QR-Code anmelden“** den Code scannen; Server, Benutzer und
Passwort werden übernommen und gleich synchronisiert. Der Text des QR-Codes (`nc://login/…`)
lässt sich alternativ ins Feld „Server-Adresse“ einfügen. Von Hand geht es mit Server-Adresse,
Benutzername und App-Passwort.

Die Kamera wird nur für den Scan gebraucht (CameraX und ZXing, ohne Google-Dienste). Meldet man
sich mit einer E-Mail-Adresse an, ermittelt die App beim ersten Sync die Nextcloud-Benutzer-ID
für den WebDAV-Pfad.

Die App legt im gewählten Ordner zwei Dateien an:

- `news-sync.json`: Feeds (inklusive gelöschter, damit sie nicht zurückkommen), gemerkte
  Artikel und Notizen. Bei Konflikten gewinnt pro Feed bzw. Artikel die jüngste Änderung.
- `feeds.opml`: Kopie der Abonnements für andere Programme. Wird nur geschrieben, nie gelesen.

Der Gelesen-Status wird nicht synchronisiert. Das Passwort wird verschlüsselt im Android
Keystore abgelegt.

## Aufbau

| Pfad | Inhalt |
| --- | --- |
| `data/Database.kt` | Room-Datenbank: Feeds, Artikel, Löschmarker, Suchindex |
| `data/FeedParser.kt` | RSS 2.0, RSS 1.0 (RDF) und Atom, Datumsformate |
| `data/Network.kt` | Abruf, Feed-Suche auf Webseiten, Volltext-Extraktion |
| `data/Opml.kt` | OPML lesen und schreiben |
| `data/Sync.kt` | WebDAV-Client und Abgleich |
| `data/NextcloudLogin.kt` | Anmelde-QR-Code lesen und dekodieren |
| `data/Repository.kt` | Aktualisieren, Abonnements, Artikelstatus |
| `work/RefreshWorker.kt` | Hintergrund-Aktualisierung mit WorkManager |
| `work/Notifier.kt` | Benachrichtigung über neue Artikel |
| `widget/NewsWidget.kt` | Startbildschirm-Widget |
| `ui/QrScanner.kt` | Kamera-Ansicht für den QR-Code |
| `ui/` | Oberfläche: Artikelliste, Leseansicht, Feeds, Einstellungen |
