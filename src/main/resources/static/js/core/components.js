// Shared view layer for every PC-Agent page: one formatting, one status language, one table.
// Text rule: user-facing strings go through SolarMinerI18n.t(); agent-provided strings go
// through s(). See pc-agent/FRONTEND-I18N.md.
(() => {
  const i18n = () => window.SolarMinerI18n;
  const t = (value, params) => window.SolarMinerI18n?.t(value, params) ?? value;
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
  const coinTicker = id => id === 'none' ? t('Frei') : coinById.get(id)?.ticker || id;
  const gpuCoinIds = coinCatalog.filter(coin => coin.device === 'GPU').map(coin => coin.id);
  /** Algorithm names as the miner reports them differ in case and spelling from the display names. */
  const minerAlgorithms = {monero: 'RandomX', pearl: 'PearlHash', ravencoin: 'kawpow',
    ethereumclassic: 'etchash', decred: 'blake3_decred', quantus: 'quantus'};
  const matchesAlgorithm = (coin, worker) =>
    (minerAlgorithms[coin.id] || coin.algorithm).toLowerCase() === String(worker.currentAlgorithm || '').toLowerCase();

  /** The overview keeps each coin's configuration in a different place; pages must not each re-derive it. */
  function configurationFor(overview, coinId) {
    if (coinId === 'monero') return overview.moneroConfiguration;
    if (coinId === 'pearl') return overview.pearlConfiguration;
    return overview.gpuCoins?.[coinId]?.configuration;
  }

  // ------------------------------------------------------------------ status language

  const statusLabels = {MINING: 'Mining aktiv', PAUSED: 'Pausiert', STOPPED: 'Gestoppt', ERROR: 'Fehler'};
  function statusPill(status, override) {
    const pill = element('span', `pill status-${String(status || 'unknown').toLowerCase()}`);
    pill.append(element('i', 'pill-dot'), element('span', '', t(override || statusLabels[status] || 'Unbekannt')));
    return pill;
  }

  // ------------------------------------------------------------------ DOM builders

  function element(tagName, className, textValue, params) {
    const node = document.createElement(tagName);
    if (className) node.className = className;
    if (textValue !== undefined && textValue !== null) node.textContent = t(textValue, params);
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
      cell.colSpan = columns.length; cell.className = 'table-empty'; cell.textContent = t(empty || 'Keine Einträge.');
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
        else cell.textContent = String(value);
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
      item.append(element('span', '', `${label}: `));
      const pill = statusPill(null, value);
      if (tone) pill.classList.add(tone);
      item.append(pill);
      if (href) { const link = element('a', '', 'Öffnen'); link.href = href; item.append(link); }
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
    badge.textContent = t(state === 'online' ? 'Agent verbunden' : state === 'offline' ? 'Agent nicht erreichbar' : 'Verbinde …');
  }
  function setUpdated(at) {
    const node = $('updated');
    if (node) node.textContent = t('Aktualisiert {time}', {time: new Date(at ?? Date.now()).toLocaleTimeString(i18n().locale)});
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
  async function postJson(url, body, {confirmation = null} = {}) {
    if (confirmation && !confirm(t(confirmation))) return null;
    const response = await fetch(url, {
      method: 'POST', headers: body === undefined ? {} : {'Content-Type': 'application/json'},
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (!response.ok) throw await readError(response);
    const result = await response.json().catch(() => null);
    if (result !== true) throw new Error(s(result?.message || result?.detail) || 'Der Agent hat die Änderung abgelehnt.');
    return true;
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
    if (Number(source.temperatureCelsius) > 0) return {value: Number(source.temperatureCelsius), source: 'Miner'};
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
    if (usable) return {value: Number(usable.value), source: 'Host-Sensor'};
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
        name: worker.workerDisplayName || 'Worker',
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
  const operatingStates = new Set(['MINING', 'ERROR']);
  function operatingRows(rows) { return rows.filter(row => operatingStates.has(row.status)); }

  window.SolarMinerUI = {
    $, t, s, number, hashrate, difficulty, power, temperature, latency, efficiency,
    kilowattHours, duration, tariff, money, workerUsdPerDay, revenuePerKwh, moneyPerKwh,
    statusLabels, statusPill, element, cellLines, metric, table,
    statusStrip, notice, clearNotice, setConnection, setUpdated, filterButtonGroup, debounce,
    getJson, postJson, readError,
    coinCatalog, coinName, coinTicker, gpuCoinIds, configurationFor, matchesAlgorithm,
    temperatureOf, workerRows, poolRows, shortWorkerName, operatingRows, sensorKey
  };
})();
