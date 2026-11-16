import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { buildSite, normalizeBasePath } from '../scripts/build.mjs';
import { checkSite } from '../scripts/check.mjs';
import { searchDocs } from '../src/search.mjs';
import { pages } from '../src/config.mjs';

test('documentation, assets, anchors, and search work at root and project Pages paths', async () => {
  for (const base of ['/', '/catnap/', '/a/nested-site/']) {
    const output = await mkdtemp(path.join(tmpdir(), 'catnap-docs-'));
    try {
      await buildSite({ output, base, origin: 'https://example.github.io' });
      const result = await checkSite(output);
      assert.equal(result.base, base);
      assert.equal(result.pages, pages.length + 1);
      assert.ok(result.links > 700);
      const quickstart = await readFile(path.join(output, 'quickstart/index.html'), 'utf8');
      assert.ok(quickstart.includes(`href="${base}spring/"`));
      assert.ok(quickstart.includes(`href="${base}spring/#webflux-ndjson-and-sse"`));
      assert.ok(quickstart.includes(`href="https://example.github.io${base}quickstart/"`));
      const index = JSON.parse(await readFile(path.join(output, 'assets/search-index.json'), 'utf8'));
      assert.ok(searchDocs(index, 'cache').some(entry => entry.url === `${base}core-api/#optional-bounded-compilation-cache`));
      assert.equal(searchDocs(index, '<script>unmatched</script>').length, 0);
      const sitemap = await readFile(path.join(output, 'sitemap.xml'), 'utf8');
      assert.ok(sitemap.includes(`https://example.github.io${base}quickstart/`));
      // Prove the checker catches broken anchors, including nested page links.
      await writeFile(path.join(output, 'quickstart/index.html'), quickstart.replace(`href="${base}spring/"`, `href="${base}spring/#does-not-exist"`));
      await assert.rejects(checkSite(output), /missing anchor/);
    } finally { await rm(output, { recursive: true, force: true }); }
  }
});

test('base paths normalize and reject URLs and traversal', () => {
  assert.equal(normalizeBasePath('catnap'), '/catnap/');
  assert.equal(normalizeBasePath(''), '/');
  for (const value of ['https://example.com', '/../outside/', '/catnap/./', '/catnap?query']) assert.throws(() => normalizeBasePath(value));
});

test('search ranks titles and sections above body mentions and requires every term', () => {
  const index = [
    { title: 'Core API', section: '', url: '/core-api/', text: 'A streaming query can be reused.' },
    { title: 'Streaming', section: '', url: '/streaming/', text: 'NDJSON records and SSE events.' },
    { title: 'Spring', section: 'WebFlux streaming', url: '/spring/#webflux', text: 'NDJSON demand.' },
  ];
  assert.equal(searchDocs(index, 'streaming')[0].title, 'Streaming');
  assert.equal(searchDocs(index, 'webflux ndjson')[0].url, '/spring/#webflux');
  assert.equal(searchDocs(index, 'webflux cancellation').length, 0);
  assert.deepEqual(searchDocs(index, '  '), index.filter(entry => !entry.section));
});
