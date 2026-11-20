import { test, expect } from '@playwright/test';
import { startServer, rss, USER, PASS } from './helpers/mock-server.mjs';
import { openApp, configureSync, listTitles } from './helpers/app.mjs';

let srv;
test.beforeEach(async () => { srv = await startServer(); });
test.afterEach(async () => { await srv.close(); });
const stateFile = () => JSON.parse(srv.files.get('News/news-sync.json'));
const byTitle = (page, t) => page.evaluate(t => { const a = [...arts.values()].find(x => x.title === t); return a && { read: a.read, starred: a.starred, note: a.note, readAt: a.readAt }; }, t);

test('Feeds, Sterne, Notizen und Gelesen-Status zwischen zwei Geräten', async ({ browser }) => {
  srv.page('/feed', rss('Testfeed', [['a', 'A'], ['b', 'B']]));
  const one = (await openApp(browser)).page, two = (await openApp(browser)).page;
  await configureSync(one, srv); await configureSync(two, srv);

  await one.evaluate(u => addFeed(u, 'Technik'), srv.url + '/feed');
  await one.evaluate(async () => {
    const a = [...arts.values()].find(x => x.title === 'A');
    await setRead([a.id], true); await setStarred(a.id, true); await setNote(a.id, 'Später lesen');
  });
  expect(await one.evaluate(() => sync())).toBe(0);
  const file = stateFile();
  expect(file.feeds).toEqual([expect.objectContaining({ url: srv.url + '/feed', title: 'Testfeed', category: 'Technik', deleted: false })]);
  expect(file.articles).toEqual([expect.objectContaining({ guid: 'a', starred: true, note: 'Später lesen' })]);
  expect(Object.values(file.reads[srv.url + '/feed'])[0]).toBeGreaterThan(0);
  expect(srv.files.get('News/feeds.opml')).toContain('xmlUrl="' + srv.url + '/feed"');

  // Das zweite Gerät bekommt den Feed; der gelesene Artikel kommt beim Laden gelesen an und gilt nicht als neu
  expect(await two.evaluate(() => sync())).toBe(1);
  const r = await two.evaluate(() => refresh());
  expect(r.newArticles).toBe(1);
  expect(await byTitle(two, 'A')).toMatchObject({ read: true, starred: true, note: 'Später lesen' });
  expect(await byTitle(two, 'B')).toMatchObject({ read: false });

  // Auf dem zweiten Gerät wieder ungelesen, B gelesen: die jüngere Markierung gewinnt
  await two.waitForTimeout(5);
  await two.evaluate(async () => { const v = [...arts.values()]; await setRead([v.find(x => x.title === 'A').id], false); await setRead([v.find(x => x.title === 'B').id], true); });
  await two.evaluate(() => sync());
  await one.evaluate(() => sync());
  expect(await byTitle(one, 'A')).toMatchObject({ read: false });
  expect((await byTitle(one, 'B')).readAt).toBeGreaterThan(0);

  // Kündigung erreicht das andere Gerät
  await one.evaluate(() => deleteFeed([...feeds.values()][0]));
  await one.evaluate(() => sync());
  await two.evaluate(() => sync());
  expect(await two.evaluate(() => feeds.size)).toBe(0);
  expect(stateFile().feeds).toEqual([expect.objectContaining({ url: srv.url + '/feed', deleted: true })]);
});

test('Liest die Sync-Datei der bisherigen Kotlin-App', async ({ browser }) => {
  srv.dirs.add('News');
  // so schreibt die Android-App 1.x die Datei (ohne „reads“ in älteren Versionen)
  srv.files.set('News/news-sync.json', JSON.stringify({
    version: 1,
    feeds: [
      { url: srv.url + '/feed', title: 'Alter Feed', siteUrl: null, category: 'Alt', fetchFullText: true, updatedAt: 1700000000000, deleted: false },
      { url: 'https://weg.example/rss', title: '', siteUrl: null, category: null, fetchFullText: false, updatedAt: 1700000000001, deleted: true },
    ],
    articles: [{ feedUrl: srv.url + '/feed', guid: 'x', title: 'Gemerkt', link: 'https://example.org/x', pubDate: 1600000000000, starred: true, note: null, updatedAt: 1700000000002 }],
  }, null, 2));
  srv.page('/feed', rss('Alter Feed', [['y', 'Neu']]));
  const { page } = await openApp(browser);
  await configureSync(page, srv);
  expect(await page.evaluate(() => sync())).toBe(1);
  const f = await page.evaluate(() => [...feeds.values()].map(f => [f.title, f.category, f.fetchFullText]));
  expect(f).toEqual([['Alter Feed', 'Alt', true]]);
  expect(await page.evaluate(() => [...tombstones.keys()])).toEqual(['https://weg.example/rss']);
  expect(await byTitle(page, 'Gemerkt')).toMatchObject({ starred: true, read: true });
  const file = stateFile();
  expect(file.feeds).toHaveLength(2);
  expect(file.articles).toHaveLength(1);
});

