#!/usr/bin/env node
// Check the closed browser catalog against all static pages and literal t() keys.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../src/main/resources/static');
const catalogBox = {window: {}};
vm.runInNewContext(fs.readFileSync(path.join(root, 'js/core/i18n-catalog.js'), 'utf8'), catalogBox);
const catalog = catalogBox.window.SolarMinerI18nCatalog.frontend;
const normalize = value => value.replace(/\s+/g, ' ').trim();
const exceptions = new Set(['☼', '—', 'SolarMiner', 'PC-Agent', 'SolarMiner PC-Agent', 'SolarMiner PC Agent', 'CPU', 'GPU', '192.168.178.40']);
const failures = [];

for (const name of fs.readdirSync(root).filter(name => name.endsWith('.html'))) {
  const file = path.join(root, name);
  const html = fs.readFileSync(file, 'utf8').replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '').replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, '');
  if (!/<html\s+lang="de"/.test(html)) failures.push(`${name}: source language must be de`);
  const values = [...html.matchAll(/(?:title|placeholder|aria-label)="([^"]+)"/g)].map(match => match[1]);
  values.push(...html.replace(/<[^>]*>/g, '\0').split('\0'));
  for (const value of values) {
    const key = normalize(value);
    if (key && !exceptions.has(key) && catalog[key] === undefined) failures.push(`${name}: ${key}`);
  }
}

function walk(directory) {
  for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) walk(file);
    else if (file.endsWith('.js') && !file.endsWith('i18n-catalog.js')) {
      const source = fs.readFileSync(file, 'utf8');
      for (const match of source.matchAll(/\b(?:i18n\.)?t\(\s*(['"])(.*?)\1/g)) {
        const key = normalize(match[2]);
        if (catalog[key] === undefined) failures.push(`${path.relative(root, file)}: ${key}`);
      }
    }
  }
}
walk(path.join(root, 'js'));

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
    console, Intl, Date, Set, CustomEvent: class {}
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
if (en.t('Aktualisiert {time}', {time: '12:00'}) !== 'Updated 12:00') failures.push('English interpolation failed');
if (de.t('Aktualisiert {time}', {time: '12:00'}) !== 'Aktualisiert 12:00') failures.push('German interpolation failed');
if (en.s('Zuweisung fehlt') !== 'Assignment is missing') failures.push('German agent error did not switch to English');
if (de.s('Assign a coin to this worker first') !== 'Weise diesem Worker zuerst einen Coin zu') failures.push('English agent error did not switch to German');

if (failures.length) { console.error(failures.join('\n')); process.exitCode = 1; }
else console.log(`i18n catalog checked: ${fs.readdirSync(root).filter(name => name.endsWith('.html')).length} pages, both locales`);
