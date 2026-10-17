(() => {
  const t = window.SolarMinerI18n.t;
  const ui = window.SolarMinerUI;
  const prefs = window.SolarMinerPreferences;
  const $ = id => document.getElementById(id);
  const number = (value, digits = 2) => new Intl.NumberFormat(prefs.locale,
    {maximumFractionDigits: digits}).format(value);
  const money = value => `≈ ${prefs.moneyFromUsd(value)}`;
  const make = (tag, cls, value) => {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (value != null) node.textContent = value;
    return node;
  };
  let agents = [];
  let loading = false;
  let startupRetries = 0;

  function choosePoints(worker, coin) {
    const options = (worker.candidates || []).filter(point => point.coin === coin
      && point.minerSoftwareId && point.hashrateHps > 0 && point.measuredWatts > 0
      && point.usdPerKwh > 0);
    const miners = [...new Set(options.map(point => point.minerSoftwareId))];
    const miner = miners.includes(worker.configuredMiner) ? worker.configuredMiner
      : miners.sort((a, b) => {
        const best = id => Math.max(...options.filter(point => point.minerSoftwareId === id)
          .map(point => point.profitUsdPerDay ?? -Infinity));
        return best(b) - best(a) || a.localeCompare(b);
      })[0];
    const points = options.filter(point => point.minerSoftwareId === miner);
    const manual = points.find(point => point.limitWatts === worker.manualTargetWatts) || null;
    const efficiency = points.reduce((best, point) => !best || point.hashrateHps / point.measuredWatts
      > best.hashrateHps / best.measuredWatts ? point : best, null);
    const profit = points.filter(point => Number.isFinite(point.profitUsdPerDay))
      .reduce((best, point) => !best || point.profitUsdPerDay > best.profitUsdPerDay ? point : best, null);
    return {miner, manual, efficiency, profit};
  }

  function render() {
    const coin = $('fleet-coin').value;
    const workers = agents.filter(agent => agent.available && agent.points)
      .flatMap(agent => (agent.points.workers || []).map(worker => ({agent, worker,
        selected: choosePoints(worker, coin)})));
    const unavailable = agents.filter(agent => !agent.available || !agent.points);
    const total = workers.length;
    $('fleet-coverage').textContent = unavailable.length
      ? t('fleet_missing_agents', {count: unavailable.length})
      : total ? t('fleet_coverage', {count: total}) : t('fleet_no_workers');
    const totals = $('fleet-totals');
    const details = $('fleet-workers');
    totals.replaceChildren(); details.replaceChildren();
    const modes = [['manual', 'worker_mode_manual'], ['efficiency', 'worker_mode_efficiency'],
      ['profit', 'worker_mode_profit_day']];
    const comparable = workers.filter(row => modes.every(([field]) =>
      Number.isFinite(row.selected[field]?.profitUsdPerDay)));
    for (const [field, title] of modes) {
      const valid = comparable.map(row => row.selected[field]);
      const complete = comparable.length === total && unavailable.length === 0 && total > 0;
      const sum = key => valid.reduce((value, point) => value + point[key], 0);
      const watts = sum('measuredWatts');
      const gross = sum('usdPerDay');
      const fees = valid.reduce((value, point) => value + point.usdPerDay * point.feePercentage / 100, 0);
      const profit = sum('profitUsdPerDay');
      const card = make('article', 'fleet-mode');
      card.append(make('h2', '', t(title)), make('p', 'fleet-profit', valid.length ? money(profit) : '—'),
        make('p', 'muted', t(complete ? 'fleet_complete' : 'fleet_partial',
          {count: valid.length, total})));
      if (field === 'profit' && valid.length) {
        const manualProfit = comparable.reduce((value, row) => value + row.selected.manual.profitUsdPerDay, 0);
        card.append(make('p', 'muted', t('fleet_gain_vs_manual', {amount: money(profit - manualProfit)})));
      }
      const metrics = make('dl', 'fleet-metrics');
      for (const [label, value] of [
        ['hashrate', valid.length ? ui.hashrate(sum('hashrateHps')) : '—'],
        ['worker_mode_comparison_measured_power', valid.length ? ui.power(watts) : '—'],
        ['worker_mode_comparison_energy_day', valid.length ? `${number(watts * 24 / 1000, 3)} kWh` : '—'],
        ['worker_mode_comparison_cost_day', valid.length ? money(gross - fees - profit) : '—'],
        ['worker_mode_comparison_gross_day', valid.length ? money(gross) : '—'],
        ['worker_mode_comparison_gross_kwh', valid.length && watts > 0
          ? ui.moneyPerKwh(gross / (watts * 24 / 1000)) : '—'],
        ['fleet_fees_day', valid.length ? money(fees) : '—']
      ]) {
        const item = make('div'); item.append(make('dt', '', t(label)), make('dd', '', value)); metrics.append(item);
      }
      card.append(metrics); totals.append(card);
    }
    for (const {agent, worker, selected} of workers) {
      const card = make('article', 'fleet-worker');
      const head = make('header');
      head.append(make('h3', '', `${agent.name || t('pc_agent')} · ${worker.model || worker.deviceId}`),
        make('span', 'muted', selected.miner || t('fleet_no_miner')));
      card.append(head);
      const rate = agent.points.tariff;
      card.append(make('p', 'muted', rate
        ? t('fleet_worker_tariff', {rate: number(Number(rate.pricePerKwh), 4), currency: rate.currency,
          watts: worker.manualTargetWatts}) : t('fleet_tariff_missing')));
      const modeGrid = make('div', 'fleet-worker-modes');
      for (const [field, title] of modes) {
        const point = selected[field];
        const cell = make('div'); cell.append(make('strong', '', t(title)));
        if (point && Number.isFinite(point.profitUsdPerDay)) {
          cell.append(make('span', '', money(point.profitUsdPerDay)),
            make('span', 'muted', `${ui.hashrate(point.hashrateHps)} · ${ui.power(point.measuredWatts)} · ${point.limitWatts} W`));
        } else cell.append(make('span', 'muted', t(!selected.miner ? 'fleet_no_coin_point'
          : !point ? 'fleet_no_mode_point' : 'fleet_no_profit_point')));
        modeGrid.append(cell);
      }
      card.append(modeGrid); details.append(card);
    }
    for (const agent of unavailable) {
      const card = make('article', 'fleet-worker');
      card.append(make('h3', '', agent.name || t('pc_agent')),
        make('p', 'muted', t('fleet_agent_unavailable')));
      details.append(card);
    }
  }

  async function load() {
    if (loading) return;
    loading = true; $('refresh').disabled = true;
    try {
      const response = await fetch('/api/agent/local/network/fleet-comparison', {cache: 'no-store'});
      if (!response.ok) throw new Error('fleet_load_failed');
      agents = await response.json();
      const coins = [...new Set(agents.flatMap(agent => agent.points?.workers || [])
        .flatMap(worker => (worker.candidates || []).map(point => point.coin)))].filter(Boolean).sort();
      const previous = $('fleet-coin').value;
      $('fleet-coin').replaceChildren(...coins.map(coin =>
        new Option(ui.coinName(coin), coin)));
      $('fleet-coin').value = coins.includes(previous) ? previous : coins[0] || '';
      $('fleet-coin').disabled = !coins.length;
      $('fleet-updated').textContent = t('benchmark_updated_at', {time: new Intl.DateTimeFormat(prefs.locale,
        {dateStyle: 'medium', timeStyle: 'short'}).format(new Date())});
      $('connection').textContent = t('connected');
      $('connection').classList.remove('offline');
      render();
      if (!agents.length && startupRetries++ < 5) setTimeout(load, 2000);
    } catch (_) {
      $('fleet-updated').textContent = t('fleet_load_failed');
      $('connection').textContent = t('disconnected');
      $('connection').classList.add('offline');
    } finally { loading = false; $('refresh').disabled = false; }
  }
  $('fleet-coin').addEventListener('change', render);
  $('refresh').addEventListener('click', load);
  document.addEventListener('solarminer:preferences-changed', render);
  load();
})();
