#!/usr/bin/env node
// Independent closed catalog + API-code coverage. No legacy translations or browser assets reused.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const directory = path.resolve(__dirname, '../src/main/resources/static/console');
const script = fs.readFileSync(path.join(directory, 'i18n.js'), 'utf8');
const html = fs.readFileSync(path.join(directory, 'index.html'), 'utf8');
const ui = fs.readFileSync(path.join(directory, 'console.js'), 'utf8');
const placeholders = value => [...value.matchAll(/\{(\w+)\}/g)].map(match => match[1]).sort();
for (const language of ['de', 'en']) {
  const listeners = {}, box = {window: {}, localStorage: {getItem: () => language}, document: {documentElement: {}, addEventListener(name, fn) {listeners[name] = fn;}}};
  vm.runInNewContext(script, box);
  vm.runInNewContext(fs.readFileSync(path.join(directory, 'tools-i18n.js'),'utf8'),box);
  const {catalog, t} = box.window.OperationsI18n;
  assert.equal(box.document.documentElement.lang, language);
  for (const [key, entry] of Object.entries(catalog)) {
    assert(entry.length === 2 && entry.every(value => typeof value === 'string' && value.length), key);
    assert.deepEqual(placeholders(entry[0]), placeholders(entry[1]), key);
  }
  for (const match of (html + fs.readFileSync(path.join(directory,'tools.html'),'utf8')).matchAll(/data-(?:text|label|placeholder)="([^"]+)"/g)) assert(catalog[match[1]], `Missing HTML key ${match[1]}`);
  for (const match of (ui + fs.readFileSync(path.join(directory,'tools.js'),'utf8')).matchAll(/\bt\(\s*(['"])([^'"]+)\1/g)) assert(catalog[match[2]], `Missing JS key ${match[2]}`);
  const javaRoot = path.resolve(__dirname, '../src/main/java/de/verdox/solarminer/pcagent');
  const java = [...fs.readdirSync(path.join(javaRoot, 'console')).filter(file => file.endsWith('.java')).map(file => path.join(javaRoot, 'console', file)),
    ...['ConsoleWorkspaceController.java', 'ConsoleCommandController.java', 'ConsoleConfigurationController.java', 'ConsoleToolsController.java'].map(file => path.join(javaRoot, 'controller', file))];
  for (const file of java) {
    const code = fs.readFileSync(file, 'utf8');
    for (const match of code.matchAll(/"((?:tool_|console_|power_mode_|gpu_power_|gpu_not_|auto_tuning_|srbminer_native_|software_not_)[a-z_]+)"/g))
      assert(catalog[match[1]], `Missing API key ${match[1]} (${file})`);
    assert(!/import .*controller\.(?!Console)/.test(code), `Legacy controller dependency: ${file}`);
  }
  const toolsSource=fs.readFileSync(path.join(javaRoot,'console','ConsoleToolsService.java'),'utf8');
  const actions=toolsSource.match(/ACTIONS = Set.of\(([\s\S]*?)\);/)[1];
  for (const match of actions.matchAll(/"([a-z_]+)"/g)) assert(catalog['tool_'+match[1]], 'Missing tool action label '+match[1]);
  for (const relative of ['lowlevel/GpuTuningService.java','mining/measurement/AutoTuningSessionService.java']) {
    const source=fs.readFileSync(path.join(javaRoot,relative),'utf8');
    for (const match of source.matchAll(/"((?:auto_tuning|gpu_clock|gpu_memory|gpu_tuning)_[a-z_]+)"/g)) assert(catalog[match[1]], 'Missing domain failure '+match[1]);
  }
  assert.equal(t('selected', {count: 3}), language === 'de' ? '3 ausgewählt' : '3 selected');
  assert(!html.includes('/js/') && !html.includes('/css/'), 'Console must not load legacy assets');
  assert(!ui.includes('SolarMinerI18n') && !ui.includes('SolarMinerLive'), 'Console must not reuse legacy frontend runtime');
}
console.log('PASS: independent DE/EN catalog, interpolation, markup, JS/API codes and no legacy controller/asset dependencies.');
