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

let dashboardRows = [];
const ui = {
  $: byId,
  t: key => key,
  element,
  statusPill: element,
  operatingRows: rows => rows,
  workerRows: () => [{coin: 'monero', hashrateHps: 1, watts: 1, status: 'MINING'}],
  workerUsdPerDay: (hashrate, forecast) => forecast?.available && forecast.hashrateHps > 0
    ? forecast.usdPerDay * hashrate / forecast.hashrateHps : null,
  revenuePerKwh: (daily, watts) => daily > 0 && watts > 0 ? daily / (24 * watts / 1_000) : null,
  coinIdForAlgorithm: algorithm => String(algorithm).toLowerCase() === 'randomx' ? 'monero' : '',
  metric: element,
  table: options => { dashboardRows = options.rows; return element(); },
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
      if (name === 'network-agents') handler({agents: [{own: false, reachable: true, name: 'Peer',
        workers: [{algorithm: 'RandomX', hashrateHps: 1_000, watts: 50, status: 'MINING'}],
        earnings: [{coin: 'monero', hashrateHps: 1_000, usdPerDay: 2}]}]}, {error: null});
      return () => {};
    }};
  },
  refresh() {}, refreshAll() {}
};

vm.runInThisContext(fs.readFileSync(
  'src/main/resources/static/js/pages/overview.js', 'utf8'),
{filename: 'overview.js'});
const remote = dashboardRows.find(row => row.local === false);
if (!remote || remote.coin !== 'monero' || remote.usdPerDay !== 2 || !(remote.revenuePerKwh > 0)) {
  throw new Error(`legacy remote earnings were not derived: ${JSON.stringify(remote)}`);
}
console.log('dashboard startup: cached overview and legacy remote earnings rendered');
