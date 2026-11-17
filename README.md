# News

RSS-/Atom-Reader für Android (ab Android 8.0) und Linux, geschrieben in Kotlin mit Jetpack Compose
bzw. Compose for Desktop.

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
- **Nextcloud** (WebDAV): gleicht Feeds, gemerkte Artikel, Notizen und den Gelesen-Status
  zwischen Geräten ab

## Bauen

Benötigt JDK 17 oder neuer und das Android SDK (Plattform 35).

```bash
./gradlew :app:assembleDebug
```

Die APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.

```bash
./gradlew :app:testDebugUnitTest
```

## Linux-Version

Das Modul `desktop` ist dieselbe App für den Linux-Desktop. Datenbank, Feed-Laden, Volltext,
Suche, OPML und Nextcloud-Sync stammen aus denselben Quelldateien wie in der Android-App
(`app/src/main/java/.../data`, siehe `desktop/build.gradle.kts`). Beide Versionen verhalten sich
dadurch gleich und gleichen sich über dieselbe `news-sync.json` ab.

Unterschiede zur Android-App:

- **Drei Spalten** in breiten Fenstern: Feeds, Artikelliste, Artikel. In schmalen Fenstern
  ersetzt der Artikel die Liste wie auf dem Handy, und die Feed-Leiste lässt sich ausblenden (Strg+B)
- **Tastatur**: J/K nächster/vorheriger Artikel, S merken, M als ungelesen markieren,
  V im Browser öffnen, Strg+F suchen, F5 oder Strg+R aktualisieren, Esc schließen
- **Kontextmenü** (Rechtsklick) statt langem Druck; **Link kopieren** statt Teilen
- **Design** hell, dunkel oder wie der Desktop: erkennt das dunkle Design von Xfce, GNOME,
  Cinnamon, MATE und KDE und folgt Änderungen während des Betriebs
- Weitere Tasten: Strg+, Einstellungen, Strg+1/Strg+2 alle bzw. gemerkte Artikel,
  Strg+W Fenster schließen, Strg+Q beenden
- **Hintergrund**: Aktualisierung läuft, solange die App läuft. Beim Schließen bleibt sie auf
  Wunsch im Systembereich der Leiste (Tray) und kann beim Anmelden unsichtbar starten.
  Benachrichtigungen kommen über `notify-send`
- **App-Passwort** für Nextcloud liegt im Schlüsselbund (Secret Service über `secret-tool`),
  ohne diesen in einer nur für den Benutzer lesbaren Datei
- Kein Widget und keine WLAN-Einstellung
- Daten unter `~/.local/share/news-reader`, Einstellungen unter `~/.config/news-reader`

Starten aus dem Quellcode (JDK 17 oder neuer, kein Android SDK nötig):

```bash
./gradlew :desktop:run
```

Pakete bauen (`.deb` braucht `fakeroot`, `.rpm` braucht `rpmbuild`); sie enthalten eine
eigene Java-Laufzeit:

```bash
./gradlew :desktop:packageDeb
```

Das Paket liegt danach unter `desktop/build/compose/binaries/main/deb/`. Tests:

```bash
./gradlew :desktop:test
```

## Nextcloud-Synchronisation

In den Einstellungen Server-Adresse, Benutzername und ein App-Passwort eintragen. Die App legt
im gewählten Ordner zwei Dateien an:

- `news-sync.json`: Feeds (inklusive gelöschter, damit sie nicht zurückkommen), gemerkte
  Artikel, Notizen und der Gelesen-Status. Bei Konflikten gewinnt pro Feed bzw. Artikel die
  jüngste Änderung.
- `feeds.opml`: Kopie der Abonnements für andere Programme. Wird nur geschrieben, nie gelesen.

Der Gelesen-Status (abschaltbar) umfasst die Markierungen der letzten drei Wochen, damit die Datei
klein bleibt. Artikel, die ein anderes Gerät schon gelesen hat, kommen beim nächsten Update als
gelesen an und lösen keine Benachrichtigung aus. Ältere Versionen der App lassen den Gelesen-Status
beim Abgleich weg; deshalb sollten alle Geräte aktualisiert werden. Das Passwort wird auf Android
verschlüsselt im Android Keystore abgelegt.

## Aufbau

| Pfad | Inhalt |
| --- | --- |
| `data/Database.kt` | Room-Datenbank: Feeds, Artikel, Löschmarker, Suchindex |
| `data/FeedParser.kt` | RSS 2.0, RSS 1.0 (RDF) und Atom, Datumsformate |
| `data/Network.kt` | Abruf, Feed-Suche auf Webseiten, Volltext-Extraktion |
| `data/Opml.kt` | OPML lesen und schreiben |
| `data/Sync.kt` | WebDAV-Client und Abgleich |
| `data/Repository.kt` | Aktualisieren, Abonnements, Artikelstatus |
| `work/RefreshWorker.kt` | Hintergrund-Aktualisierung mit WorkManager |
| `work/Notifier.kt` | Benachrichtigung über neue Artikel |
| `widget/NewsWidget.kt` | Startbildschirm-Widget |
| `ui/` | Oberfläche: Artikelliste, Leseansicht, Feeds, Einstellungen |