test('Gelesen-Status abschaltbar; beschädigte Datei; falsches Passwort', async ({ browser }) => {
  srv.page('/feed', rss('Feed', [['a', 'A']]));
  const { page } = await openApp(browser);
  await configureSync(page, srv, { ncSyncRead: false });
  await page.evaluate(u => addFeed(u, null), srv.url + '/feed');
  await page.evaluate(() => setRead([...arts.keys()], true));
  await page.evaluate(() => sync());
  expect(stateFile().reads).toEqual({});

  srv.files.set('News/news-sync.json', '{kaputt');
  expect(await page.evaluate(() => sync().then(() => '', e => e.message))).toBe('Die Datei news-sync.json auf dem Server ist beschädigt');
  await page.evaluate(() => savePrefs({ ncPassword: 'falsch' }));
  expect(await page.evaluate(() => sync().then(() => '', e => e.message))).toBe('Anmeldung abgelehnt. Bitte Benutzername und App-Passwort prüfen.');
  expect(await page.evaluate(() => prefs.lastSyncError)).toContain('Anmeldung abgelehnt');
});

test('Anmeldung über den Browser (Login Flow v2) mit abweichendem Login-Namen', async ({ browser }) => {
  await srv.close();
  srv = await startServer({ loginName: 'joerg@example.org' });
  const { page } = await openApp(browser);
  await page.click('#menuBtn'); await page.click('#navSettings');
  await page.fill('#ncServer', srv.url);
  await page.click('#ncLogin');
  await expect(page.locator('#ncStatus')).toContainText('Zugriff gewähren');
  expect(await page.evaluate(() => window.__native.log.filter(l => l.startsWith('browser')))).toEqual([`browser ${srv.url}/index.php/login/v2/flow/tok123`]);
  srv.approveLogin();
  await expect(page.locator('#ncStatus')).toHaveText('Synchronisiert.', { timeout: 10000 });
  const p = await page.evaluate(() => ({ s: prefs.ncServer, u: prefs.ncUser, d: prefs.ncDavUser, pw: prefs.ncPassword }));
  expect(p).toEqual({ s: srv.url, u: 'joerg@example.org', d: USER, pw: PASS });
  expect(srv.files.has('News/news-sync.json')).toBe(true);
});

test('QR-Code-Text eines App-Passworts', async ({ browser }) => {
  const { page } = await openApp(browser);
  expect(await page.evaluate(t => parseNcLogin(t), `nc://login/user:${encodeURIComponent(USER)}&password:${PASS}&server:${encodeURIComponent(srv.url)}`))
    .toEqual({ server: srv.url, user: USER, pass: PASS });
  expect(await page.evaluate(() => parseNcLogin('https://example.org'))).toBeNull();
  await page.evaluate(t => applyNcCredentials(parseNcLogin(t)), `nc://login/server:${srv.url}&user:${USER}&password:${PASS}`);
  expect(await page.evaluate(() => sync())).toBe(0);
});

test('Automatisches Update mit Sync und Benachrichtigung', async ({ browser }) => {
  srv.page('/feed', rss('Feed', [['a', 'A']]));
  const { page } = await openApp(browser, { prefs: { notifyNewArticles: true } });
  await configureSync(page, srv);
  await page.evaluate(u => addFeed(u, null), srv.url + '/feed');
  srv.page('/feed', rss('Feed', [['a', 'A'], ['b', 'Ganz neu']]));
  await page.evaluate(() => { document.hasFocus = () => false; return backgroundUpdate(); });
  const n = await page.evaluate(() => window.__native.notifications);
  expect(n).toEqual([expect.objectContaining({ title: 'Ganz neu', body: 'Feed' })]);
  expect(srv.files.has('News/news-sync.json')).toBe(true);
  await expect.poll(() => listTitles(page)).toContain('Ganz neu');
});
