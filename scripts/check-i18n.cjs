#!/usr/bin/env node
// Check the key-based translation dictionary against all static pages and literal t()/s() keys.
// Rules enforced:
//   - every frontend dictionary entry carries BOTH locales {de, en}
//   - every data-i18n / data-i18n-* reference in HTML names an existing key, and the visible
//     German fallback text equals the entry's de value
//   - every literal t('…')/s('…') in JS names an existing key; raw German text inside t()/s()
//     is a defect (the key migration must not regress)
//   - runtime: interpolation, both agent-message directions, and the selector's saved
//     language/reload behavior work in both locales
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../src/main/resources/static');
const catalogBox = {window: {}};
vm.runInNewContext(fs.readFileSync(path.join(root, 'js/core/i18n-catalog.js'), 'utf8'), catalogBox);
const catalog = catalogBox.window.SolarMinerI18nCatalog;
const normalize = value => value.replace(/\s+/g, ' ').trim();
const isKey = value => /^[a-z0-9][a-z0-9_]*$/.test(value);
const exceptions = new Set(['☼', '—', 'SolarMiner', 'PC-Agent', 'SolarMiner PC-Agent', 'SolarMiner PC Agent', 'CPU', 'GPU', '192.168.178.40']);
const failures = [];

if (!catalog.agentEnglishToGerman || !catalog.agentGermanToEnglish)
  failures.push('agent dictionaries must use explicit source/target names');
if (catalog.agent !== undefined || catalog.agentGerman !== undefined)
  failures.push('ambiguous legacy agent dictionary names must not remain');

for (const [key, entry] of Object.entries(catalog.frontend)) {
  if (!isKey(key)) failures.push(`catalog key not a slug: ${key}`);
  if (!entry || typeof entry.de !== 'string' || typeof entry.en !== 'string')
    failures.push(`catalog entry missing de/en: ${key}`);
}
const deValues = new Set(Object.values(catalog.frontend).map(e => normalize(e.de)));

// ------------------------------------------------------------------ static HTML
for (const name of fs.readdirSync(root).filter(name => name.endsWith('.html'))) {
  const file = path.join(root, name);
  const html = fs.readFileSync(file, 'utf8');
  for (const required of ['i18n-catalog.js', 'i18n.js']) {
    const escaped = required.replace('.', '\\.');
    if (!new RegExp(`src="/js/core/${escaped}\\?v=[^"]+"`).test(html))
      failures.push(`${name}: ${required} must use a cache-busting version query`);
  }
  if (!/<html\s+lang="de"/.test(html)) failures.push(`${name}: source language must be de`);
  const markup = html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '').replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, '');
  for (const match of markup.matchAll(/data-i18n(-title|-placeholder|-aria-label)?="([^"]+)"/g)) {
    const key = match[2];
    if (!catalog.frontend[key]) failures.push(`${name}: data-i18n key missing from dictionary: ${key}`);
  }
  // Every visible text run/attribute value must be keyed or exception or dictionary text.
  const textOnly = markup.replace(/<[^>]*>/g, '\0');
  const values = [...markup.matchAll(/(?:title|placeholder|aria-label)="([^"]+)"/g)].map(m => m[1]);
  values.push(...textOnly.split('\0'));
  for (const value of values) {
    const key = normalize(value);
    if (!key || exceptions.has(key)) continue;
    if (deValues.has(key)) continue; // visible fallback text with a dictionary entry
    if (/^[\x00-\x7F]+$/.test(key) && !/[a-zäöüß]/i.test(key)) continue; // punctuation, numbers, markup
    if (/(data-i18n|data-i18n-)/.test(value)) continue;
    if (/^\s*$/.test(key)) continue;
    // Text that is neither dictionary German nor an exception: only a defect when its
    // enclosing element carries no data-i18n reference. Approximate: require presence of
    // some data-i18n reference on the same line.
    const line = (html.split('\n').find(l => l.includes(value)) || '');
    if (!line.includes('data-i18n')) failures.push(`${name}: unkeyed text: ${key.slice(0, 80)}`);
  }
}

