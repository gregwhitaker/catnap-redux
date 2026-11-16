import { readFile, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseHTML } from 'linkedom';
import { websiteRoot } from './build.mjs';

async function htmlFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  return (await Promise.all(entries.map(entry => entry.isDirectory()
    ? htmlFiles(path.join(directory, entry.name))
    : entry.name.endsWith('.html') ? [path.join(directory, entry.name)] : []))).flat();
}

export async function checkSite(directory = path.join(websiteRoot, 'dist')) {
  const info = JSON.parse(await readFile(path.join(directory, 'build-info.json'), 'utf8'));
  const documents = new Map();
  const errors = [];
  const files = await htmlFiles(directory);
  for (const file of files) {
    const { document } = parseHTML(await readFile(file, 'utf8'));
    documents.set(file, document);
    const relative = path.relative(directory, file);
    if (document.querySelectorAll('h1').length !== 1) errors.push(`${relative}: expected exactly one h1`);
    if (!document.querySelector('main') || !document.querySelector('title')?.textContent) errors.push(`${relative}: missing main or title`);
    const ids = [...document.querySelectorAll('[id]')].map(node => node.id);
    if (new Set(ids).size !== ids.length) errors.push(`${relative}: duplicate element IDs`);
    for (const button of document.querySelectorAll('button')) {
      if (!button.getAttribute('aria-label') && !button.textContent.trim()) errors.push(`${relative}: button without an accessible name`);
    }
    for (const image of document.querySelectorAll('img')) {
      if (!image.hasAttribute('alt')) errors.push(`${relative}: image without alt text`);
    }
  }
  let checkedLinks = 0;
  async function checkLink(href, fromFile) {
    if (!href || /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(href)) return;
    const current = `https://docs.test${info.base}${path.relative(directory, fromFile).split(path.sep).join('/')}`;
    const resolved = new URL(href, current);
    if (!resolved.pathname.startsWith(info.base)) { errors.push(`${path.relative(directory, fromFile)}: link escapes base ${href}`); return; }
    let file = path.join(directory, decodeURIComponent(resolved.pathname.slice(info.base.length)));
    if (path.relative(directory, file).startsWith('..')) { errors.push(`Path traversal: ${href}`); return; }
    let entry = await stat(file).catch(() => null);
    if (entry?.isDirectory()) { file = path.join(file, 'index.html'); entry = await stat(file).catch(() => null); }
    if (!entry?.isFile()) { errors.push(`${path.relative(directory, fromFile)}: missing target ${href}`); return; }
    if (resolved.hash && documents.has(file) && !documents.get(file).getElementById(decodeURIComponent(resolved.hash.slice(1)))) {
      errors.push(`${path.relative(directory, fromFile)}: missing anchor ${href}`);
    }
    checkedLinks++;
  }
  for (const [file, document] of documents) {
    for (const element of document.querySelectorAll('[href], [src]')) {
      if (element.matches('link[rel="canonical"]')) continue;
      await checkLink(element.getAttribute('href') || element.getAttribute('src'), file);
    }
  }
  const search = JSON.parse(await readFile(path.join(directory, 'assets/search-index.json'), 'utf8'));
  for (const entry of search) await checkLink(entry.url, path.join(directory, 'index.html'));
  for (const page of info.pages) {
    if (!search.some(entry => entry.url.split('#')[0] === page)) errors.push(`Missing search entry: ${page}`);
  }
  if (errors.length) throw new Error(`Site checks failed:\n${errors.join('\n')}`);
  return { pages: files.length, links: checkedLinks, searchEntries: search.length, base: info.base };
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const result = await checkSite();
  console.log(`Checked ${result.pages} pages, ${result.links} local links/assets, and ${result.searchEntries} search entries (base: ${result.base}).`);
}
