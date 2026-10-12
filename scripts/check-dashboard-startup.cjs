#!/usr/bin/env node
// Regression harness: a cached overview is delivered synchronously by live-data.js. The page
// must be fully initialized before that first subscription callback renders the dashboard.
const fs = require('node:fs');
const vm = require('node:vm');

const nodes = new Map();
function element() {
  return {
    append() {}, replaceChildren() {}, addEventListener() {}, setAttribute() {},
    classList: {add() {}, remove() {}, toggle() {}, contains() { return false; }},
    dataset: {}, style: {}, hidden: false, disabled: false, open: false, value: '',
    textContent: ''
  };
}
const byId = id => {
  if (!nodes.has(id)) nodes.set(id, element());
  return nodes.get(id);
};

global.window = global;
global.document = {
  activeElement: null,
  createElement: element,
  getElementById: byId,
  addEventListener() {}
};
global.prompt = () => null;
global.alert = () => {};

const ui = {
  $: byId,
  t: key => key,
  element,
  statusPill: element,
  operatingRows: rows => rows,
  workerRows: () => [{coin: 'monero', hashrateHps: 1, watts: 1, status: 'MINING'}],
  workerUsdPerDay: () => 1,
  revenuePerKwh: () => 1,
  metric: element,
  table: element,
  cellLines: element,
  shortWorkerName: () => 'cpu',
  hashrate: value => String(value),
  power: value => String(value),
  moneyPerKwh: value => String(value),
  kilowattHours: value => String(value),
  duration: value => String(value),
  setConnection() {},
  setUpdated() {},
  notice() {},
  clearNotice() {},
  getJson: async () => ({name: null}),
  postJson: async () => ({name: null})
};
window.SolarMinerUI = ui;
window.SolarMinerI18n = {locale: 'en'};
window.SolarMinerPreferences = {
  locale: 'en', currency: 'USD', moneyFromUsd: value => String(value),
  money: value => String(value), convert: value => value
};
window.SolarMinerMiningCache = {read: () => null, write() {}};
window.SolarMinerAgentIdentity = {apply() {}};

const overview = {
  proxy: {reachable: true, mode: 'standalone'},
  stats: {workers: []},
  earnings: [{coin: 'monero', available: true}],
  gpus: []
};
window.SolarMinerLive = {
  channel(name) {
    return {subscribe(handler) {
      if (name === 'overview') handler(overview, {source: 'snapshot', stale: true, error: null});
      return () => {};
    }};
  },
  refresh() {}, refreshAll() {}
};

vm.runInThisContext(fs.readFileSync(
  'src/main/resources/static/js/pages/overview.js', 'utf8'),
{filename: 'overview.js'});
console.log('dashboard startup: cached overview rendered without initialization errors');
