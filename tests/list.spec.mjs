import { test, expect } from '@playwright/test';
import { startServer, rss } from './helpers/mock-server.mjs';
import { openApp, listTitles } from './helpers/app.mjs';

let srv;
test.beforeEach(async () => { srv = await startServer(); });
test.afterEach(async () => { await srv.close(); });

async function twoFeeds(page) {
  srv.page('/tech', rss('Technik', [['t1', 'Neuer Kernel', '<p>Linux bekommt Treiber für Grafikkarten</p>', 1], ['t2', 'Alter Test', '<p>Ein Vergleich</p>', 30]]));
  srv.page('/welt', rss('Welt', [['w1', 'Wetter in München', '<p>Über den Alpen schneit es</p>', 2]]));
  await page.evaluate(async ([a, b]) => { await addFeed(a, 'IT'); await addFeed(b, null); render(); }, [srv.url + '/tech', srv.url + '/welt']);
}

test('Schnellfilter: ungelesen, neu, Ordner; Feed und Gemerkt', async ({ browser }) => {
  const { page } = await openApp(browser, { prefs: { hideReadAfterMinutes: -1 } });
  await twoFeeds(page);
  await expect.poll(() => listTitles(page)).toEqual(['Neuer Kernel', 'Wetter in München', 'Alter Test']);
  await page.click('[data-chip="recent"]');
  await expect.poll(() => listTitles(page)).toEqual(['Neuer Kernel', 'Wetter in München']);
  await page.click('[data-chip="folder"]');
  await page.click('#menu button:has-text("IT")');
  await expect.poll(() => listTitles(page)).toEqual(['Neuer Kernel']);
  await page.click('[data-chip="folder"]');
  await page.click('#menu button:has-text("Alle Ordner")');
  await page.click('[data-chip="recent"]');
  // Gelesen per Kontextmenü; mit „Ungelesen“ bleibt er sichtbar, bis die Liste neu gewählt wird
  await page.click('#list .row:has-text("Alter Test")', { button: 'right' });
  await page.click('#menu button:has-text("Als gelesen markieren")');
  await page.click('[data-chip="unread"]');
  await expect.poll(() => listTitles(page)).toEqual(['Neuer Kernel', 'Wetter in München']);
  await page.click('[data-chip="unread"]');
  // Stern und Gemerkt
  await page.click('#list .row:has-text("Wetter") [data-star]');
  await page.click('#menuBtn');
  await page.click('#sideList [data-scope="starred"]');
  await expect(page.locator('#listTitle')).toHaveText('Gemerkt');
  await expect.poll(() => listTitles(page)).toEqual(['Wetter in München']);
  await page.click('#menuBtn');
  await page.click('#sideList .nav:has-text("Technik")');
  await expect.poll(() => listTitles(page)).toEqual(['Neuer Kernel', 'Alter Test']);
  await expect(page.locator('#sideList .nav:has-text("Alle Artikel") .cnt')).toHaveText('2');
});

test('Gelesene verschwinden nach der eingestellten Zeit, Alle-gelesen mit Rückfrage', async ({ browser }) => {
  const { page } = await openApp(browser, { prefs: { hideReadAfterMinutes: 0 } });
  await twoFeeds(page);
  await page.click('#list .row:has-text("Neuer Kernel")');
  await page.click('#rdBack');
  // bleibt während der Sitzung sichtbar, verschwindet nach Wechsel der Ansicht
  await expect.poll(() => listTitles(page)).toContain('Neuer Kernel');
  await page.evaluate(() => selectScope({ type: 'all' }));
  await expect.poll(() => listTitles(page)).toEqual(['Wetter in München', 'Alter Test']);
  await page.click('#markAllBtn');
  await page.click('[data-ok]');
  await expect(page.locator('#list .empty')).toHaveText('Keine ungelesenen Artikel.');
  // Die Suche findet auch Gelesene
  await page.click('#searchBtn');
  await page.fill('#search', 'kern');
  await expect.poll(() => listTitles(page)).toEqual(['Neuer Kernel']);
});

