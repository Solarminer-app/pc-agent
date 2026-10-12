#!/usr/bin/env node
// Render simulation: applies the same static-markup translation pass as i18n.js to each
// page's HTML for both locales and asserts no German fallback text survives in English
// output (and no English dictionary text leaks into German output).
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../src/main/resources/static');
const box = {window: {}};
vm.runInNewContext(fs.readFileSync(path.join(root, 'js/core/i18n-catalog.js'), 'utf8'), box);
const catalog = box.window.SolarMinerI18nCatalog;
const normalize = v => v.replace(/\s+/g, ' ').trim();

function t(key, locale) {
  const k = normalize(key);
  const entry = catalog.frontend[k];
  if (entry) return entry[locale] ?? entry.de;
  if (locale === 'en') return catalog.agentGermanToEnglish[k] ?? k;
  return k;
}

const exceptions = new Set(['☼', '—', 'SolarMiner', 'PC-Agent', 'SolarMiner PC-Agent', 'SolarMiner PC Agent', 'CPU', 'GPU', '192.168.178.40', 'Deutsch', 'English', 'JSON ↗', '↗']);
let failures = 0;
for (const name of fs.readdirSync(root).filter(n => n.endsWith('.html'))) {
  const html = fs.readFileSync(path.join(root, name), 'utf8');
  for (const locale of ['en', 'de']) {
    let out = html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '').replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, '');
    // title
    out = out.replace(/<title[^>]*>([^<]*)<\/title>/, (m, txt) => `<title>${t(normalize(txt), locale)}</title>`);
    // data-i18n elements: replace their inner text
    out = out.replace(/<([a-zA-Z][a-zA-Z0-9]*)([^>]*\sdata-i18n="([^"]+)")[^>]*>([^<]*)<\/\1>/g,
      (m, tag, attrs, key, inner) => `<${tag}${attrs}>${t(normalize(key), locale)}</${tag}>`);
    // data-i18n-* attributes
    out = out.replace(/\b(title|placeholder|aria-label)="([^"]*)"[^>]*?data-i18n-\1="([^"]+)"/g,
      (m, attr, value, key) => `${attr}="${t(normalize(key), locale)}"`);
    out = out.replace(/\bdata-i18n-(title|placeholder|aria-label)="[^"]*"/g, '');
    // leftover bare text nodes: resolve through the same value fallback as i18n.js
    out = out.replace(/>([^<>]+)</g, (m, txt) => `>${t(normalize(txt), locale)}<`);
    // assert: no German dictionary source text survives in EN; no English text in DE.
    // Compare whole text nodes only (substring checks false-positive on shared words like
    // "Miner" inside "Miner-Software").
    const nodes = [...out.matchAll(/>([^<>]+)</g)].map(m => normalize(m[1]))
      .filter(v => v && !exceptions.has(v));
    const deSet = new Set(Object.values(catalog.frontend).map(e => normalize(e.de)));
    const enSet = new Set(Object.values(catalog.frontend).map(e => normalize(e.en)));
    for (const node of nodes) {
      // A node is translated when it equals the correct-locale text of some entry; the
      // wrong-locale text of another entry only matters when it is not also valid here.
      const validHere = locale === 'en' ? enSet.has(node) : deSet.has(node);
      const wrongHere = locale === 'en' ? deSet.has(node) : enSet.has(node);
      if (!validHere && wrongHere) {
        console.log(`${name} [${locale}]: untranslated text node "${node.slice(0, 70)}"`);
        failures++;
      }
    }
  }
}
console.log(failures ? `render simulation failures: ${failures}` : 'render simulation: all 9 pages fully translated in both locales');
if (failures) process.exitCode = 1;
