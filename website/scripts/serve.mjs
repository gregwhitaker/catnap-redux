import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { watch } from 'node:fs';
import path from 'node:path';
import { buildSite, websiteRoot, repositoryRoot } from './build.mjs';

const development = process.argv.includes('--watch');
const directory = path.join(websiteRoot, 'dist');
if (development || !await stat(path.join(directory, 'index.html')).catch(() => null)) await buildSite();
const info = JSON.parse(await readFile(path.join(directory, 'build-info.json'), 'utf8'));
const port = Number(process.env.PORT || 4173);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('PORT must be between 1 and 65535.');
const clients = new Set();
const mime = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8', '.png': 'image/png', '.svg': 'image/svg+xml', '.xml': 'application/xml', '.txt': 'text/plain; charset=utf-8' };
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, `http://127.0.0.1:${port}`);
    if (development && url.pathname === `${info.base}__events`) {
      response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
      response.write(': connected\n\n');
      clients.add(response);
      request.on('close', () => clients.delete(response));
      return;
    }
    if (url.pathname === '/' && info.base !== '/') {
      response.writeHead(302, { Location: info.base }); response.end(); return;
    }
    const inBase = url.pathname.startsWith(info.base);
    let file = path.resolve(directory, inBase ? decodeURIComponent(url.pathname.slice(info.base.length)) : './404.html');
    if (path.relative(directory, file).startsWith('..')) { response.writeHead(403); response.end('Forbidden'); return; }
    let entry = inBase ? await stat(file).catch(() => null) : null;
    if (entry?.isDirectory()) {
      if (!url.pathname.endsWith('/')) { response.writeHead(301, { Location: `${url.pathname}/${url.search}` }); response.end(); return; }
      file = path.join(file, 'index.html');
      entry = await stat(file).catch(() => null);
    }
    const missing = !entry?.isFile();
    if (missing) file = path.join(directory, '404.html');
    const extension = path.extname(file);
    let body = await readFile(file);
    if (development && extension === '.html') {
      body = Buffer.from(body.toString().replace('</body>', `<script>new EventSource('${info.base}__events').onmessage=()=>location.reload()</script></body>`));
    }
    response.writeHead(missing ? 404 : 200, { 'Content-Type': mime[extension] || 'application/octet-stream', 'Cache-Control': 'no-store', 'Content-Length': body.length });
    response.end(request.method === 'HEAD' ? undefined : body);
  } catch {
    response.writeHead(400); response.end('Bad request');
  }
});
server.listen(port, '127.0.0.1', () => console.log(`Catnap docs: http://127.0.0.1:${port}${info.base}${development ? ' (live reload)' : ''}`));

const watchers = [];
if (development) {
  let timer;
  let building = false;
  let dirty = false;
  async function rebuild() {
    if (building) { dirty = true; return; }
    building = true;
    try {
      await buildSite({ base: info.base });
      console.log('Documentation rebuilt.');
      clients.forEach(client => client.write('data: reload\n\n'));
    } catch (error) { console.error(error.message); }
    finally { building = false; if (dirty) { dirty = false; rebuild(); } }
  }
  for (const watched of [path.join(websiteRoot, 'src'), path.join(repositoryRoot, 'docs'), path.join(repositoryRoot, 'gradle.properties')]) {
    watchers.push(watch(watched, { recursive: true }, (_event, filename) => {
      if (filename && !/\.(md|mjs|js|css|png|properties)$/.test(filename)) return;
      clearTimeout(timer); timer = setTimeout(rebuild, 150);
    }));
  }
}
function shutdown() {
  watchers.forEach(watcher => watcher.close());
  clients.forEach(client => client.end());
  server.close();
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
