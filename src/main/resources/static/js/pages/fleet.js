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
  let catalog = [];
  let loading = false;
  let startupRetries = 0;

  function choosePoints(worker, coin) {
    return window.SolarMinerProfileComparison.select(worker.candidates, coin);
  }
  const minerName = (coin,id) => catalog.find(row => row.id === coin)?.miners?.find(miner => miner.id === id)?.name || id;

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
    const modes = [['efficiency', 'benchmark_goal_efficiency'], ['profit', 'benchmark_goal_profit']];
    const comparable = workers.filter(row => modes.every(([field]) =>
      Number.isFinite(row.selected[field]?.profitUsdPerDay)));
    for (const [field, title] of modes) {
      const valid = comparable.map(row => row.selected[field]);
      const complete = comparable.length === total && unavailable.length === 0 && total > 0;
      const sum = key => valid.reduce((value, point) => value + point[key], 0);
      const watts = sum('measuredWatts');
      const gross = sum('usdPerDay');
      const profit = sum('profitUsdPerDay');
      const card = make('article', 'fleet-mode');
      card.dataset.mode=field; card.dataset.profitUsd=valid.length?String(profit):''; card.dataset.workers=String(valid.length);
      card.append(make('h2', '', t(title)), make('p', 'fleet-profit', valid.length ? field === 'efficiency' && watts > 0 ? ui.moneyPerKwh(gross / (watts * 24 / 1000)) : money(profit) : '—'),
        make('p', 'muted', t(complete ? 'fleet_complete' : 'fleet_partial',
          {count: valid.length, total})));
      if (field === 'profit' && valid.length) {
        const efficiencyProfit = comparable.reduce((value, row) => value + row.selected.efficiency.profitUsdPerDay, 0);
        card.append(make('p', 'muted', t('profiles_gain_vs_efficiency', {amount: money(profit - efficiencyProfit)})));
      }
      const metrics = make('dl', 'fleet-metrics');
      for (const [label, value] of [
        ['hashrate', valid.length ? ui.hashrate(sum('hashrateHps')) : '—'],
        ['worker_mode_comparison_measured_power', valid.length ? ui.power(watts) : '—'],
        ['worker_mode_comparison_energy_day', valid.length ? `${number(watts * 24 / 1000, 3)} kWh` : '—'],
        ['worker_mode_comparison_cost_day', valid.length ? money(gross - profit) : '—'],
        ['worker_mode_comparison_gross_day', valid.length ? money(gross) : '—'],
        ['worker_mode_comparison_net_day', valid.length ? money(profit) : '—'],
        ['worker_mode_comparison_gross_kwh', valid.length && watts > 0
          ? ui.moneyPerKwh(gross / (watts * 24 / 1000)) : '—']
      ]) {
        const item = make('div'); item.append(make('dt', '', t(label)), make('dd', '', value)); metrics.append(item);
      }
      card.append(metrics); totals.append(card);
    }
    const query = $('fleet-search').value.trim().toLowerCase();
    for (const [index, {agent, worker, selected}] of workers.entries()) {
      if (!`${agent.name} ${worker.model} ${worker.deviceId}`.toLowerCase().includes(query)) continue;
      const card = make('details', 'fleet-worker'); card.open = index === 0 && !query;
      const head = make('summary');
      head.append(make('strong', '', `${agent.name || t('pc_agent')} · ${worker.model || worker.deviceId}`),
        make('span', 'muted', `${worker.deviceId} · ${selected.miners} ${t('profiles_miners')}`));
      card.append(head);
      const rate = agent.points.tariff;
      card.append(make('p', 'muted', rate
        ? t('profiles_tariff', {rate:number(Number(rate.pricePerKwh),4),currency:rate.currency}) : t('fleet_tariff_missing')));
      card.append(window.SolarMinerProfileComparison.cards(selected, minerName)); details.append(card);
    }
    for (const agent of unavailable) {
      const card = make('article', 'fleet-worker');
      card.append(make('h3', '', agent.name || t('pc_agent')),
        make('p', 'muted', t({update_required: 'fleet_agent_update_required',
          profit_model_update_required: 'fleet_agent_profit_model_update_required',
          sharing_disabled: 'fleet_agent_sharing_disabled', warming_up: 'fleet_agent_warming_up',
          unreachable: 'fleet_agent_unreachable'}[agent.reason] || 'fleet_agent_unavailable')));
      details.append(card);
    }
  }

  async function load() {
    if (loading) return;
    loading = true; $('refresh').disabled = true;
    try {
      const [response, catalogResponse] = await Promise.all([
        fetch('/api/agent/local/network/fleet-comparison', {cache: 'no-store'}),
        fetch('/api/agent/local/miner-catalog', {cache: 'no-store'}).catch(() => null)]);
      if (!response.ok) throw new Error('fleet_load_failed');
      agents = await response.json();
      if (catalogResponse?.ok) catalog = await catalogResponse.json();
      const coins = [...new Set(agents.filter(agent => agent.available && agent.points)
        .flatMap(agent => agent.points.workers || [])
        .flatMap(worker => worker.candidates || [])
        .filter(point => point.coin && point.minerSoftwareId && point.hashrateHps > 0
          && point.measuredWatts > 0 && point.usdPerKwh > 0)
        .map(point => point.coin))].sort((a, b) => a.localeCompare(b, prefs.locale));
      const previous = $('fleet-coin').value;
      $('fleet-coin').replaceChildren(...coins.map(coin => {
        const entry = catalog.find(row => row.id === coin)
          || ui.coinCatalog.find(row => row.id === coin);
        return new Option(entry?.algorithm ? `${entry.name} · ${entry.algorithm}` : entry?.name || coin, coin);
      }));
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
  $('fleet-search').addEventListener('input', render);
  $('refresh').addEventListener('click', load);
  document.addEventListener('solarminer:preferences-changed', render);
  load();
})();
