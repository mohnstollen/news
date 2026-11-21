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
- **Podcasts**: Folgen mit Audio- oder Videodatei bekommen einen Play-Knopf in der Liste und im
  Artikel. Wiedergabe im Hintergrund mit Mini-Player, Player mit Positionsregler und Geschwindigkeit
  (0,8× bis 2×), 10 s zurück / 30 s vor, Steuerung über Benachrichtigung, Sperrbildschirm, Kopfhörer
  und Bluetooth. Jede Folge merkt sich, wo sie aufgehört hat; zu Ende gehörte Folgen gelten als gelesen
- **Downloads**: Folgen pro Stück herunterladen (Knopf im Artikel) oder pro Feed automatisch nach jedem
  Update, einstellbar schon beim Abonnieren oder später unter Feeds verwalten → Feed antippen. Standardmäßig nur im WLAN; unterbrochene Downloads laufen
  weiter, wo sie aufgehört haben. Gehörte Folgen werden auf Wunsch gelöscht; Belegung und „Alle löschen“
  unter Einstellungen → Podcasts
- **Warteschlange**: Folgen im Artikel einreihen oder in der Liste nach rechts wischen; sie laufen
  nacheinander, wenn eine Folge zu Ende ist. Reihenfolge ändern und entfernen unter „Warteschlange“ im Menü
- **Sleep-Timer** im Player: 5 bis 90 Minuten oder bis zum Ende der Folge, mit sanftem Ausblenden
- **Statistik**: Hörzeit dieser Woche, dieses Monats, dieses Jahres und insgesamt, Lieblingspodcast,
  meistgehörte Podcasts, die letzten 7 Tage, zu Ende gehörte Folgen und durch Geschwindigkeit gesparte Zeit.
  Bleibt erhalten, wenn ein Podcast abbestellt wird
- **Podcasts entdecken**: Suche im öffentlichen Podcast-Katalog von Apple und Vorschläge passend zu den
  abonnierten Podcasts („Weil du … hörst“): gleiche Kategorie, Themen aus den Folgentiteln. Fragt den
  Katalog nur auf Knopfdruck; dabei gehen Podcast-Namen und Stichwörter an Apple, ohne Konto
- **Archiv**: Artikel oder Folgen in der Liste nach links wischen (oder im Artikel oben archivieren), damit
  sie aus allen Listen verschwinden. Mit „Rückgängig“, über das Archiv im Menü und über die Suche
  zurückzuholen

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

In der App unter **Einstellungen → Nextcloud-Synchronisation** einer von drei Wegen:

- **„Mit Nextcloud anmelden“** (am einfachsten): Server-Adresse eintragen, antippen, auf der
  Anmeldeseite der Nextcloud anmelden und „Zugriff gewähren“ (Login Flow v2). Die App erhält ein
  eigenes App-Passwort „News (Android)“, speichert alles und synchronisiert. Die Seite öffnet sich
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

## Aufbau

| Pfad | Inhalt |
| --- | --- |
| `data/Database.kt` | Room-Datenbank: Feeds, Artikel, Löschmarker, Suchindex |
| `data/FeedParser.kt` | RSS 2.0, RSS 1.0 (RDF) und Atom, Datumsformate |
| `data/Network.kt` | Abruf, Feed-Suche auf Webseiten, Volltext-Extraktion |
| `data/Opml.kt` | OPML lesen und schreiben |
| `data/Sync.kt` | WebDAV-Client und Abgleich |
| `data/NextcloudLogin.kt` | Anmeldung: Login Flow v2, Anmelde-QR-Code lesen und dekodieren |
| `data/Repository.kt` | Aktualisieren, Abonnements, Artikelstatus |
| `work/RefreshWorker.kt` | Hintergrund-Aktualisierung mit WorkManager |
| `work/Notifier.kt` | Benachrichtigung über neue Artikel |
| `widget/NewsWidget.kt` | Startbildschirm-Widget |
| `ui/QrScanner.kt` | Kamera-Ansicht für den QR-Code |
| `ui/` | Oberfläche: Artikelliste, Leseansicht, Feeds, Einstellungen |
