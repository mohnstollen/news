#!/usr/bin/env bash
# Baut die Linux-Pakete nach dist/:  newsreader_<version>_all.deb   (Debian, Ubuntu, MX Linux, Mint)
#                                    newsreader-<version>-1.noarch.rpm  (Fedora) – nur wenn rpmbuild vorhanden ist
# Aufruf:  bash linux/build.sh [VERSION]      (Standard: version aus package.json)
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:-$(sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' package.json | head -1)}"
PKGVER="${VERSION//-/\~}"            # 2.0.14-dev.abc → 2.0.14~dev.abc (sortiert vor 2.0.14)
ID=io.github.mohnstollen.newsreader
SUMMARY="RSS- und Atom-Reader mit Nextcloud-Abgleich"
BUILD=build/linux
rm -rf "$BUILD"; mkdir -p dist

# Dateibaum, wie er auf dem Zielsystem liegt
stage() {   # $1 = Zielordner
  local r="$1" app="$1/usr/share/newsreader"
  install -d "$app/www" "$r/usr/bin"
  install -m 644 linux/newsreader.py linux/bridge.js "$app/"
  install -m 644 index.html manifest.webmanifest "$app/www/"
  cp -r icons vendor "$app/www/"
  printf '%s\n' "$VERSION" > "$app/version"
  printf '#!/bin/sh\nexec /usr/bin/python3 /usr/share/newsreader/newsreader.py "$@"\n' > "$r/usr/bin/newsreader"
  chmod 755 "$r/usr/bin/newsreader"
  install -Dm 644 linux/$ID.desktop "$r/usr/share/applications/$ID.desktop"
  install -Dm 644 linux/$ID.metainfo.xml "$r/usr/share/metainfo/$ID.metainfo.xml"
  install -Dm 644 icons/favicon.png "$r/usr/share/icons/hicolor/64x64/apps/$ID.png"
  install -Dm 644 icons/icon-192.png "$r/usr/share/icons/hicolor/192x192/apps/$ID.png"
  install -Dm 644 icons/icon-512.png "$r/usr/share/icons/hicolor/512x512/apps/$ID.png"
  find "$r" -type d -exec chmod 755 {} +
  find "$r" -type f ! -path "$r/usr/bin/*" -exec chmod 644 {} +
}

# ----- .deb -----
DEB="$BUILD/deb"
stage "$DEB"
install -d "$DEB/DEBIAN"
cat > "$DEB/DEBIAN/control" <<CONTROL
Package: newsreader
Version: $PKGVER
Architecture: all
Maintainer: mohnstollen <mohnstollen@users.noreply.github.com>
Section: net
Priority: optional
Depends: python3 (>= 3.8), python3-gi, gir1.2-gtk-3.0, gir1.2-webkit2-4.1 | gir1.2-webkit2-4.0
Installed-Size: $(du -sk "$DEB/usr" | cut -f1)
Homepage: https://github.com/mohnstollen/news
Description: $SUMMARY
 Feedreader mit Ordnern, Schnellfiltern, Suche, Volltext, Notizen und
 gemerkten Artikeln. Abonnements, gemerkte Artikel, Notizen und der
 Gelesen-Status lassen sich über die eigene Nextcloud abgleichen.
CONTROL
dpkg-deb --root-owner-group -Zxz --build "$DEB" "dist/newsreader_${PKGVER}_all.deb" >/dev/null
echo "dist/newsreader_${PKGVER}_all.deb"

# ----- .rpm -----
if ! command -v rpmbuild >/dev/null; then
  echo "rpmbuild nicht gefunden – das Fedora-Paket wird übersprungen." >&2
  exit 0
fi
RPM="$PWD/$BUILD/rpm"
stage "$RPM/root"
cat > "$RPM/newsreader.spec" <<SPEC
Name:           newsreader
Version:        $PKGVER
Release:        1
Summary:        $SUMMARY
License:        LicenseRef-unspecified
URL:            https://github.com/mohnstollen/news
BuildArch:      noarch
Requires:       python3, python3-gobject, gtk3, webkit2gtk4.1
# fertigen Dateibaum unverändert übernehmen (kein Byte-Compile, keine Shebang-Anpassung)
%global __os_install_post %{nil}
%global _binary_payload w6.xzdio
%global _build_id_links none

%description
Feedreader mit Ordnern, Schnellfiltern, Suche, Volltext, Notizen und
gemerkten Artikeln. Abonnements, gemerkte Artikel, Notizen und der
Gelesen-Status lassen sich über die eigene Nextcloud abgleichen.

%install
cp -a "$RPM/root/." %{buildroot}/

%files
%attr(755,root,root) /usr/bin/newsreader
/usr/share/newsreader
/usr/share/applications/$ID.desktop
/usr/share/metainfo/$ID.metainfo.xml
/usr/share/icons/hicolor/*/apps/$ID.png
SPEC
rpmbuild -bb --quiet --define "_topdir $RPM/top" --define "_rpmdir $RPM/out" "$RPM/newsreader.spec"
cp "$RPM"/out/noarch/newsreader-*.noarch.rpm dist/
ls dist/newsreader-"$PKGVER"-1.noarch.rpm
