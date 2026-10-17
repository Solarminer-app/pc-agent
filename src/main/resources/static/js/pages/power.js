(() => {
  const $ = id => document.getElementById(id), t = window.SolarMinerI18n.t;
  const prefs = window.SolarMinerPreferences, locale = window.SolarMinerI18n.locale;
  const num = (value, digits = 2) => new Intl.NumberFormat(locale, {maximumFractionDigits: digits}).format(value);
  const hash = value => window.SolarMinerUI.hashrate(value);
  const day = value => { const d = new Date(value); return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10); };
  const from = $('date-from'), to = $('date-to');
  to.value = day(new Date()); from.value = day(Date.now() - 6 * 86400000);

  function money(costs) {
    if (!costs || !Object.keys(costs).length) return null;
    let total = 0;
    for (const [currency, amount] of Object.entries(costs)) {
      const converted = prefs.convert(amount, currency);
      if (!Number.isFinite(converted)) return null;
      total += converted;
    }
    return prefs.money(total, prefs.currency);
  }
  function cells(row, values) { values.forEach(value => { const cell = document.createElement('td'); cell.textContent = value; row.append(cell); }); }
  let lastData = null, lastWorkers = null;
  function graph(points, hourly, showHash) {
    if (!points.some(p => p.runtimeSeconds > 0)) { const empty = document.createElement('p'); empty.className = 'muted'; empty.textContent = t('no_power_history'); return empty; }
    const W = 920, H = 290, L = 90, R = 90, T = 30, B = 52, ns = 'http://www.w3.org/2000/svg';
    const svgEl = (tag, attrs = {}) => { const node = document.createElementNS(ns, tag); Object.entries(attrs).forEach(([key, value]) => node.setAttribute(key, value)); return node; };
    const svg = svgEl('svg', {viewBox: `0 0 ${W} ${H}`, role: 'group', 'aria-label': t('mining_power_chart')});
    const maxE = Math.max(0.00001, ...points.map(p => p.kilowattHours || 0)), maxH = Math.max(1, ...points.map(p => p.averageHashrateHs || 0));
    const energyDigits = maxE < 0.001 ? 6 : maxE < 0.01 ? 5 : maxE < 1 ? 3 : 2;
    const x = i => L + i * (W - L - R) / Math.max(1, points.length - 1), y = (v, max) => H - B - v / max * (H - T - B);
    for (let step = 0; step <= 4; step++) {
      const yy = y(step, 4); svg.append(svgEl('path', {class: 'grid', d: `M${L} ${yy}H${W - R}`}));
      const e = svgEl('text', {x: L - 9, y: yy + 4, 'text-anchor': 'end'}); e.textContent = num(maxE * step / 4, energyDigits); svg.append(e);
      if (showHash) { const h = svgEl('text', {x: W - R + 9, y: yy + 4}); h.textContent = hash(maxH * step / 4); svg.append(h); }
    }
    const eUnit = svgEl('text', {x: L, y: 15, class: 'axis-title'}); eUnit.textContent = t(hourly ? 'kwh_per_hour' : 'kwh_per_day'); svg.append(eUnit);
    if (showHash) { const hUnit = svgEl('text', {x: W - R, y: 15, class: 'axis-title', 'text-anchor': 'end'}); hUnit.textContent = t('hashrate_axis'); svg.append(hUnit); }
    const draw = (key, max, cls, valid, format) => {
      let segment = [];
      const flush = () => { if (segment.length > 1) svg.append(svgEl('polyline', {class: cls, points: segment.join(' ')})); segment = []; };
      points.forEach((point, i) => {
        if (!valid(point)) { flush(); return; }
        const xx = x(i), yy = y(point[key] || 0, max); segment.push(`${xx},${yy}`);
        const dot = svgEl('circle', {cx: xx, cy: yy, r: 4, class: `${cls}-dot`, tabindex: '0', role: 'img'});
        const title = svgEl('title');
        title.textContent = `${new Date(hourly ? point.at : `${point.at}T00:00:00`).toLocaleString(locale, hourly ? {dateStyle: 'short', timeStyle: 'short'} : {dateStyle: 'medium'})}: ${format(point[key] || 0)} · ${t('measurement_coverage')}: ${point.runtimeSeconds ? num(point.measuredSeconds / point.runtimeSeconds * 100, 0) : 0} %`;
        dot.setAttribute('aria-label', title.textContent); dot.append(title); svg.append(dot);
      }); flush();
    };
    draw('kilowattHours', maxE, 'energy-line', p => p.measuredSeconds > 0, value => `${num(value, energyDigits)} kWh`);
    if (showHash) draw('averageHashrateHs', maxH, 'hash-line', p => p.runtimeSeconds > 0, hash);
    points.forEach((point, i) => {
      if (i % Math.max(1, Math.ceil(points.length / 6)) && i !== points.length - 1) return;
      const label = svgEl('text', {x: x(i), y: H - 12, 'text-anchor': 'middle'});
      label.textContent = new Date(hourly ? point.at : `${point.at}T00:00:00`).toLocaleString(locale, hourly ? {month: 'numeric', day: 'numeric', hour: 'numeric'} : {month: 'short', day: 'numeric'}); svg.append(label);
    });
    const wrap = document.createElement('div'); wrap.className = 'power-chart-wrap'; wrap.append(svg); return wrap;
  }
  function render(data, workers) {
    const rows = (data.daily || []).filter(p => p.runtimeSeconds > 0 || p.kilowattHours > 0);
    const total = rows.reduce((a, p) => ({kwh: a.kwh + p.kilowattHours, runtime: a.runtime + p.runtimeSeconds, measured: a.measured + p.measuredSeconds, hashSeconds: a.hashSeconds + p.averageHashrateHs * p.runtimeSeconds}), {kwh: 0, runtime: 0, measured: 0, hashSeconds: 0});
    const costs = {};
    if (rows.every(row => row.estimatedCosts)) rows.forEach(row => Object.entries(row.estimatedCosts).forEach(([currency, amount]) => { costs[currency] = (costs[currency] || 0) + amount; }));
    const algorithms = new Set(rows.flatMap(row => row.algorithms || []));
    const showHash = algorithms.size === 1 && rows.every(row => row.algorithms?.length === 1);
    const metrics = [[t('measured_energy'), `${num(total.kwh, 3)} kWh`], [t('estimated_electricity_cost'), rows.length && rows.every(row => row.estimatedCosts) ? money(costs) || '—' : '—'], [showHash ? t('average_hashrate') : t('algorithms'), showHash ? hash(total.hashSeconds / total.runtime) : algorithms.size ? String(algorithms.size) : '—'], [t('mining_time'), `${num(total.runtime / 3600, 1)} h`], [t('measurement_coverage'), `${total.runtime ? num(total.measured / total.runtime * 100, 0) : 0} %`]];
    $('metrics').replaceChildren(...metrics.map(([label, value]) => { const item = document.createElement('article'), name = document.createElement('span'), amount = document.createElement('strong'); item.className = 'stat'; name.textContent = label; amount.textContent = value; item.append(name, amount); return item; }));
    const hourly = new Date(`${to.value}T00:00:00`) - new Date(`${from.value}T00:00:00`) < 2 * 86400000;
    $('energy-legend').textContent = t(hourly ? 'energy_per_hour' : 'energy_per_day'); $('hash-legend').hidden = !showHash;
    $('chart-note').hidden = showHash || !rows.length; $('chart-note').textContent = t(algorithms.size > 1 ? 'mixed_algorithms_no_hashrate' : 'hashrate_algorithm_unavailable');
    $('chart').replaceChildren(graph(hourly ? data.hourly || [] : data.daily || [], hourly, showHash));
    const body = $('daily-rows'); body.replaceChildren(); $('empty').hidden = rows.length > 0;
    [...rows].reverse().forEach(point => { const tr = document.createElement('tr'), coverage = point.runtimeSeconds ? point.measuredSeconds / point.runtimeSeconds : 0; if (coverage < 0.9) tr.className = 'low-coverage'; cells(tr, [new Date(`${point.at}T00:00:00`).toLocaleDateString(locale), (point.algorithms || []).length === 1 ? hash(point.averageHashrateHs) : '—', `${num(point.runtimeSeconds / 3600, 1)} h`, `${num(point.kilowattHours, 3)} kWh`, money(point.estimatedCosts) || '—', `${num(coverage * 100, 0)} %${coverage < 0.9 ? ` · ${t('incomplete_measurement')}` : ''}`]); body.append(tr); });
    const gpuRows = (workers || []).filter(row => row.hardwareType === 'GPU' && row.runtimeSeconds > 0); $('workers-empty').hidden = gpuRows.length > 0;
    const workerBody = $('worker-rows'); workerBody.replaceChildren();
    gpuRows.sort((a, b) => b.kilowattHours - a.kilowattHours).forEach(row => { const tr = document.createElement('tr'), watts = row.measuredSeconds > 0 ? row.kilowattHours * 3600000 / row.measuredSeconds : null, efficiency = watts > 0 && row.measuredSeconds / row.runtimeSeconds >= 0.9 ? row.averageHashrateHs / watts : null; cells(tr, [row.hardwareModel || row.deviceId, row.coin || '—', row.algorithm || '—', hash(row.averageHashrateHs), watts == null ? '—' : `${num(watts, 1)} W`, efficiency == null ? '—' : `${num(efficiency, 2)} H/s/W`, `${num(row.kilowattHours, 3)} kWh`, `${num(row.measuredSeconds / row.runtimeSeconds * 100, 0)} %`]); workerBody.append(tr); });
  }
  async function load() {
    window.SolarMinerUI?.setConnection('connecting'); $('range-status').textContent = t('loading_power_history');
    try {
      const start = new Date(`${from.value}T00:00:00`), end = new Date(`${to.value}T00:00:00`); end.setDate(end.getDate() + 1);
      if (!from.value || !to.value || to.value < from.value || end - start > 366 * 86400000) throw new Error('invalid_range');
      const query = `from=${encodeURIComponent(start.toISOString())}&to=${encodeURIComponent(end.toISOString())}`;
      const [history, workers] = await Promise.all([fetch(`/api/agent/local/economics/history?${query}`, {cache: 'no-store'}), fetch(`/api/agent/local/economics/history/workers?${query}`, {cache: 'no-store'})]);
      if (!history.ok || !workers.ok) throw new Error('history_unavailable');
      lastData = await history.json(); lastWorkers = await workers.json(); render(lastData, lastWorkers);
      $('range-status').textContent = ''; window.SolarMinerUI?.setConnection('online');
    } catch (error) { $('range-status').textContent = t(error.message === 'invalid_range' ? 'invalid_date_range' : 'power_history_unavailable'); window.SolarMinerUI?.setConnection(error.message === 'invalid_range' ? 'online' : 'offline'); }
  }
  $('apply').addEventListener('click', load); $('refresh').addEventListener('click', load); load();
  document.addEventListener('solarminer:preferences-changed', () => { if (lastData) render(lastData, lastWorkers); });
})();
