// Hilfsfunktionen: ein „Gerät“ = eigener Browser-Kontext mit eigenem Speicher
import { USER, PASS } from './mock-server.mjs';

export const APP = 'http://127.0.0.1:4173/index.html';

/**
 * Öffnet die App in einem neuen Kontext.
 * @param {object} o
 * @param {boolean} [o.native=true]  simulierte Android-App (Netz über das Plugin NewsHttp)
 * @param {object}  [o.prefs]        Einstellungen beim ersten Laden
 * @param {object}  [o.viewport]
 * @param {string}  [o.colorScheme]
 */
export async function openApp(browser, o = {}) {
  const { native = true, prefs = {}, viewport = { width: 390, height: 844 }, colorScheme = 'dark' } = o;
  const ctx = await browser.newContext({ viewport, hasTouch: viewport.width < 900, colorScheme, locale: 'de-DE', timezoneId: 'Europe/Berlin' });
  await ctx.addInitScript(p => {
    if (sessionStorage.getItem('__seeded')) return;
    sessionStorage.setItem('__seeded', '1');
    localStorage.setItem('news_prefs', JSON.stringify({ autoUpdate: false, ...p }));
  }, prefs);
  if (native) await ctx.addInitScript(nativeMock);
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(APP);
  await page.waitForFunction(() => window.__newsReady);
  return { ctx, page, errors };
}

/** Nextcloud direkt eintragen (ohne Dialog) */
export const configureSync = (page, server, extra = {}) => page.evaluate(([url, user, pass, extra]) =>
  savePrefs({ ncServer: url, ncUser: user, ncPassword: pass, ncFolder: 'News', ...extra }), [server.url, USER, PASS, extra]);

/** Titel der Artikel in der angezeigten Liste */
export const listTitles = page => page.$$eval('#list .row .t', els => els.map(e => e.textContent));

/**
 * Läuft im Browser: bildet window.Capacitor mit den Plugins der Android-App nach.
 * Aufrufe landen in window.__native.log.
 */
function nativeMock() {
  const log = [], listeners = {};
  window.__native = { log, notifications: [], fire: (ev, arg) => listeners[ev] && listeners[ev](arg) };
  const toB64 = buf => { let s = ''; const b = new Uint8Array(buf); for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000)); return btoa(s); };
  window.Capacitor = { isNativePlatform: () => true, getPlatform: () => 'android', Plugins: {
    NewsHttp: { request: async ({ url, method = 'GET', headers = {}, body, binary }) => {
      log.push(`http ${method} ${url}`);
      const h = { ...headers }; delete h['User-Agent'];
      const r = await fetch(url, { method, headers: h, body: body ?? undefined });
      const rh = {}; r.headers.forEach((v, k) => { rh[k] = v; });
      return { status: r.status, headers: rh, url: r.url || url, data: binary ? toB64(await r.arrayBuffer()) : await r.text() };
    } },
    Filesystem: { writeFile: async o => { log.push(`write ${o.directory} ${o.path}`); window.__native.file = o.data; return { uri: 'file:///cache/' + o.path }; } },
    Share: { share: async o => { log.push(`share ${(o.files || []).join(',') || o.url || ''}`); } },
    Browser: { open: async o => { log.push(`browser ${o.url}`); }, close: async () => {} },
    App: { addListener: (ev, cb) => { listeners[ev] = cb; }, minimizeApp: async () => { log.push('minimize'); } },
    NewsBackground: {
      configure: async o => { window.__native.background = o; log.push('bg configure'); },
      take: async () => { const items = window.__native.pending || []; window.__native.pending = []; return { items }; },
    },
    LocalNotifications: {
      requestPermissions: async () => ({ display: 'granted' }),
      addListener: (ev, cb) => { listeners['ln:' + ev] = cb; },
      schedule: async o => { window.__native.notifications.push(...o.notifications); log.push('notify'); },
    },
  } };
}
