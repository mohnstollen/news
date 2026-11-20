# News

RSS-/Atom-Reader mit Nextcloud-Abgleich – eine Web-App (`index.html`), die als **Android-App**
(Capacitor), als **Linux-App** (GTK + WebKitGTK) und im Browser (PWA) läuft. Alle drei teilen sich
denselben Code; nur der Zugriff aufs Netz und aufs System kommt von der jeweiligen Hülle.

## Funktionen

- **Feeds** hinzufügen (mit Prüfung der Adresse; bei einer normalen Website wird der Feed
  gesucht), bearbeiten, in Ordner sortieren und löschen; **OPML** Import und Export
- **Anzeige**: alle Artikel, ein einzelner Feed oder die gemerkten Artikel; neueste oder älteste
  zuerst; Schnellfilter für Ungelesene, Artikel der letzten drei Stunden und einen Ordner
- **Gelesen-Status**: automatisch beim Vorbeiscrollen (abschaltbar), per langem Druck bzw.
  Rechtsklick, oder alle Artikel der aktuellen Ansicht auf einmal. Gelesene Artikel verschwinden
  nach einstellbarer Zeit aus den Listen und bleiben über die Suche auffindbar
- **Merken** (Stern), **Notiz** und **Teilen** für jeden Artikel
- **Offline**: Artikel liegen lokal (IndexedDB). Der **Volltext** der verlinkten Seite lässt sich
  pro Artikel laden oder pro Feed automatisch bei jedem Update
- **Suche** über Titel, Text, Volltext und Notizen (Wortanfänge, Umlaute egal)
- **Aktualisierung** im einstellbaren Intervall, auf Android auch bei geschlossener App, mit
  **Benachrichtigung** über neue Artikel (abschaltbar, auch pro Feed)
- **Nextcloud**: gleicht Feeds, gemerkte Artikel, Notizen und den Gelesen-Status ab. Anmeldung
  über den Browser (Login Flow v2), per **QR-Code** eines App-Passworts oder von Hand
- **Design** hell, dunkel oder wie das System; auf Linux wird das dunkle GTK-Thema erkannt
- **Breite Fenster**: drei Spalten (Feeds, Liste, Artikel) und Tastenkürzel – J/K nächster bzw.
  vorheriger Artikel, S merken, M als ungelesen markieren, V im Browser öffnen, Strg+F suchen,
  F5 aktualisieren, Strg+B Feed-Leiste, Esc zurück

## Installieren

Die CI baut bei jedem Push eine APK und die Linux-Pakete (unter *Actions* → Lauf → *Artifacts*).
Auf `main` entsteht zusätzlich ein Release mit festen Dateinamen:

- Android: `newsreader.apk` installieren. Alle Builds sind mit demselben Schlüssel
  (`android/app/debug.keystore`) signiert und lassen sich deshalb als Update übereinander
  installieren. Wer einen eigenen Schlüssel will, hinterlegt ihn als Secrets
  `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (und optional
  `ANDROID_KEY_PASSWORD`); ein Wechsel des Schlüssels erfordert einmal eine Neuinstallation.
- Debian, Ubuntu, MX Linux, Mint:

  ```bash
  sudo apt install ./newsreader.deb
  ```

- Fedora: `sudo dnf install ./newsreader.rpm`

Die App heißt im Startmenü „News“, der Befehl ist `newsreader`. Daten liegen unter
`~/.local/share/newsreader`.

### Umstieg von der früheren Kotlin-App

Die neue App hat eine eigene Kennung (`io.github.mohnstollen.newsreader`) und läuft neben der
alten. Die Daten übernimmt sie über Nextcloud – das Format von `news-sync.json` ist dasselbe – oder
per OPML-Export und -Import. Danach lässt sich die alte App deinstallieren
(unter Linux: `sudo apt remove news-reader`).

## Entwickeln

Benötigt Node.js 22. Die Web-App selbst braucht keinen Build-Schritt:

```bash
npm ci
```

```bash
npm test
```

Die Tests (Playwright) prüfen Feeds, Liste, Suche, Sync und die Android-Anbindung gegen einen
nachgebauten Server für Feeds, Webseiten und Nextcloud (`tests/helpers/mock-server.mjs`).

Linux-App aus dem Quellcode starten bzw. Ende-zu-Ende prüfen (braucht `python3-gi`,
`gir1.2-webkit2-4.1`, für den Test `xvfb`):

```bash
python3 linux/newsreader.py
```

```bash
node linux/test.mjs
```

Pakete bauen (`.rpm` nur, wenn `rpmbuild` installiert ist):

```bash
bash linux/build.sh
```

Android-App (braucht JDK 21 und das Android SDK):

```bash
npm run sync
```

```bash
cd android && ./gradlew assembleRelease
```

Nach einer Änderung an Readability oder jsQR in `package.json`: `npm run vendor`.
Die Symbole erzeugt `python3 scripts/make-icons.py`.

## Nextcloud-Synchronisation

In den Einstellungen „Mit Nextcloud anmelden“ (öffnet die Anmeldung im Browser), den QR-Code eines
App-Passworts scannen oder Server, Benutzer und App-Passwort von Hand eintragen. Die App legt im
gewählten Ordner zwei Dateien an:

- `news-sync.json`: Feeds (inklusive gekündigter, damit sie nicht zurückkommen), gemerkte Artikel,
  Notizen und Gelesen-Markierungen der letzten drei Wochen. Bei Konflikten gewinnt pro Feed bzw.
  Artikel die jüngste Änderung.
- `feeds.opml`: Kopie der Abonnements für andere Programme. Wird nur geschrieben, nie gelesen.

Artikel, die ein anderes Gerät schon gelesen hat, kommen beim nächsten Update als gelesen an und
lösen keine Benachrichtigung aus. Der Gelesen-Abgleich lässt sich abschalten.

## Aufbau

| Pfad | Inhalt |
| --- | --- |
| `index.html` | die ganze App: Speicher (IndexedDB), Feed-Parser, Volltext, Sync, Oberfläche |
| `vendor/` | Readability (Volltext) und jsQR (QR-Code), aus `node_modules` kopiert |
| `android/` | Capacitor-Projekt; `NewsHttpPlugin` (Netz ohne CORS), `RefreshWorker` (Updates bei geschlossener App) |
| `linux/` | `newsreader.py` (GTK-Fenster mit WebKitGTK), `bridge.js` (dieselben Plugins wie Android), Paketbau |
| `tests/` | Playwright-Tests und Server-Nachbau |
| `app/`, `desktop/` | die frühere Kotlin-App (Jetpack Compose) und ihre Desktop-Version |