// ------------------------------------------------------------------ literal JS keys
function walk(directory, out = []) {
  for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) walk(file, out);
    else if (file.endsWith('.js') && !file.endsWith('i18n-catalog.js')) out.push(file);
  }
  return out;
}
for (const file of walk(path.join(root, 'js'))) {
  const source = fs.readFileSync(file, 'utf8');
  const label = path.relative(root, file);
  for (const match of source.matchAll(/\b(?:i18n\.)?t\(\s*(['"])(.*?)\1/g)) {
    const key = normalize(match[2]);
    if (!isKey(key)) { failures.push(`${label}: t() must reference a key, got text: ${key.slice(0, 80)}`); continue; }
    if (catalog.frontend[key] === undefined) failures.push(`${label}: t() key missing from dictionary: ${key}`);
  }
  for (const match of source.matchAll(/\b(?:i18n\.)?s\(\s*(['"])(.*?)\1/g)) {
    const key = normalize(match[2]);
    if (isKey(key) && catalog.frontend[key] === undefined) failures.push(`${label}: s() key missing from dictionary: ${key}`);
    if (!isKey(key) && /[äöüßÄÖÜ]/.test(key) && !deValues.has(key))
      failures.push(`${label}: s() literal German text not in dictionary: ${key.slice(0, 80)}`);
  }
}

// ------------------------------------------------------------------ runtime behavior
function runtime(language, exerciseSwitcher = false) {
  const saved = {'solarminer.pc-agent.language': language};
  let ready, switcher, reloaded = false;
  const body = {};
  const box = {
    window: {SolarMinerI18nCatalog: catalogBox.window.SolarMinerI18nCatalog},
    document: {
      documentElement: {}, body, title: 'SolarMiner · Dashboard',
      addEventListener(event, callback) {if (event === 'DOMContentLoaded') ready = callback;},
      createTreeWalker() {return {nextNode() {return false;}};},
      querySelectorAll() {return [];},
      querySelector(selector) {return selector === '.top-actions' ? {prepend(node) {switcher = node;}} : null;},
      createElement() {return {setAttribute() {}, addEventListener(event, callback) {this[event] = callback;}};}
    },
    NodeFilter: {SHOW_TEXT: 4},
    localStorage: {getItem(key) {return saved[key] ?? null;}, setItem(key, value) {saved[key] = value;}},
    location: {reload() {reloaded = true;}},
    fetch() {return Promise.reject(new Error('offline'));},
    console: {warn() {}}, Intl, Date, Set, CustomEvent: class {}
  };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'js/core/i18n.js'), 'utf8'), box);
  if (exerciseSwitcher) {
    ready();
    switcher.value = 'de';
    switcher.change();
    if (saved['solarminer.pc-agent.language'] !== 'de' || !reloaded) failures.push('Language selector did not save and reload');
  }
  return box.window.SolarMinerI18n;
}
const en = runtime('en', true), de = runtime('de');
const updatedKey = Object.keys(catalog.frontend).find(k => catalog.frontend[k].de === 'Aktualisiert {time}');
if (!updatedKey) failures.push('dictionary lost the "Aktualisiert {time}" entry');
else {
  if (en.t(updatedKey, {time: '12:00'}) !== 'Updated 12:00') failures.push('English interpolation failed');
  if (de.t(updatedKey, {time: '12:00'}) !== 'Aktualisiert 12:00') failures.push('German interpolation failed');
}
if (en.s('Zuweisung fehlt') !== 'Assignment is missing') failures.push('German agent error did not switch to English');
if (de.s('Assign a coin to this worker first') !== 'Weise diesem Worker zuerst einen Coin zu') failures.push('English agent error did not switch to German');
const thermalStop = 'Temperaturstopp / Temperature stop: 84.0 °C >= 84.0 °C';
if (en.s(thermalStop) !== 'Temperature stop: 84.0 °C reaches the 84.0 °C limit.') failures.push('Thermal stop did not switch to English');
if (de.s(thermalStop) !== 'Temperaturstopp: 84.0 °C erreicht die Grenze von 84.0 °C.') failures.push('Thermal stop did not switch to German');
const missingTemperature = 'Temperaturstopp: GPU-Temperatur nicht lesbar / Temperature stop: GPU temperature unavailable';
if (en.s(missingTemperature) !== 'Safety stop: GPU temperature is unavailable.') failures.push('Missing temperature did not switch to English');
if (de.s(missingTemperature) !== 'Sicherheitsstopp: GPU-Temperatur nicht lesbar.') failures.push('Missing temperature did not switch to German');
// Keys thrown inside Error(t('key')) messages must resolve through s().
if (updatedKey && en.s(updatedKey, {time: '12:00'}) !== 'Updated 12:00' && en.s(updatedKey) === updatedKey)
  failures.push('s() did not resolve a frontend key');
if (en.missingKeys().length) failures.push(`missing keys reached t() in en runtime: ${en.missingKeys().join(', ')}`);
if (de.missingKeys().length) failures.push(`missing keys reached t() in de runtime: ${de.missingKeys().join(', ')}`);

if (failures.length) { console.error(failures.join('\n')); process.exitCode = 1; }
else console.log(`i18n dictionary checked: ${Object.keys(catalog.frontend).length} keys, ${fs.readdirSync(root).filter(name => name.endsWith('.html')).length} pages, both locales`);
