import { test, expect } from '@playwright/test';
import { startServer, rss } from './helpers/mock-server.mjs';
import { openApp, listTitles } from './helpers/app.mjs';

let srv;
test.beforeEach(async () => { srv = await startServer(); });
test.afterEach(async () => { await srv.close(); });

test('Feed hinzufügen, Liste und Artikel (HTML wird bereinigt)', async ({ browser }) => {
  srv.page('/feed', rss('Testfeed', [['a', 'Erster Artikel', '<p>Hallo <b>Welt</b> <script>alert(1)</script><img src="/bild.png" onerror="x()"><img src="/px.gif" width="1" height="1"><a href="javascript:alert(1)">böse</a></p>', 2], ['b', 'Zweiter Beitrag']]));
  const { page, errors } = await openApp(browser);
  await page.click('#menuBtn');
  await page.click('#navFeeds');
  await page.click('#addFeedBtn');
  await page.fill('#fUrl', srv.url + '/feed');
  await page.fill('#fFolder', 'Technik');
  await page.click('[data-ok]');
  await expect(page.locator('#feedList .feed .t')).toHaveText(['Testfeed']);
  await expect(page.locator('#feedList .feed .st')).toContainText('Technik · 2 ungelesen');
  await page.click('#feedsPage [data-back]');
  await expect.poll(() => listTitles(page)).toEqual(['Zweiter Beitrag', 'Erster Artikel']);
  await page.click('#list .row:nth-child(2)');
  await expect(page.locator('#article h1')).toHaveText('Erster Artikel');
  const html = await page.locator('#article').innerHTML();
  expect(html).toContain('<b>Welt</b>');
  expect(html).not.toContain('script');
  expect(html).not.toContain('onerror');
  expect(html).not.toContain('javascript:');
  expect(html).not.toContain('px.gif');
  const stored = await page.evaluate(() => getBody(ui.openId).then(b => b.content));
  expect(stored).toContain('src="https://example.org/bild.png"');    // relativ zum Artikel-Link
  expect(errors).toEqual([]);
});

test('Website statt Feed: der Feed wird gefunden; doppeltes Abo wird abgelehnt', async ({ browser }) => {
  srv.page('/blog', `<html><head><link rel="alternate" type="application/rss+xml" href="/blog/rss"></head><body>Blog</body></html>`, { type: 'text/html' });
  srv.page('/blog/rss', rss('Blogfeed', [['x', 'Eintrag']]));
  const { page } = await openApp(browser);
  const feed = await page.evaluate(u => addFeed(u, null).then(f => f.url), srv.url + '/blog');
  expect(feed).toBe(srv.url + '/blog/rss');
  const err = await page.evaluate(u => addFeed(u, null).then(() => '', e => e.message), srv.url + '/blog/rss');
  expect(err).toBe('„Blogfeed“ ist bereits abonniert');
  const none = await page.evaluate(u => addFeed(u, null).then(() => '', e => e.message), srv.url + '/nichts');
  expect(none).toContain('nicht erreichbar');
});

test('Zeichensätze, schlampiges XML, Atom und Datumsformate', async ({ browser }) => {
  // ISO-8859-1 laut XML-Deklaration, HTML-Entitäten und ein nacktes & im XML
  const latin = Buffer.from('<?xml version="1.0" encoding="ISO-8859-1"?><rss version="2.0"><channel><title>Grüße & Küsse</title>' +
    '<item><guid>1</guid><title>Übergröße&nbsp;mit&hellip;</title><description>&lt;p&gt;Straße&lt;/p&gt;</description>' +
    '<pubDate>Tue, 03 Nov 2026 10:00:00 CEST</pubDate></item></channel></rss>', 'latin1');
  srv.page('/latin', latin, { type: 'application/xml' });
  srv.page('/atom', `<?xml version="1.0" encoding="utf-8"?><feed xmlns="http://www.w3.org/2005/Atom"><title>Atom-Feed</title>
    <link rel="alternate" href="https://atom.example/"/><entry><id>tag:1</id><title type="html">Atom &amp;lt;Eintrag&amp;gt;</title>
    <link rel="alternate" href="/eintrag"/><updated>2026-11-02T08:30:00+01:00</updated><author><name>Anna</name></author>
    <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Inline <em>XHTML</em></p></div></content></entry></feed>`, { type: 'application/atom+xml' });
  const { page } = await openApp(browser);
  const r = await page.evaluate(async ([a, b]) => {
    const f1 = await addFeed(a, null), f2 = await addFeed(b, null);
    const list = [...arts.values()];
    const one = list.find(x => x.feedId === f1.id), two = list.find(x => x.feedId === f2.id);
    return { t1: f1.title, a1: one.title, s1: one.summary, d1: new Date(one.pubDate).toISOString(),
      t2: f2.title, a2: two.title, link: two.link, author: two.author, d2: new Date(two.pubDate).toISOString(), body: (await getBody(two.id)).content };
  }, [srv.url + '/latin', srv.url + '/atom']);
  expect(r.t1).toBe('Grüße & Küsse');
  expect(r.a1).toBe('Übergröße mit…');
  expect(r.s1).toBe('Straße');
  expect(r.d1).toBe('2026-11-03T08:00:00.000Z');
  expect(r.t2).toBe('Atom-Feed');
  expect(r.a2).toBe('Atom <Eintrag>');
  expect(r.link).toBe(srv.url + '/eintrag');
  expect(r.author).toBe('Anna');
  expect(r.d2).toBe('2026-11-02T07:30:00.000Z');
  expect(r.body).toContain('<em>XHTML</em>');
});

