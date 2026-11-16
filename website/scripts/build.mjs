import { readFile, writeFile, mkdir, rm, copyFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import MarkdownIt from 'markdown-it';
import hljs from 'highlight.js';
import { groups, pages, examples } from '../src/config.mjs';
import { icon } from '../src/icons.mjs';

export const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const repositoryRoot = path.resolve(websiteRoot, '..');
const escape = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);

export function normalizeBasePath(value = '/') {
  if (!value || value === '/') return '/';
  if (!/^\/?[a-zA-Z0-9_./-]+\/?$/.test(value) || value.split('/').some(segment => segment === '.' || segment === '..')) {
    throw new Error('BASE_PATH must be a URL path, for example /catnap/.');
  }
  const segments = value.split('/').filter(Boolean);
  return segments.length ? `/${segments.join('/')}/` : '/';
}

export function headingSlug(text) {
  return text.toLowerCase().replace(/[^\p{L}\p{N}\s_-]/gu, '').trim().replace(/\s/g, '-');
}

export async function buildSite(options = {}) {
  const output = options.output || path.join(websiteRoot, 'dist');
  const base = normalizeBasePath(options.base ?? process.env.BASE_PATH);
  const origin = options.origin ?? process.env.SITE_URL ?? '';
  if (origin && (!/^https?:\/\/[^/]+\/?$/.test(origin) || new URL(origin).username || new URL(origin).password)) {
    throw new Error('SITE_URL must be an http(s) origin without a path or credentials; use BASE_PATH for the path.');
  }
  const repository = process.env.GITHUB_REPOSITORY || 'gregwhitaker/catnap';
  if (!/^[\w.-]+\/[\w.-]+$/.test(repository)) throw new Error('Invalid GITHUB_REPOSITORY.');
  const ref = process.env.DOCS_REF || 'master';
  const github = `https://github.com/${repository}`;
  const sourceUrl = (source, mode = 'blob') => `${github}/${mode}/${encodeURIComponent(ref)}/${source.split('/').map(encodeURIComponent).join('/')}`;
  const url = slug => `${base}${slug ? `${slug}/` : ''}`;
  const asset = name => `${base}assets/${name}`;
  const properties = await readFile(path.join(repositoryRoot, 'gradle.properties'), 'utf8');
  const version = properties.match(/^version=(.+)$/m)?.[1]?.trim();
  if (!version) throw new Error('Missing Catnap version in gradle.properties.');
  const sourcePages = new Map(pages.filter(page => page.source).map(page => [page.source, page]));
  const search = [];
  const markdown = new MarkdownIt({ html: false, linkify: true, typographer: true });

  async function resolveSourceLink(href, source) {
    if (/^(?:[a-z][a-z0-9+.-]*:|\/\/|#)/i.test(href)) return href;
    const match = href.match(/^([^?#]*)(\?[^#]*)?(#.*)?$/);
    const target = path.posix.normalize(path.posix.join(path.posix.dirname(source), decodeURIComponent(match[1])));
    if (target.startsWith('../') || path.isAbsolute(target)) throw new Error(`Link outside repository in ${source}: ${href}`);
    const page = sourcePages.get(target);
    if (page) return `${url(page.slug)}${match[2] || ''}${match[3] || ''}`;
    // Keep the repository's reports, source files, and archives at their canonical source.
    const { stat } = await import('node:fs/promises');
    const entry = await stat(path.join(repositoryRoot, target)).catch(() => null);
    if (!entry) throw new Error(`Broken source link in ${source}: ${href}`);
    return `${sourceUrl(target, entry.isDirectory() ? 'tree' : 'blob')}${match[2] || ''}${match[3] || ''}`;
  }

  markdown.renderer.rules.fence = (tokens, index) => {
    const token = tokens[index];
    const language = token.info.trim().split(/\s+/)[0] || 'text';
    const highlighted = hljs.getLanguage(language)
      ? hljs.highlight(token.content, { language, ignoreIllegals: true }).value
      : escape(token.content);
    return `<div class="code-block"><div class="code-toolbar"><span>${escape(language)}</span><button class="copy-button" type="button" aria-label="Copy code">${icon('copy')}<span>Copy</span></button></div><pre tabindex="0"><code class="hljs language-${escape(language)}">${highlighted}</code></pre></div>`;
  };
  markdown.renderer.rules.table_open = () => '<div class="table-scroll" role="region" aria-label="Documentation table" tabindex="0"><table>';
  markdown.renderer.rules.table_close = () => '</table></div>';

  async function renderDocument(page) {
    const content = await readFile(path.join(repositoryRoot, page.source), 'utf8');
    const tokens = markdown.parse(content, {});
    const toc = [];
    const usedIds = new Map();
    let section = '';
    let sectionId = '';
    let sectionText = [];
    const addSearch = () => {
      search.push({ title: page.title, section, group: page.group, url: `${url(page.slug)}${sectionId ? `#${sectionId}` : ''}`, text: sectionText.join(' ') });
      sectionText = [];
    };
    for (let index = 0; index < tokens.length; index++) {
      const token = tokens[index];
      if (token.type === 'heading_open') {
        const inline = tokens[index + 1];
        const title = inline.children.filter(child => child.type !== 'html_inline').map(child => child.content).join('');
        const slug = headingSlug(title);
        const count = usedIds.get(slug) || 0;
        usedIds.set(slug, count + 1);
        const id = count ? `${slug}-${count}` : slug;
        token.attrSet('id', id);
        if (token.tag === 'h1') {
          // The page shell owns the title and description.
          token.hidden = inline.hidden = tokens[index + 2].hidden = true;
          inline.children = [];
          continue;
        }
        if (token.tag === 'h2') {
          addSearch();
          section = title;
          sectionId = id;
          toc.push({ title, id });
        }
      }
      if (token.type === 'inline') sectionText.push((token.children || []).map(child => child.type === 'softbreak' ? ' ' : child.content).join(''));
      if (token.type === 'fence') sectionText.push(token.content);
      for (const child of token.children || []) {
        if (child.type === 'link_open') {
          const href = await resolveSourceLink(child.attrGet('href'), page.source);
          child.attrSet('href', href);
        }
        if (child.type === 'image') {
          const src = child.attrGet('src');
          if (!/^https?:\/\//.test(src)) throw new Error(`Add a local image copy rule before using ${src} in ${page.source}.`);
          child.attrSet('loading', 'lazy');
        }
      }
    }
    addSearch();
    return { html: markdown.renderer.render(tokens, markdown.options, {}), toc };
  }

  function navigation(current) {
    return `<nav aria-label="Documentation">${groups.map(group => `<div class="nav-group"><h2>${escape(group.title)}</h2>${group.pages.map(page => `<a href="${url(page.slug)}" class="nav-link${current === page.slug ? ' active' : ''}"${current === page.slug ? ' aria-current="page"' : ''}>${page.icon ? icon(page.icon) : '<span class="nav-dash" aria-hidden="true"></span>'}<span>${escape(page.title)}</span>${page.slug === 'quickstart' ? '<span class="nav-shortcut" aria-hidden="true">↗</span>' : ''}</a>`).join('')}</div>`).join('')}</nav>`;
  }

  function shell(page, content, toc = []) {
    const title = `${page.slug === '' ? 'Documentation' : page.title} · Catnap`;
    const canonical = origin ? `${origin.replace(/\/$/, '')}${page.slug === '404' ? `${base}404.html` : url(page.slug)}` : '';
    return `<!doctype html>
<html lang="en" data-base="${base}">
<head>
  <meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light dark"><meta name="theme-color" content="#172b40">
  <title>${escape(title)}</title><meta name="description" content="${escape(page.description)}">
  <meta property="og:title" content="${escape(title)}"><meta property="og:description" content="${escape(page.description)}"><meta property="og:type" content="website">
  ${canonical ? `<link rel="canonical" href="${escape(canonical)}"><meta property="og:url" content="${escape(canonical)}">` : ''}
  ${page.slug === '404' ? '<meta name="robots" content="noindex">' : ''}
  <link rel="icon" href="${asset('logo.png')}" type="image/png"><link rel="stylesheet" href="${asset('styles.css')}">
  <script>try{const t=localStorage.getItem('catnap-theme');document.documentElement.dataset.theme=t==='dark'||t==='light'?t:matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'}catch{}</script>
  <script type="module" src="${asset('app.js')}"></script>
  <noscript><style>.search-trigger,.theme-toggle,.menu-toggle,.copy-button,.copy-query,.demo-topbar{display:none!important}@media(max-width:720px){.sidebar{display:block;position:static;width:auto;margin-top:64px;padding:20px}.page-layout,.page-layout.home-layout{padding-top:32px}}</style></noscript>
</head>
<body>
  <a class="skip-link" href="#main">Skip to content</a>
  <header class="site-header">
    <a class="brand" href="${url('')}" aria-label="Catnap documentation home"><img src="${asset('logo.png')}" alt="Catnap" width="140" height="47"></a>
    <span class="header-divider"></span><a class="docs-label" href="${url('')}">Documentation</a>
    <div class="header-actions">
      <button class="search-trigger" type="button" aria-label="Search documentation">${icon('search')}<span>Search docs...</span><kbd>⌘ K</kbd></button>
      <a class="github-link" href="${github}" aria-label="Catnap on GitHub">${icon('github')}<span>GitHub</span>${icon('external')}</a>
      <span class="action-divider"></span>
      <button class="icon-button theme-toggle" type="button" aria-label="Switch color theme">${icon('moon', 'moon')}${icon('sun', 'sun')}</button>
      <button class="icon-button menu-toggle" type="button" aria-label="Open navigation" aria-controls="mobile-navigation" aria-expanded="false">${icon('menu')}</button>
    </div>
  </header>
  <aside class="sidebar"><a class="version" href="${url('release-audit')}"><span class="status-dot"></span>${escape(version)}<span class="version-caption">Development</span></a>${navigation(page.slug)}<div class="sidebar-footer">Small responses. Rest easy.</div></aside>
  <dialog id="mobile-navigation" class="mobile-navigation" aria-label="Documentation navigation"><div class="mobile-nav-heading"><strong>Documentation</strong><button class="icon-button close-menu" type="button" aria-label="Close navigation">${icon('close')}</button></div>${navigation(page.slug)}</dialog>
  <div class="page-layout ${page.slug === '' ? 'home-layout' : ''}">
    <main id="main" tabindex="-1">${content}<footer class="page-footer"><span>Catnap · Apache 2.0</span><a href="${sourceUrl('LICENSE.txt')}">License ${icon('external')}</a><a href="${github}">Source ${icon('external')}</a></footer></main>
    ${toc.length ? `<aside class="toc"><nav aria-label="On this page"><h2>On this page</h2>${toc.map(item => `<a href="#${escape(item.id)}">${escape(item.title)}</a>`).join('')}</nav><div class="toc-help"><span>Improve these docs</span><a href="${sourceUrl(page.source, 'edit')}">Edit on GitHub ${icon('external')}</a></div></aside>` : ''}
  </div>
  <dialog class="search-dialog" aria-labelledby="search-title"><div class="search-bar">${icon('search')}<label id="search-title" class="sr-only" for="search-input">Search documentation</label><input id="search-input" type="search" placeholder="Search documentation..." autocomplete="off" spellcheck="false" aria-controls="search-results"><button class="close-search" type="button" aria-label="Close search"><kbd>Esc</kbd></button></div><div id="search-results" class="search-results"></div><div class="search-footer"><span id="search-status" role="status" aria-live="polite">Search guides, concepts, and reference.</span><span><kbd>↑</kbd><kbd>↓</kbd> navigate <kbd>↵</kbd> open</span></div></dialog>
  <div id="announcement" class="sr-only" role="status" aria-live="polite"></div>
</body></html>`;
  }

  function home() {
    const demo = examples[0];
    const json = value => hljs.highlight(JSON.stringify(value, null, 2), { language: 'json' }).value;
    return `<div class="home-content">
      <div class="eyebrow"><span class="eyebrow-line"></span> THE CATNAP DOCUMENTATION</div>
      <section class="hero" aria-labelledby="hero-title"><div class="hero-copy"><h1 id="hero-title">Your JSON.<br><span>Only what matters.</span></h1><p>A small JVM library for selecting just the fields you need.<br class="desktop-break"> Keep your serializers. Bring your favorite framework.</p><div class="hero-actions"><a class="button primary" href="${url('quickstart')}">Get started ${icon('arrow')}</a><a class="button secondary" href="${url('query-syntax')}">Explore the query syntax ${icon('arrow')}</a></div></div><div class="hero-mark" aria-hidden="true"><span class="orbit orbit-one"></span><span class="orbit orbit-two"></span><span class="hero-brace">{<span>…</span>}</span><span class="orbit-label label-json">JSON in</span><span class="orbit-label label-fields">your fields out</span></div></section>
      <div class="capability-strip"><span>${icon('check')} Java 17+ core</span><span>${icon('check')} Two query dialects</span><span>${icon('check')} JSON, NDJSON & SSE</span></div>
      <section class="projection-section" aria-labelledby="projection-title"><div class="section-heading"><div><span class="section-kicker">A LITTLE QUERY. A LIGHTER RESPONSE.</span><h2 id="projection-title">See what stays.</h2></div><a class="text-link" href="${url('query-syntax')}">Query reference ${icon('arrow')}</a></div>
        <div class="projection-demo"><div class="demo-topbar"><div class="example-tabs" role="group" aria-label="Query examples">${examples.map((example, index) => `<button type="button" data-example="${example.id}" aria-pressed="${index === 0}">${example.title}</button>`).join('')}</div><div class="dialect-toggle" role="group" aria-label="Query dialect"><button type="button" data-dialect="google" aria-pressed="true">Google</button><button type="button" data-dialect="linkedin" aria-pressed="false">LinkedIn</button></div></div>
        <div class="query-bar"><span class="method-label">fields</span><code id="demo-query">${escape(demo.google)}</code><button class="copy-query icon-button" type="button" aria-label="Copy query">${icon('copy')}</button></div>
        <div class="json-comparison"><div class="json-pane"><div class="json-label"><span>Original response</span><span>INPUT</span></div><pre tabindex="0"><code id="demo-input">${json(demo.input)}</code></pre></div><div class="json-pane projected"><div class="json-label"><span>${icon('check')} Projected response</span><span>OUTPUT</span></div><pre tabindex="0"><code id="demo-output">${json(demo.output)}</code></pre></div></div>
        <div class="demo-caption"><span class="status-dot"></span><span id="demo-note" aria-live="polite">${demo.note}</span><span class="example-caption">Illustrative example</span></div></div>
        <script id="query-examples" type="application/json">${JSON.stringify(examples).replace(/</g, '\\u003c')}</script>
      </section>
      <section class="start-section" aria-labelledby="start-title"><div class="section-heading"><div><span class="section-kicker">MAKE YOURSELF AT HOME</span><h2 id="start-title">A good place to start.</h2></div></div><div class="start-grid"><a class="start-card" href="${url('quickstart')}"><span class="card-icon">${icon('terminal')}</span><h3>Your first projection ${icon('arrow')}</h3><p>Run an example, make a request, and get back exactly the fields you asked for.</p><span class="card-link">Follow the quickstart</span></a><a class="start-card" href="${url('core-api')}"><span class="card-icon">${icon('braces')}</span><h3>Meet the core ${icon('arrow')}</h3><p>Compile once. Reuse an immutable query across your native JSON trees.</p><span class="card-link">Understand the API</span></a><a class="start-card" href="${url('streaming')}"><span class="card-icon">${icon('stream')}</span><h3>Keep things flowing ${icon('arrow')}</h3><p>Project one NDJSON record or JSON-bearing SSE payload at a time.</p><span class="card-link">Explore streaming</span></a></div></section>
      <section class="integrations-section" aria-labelledby="integrations-title"><div class="section-heading"><div><span class="section-kicker">FITS RIGHT IN</span><h2 id="integrations-title">Your stack. Your serialization.</h2></div><a class="text-link" href="${url('compatibility')}">Compatibility ${icon('arrow')}</a></div><p class="section-description">Independent adapters work at your framework’s JSON boundary.</p><div class="integration-list">${[
        ['spring', 'S', 'Spring', 'MVC & WebFlux', 'spring'], ['micronaut', 'μ', 'Micronaut', 'Native JsonMapper', 'micronaut'], ['quarkus', 'Q', 'Quarkus', 'REST & Jackson 2', 'quarkus'], ['ktor', 'K', 'Ktor', 'Coroutines & Flow', 'ktor'],
      ].map(([slug, mark, title, description, style]) => `<a class="integration" href="${url(slug)}"><span class="framework-mark ${style}" aria-hidden="true">${mark}</span><span><strong>${title}</strong><small>${description}</small></span>${icon('arrow')}</a>`).join('')}</div><a class="standalone-link" href="${url('json-bindings')}">No framework? Use Jackson 2, Jackson 3, or Kotlin serialization directly. ${icon('arrow')}</a></section>
      <aside class="snapshot-note">${icon('info')}<div><strong>A fresh start for Catnap.</strong><p>You’re reading the ${escape(version)} docs. This is a new major JVM API, currently in development. <a href="${url('migration')}">Coming from v2? Start here ${icon('arrow')}</a></p></div></aside>
    </div>`;
  }

  await mkdir(path.join(output, 'assets'), { recursive: true });
  await Promise.all([
    copyFile(path.join(websiteRoot, 'src/styles.css'), path.join(output, 'assets/styles.css')),
    copyFile(path.join(websiteRoot, 'src/app.js'), path.join(output, 'assets/app.js')),
    copyFile(path.join(websiteRoot, 'src/search.mjs'), path.join(output, 'assets/search.mjs')),
    copyFile(path.join(repositoryRoot, 'brand/logos/colorways/catnap-slate-graphite.png'), path.join(output, 'assets/logo.png')),
    writeFile(path.join(output, '.nojekyll'), ''),
  ]);
  for (const [index, page] of pages.entries()) {
    let content;
    let toc = [];
    if (!page.source) {
      content = home();
      search.push({ title: 'Introduction', section: '', group: page.group, url: url(''), text: `${page.description} JSON projection Java NDJSON SSE Google LinkedIn frameworks` });
    } else {
      const rendered = await renderDocument(page);
      toc = rendered.toc;
      const previous = pages[index - 1];
      const next = pages[index + 1];
      content = `<article class="doc-content"><div class="breadcrumb"><a href="${url('')}">Docs</a><span>/</span>${escape(page.group)}</div><header class="doc-heading"><h1>${escape(page.title)}</h1><p>${escape(page.description)}</p></header><div class="prose">${rendered.html}</div><div class="edit-link"><a href="${sourceUrl(page.source, 'edit')}">Edit this page on GitHub ${icon('external')}</a></div><nav class="page-pagination" aria-label="Previous and next pages">${previous ? `<a class="previous" href="${url(previous.slug)}"><small>Previous</small><span>← ${escape(previous.title)}</span></a>` : '<span></span>'}${next ? `<a class="next" href="${url(next.slug)}"><small>Next</small><span>${escape(next.title)} →</span></a>` : ''}</nav></article>`;
    }
    const directory = path.join(output, page.slug);
    await mkdir(directory, { recursive: true });
    await writeFile(path.join(directory, 'index.html'), shell(page, content, toc));
  }
  const missing = { slug: '404', title: 'Page not found', description: 'Find your way back to the Catnap documentation.' };
  await writeFile(path.join(output, '404.html'), shell(missing, `<section class="not-found"><span class="eyebrow">404 · CAT NAP IN PROGRESS</span><h1>This page wandered off.</h1><p>Head back to the docs, or search for what you need.</p><a class="button primary" href="${url('')}">Back to documentation ${icon('arrow')}</a><button class="button secondary search-trigger" type="button">${icon('search')} Search docs</button></section>`));
  await writeFile(path.join(output, 'assets/search-index.json'), JSON.stringify(search));
  await writeFile(path.join(output, 'build-info.json'), JSON.stringify({ base, version, pages: pages.map(page => url(page.slug)) }));
  if (origin) {
    const sitemap = `${origin.replace(/\/$/, '')}${base}sitemap.xml`;
    await writeFile(path.join(output, 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${pages.map(page => `<url><loc>${escape(`${origin.replace(/\/$/, '')}${url(page.slug)}`)}</loc></url>`).join('')}</urlset>`);
    await writeFile(path.join(output, 'robots.txt'), `User-agent: *\nAllow: /\nSitemap: ${sitemap}\n`);
  }
  return { output, base, pageCount: pages.length + 1, searchEntries: search.length };
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  await rm(path.join(websiteRoot, 'dist'), { recursive: true, force: true });
  const result = await buildSite();
  console.log(`Built ${result.pageCount} pages and ${result.searchEntries} search entries at ${result.output} (base: ${result.base}).`);
}