test('Suche: Wortanfänge, Umlaute, Text und Notizen', async ({ browser }) => {
  const { page } = await openApp(browser);
  await twoFeeds(page);
  await page.evaluate(async () => { const a = [...arts.values()].find(x => x.title === 'Alter Test'); await setNote(a.id, 'unbedingt nachlesen'); });
  await page.click('#searchBtn');
  const search = async q => { await page.fill('#search', q); await page.waitForTimeout(400); return listTitles(page); };
  expect(await search('muench')).toEqual([]);
  expect(await search('munch')).toEqual(['Wetter in München']);        // Umlaute ohne Akzent
  expect(await search('alpen')).toEqual(['Wetter in München']);        // im Text
  expect(await search('grafik treib')).toEqual(['Neuer Kernel']);      // alle Wörter, als Anfang
  expect(await search('rafik')).toEqual([]);                           // nicht mitten im Wort
  expect(await search('nachlesen')).toEqual(['Alter Test']);           // Notiz
  await page.keyboard.press('Escape');
  await expect(page.locator('#searchBar')).toBeHidden();
});

test('Beim Scrollen als gelesen markieren', async ({ browser }) => {
  const items = Array.from({ length: 40 }, (_, i) => [`g${i}`, `Artikel ${i}`, '<p>Text</p>', i * 0.5]);
  srv.page('/viel', rss('Viel', items));
  const { page } = await openApp(browser, { prefs: { hideReadAfterMinutes: -1 } });
  await page.evaluate(u => addFeed(u, null).then(() => render()), srv.url + '/viel');
  await expect(page.locator('#list .row')).toHaveCount(40);
  await page.evaluate(() => { $('list').scrollTop = 1000; });
  await expect.poll(() => page.evaluate(() => [...arts.values()].filter(a => a.read).length)).toBeGreaterThan(5);
  const read = await page.evaluate(() => [...arts.values()].filter(a => a.read).map(a => a.title));
  expect(read).toContain('Artikel 0');
  expect(read).not.toContain('Artikel 39');
});

test('Breites Fenster: drei Spalten und Tastatur', async ({ browser }) => {
  const { page } = await openApp(browser, { viewport: { width: 1360, height: 860 } });
  await twoFeeds(page);
  await expect(page.locator('#side')).toBeVisible();
  await expect(page.locator('#readerEmpty')).toBeVisible();
  await page.keyboard.press('j');
  await expect(page.locator('#article h1')).toHaveText('Neuer Kernel');
  await page.keyboard.press('j');
  await expect(page.locator('#article h1')).toHaveText('Wetter in München');
  await page.keyboard.press('s');
  expect(await page.evaluate(() => arts.get(ui.openId).starred)).toBe(true);
  await page.keyboard.press('k');
  await expect(page.locator('#article h1')).toHaveText('Neuer Kernel');
  await page.keyboard.press('m');
  await expect(page.locator('#readerEmpty')).toBeVisible();
  expect(await page.evaluate(() => [...arts.values()].find(a => a.title === 'Neuer Kernel').read)).toBe(false);
  await page.screenshot({ path: 'test-results/wide.png' });
});

test('Zurück-Taste schließt Ebenen der Reihe nach', async ({ browser }) => {
  const { page } = await openApp(browser);
  await twoFeeds(page);
  await page.click('#list .row');
  await expect(page.locator('#readerPane')).toBeVisible();
  await page.click('#rdNote');
  await expect(page.locator('#dlgOverlay')).toBeVisible();
  await page.goBack();
  await expect(page.locator('#dlgOverlay')).toBeHidden();
  await expect(page.locator('#readerPane')).toBeVisible();
  await page.evaluate(() => window.__native.fire('backButton'));
  await expect(page.locator('#readerPane')).toBeHidden();
  await page.evaluate(() => window.__native.fire('backButton'));
  expect(await page.evaluate(() => window.__native.log.includes('minimize'))).toBe(true);
});
