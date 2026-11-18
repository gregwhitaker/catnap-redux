import { searchDocs, excerpt } from './search.mjs';

const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];
const announce = message => { $('#announcement').textContent = message; };

// Keep the theme consistent across full, static page navigations.
const themeButton = $('.theme-toggle');
function updateThemeLabel() {
  themeButton.setAttribute('aria-label', `Switch to ${document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'} theme`);
}
updateThemeLabel();
themeButton.addEventListener('click', () => {
  const theme = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = theme;
  try { localStorage.setItem('catnap-theme', theme); } catch { /* Storage may be disabled. */ }
  updateThemeLabel();
});

const mobileMenu = $('#mobile-navigation');
const menuButton = $('.menu-toggle');
menuButton.addEventListener('click', () => {
  menuButton.setAttribute('aria-expanded', 'true');
  mobileMenu.showModal();
});
$('.close-menu').addEventListener('click', () => mobileMenu.close());
mobileMenu.addEventListener('close', () => menuButton.setAttribute('aria-expanded', 'false'));
mobileMenu.addEventListener('click', event => { if (event.target === mobileMenu) mobileMenu.close(); });
matchMedia('(min-width: 721px)').addEventListener('change', event => { if (event.matches && mobileMenu.open) mobileMenu.close(); });

async function copy(text, button) {
  try {
    await navigator.clipboard.writeText(text);
    const label = button.querySelector('span');
    const original = label?.textContent;
    if (label) label.textContent = 'Copied';
    button.setAttribute('data-copied', 'true');
    announce('Copied to clipboard.');
    setTimeout(() => {
      if (label) label.textContent = original;
      button.removeAttribute('data-copied');
    }, 1800);
  } catch {
    announce('Clipboard unavailable. Select the code to copy it.');
  }
}
$$('.copy-button').forEach(button => button.addEventListener('click', () => copy(button.closest('.code-block').querySelector('code').textContent, button)));

// Predefined examples keep all three dialects in sync without approximating the JVM engine.
const exampleData = $('#query-examples');
if (exampleData) {
  const examples = JSON.parse(exampleData.textContent);
  let active = examples[0];
  let dialect = 'catnap';
  const escape = text => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const highlightJSON = value => escape(JSON.stringify(value, null, 2)).replace(/("(?:\\.|[^"\\])*"\s*:)|("(?:\\.|[^"\\])*")|\b(true|false|null)\b|(-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)/g, (match, key, string, literal) => `<span class="hljs-${key ? 'attr' : string ? 'string' : literal ? 'literal' : 'number'}">${match}</span>`);
  function updateExample() {
    $('#demo-query').textContent = active[dialect];
    $('#demo-input').innerHTML = highlightJSON(active.input);
    $('#demo-output').innerHTML = highlightJSON(active.output);
    $('#demo-note').textContent = active.note;
    $$('[data-example]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.example === active.id)));
    $$('[data-dialect]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.dialect === dialect)));
  }
  $$('[data-example]').forEach(button => button.addEventListener('click', () => {
    active = examples.find(example => example.id === button.dataset.example);
    updateExample();
  }));
  $$('[data-dialect]').forEach(button => button.addEventListener('click', () => {
    dialect = button.dataset.dialect;
    updateExample();
  }));
  $('.copy-query').addEventListener('click', event => copy(active[dialect], event.currentTarget));
}

const searchDialog = $('.search-dialog');
const searchInput = $('#search-input');
const searchResults = $('#search-results');
const searchStatus = $('#search-status');
let searchIndex;
let loading;
let selected = 0;

function selectResult(index) {
  const links = [...searchResults.querySelectorAll('a')];
  if (!links.length) return;
  selected = (index + links.length) % links.length;
  links.forEach((link, current) => link.classList.toggle('selected', current === selected));
  links[selected].scrollIntoView({ block: 'nearest' });
}

function renderResults() {
  if (!searchIndex) return;
  const results = searchDocs(searchIndex, searchInput.value);
  searchResults.replaceChildren();
  if (!results.length) {
    const empty = document.createElement('p');
    empty.className = 'search-empty';
    empty.textContent = 'No results. Try “streaming”, “query”, or a framework name.';
    searchResults.append(empty);
  }
  results.forEach((result, index) => {
    const link = document.createElement('a');
    link.className = `search-result${index === 0 ? ' selected' : ''}`;
    link.href = result.url;
    const category = document.createElement('small');
    category.textContent = `${result.group} / ${result.title}`;
    const title = document.createElement('strong');
    title.textContent = result.section || result.title;
    const preview = document.createElement('p');
    preview.textContent = excerpt(result.text, searchInput.value);
    link.append(category, title, preview);
    link.addEventListener('click', () => searchDialog.close());
    searchResults.append(link);
  });
  selected = 0;
  searchStatus.textContent = searchInput.value.trim() ? `${results.length} result${results.length === 1 ? '' : 's'}.` : 'Popular starting points';
}

async function openSearch() {
  if (!searchDialog.open) searchDialog.showModal();
  searchInput.focus();
  if (searchIndex) { renderResults(); return; }
  searchStatus.textContent = 'Loading documentation…';
  try {
    loading ||= fetch(`${document.documentElement.dataset.base}assets/search-index.json`).then(response => {
      if (!response.ok) throw new Error('Search index unavailable');
      return response.json();
    });
    searchIndex = await loading;
    renderResults();
  } catch {
    loading = undefined;
    searchStatus.textContent = 'Search could not load. Reopen search to retry, or use the navigation.';
  }
}
$$('.search-trigger').forEach(button => button.addEventListener('click', openSearch));
$('.close-search').addEventListener('click', () => searchDialog.close());
searchDialog.addEventListener('click', event => { if (event.target === searchDialog) searchDialog.close(); });
searchInput.addEventListener('input', renderResults);
searchInput.addEventListener('keydown', event => {
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    selectResult(selected + (event.key === 'ArrowDown' ? 1 : -1));
  }
  if (event.key === 'Enter') {
    const links = [...searchResults.querySelectorAll('a')];
    if (links[selected]) { event.preventDefault(); links[selected].click(); }
  }
});
document.addEventListener('keydown', event => {
  const typing = event.target instanceof Element && event.target.closest('input,textarea,[contenteditable="true"]');
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k' || event.key === '/' && !typing && !mobileMenu.open) {
    event.preventDefault();
    if (!mobileMenu.open) openSearch();
  }
});
if (!/Mac|iPhone|iPad/.test(navigator.platform)) $$('.search-trigger kbd').forEach(kbd => { kbd.textContent = 'Ctrl K'; });

const tocLinks = $$('.toc nav a');
if (tocLinks.length && 'IntersectionObserver' in window) {
  const observer = new IntersectionObserver(entries => {
    for (const entry of entries) {
      if (entry.isIntersecting) tocLinks.forEach(link => link.classList.toggle('active', decodeURIComponent(link.hash.slice(1)) === entry.target.id));
    }
  }, { rootMargin: '-90px 0px -65% 0px', threshold: 0 });
  $$('.prose h2[id]').forEach(heading => observer.observe(heading));
}
