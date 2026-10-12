#!/usr/bin/env node
// Regression guard: browser-authored display text must be a frontend key, even when it has
// no German umlaut (for example, "Alles im erwarteten Bereich"). Runtime agent text is
// handled exclusively by i18n.js and is deliberately outside this scan.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../src/main/resources/static');
const box = {window: {}};
vm.runInNewContext(fs.readFileSync(path.join(root, 'js/core/i18n-catalog.js'), 'utf8'), box);
const catalog = box.window.SolarMinerI18nCatalog;
const allowedTechnicalText = new Set(['Ethereum Classic', 'QPoW (Poseidon2)', 'GPU', 'HTTP', 'a, button']);
const isNonDisplayLiteral = value => {
  if (!value || allowedTechnicalText.has(value)) return true;
  // CSS classes/selectors and SVG paths are implementation data, not UI copy.
  if (/^[a-z][a-z0-9-]*(?: [a-z][a-z0-9-]*)*$/.test(value)) return true;
  if (/^[#.(][^'\"]+$/.test(value)) return true;
  if (/^[A-Za-z]\d[\dA-Za-z .,-]*$/.test(value)) return true;
  return !/[A-Za-zÄÖÜäöüß]/.test(value) || !/\s/.test(value);
};
function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, {withFileTypes: true})) {
    const f = path.join(dir, e.name);
    if (e.isDirectory()) walk(f, out);
    else if (f.endsWith('.js') && !f.endsWith('i18n-catalog.js') && !f.endsWith('i18n.js')) out.push(f);
  }
  return out;
}
const pat = /(['"])((?:\\.|(?!\1)[^\\\n])*)\1/g;
let count = 0;
for (const file of walk(path.join(root, 'js'))) {
  const lines = fs.readFileSync(file, 'utf8').split('\n');
  lines.forEach((line, i) => {
    if (line.trim().startsWith('//') || line.trim().startsWith('*')) return;
    for (const m of line.matchAll(pat)) {
      const v = m[2];
      const n = v.replace(/\s+/g, ' ').trim();
      if (isNonDisplayLiteral(n)) continue;
      count++;
      console.log(`${path.relative(root, file)}:${i + 1}: ${v.slice(0, 100)}`);
    }
  });
}
console.log(`unkeyed browser display literals: ${count}`);
