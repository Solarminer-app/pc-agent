// Shared view layer for every PC-Agent page: one formatting, one status language, one table.
// Text rule: user-facing strings go through SolarMinerI18n.t(); agent-provided strings go
// through s(). See docs/agent-wiki/frontend-i18n.md.
(() => {
  const i18n = () => window.SolarMinerI18n;
  const t = (value, params) => window.SolarMinerI18n?.t(value, params) ?? value;
  // Builders accept translation keys and already formatted telemetry/identifiers.
  const displayText = (value, params) => Object.hasOwn(window.SolarMinerI18nCatalog?.frontend || {}, value)
    ? t(value, params) : window.SolarMinerI18n?.s(value) ?? value;
  const s = value => window.SolarMinerI18n?.s(value) ?? value;
  const $ = id => document.getElementById(id);

  // ------------------------------------------------------------------ formatting

  const number = (value, digits = 2) => Number.isFinite(value)
    ? new Intl.NumberFormat(i18n()?.locale || 'de-DE', {maximumFractionDigits: digits}).format(value) : '—';

  const hashUnits = ['H/s', 'kH/s', 'MH/s', 'GH/s', 'TH/s', 'PH/s', 'EH/s'];
  function hashrate(hashesPerSecond) {
    if (!(hashesPerSecond > 0) || !Number.isFinite(hashesPerSecond)) return '—';
    let index = 0;
    while (hashesPerSecond >= 1000 && index < hashUnits.length - 1) { hashesPerSecond /= 1000; index++; }
    return `${number(hashesPerSecond, hashesPerSecond >= 100 ? 0 : 2)} ${hashUnits[index]}`;
  }
  function difficulty(value) {
    if (!(value > 0) || !Number.isFinite(value)) return '—';
    const units = ['', 'k', 'M', 'G', 'T', 'P', 'E'];
    let index = 0;
    while (value >= 1000 && index < units.length - 1) { value /= 1000; index++; }
    return `${number(value, value >= 100 ? 0 : 1)}${units[index]}`;
  }
  const power = watts => Number.isFinite(watts) && watts > 0 ? `${number(watts, 0)} W` : '—';
  const temperature = celsius => Number.isFinite(celsius) && celsius > 0 ? `${number(celsius, 0)} °C` : '—';
  const latency = ms => Number.isFinite(ms) && ms >= 0 ? `${number(ms, 0)} ms` : '—';
  function efficiency(hashesPerSecond, watts) {
    if (!(hashesPerSecond > 0) || !(watts > 0)) return '—';
    let value = watts / hashesPerSecond, index = 0;
    while (value < 1 && index < hashUnits.length - 1) { value *= 1000; index++; }
    return `${number(value, value >= 100 ? 0 : 2)} J/${hashUnits[index].replace('/s', '')}`;
  }
  /** Energy always reads as kWh with three decimals; the journal reports watt-hours. */
  const kilowattHours = (wattHours, digits = 3) => Number.isFinite(wattHours)
    ? `${new Intl.NumberFormat(i18n()?.locale || 'de-DE', {maximumFractionDigits: digits}).format(wattHours / 1000)} kWh` : '—';
  function duration(seconds) {
    const hours = Math.floor((seconds || 0) / 3600), minutes = Math.floor(((seconds || 0) % 3600) / 60);
    return hours ? `${hours} h ${minutes} min` : `${minutes} min`;
  }
  /** Tariff shows in the display currency when a rate exists, in the stored currency when it does not. */
  function tariff(energy) {
    const preferences = window.SolarMinerPreferences;
    if (!energy?.settings) return {value: '—', currency: preferences?.currency || ''};
    const converted = preferences?.convert(energy.settings.pricePerKwh, energy.settings.currency);
    const currency = converted == null ? energy.settings.currency : preferences.currency;
    const amount = converted == null ? energy.settings.pricePerKwh : converted;
    return {value: new Intl.NumberFormat(preferences?.locale || i18n().locale, {maximumFractionDigits: 4}).format(amount), currency};
  }
  const money = (value, currency) => window.SolarMinerPreferences?.money(value, currency)
    ?? `${number(value)} ${currency || ''}`.trim();

  /**
   * Gross revenue per kilowatt-hour a worker consumes — the only figure that makes coins on
   * different hardware comparable. The worker share is taken from the coin forecast exactly as
   * the dashboard splits it, so per-device values always add up to the coin total.
   */
  function workerUsdPerDay(hashesPerSecond, forecast) {
    if (!(hashesPerSecond > 0) || !forecast?.available || !(forecast.usdPerDay > 0) || !(forecast.hashrateHps > 0)) return null;
    return forecast.usdPerDay * hashesPerSecond / forecast.hashrateHps;
  }
  function revenuePerKwh(usdPerDay, watts) {
    if (!(usdPerDay > 0) || !(watts > 0)) return null;
    return usdPerDay / (24 * watts / 1_000);
  }
  function moneyPerKwh(value) {
    if (!Number.isFinite(value) || value <= 0) return '—';
    const digits = value >= 1 ? 2 : value >= 0.01 ? 3 : 5;
    const preferred = window.SolarMinerPreferences?.money;
    return preferred ? `${preferred(value, 'USD', {maximumFractionDigits: digits})} /kWh` : `${number(value, digits)} USD/kWh`;
  }

  // ------------------------------------------------------------------ coin identity

  /** Single source for coin id → display identity; every page reads its lists from here. */
  const coinCatalog = [
    {id: 'monero', name: 'Monero', ticker: 'XMR', algorithm: 'RandomX', device: 'CPU'},
    {id: 'pearl', name: 'Pearl', ticker: 'PRL', algorithm: 'PearlHash', device: 'GPU'},
    {id: 'ravencoin', name: 'Ravencoin', ticker: 'RVN', algorithm: 'KAWPOW', device: 'GPU'},
    {id: 'ethereumclassic', name: 'Ethereum Classic', ticker: 'ETC', algorithm: 'ETCHash', device: 'GPU'},
    {id: 'decred', name: 'Decred', ticker: 'DCR', algorithm: 'BLAKE3', device: 'GPU'},
    {id: 'quantus', name: 'Quantus', ticker: 'QTC', algorithm: 'QPoW (Poseidon2)', device: 'GPU'}
  ];
  const coinById = new Map(coinCatalog.map(coin => [coin.id, coin]));
  const coinName = id => coinById.get(id)?.name || id;
  const coinTicker = id => id === 'none' ? t('unassigned') : coinById.get(id)?.ticker || id;
  const gpuCoinIds = coinCatalog.filter(coin => coin.device === 'GPU').map(coin => coin.id);
  /** Algorithm names as the miner reports them differ in case and spelling from the display names. */
  const minerAlgorithms = {monero: ['randomx'], pearl: ['pearlhash'], ravencoin: ['kawpow'],
    ethereumclassic: ['etchash'], decred: ['blake3_decred', 'blake3'], quantus: ['quantus', 'qpow']};
  const matchesAlgorithm = (coin, worker) =>
    (minerAlgorithms[coin.id] || [coin.algorithm]).map(value => String(value).toLowerCase())
      .includes(String(worker.currentAlgorithm || '').toLowerCase());
  const coinIdForAlgorithm = algorithm => coinCatalog.find(coin =>
    matchesAlgorithm(coin, {currentAlgorithm: algorithm}))?.id || '';

  /** The overview keeps each coin's configuration in a different place; pages must not each re-derive it. */
  function configurationFor(overview, coinId) {
    if (coinId === 'monero') return overview.moneroConfiguration;
    if (coinId === 'pearl') return overview.pearlConfiguration;
    return overview.gpuCoins?.[coinId]?.configuration;
  }

  // ------------------------------------------------------------------ status language

  const statusLabels = {MINING: 'mining_active', STARTING: 'miner_starting', BENCHMARKING: 'benchmarking', POWER_SWEEP: 'power_sweep_running', AUTO_TUNING: 'benchmark_optimization_tuning', PAUSED: 'paused', STOPPED: 'stopped', ERROR: 'error'};
  function statusPill(status, override) {
    const pill = element('span', `pill status-${String(status || 'unknown').toLowerCase()}`);
    pill.append(element('i', 'pill-dot'), element('span', '', displayText(override || statusLabels[status] || 'unknown')));
    return pill;
  }

  // ------------------------------------------------------------------ DOM builders

  function element(tagName, className, textValue, params) {
    const node = document.createElement(tagName);
    if (className) node.className = className;
    if (textValue !== undefined && textValue !== null) node.textContent = displayText(textValue, params);
    return node;
  }
  /** Two-line table cell: the value on top, its provenance or identifier underneath. */
  function cellLines(primary, secondary) {
    const wrap = element('div');
    wrap.append(element('strong', secondary ? undefined : 'cell-truncate', primary));
    if (secondary) wrap.append(element('span', 'cell-sub', secondary));
    return wrap;
  }
  function metric({label, value, detail, tone = '', action}) {
    const card = element('article', `metric${tone ? ` ${tone}` : ''}`);
    card.append(element('span', 'metric-label', label));
    card.append(element('strong', 'metric-value', value));
    if (detail) card.append(element('small', 'metric-detail', detail));
    if (action) card.append(action);
    return card;
  }

  /**
   * One table implementation for every page so sorting, alignment and the "no value"
   * language never diverge. Column labels are translated here because the table builds them.
   */
  function table({columns, rows, empty, sortKey = null, sortDirection = 'desc', rowClass, onRow}) {
    const scroll = element('div', 'table-scroll');
    const table = element('table', 'data-table');
    const head = document.createElement('thead'), headRow = document.createElement('tr');
    for (const column of columns) {
      const cell = document.createElement('th');
      cell.scope = 'col';
      cell.textContent = t(column.label);
      if (column.align) cell.dataset.align = column.align;
      if (column.width) cell.style.width = column.width;
      if (column.sortable) {
        cell.setAttribute('aria-sort', column.key === sortKey ? (sortDirection === 'asc' ? 'ascending' : 'descending') : 'none');
        const button = element('button', 'table-sort', column.label);
        button.type = 'button';
        button.addEventListener('click', () => column.onSort?.());
        cell.replaceChildren(button);
      }
      headRow.append(cell);
    }
    head.append(headRow);
    const body = document.createElement('tbody');
    if (!rows.length) {
      const row = document.createElement('tr'), cell = document.createElement('td');
      cell.colSpan = columns.length; cell.className = 'table-empty'; cell.textContent = t(empty || 'no_entries');
      row.append(cell); body.append(row);
    }
    for (const [index, row] of rows.entries()) {
      const tr = document.createElement('tr');
      if (rowClass) tr.className = rowClass(row, index) || '';
      if (onRow) { tr.tabIndex = 0; tr.addEventListener('click', () => onRow(row)); tr.addEventListener('keydown', event => { if (event.key === 'Enter') onRow(row); }); }
      for (const column of columns) {
        const cell = document.createElement('td');
        if (column.align) cell.dataset.align = column.align;
        const value = column.render(row, index);
        if (value == null) cell.textContent = '—';
        else if (value instanceof Node) cell.append(value);
        else cell.textContent = displayText(String(value));
        tr.append(cell);
      }
      body.append(tr);
    }
    table.append(head, body);
    scroll.append(table);
    return scroll;
  }

  // ------------------------------------------------------------------ page chrome

  /** Shared status strip: label, coloured pill and an optional jump target. */
  function statusStrip(strip, entries) {
    strip.replaceChildren();
    for (const {label, value, tone, href} of entries) {
      const item = element('span', 'strip-item');
      item.append(element('span', '', `${t(label)}: `));
      const pill = statusPill(null, value);
      if (tone) pill.classList.add(tone);
      item.append(pill);
      if (href) { const link = element('a', '', 'open'); link.href = href; item.append(link); }
      strip.append(item);
    }
  }

  /** One banner per page. `kind` separates operator feedback from connection failures so a
   *  recovered connection clears its own banner without erasing what the user just did. */
  function notice(message, {error = false, kind = 'action'} = {}) {
    const node = $('notice');
    if (!node) return;
    node.dataset.kind = kind;
    node.className = `notice${error ? ' error' : ''}`;
    node.textContent = t(message);
    node.hidden = !message;
  }
  function clearNotice(kind) {
    const node = $('notice');
    if (node && node.dataset.kind === kind) node.hidden = true;
  }
  function setConnection(state) {
    const badge = $('connection');
    if (!badge) return;
    badge.className = `badge${state === 'online' ? ' online' : state === 'offline' ? ' offline' : ''}`;
    badge.textContent = t(state === 'online' ? 'agent_connected' : state === 'offline' ? 'agent_unavailable' : 'connecting');
  }
  function setUpdated(at) {
    const node = $('updated');
    if (node) node.textContent = t('updated_time', {time: new Date(at ?? Date.now()).toLocaleTimeString(i18n().locale)});
  }

  /** Filter button groups (`[data-*]` chips) appear on several pages with identical wiring. */
  function filterButtonGroup(selector, dataKey, choose) {
    const buttons = [...document.querySelectorAll(selector)];
    for (const button of buttons) button.addEventListener('click', () => {
      choose(button.dataset[dataKey]);
      for (const other of buttons) other.classList.toggle('selected', other === button);
    });
    return buttons;
  }
  function debounce(fn, ms = 150) {
    let timer = null;
    return (...args) => {
      clearTimeout(timer);
      timer = setTimeout(() => { timer = null; fn(...args); }, ms);
    };
  }

  // ------------------------------------------------------------------ transport

  /** Every page reads the same error language out of an agent response body. */
  async function readError(response, fallback) {
    const body = await response.json().catch(() => null);
    const detail = s(body?.message || body?.detail);
    return new Error(detail || fallback || `HTTP ${response.status}`);
  }
  async function getJson(url, {timeout = 8000} = {}) {
    const response = await fetch(url, {cache: 'no-store', signal: AbortSignal.timeout(timeout)});
    if (!response.ok) throw await readError(response);
    return response.json();
  }
  // P7: the coin catalog is a closed enum that only changes on agent restart; cache it per
  // tab so page switches never pay for the fetch again (server TTL is 1 h, same net here).
  const COIN_CATALOG_KEY = 'solarminer.coin-catalog.v1', COIN_CATALOG_MAX_AGE_MS = 3_600_000;
  async function cachedCoinCatalog() {
    if (window.SolarMinerCoinCatalog) return window.SolarMinerCoinCatalog;
    try {
      const saved = JSON.parse(sessionStorage.getItem(COIN_CATALOG_KEY) || 'null');
      const age = Date.now() - (saved?.savedAt || 0);
      if (age >= 0 && age < COIN_CATALOG_MAX_AGE_MS && Array.isArray(saved.catalog) && saved.catalog.length) {
        window.SolarMinerCoinCatalog = saved.catalog;
        return saved.catalog;
      }
    } catch (_) { /* fall through to a fresh read */ }
    const catalog = await getJson('/api/agent/local/status/coin-catalog');
    window.SolarMinerCoinCatalog = catalog;
    try { sessionStorage.setItem(COIN_CATALOG_KEY, JSON.stringify({savedAt: Date.now(), catalog})); } catch (_) {}
    return catalog;
  }
  async function postJson(url, body, {confirmation = null} = {}) {
    if (confirmation && !confirm(t(confirmation))) return null;
    const response = await fetch(url, {
      method: 'POST', headers: body === undefined ? {} : {'Content-Type': 'application/json'},
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (!response.ok) throw await readError(response);
    const result = await response.json().catch(() => null);
    // Mutations either acknowledge with `true` or return the updated resource.  Treating a
    // successful object response as a rejection made settings such as the agent name appear
    // unsaved even though the server had already persisted them.
    if (result === false || result == null) throw new Error(s(result?.message || result?.detail) || 'the_agent_rejected_the_change');
    return result;
  }

  // ------------------------------------------------------------------ derived models

  const sensorKey = value => String(value).toLowerCase().replace(/[^a-z0-9]+/g, '_');

  /**
   * A worker temperature is only real when a sensor reports it. GPU workers never had a
   * temperature in the miner payload, so the host sensor is used and the source is named.
   * One lookup for dashboard, worker and sensor pages: vendor-specific keys first, then a
   * model-name match, then the single-AMD-sensor case. Missing stays missing.
   */
  function temperatureOf(source, telemetry, gpus) {
    if (Number(source.temperatureCelsius) > 0) return {value: Number(source.temperatureCelsius), source: 'miners_448600'};
    const metrics = telemetry?.metrics || {};
    const card = source.deviceId ? (gpus || telemetry?.gpus || []).find(entry => entry.deviceId === source.deviceId) : null;
    const vendor = String(source.vendor || card?.vendor || '').toLowerCase();
    const index = source.index ?? card?.index;
    const model = sensorKey(source.hardwareModel || card?.model || '');
    const candidates = [];
    if (source.hardwareType === 'CPU') candidates.push(metrics['cpu.temperature']);
    else {
      if (index != null) candidates.push(metrics[`gpu.nvidia.${index}.temperature`]);
      if (index != null) candidates.push(metrics[`gpu.amd.card${index}.temperature`]);
      for (const [key, metric] of Object.entries(metrics)) {
        if (!/temperature$/i.test(key) || !key.startsWith('gpu.amd.') && !key.startsWith('hardware.')) continue;
        if (model.length > 4 && key.includes(model)) candidates.push(metric);
      }
      if (vendor === 'amd') {
        const amdSensors = Object.entries(metrics)
          .filter(([key, metric]) => key.startsWith('gpu.amd.') && /temperature$/i.test(key) && metric?.available);
        if (amdSensors.length === 1) candidates.push(amdSensors[0][1]);
      }
    }
    const usable = candidates.find(metric => metric?.available && Number.isFinite(metric.value) && metric.value > 0);
    if (usable) return {value: Number(usable.value), source: 'host_sensor'};
    return {value: null, source: null};
  }

  /** Normalized worker rows shared by the dashboard and the worker page. */
  function workerRows(overview, telemetry) {
    const gpus = overview.gpus || [];
    return (overview.stats?.workers || []).map(worker => {
      const heat = temperatureOf(worker, telemetry, gpus);
      const watts = Number(worker.approximatedPowerUsageWatts) > 0 ? Number(worker.approximatedPowerUsageWatts) : null;
      const hashes = (Number(worker.terahashPerSecond) || 0) * 1e12;
      const coin = (overview.coins || []).find(entry => matchesAlgorithm(entry, worker)
        && (entry.device === worker.hardwareType || entry.id === 'monero' && worker.hardwareType === 'CPU'));
      const pool = worker.pool || {};
      return {
        id: worker.deviceId || worker.workerDisplayName,
        name: worker.workerDisplayName || 'worker',
        coin: coin?.id || '', coinName: coin?.name || '—', ticker: coin?.ticker || '',
        algorithm: worker.currentAlgorithm || '—',
        hardwareType: worker.hardwareType || '—', hardwareModel: worker.hardwareModel || '',
        deviceId: worker.deviceId || '',
        status: worker.miningStatus,
        hashrateHps: hashes,
        temperature: heat,
        watts, powerTargetWatts: Number(worker.powerTargetWatts) || null,
        shares: {accepted: worker.acceptedShares, rejected: worker.rejectedShares, stale: pool.staleShares},
        difficulty: pool.difficulty ?? null, bestShare: pool.bestShareDifficulty ?? null, latency: pool.latencyMs ?? null,
        pools: (worker.pools || []).map(entry => entry.poolUrl).filter(Boolean),
        raw: worker
      };
    });
  }

  /** One row per coin describing how its miners currently reach a pool. */
  function poolRows(overview) {
    const pearl = overview.pearl || {};
    const states = coin => coin === 'pearl' ? pearl.gpus || [] : overview.gpuCoins?.[coin]?.gpus || [];
    return (overview.coins || []).map(coin => {
      const gpuStates = states(coin.id);
      const workers = (overview.stats?.workers || []).filter(worker =>
        (coin.id === 'monero' ? worker.hardwareType === 'CPU' : false)
        || (coin.id !== 'monero' && matchesAlgorithm(coin, worker)));
      const running = coin.id === 'pearl' ? Boolean(pearl.running) : coin.id === 'monero'
        ? coin.status === 'MINING' : Boolean(overview.gpuCoins?.[coin.id]?.running);
      const connected = coin.id === 'monero' ? (running && coin.status === 'MINING' ? 1 : 0)
        : gpuStates.filter(gpu => gpu.running && gpu.poolHealthy).length;
      const selected = coin.id === 'monero' ? 1 : gpuStates.filter(gpu => gpu.selected).length;
      const pool = workers.map(worker => worker.pool).find(entry => entry?.difficulty != null) || {};
      return {
        coin: coin.id, coinName: coin.name, ticker: coin.ticker, algorithm: coin.algorithm, device: coin.device,
        configured: Boolean(coin.configured), experimental: Boolean(coin.experimental),
        proxyUrl: overview.proxy?.[`${coin.id}Url`] || overview.proxy?.moneroUrl && coin.id === 'monero' || '',
        poolUrl: configurationFor(overview, coin.id)?.poolUrl || '',
        reachable: Boolean(overview.proxy?.reachable),
        feeReady: Boolean(overview.proxy?.[`${coin.id}FeeReady`]),
        running, selectedWorkers: selected, connectedWorkers: connected,
        latency: pool.latencyMs ?? null, difficulty: pool.difficulty ?? null,
        detail: coin.id === 'pearl' ? pearl.connectionDetail : overview.gpuCoins?.[coin.id]?.minerError || '',
        payout: (overview.payoutDefaults || []).find(entry => entry.coin === coin.id) || null
      };
    });
  }

  /** Miner display names carry a tool prefix and a device suffix; tables need the readable part. */
  function shortWorkerName(row) {
    return String(row.name || '').replace(/^(SRBMiner|XMRig)\s+/i, '').replace(/\s*\((NVIDIA|AMD):[0-9]+\)$/i, '');
  }

  // A paused miner keeps reporting its devices, which would list one GPU twice under two coins and imply capacity
  // that does not exist. Only a live miner process owns a device, so operation lists use exactly these states.
  const operatingStates = new Set(['MINING', 'STARTING', 'ERROR']);
  function operatingRows(rows) { return rows.filter(row => operatingStates.has(row.status)); }

  window.SolarMinerUI = {
    $, t, s, number, hashrate, difficulty, power, temperature, latency, efficiency,
    kilowattHours, duration, tariff, money, workerUsdPerDay, revenuePerKwh, moneyPerKwh,
    statusLabels, statusPill, element, cellLines, metric, table,
    statusStrip, notice, clearNotice, setConnection, setUpdated, filterButtonGroup, debounce,
    getJson, postJson, readError, cachedCoinCatalog,
    coinCatalog, coinName, coinTicker, gpuCoinIds, configurationFor, matchesAlgorithm, coinIdForAlgorithm,
    temperatureOf, workerRows, poolRows, shortWorkerName, operatingRows, sensorKey
  };
})();
