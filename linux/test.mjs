// Prüft die Linux-Anwendung Ende-zu-Ende gegen den Server-Nachbau der Playwright-Tests:
// Feeds über die native Brücke (ohne CORS), Speichern über Neustarts, Nextcloud-Sync, Design.
// Aufruf:  node linux/test.mjs     (braucht xvfb-run; NEWSREADER_CMD="newsreader" testet das installierte Paket)
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';
import { startServer, rss, USER, PASS } from '../tests/helpers/mock-server.mjs';

const CMD = (process.env.NEWSREADER_CMD || `python3 ${new URL('newsreader.py', import.meta.url).pathname}`).split(' ');
const tmp = mkdtempSync(join(tmpdir(), 'newsreader-test-'));
const srv = await startServer();

/** Startet die App mit eigenem Benutzerordner, führt js in der Seite aus und liefert dessen Rückgabewert */
async function run(home, js, env = {}) {
  const script = join(tmp, 'test.js');
  // erst warten, bis die App ihre Daten geladen hat
  writeFileSync(script, `for (let i = 0; i < 100 && !window.__newsReady; i++) await new Promise(r => setTimeout(r, 50));\n${js}`);
  const child = spawn('xvfb-run', ['-a', ...CMD], { stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, HOME: join(tmp, home), XDG_DATA_HOME: '', XDG_CACHE_HOME: '', XDG_CONFIG_HOME: '', NEWSREADER_TEST_JS: script, ...env } });
  let out = '', err = '';
  child.stdout.on('data', d => out += d); child.stderr.on('data', d => err += d);
  const timer = setTimeout(() => child.kill('SIGKILL'), 60000);
  await new Promise(r => child.on('close', r));
  clearTimeout(timer);
  const line = out.split('\n').find(l => l.startsWith('TEST '));
  assert.ok(line, `keine Antwort der App:\n${out}\n${err}`);
  const res = JSON.parse(line.slice(5));
  assert.ok(!res.error, res.error);
  return res.ok;
}
const step = async (name, fn) => { await fn(); console.log('✓', name); };

try {
  srv.page('/feed', rss('Linux-Feed', [['a', 'Erster'], ['b', 'Zweiter']]));
  srv.page('/latin', Buffer.from('<?xml version="1.0" encoding="ISO-8859-1"?><rss version="2.0"><channel><title>Größe</title>' +
    '<item><guid>1</guid><title>Übermäßig</title></item></channel></rss>', 'latin1'), { type: 'text/xml' });

  await step('Start: Linux-Brücke, sicherer Kontext, Version', async () => {
    const r = await run('a', `const info = await Native.App.getInfo(); return { linux: IS_LINUX, platform: PLATFORM, secure: isSecureContext, info };`);
    assert.equal(r.linux, true); assert.equal(r.platform, 'linux'); assert.equal(r.secure, true);
    assert.match(r.info.version, /^\d+\.\d+/);
  });

  await step('Feeds über die native Brücke laden (auch Latin-1), ohne CORS-Freigabe', async () => {
    const r = await run('a', `await addFeed(${JSON.stringify(srv.url + '/feed')}, 'Linux'); await addFeed(${JSON.stringify(srv.url + '/latin')}, null);
      return { feeds: sortedFeeds().map(f => f.title), titles: [...arts.values()].map(a => a.title).sort() };`);
    assert.deepEqual(r.feeds, ['Größe', 'Linux-Feed']);
    assert.deepEqual(r.titles, ['Erster', 'Zweiter', 'Übermäßig']);
    const ua = srv.log.find(e => e.path === '/feed').headers['user-agent'];
    assert.match(ua, /NewsReader/);
  });

  await step('Daten bleiben über einen Neustart erhalten; Sync mit Nextcloud', async () => {
    const r = await run('a', `savePrefs({ ncServer: ${JSON.stringify(srv.url)}, ncUser: ${JSON.stringify(USER)}, ncPassword: ${JSON.stringify(PASS)} });
      const a = [...arts.values()].find(x => x.title === 'Erster'); await setStarred(a.id, true);
      const added = await sync(); return { feeds: feeds.size, added, err: prefs.lastSyncError };`);
    assert.deepEqual(r, { feeds: 2, added: 0, err: null });
    const state = JSON.parse(srv.files.get('News/news-sync.json'));
    assert.deepEqual(state.articles.map(a => a.guid), ['a']);
  });

  await step('Zweiter Rechner bekommt Feeds und Sterne', async () => {
    const r = await run('b', `savePrefs({ ncServer: ${JSON.stringify(srv.url)}, ncUser: ${JSON.stringify(USER)}, ncPassword: ${JSON.stringify(PASS)} });
      const added = await sync(); await refresh();
      return { added, starred: [...arts.values()].filter(a => a.starred).map(a => a.title) };`);
    assert.deepEqual(r, { added: 2, starred: ['Erster'] });
  });

  await step('Dunkles Design des Desktops wird übernommen', async () => {
    const dark = await run('c', `return document.documentElement.dataset.theme;`, { GTK_THEME: 'Adwaita:dark' });
    assert.equal(dark, 'dark');
    const light = await run('c', `return document.documentElement.dataset.theme;`, { GTK_THEME: 'Adwaita' });
    assert.equal(light, 'light');
  });
  console.log('Alle Linux-Tests bestanden');
} finally {
  await srv.close();
  rmSync(tmp, { recursive: true, force: true });
}
