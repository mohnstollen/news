// Kopiert die Fremdbibliotheken aus node_modules nach vendor/ (eingecheckt, damit die
// Web-App ohne Build-Schritt läuft). Nach einem Versionswechsel in package.json ausführen.
import { copyFileSync } from 'node:fs';

const FILES = {
  'node_modules/@mozilla/readability/Readability.js': 'vendor/Readability.js',
  'node_modules/jsqr/dist/jsQR.js': 'vendor/jsQR.js',
};
for (const [from, to] of Object.entries(FILES)) copyFileSync(from, to);
console.log('vendor/ aktualisiert:', Object.values(FILES).join(', '));
