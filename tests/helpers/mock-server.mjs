// Nachbau der Gegenseite: Feeds und Webseiten, Nextcloud-WebDAV (/remote.php/dav/files/<user>/…),
// Login Flow v2 und die OCS-Benutzerabfrage. Alle Antworten erlauben CORS.
import { createServer } from 'node:http';

export const USER = 'jörg';
export const PASS = 'app-pass';

/**
 * @param {object} o
 * @param {string} [o.loginName=USER]   Login-Name aus dem Login Flow (z. B. E-Mail statt Benutzer-ID)
 */
export async function startServer(o = {}) {
  const { loginName = USER } = o;
  /** Pfad → { body (String oder Buffer), type, status, headers } */
  const pages = new Map();
  const files = new Map(), dirs = new Set(['']), log = [];
  let approved = false, url = '';
  const basic = name => 'Basic ' + Buffer.from(`${name}:${PASS}`).toString('base64');
  const authOk = h => h === basic(USER) || h === basic(loginName);

  const server = createServer((req, res) => {
    const cors = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,PUT,POST,MKCOL,OPTIONS,PROPFIND',
      'Access-Control-Allow-Headers': 'Authorization, Content-Type, Accept, OCS-APIRequest, If-None-Match, If-Modified-Since',
      'Access-Control-Expose-Headers': 'ETag, Last-Modified',
    };
    if (req.method === 'OPTIONS') { res.writeHead(200, cors); res.end(); return; }
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
      const body = Buffer.concat(chunks).toString('utf8');
      const path = decodeURI(req.url.split('?')[0]);
      const entry = { method: req.method, path, status: 0, headers: req.headers };
      log.push(entry);
      const send = (status, headers = {}, data = '') => { entry.status = status; res.writeHead(status, { ...cors, ...headers }); res.end(data); };
      const json = (status, obj) => send(status, { 'Content-Type': 'application/json' }, JSON.stringify(obj));

      // Login Flow v2
      if (path === '/index.php/login/v2' && req.method === 'POST')
        return json(200, { poll: { token: 'tok123', endpoint: url + '/index.php/login/v2/poll' }, login: url + '/index.php/login/v2/flow/tok123' });
      if (path === '/index.php/login/v2/poll' && req.method === 'POST')
        return body === 'token=tok123' && approved ? json(200, { server: url, loginName, appPassword: PASS }) : send(404);
      if (path.startsWith('/index.php/login')) return send(200, { 'Content-Type': 'text/html' }, '<p>Anmeldung (Nachbau)</p>');
      if (path.startsWith('/ocs/v2.php/cloud/user')) {
        if (!authOk(req.headers.authorization) || req.headers['ocs-apirequest'] !== 'true') return send(401);
        return json(200, { ocs: { meta: { status: 'ok' }, data: { id: USER } } });
      }

      // WebDAV
      const m = path.match(/^\/remote\.php\/dav\/files\/([^/]+)\/?(.*)$/);
      if (m) {
        entry.path = 'dav:' + m[2];
        if (!authOk(req.headers.authorization)) return send(401);
        if (m[1] !== USER) return send(404);
        const p = m[2].replace(/\/$/, ''), parent = p.split('/').slice(0, -1).join('/');
        if (req.method === 'MKCOL') {
          if (dirs.has(p)) return send(405);
          if (!dirs.has(parent)) return send(409);
          dirs.add(p); return send(201);
        }
        if (req.method === 'GET') return files.has(p) ? send(200, { 'Content-Type': 'application/octet-stream' }, files.get(p)) : send(404);
        if (req.method === 'PUT') {
          if (!dirs.has(parent)) return send(409);
          files.set(p, body); return send(201);
        }
        return send(405);
      }

      // Feeds und Seiten
      const page = pages.get(path);
      if (!page) return send(404, {}, 'nicht gefunden');
      if (page.etag && req.headers['if-none-match'] === page.etag) return send(304);
      return send(page.status || 200, { 'Content-Type': page.type || 'application/rss+xml', ...(page.etag ? { ETag: page.etag } : {}), ...(page.headers || {}) }, page.body);
    });
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  url = `http://127.0.0.1:${server.address().port}`;
  return {
    url, log, files, dirs, pages,
    page: (path, body, opts = {}) => pages.set(path, { body, ...opts }),
    approveLogin: () => { approved = true; },
    davUrl: p => `${url}/remote.php/dav/files/${encodeURIComponent(USER)}/${p}`,
    close: () => new Promise(r => server.close(r)),
  };
}

/** Ein RSS-2.0-Feed aus [guid, titel, html, stundenAlt] */
export function rss(title, items, { site = 'https://example.org/' } = {}) {
  const now = Date.now();
  const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  return `<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/"><channel><title>${esc(title)}</title><link>${site}</link>` +
    items.map(([guid, t, html = `<p>Text über ${t}</p>`, hours = 1]) =>
      `<item><guid>${guid}</guid><title>${esc(t)}</title><link>${site}${guid}</link><dc:creator>Redaktion</dc:creator>` +
      `<description>${esc(html)}</description><pubDate>${new Date(now - hours * 3600000).toUTCString()}</pubDate></item>`).join('') +
    '</channel></rss>';
}