test('Aktualisieren: neue Artikel, ETag, Fehler am Feed', async ({ browser }) => {
  srv.page('/feed', rss('Feed', [['a', 'A']]), { etag: '"v1"' });
  const { page } = await openApp(browser);
  await page.evaluate(u => addFeed(u, null), srv.url + '/feed');
  let r = await page.evaluate(() => refresh());
  expect(r.newArticles).toBe(0);
  expect(srv.log.filter(e => e.path === '/feed').at(-1).status).toBe(304);
  srv.page('/feed', rss('Feed', [['a', 'A'], ['b', 'B']]), { etag: '"v2"' });
  r = await page.evaluate(() => refresh());
  expect(r).toMatchObject({ newArticles: 1, failedFeeds: 0 });
  srv.pages.delete('/feed');
  r = await page.evaluate(() => refresh());
  expect(r.failedFeeds).toBe(1);
  expect(await page.evaluate(() => [...feeds.values()][0].lastError)).toBe('HTTP 404');
});

test('Volltext laden und zwischen Feed-Text und Volltext wechseln', async ({ browser }) => {
  const long = 'Dies ist ein ausführlicher Absatz mit viel Inhalt. '.repeat(20);
  srv.page('/feed', rss('Feed', [['art', 'Langer Artikel', '<p>Nur ein Anriss</p>']], { site: srv.url + '/' }));
  srv.page('/art', `<html><head><title>Langer Artikel</title></head><body><nav>Menü</nav><article><h1>Langer Artikel</h1>
    <p>${long}</p><p>${long}</p><img data-src="/gross.jpg" src="data:image/gif;base64,R0lGOD"></article><footer>Impressum</footer></body></html>`, { type: 'text/html; charset=utf-8' });
  const { page } = await openApp(browser);
  await page.evaluate(u => addFeed(u, null), srv.url + '/feed');
  await page.evaluate(() => render());
  await page.click('#list .row');
  await expect(page.locator('#article')).toContainText('Nur ein Anriss');
  await page.click('#rdFull');
  await expect(page.locator('#article')).toContainText('ausführlicher Absatz');
  await expect(page.locator('#article')).not.toContainText('Impressum');
  const full = await page.evaluate(() => getBody(ui.openId).then(b => b.fullText));
  expect(full).toContain(`src="${srv.url}/gross.jpg"`);      // nachgeladenes Bild statt Platzhalter
  await page.click('#rdFull');
  await expect(page.locator('#article')).toContainText('Nur ein Anriss');
});

test('Feed bearbeiten und löschen, OPML-Import und -Export', async ({ browser }) => {
  srv.page('/a', rss('Feed A', [['1', 'Eins']]));
  srv.page('/b', rss('Feed B', [['2', 'Zwei']]));
  const { page } = await openApp(browser);
  const opml = `<?xml version="1.0"?><opml version="2.0"><body><outline text="Technik"><outline text="Innen" title="Innen">
    <outline type="rss" text="Feed A" xmlUrl="${srv.url}/a"/></outline></outline><outline type="rss" title="Feed B" xmlUrl="${srv.url}/b" htmlUrl="https://b.example/"/></body></opml>`;
  const res = await page.evaluate(t => importOpml(t), opml);
  expect(res).toEqual({ added: 2, total: 2 });
  expect(await page.evaluate(t => importOpml(t), opml)).toEqual({ added: 0, total: 2 });
  await page.evaluate(() => refresh());
  const cats = await page.evaluate(() => sortedFeeds().map(f => [f.title, f.category]));
  expect(cats).toEqual([['Feed A', 'Technik / Innen'], ['Feed B', null]]);

  await page.click('#menuBtn'); await page.click('#navFeeds');
  await page.click('#feedList .feed:nth-child(1) .body');
  await page.fill('#fTitle', 'Umbenannt');
  await page.click('label:has(#fFull) .sw');
  await page.click('[data-ok]');
  await expect(page.locator('#feedList .feed .t').first()).toHaveText('Feed B');
  await expect(page.locator('#feedList')).toContainText('Umbenannt');
  await page.click('#feedList .feed:has-text("Feed B") [data-del]');
  await page.click('[data-ok]');
  await expect(page.locator('#feedList .feed')).toHaveCount(1);
  expect(await page.evaluate(() => [...tombstones.keys()])).toEqual([srv.url + '/b']);

  await page.click('#opmlBtn');
  await page.click('#menu button:has-text("OPML exportieren")');
  await expect.poll(() => page.evaluate(() => window.__native.log.filter(l => l.startsWith('share')))).toEqual(['share file:///cache/feeds.opml']);
  const out = await page.evaluate(() => window.__native.file);
  expect(out).toContain('<outline text="Technik / Innen" title="Technik / Innen">');
  expect(out).toContain(`title="Umbenannt" xmlUrl="${srv.url}/a"`);
});

test('Gleichzeitiges Speichern derselben Artikel bricht nichts ab', async ({ browser }) => {
  srv.page('/feed', rss('Feed', [['a', 'A']]));
  const { page } = await openApp(browser);
  await page.evaluate(u => addFeed(u, null), srv.url + '/feed');
  const r = await page.evaluate(async () => {
    const feed = [...feeds.values()][0];
    const parsed = { title: 'Feed', siteUrl: null, items: [{ guid: 'b', title: 'B', link: null, author: null, contentHtml: '<p>x</p>', pubDate: Date.now() }] };
    const [x, y] = await Promise.all([storeItems(feed, parsed, Date.now()), storeItems(feed, parsed, Date.now())]);
    const loaded = await getAll('articles');
    return { ids: x.length + y.length, mem: [...arts.values()].filter(a => a.guid === 'b').length, db: loaded.filter(a => a.guid === 'b').length };
  });
  expect(r).toEqual({ ids: 1, mem: 1, db: 1 });
});
