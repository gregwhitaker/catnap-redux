export function searchDocs(entries, query, limit = 12) {
  const words = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return entries.filter(entry => !entry.section).slice(0, 7);
  return entries.map(entry => {
    const title = entry.title.toLocaleLowerCase();
    const section = entry.section.toLocaleLowerCase();
    const text = entry.text.toLocaleLowerCase();
    if (!words.every(word => `${title} ${section} ${text}`.includes(word))) return null;
    const score = words.reduce((total, word) => total + (title.includes(word) ? 12 : 0) + (section.includes(word) ? 8 : 0) + (text.includes(word) ? 1 : 0), 0);
    return { entry, score: score + (!entry.section ? 2 : 0) };
  }).filter(Boolean).sort((a, b) => b.score - a.score || a.entry.url.localeCompare(b.entry.url)).slice(0, limit).map(result => result.entry);
}

export function excerpt(text, query, length = 150) {
  const clean = text.replace(/[`#*]/g, '').replace(/\s+/g, ' ').trim();
  const word = query.trim().toLowerCase().split(/\s+/)[0];
  const position = word ? clean.toLowerCase().indexOf(word) : 0;
  const start = Math.max(0, position - 40);
  return `${start ? '…' : ''}${clean.slice(start, start + length)}${clean.length > start + length ? '…' : ''}`;
}
