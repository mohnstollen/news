import { test, expect } from '@playwright/test';
import { startServer, rss } from './helpers/mock-server.mjs';
import { openApp, listTitles } from './helpers/app.mjs';

let srv;
test.beforeEach(async () => { srv = await startServer(); });
test.afterEach(async () => { await srv.close(); });

test('Hintergrund-Update: Feeds und bekannte Artikel gehen an den Worker, Abgeholtes wird gespeichert', async ({ browser }) => {
  srv.page('/feed', rss('Feed', [['a', 'A']]));
  const { page } = await openApp(browser, { prefs: { autoUpdate: true, intervalMinutes: 30, notifyNewArticles: true } });
  await page.evaluate(u => addFeed(u, null).then(() => render()), srv.url + '/feed');
  await expect.poll(() => page.evaluate(() => window.__native.background), { timeout: 5000 }).toMatchObject({
    enabled: true, intervalMinutes: 30, notify: true,
    feeds: [{ url: srv.url + '/feed', title: 'Feed', notify: true, known: ['a', 'A'] }],
  });
  // Der Worker hat den Feed in der Zwischenzeit geladen (Latin-1, Base64)
  const xml = Buffer.from(rss('Feed', [['a', 'A'], ['b', 'Größer']]).replace('UTF-8', 'ISO-8859-1'), 'latin1').toString('base64');
  await page.evaluate(([url, data]) => { window.__native.pending = [{ url, data, etag: '"x"', lastModified: '', contentType: 'application/rss+xml', fetchedAt: Date.now() }]; }, [srv.url + '/feed', xml]);
  await page.evaluate(() => window.__native.fire('appStateChange', { isActive: true }));
  await expect.poll(() => listTitles(page)).toEqual(['Größer', 'A']);
  expect(await page.evaluate(() => [...feeds.values()][0].etag)).toBe('"x"');
});
