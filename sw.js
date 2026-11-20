/* Service Worker der PWA: die App selbst offline verfügbar halten */
const CACHE = 'news-v1';
const ASSETS = ['./', './index.html', './manifest.webmanifest', './icons/icon-192.png', './icons/icon-512.png',
  './icons/icon-maskable-512.png', './icons/apple-touch-icon.png', './icons/favicon.png', './vendor/Readability.js', './vendor/jsQR.js'];
self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const { request } = e;
  if (request.method !== 'GET') return;
  // Feeds, Bilder und Nextcloud nie aus diesem Cache bedienen
  if (new URL(request.url).origin !== self.location.origin) return;
  // Erst das Netz (Updates kommen sofort an), offline aus dem Cache
  e.respondWith(fetch(request).then(resp => {
    if (resp.ok) { const copy = resp.clone(); caches.open(CACHE).then(c => c.put(request, copy)); }
    return resp;
  }).catch(() => caches.match(request).then(r => r || caches.match('./index.html'))));
});
