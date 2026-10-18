// Dashboard: readiness first, then measurement, then money.
// Stable dashboard snapshots use REST; only telemetry remains live over SSE. A channel that fails
// drops its derived state instead of keeping dead numbers on screen.
(() => {
  const ui = window.SolarMinerUI;
  const $ = ui.$;
  const t = ui.t;
  const preferences = window.SolarMinerPreferences;
  let overview, telemetry, settings, assessment, energy, agentUi = null, energyError = null, uiRequested = false;
  let networkAgents = null;
  let benchmarkRunning = false, sweepWorkerIds = new Set();
  let activeBenchmark = null, activeSweep = null;
  const openSweepPlans = new Set();
  const pendingNetworkWorkers = new Set();
  const pendingNetworkStarts = new Set();
  let networkActionMessage = '', networkActionError = false;
  let savedAt = null, fresh = true;
  // A persisted overview snapshot may invoke the subscription synchronously. Every value read
  // by that render path must therefore be initialized before any channel is registered.
  let agentName = null, agentNameBusy = false, agentNameState = '', agentNameError = false;

  function renderStatusStrip(data) {
    const strip = $('status-strip');
    strip.replaceChildren();
    const add = (label, value, tone, href) => {
      const item = document.createElement('span');
      item.className = 'strip-item';
      item.append(ui.element('span', '', `${t(label)}: `));
      const pill = ui.statusPill(null, value);
      if (tone) pill.classList.add(tone);
      item.append(pill);
      if (href) { const link = ui.element('a', '', 'open'); link.href = href; item.append(link); }
      strip.append(item);
    };
    const proxy = data.proxy || {};
    add('Agent', !fresh ? 'unavailable_9da3a4' : 'connected_e0e3b1',
      fresh ? 'tone-ok' : 'tone-bad');
    add('mining_connection', !proxy.reachable ? 'disconnected' : proxy.mode === 'standalone' ? 'local_proxy_5d93d2' : 'network_proxy',
      proxy.reachable ? 'tone-ok' : 'tone-bad', '/proxy.html');
    add('Node', assessment?.connected ? 'connected_e0e3b1' : assessment?.connected === false ? 'not_connected' : 'unknown_a4967c',
      assessment?.connected ? 'tone-ok' : '', '/workers.html');
    const workers = (data.stats?.workers || []);
    const errors = workers.filter(worker => worker.miningStatus === 'ERROR').length;
    if (errors) add('notifications', t('count_workers_with_errors', {count: errors}), 'tone-bad', '/workers.html');
  }

  // Phase 5c: the alert rules run once in the backend (AgentStatusService.uiState) and
  // arrive on the "ui" channel; the UI only renders rows. Keys are closed-catalog entries.
  function renderAlerts(agentUi) {
    const list = $('alerts');
    list.replaceChildren();
    for (const alert of agentUi?.alerts || []) {
      const row = ui.element('div', `notice-line ${alert.tone}`);
      const copy = ui.element('div');
      const title = alert.titleKey ? t(alert.titleKey, alert.titleParams || {}) : '';
      const description = alert.descriptionKey ? t(alert.descriptionKey, alert.descriptionParams || {})
        : (alert.descriptionLiteral || '');
      const strong = document.createElement('strong'); strong.textContent = title;
      const detail = document.createElement('div'); detail.textContent = description;
      copy.append(strong, detail);
      row.append(copy);
      if (alert.href) { const link = ui.element('a', '', alert.actionKey); link.href = alert.href; row.append(link); }
      list.append(row);
    }
  }

  function dashboardWorkers(data) {
    const profitPerDay = (row, settings, localFallback = false) => {
      const tariffSettings = settings || (localFallback ? energy?.settings : null);
      if (!tariffSettings || !(Number(row.watts) > 0)) return null;
      const forecast = Number(row.usdPerDay);
      const tariffUsd = preferences.convert(tariffSettings.pricePerKwh, tariffSettings.currency, 'USD');
      if (!(forecast > 0) || !Number.isFinite(tariffUsd)) return null;
      return {value: forecast - Number(row.watts) * 24 / 1000 * tariffUsd,
        usedLocalTariff: !settings && localFallback};
    };
    const localRows = ui.operatingRows(ui.workerRows(data, telemetry)).map(row => {
      const forecast = (data.earnings || []).find(item => item.coin === row.coin && item.available);
      const usdPerDay = ui.workerUsdPerDay(row.hashrateHps, forecast);
      const profitEstimate = profitPerDay({ ...row, usdPerDay }, energy?.settings);
      return {...row, status: row.status === 'MINING' ? sweepWorkerIds.has(row.deviceId) ? 'POWER_SWEEP' : benchmarkRunning ? 'BENCHMARKING' : 'MINING' : row.status,
        agent: agentName || t('this_pc_agent'), local: true, usdPerDay,
        profitUsdPerDay: profitEstimate?.value ?? null, usedLocalTariff: false,
        revenuePerKwh: ui.revenuePerKwh(usdPerDay, row.watts)};
    });
    const remoteRows = (networkAgents?.agents || []).filter(agent => !agent.own && agent.reachable)
      .flatMap(agent => (agent.workers || []).map(row => {
        // Current peers publish coin/available explicitly. For older peers, infer the coin from
        // the closed algorithm catalog and treat their already-filtered earnings rows as available.
        const coin = row.coin || ui.coinIdForAlgorithm(row.algorithm);
        const rawForecast = (agent.earnings || []).find(item => item.coin === coin);
        const forecast = rawForecast ? {...rawForecast, available: rawForecast.available !== false} : null;
        const usdPerDay = ui.workerUsdPerDay(Number(row.hashrateHps), forecast);
        const profitEstimate = profitPerDay({ ...row, usdPerDay }, agent.energy?.settings, true);
        return {...row, coin, status: row.status === 'MINING' ? row.sweeping === true ? 'POWER_SWEEP' : row.benchmarking === true ? 'BENCHMARKING' : 'MINING' : row.status,
          agent: agent.name || t('pc_agent'), agentId: agent.agentId,
          dashboardUrl: peerDashboardUrl(agent),
          local: false, controlEnabled: Boolean(agent.controlEnabled), usdPerDay,
          profitUsdPerDay: profitEstimate?.value ?? null, usedLocalTariff: profitEstimate?.usedLocalTariff === true,
          revenuePerKwh: ui.revenuePerKwh(usdPerDay, Number(row.watts))};
      }));
    return [...localRows, ...remoteRows];
  }

  function peerDashboardUrl(agent) {
    const host = String(agent.host || '');
    const port = Number(agent.dashboardPort);
    if (!/^[a-zA-Z0-9.:-]+$/.test(host) || !Number.isInteger(port) || port < 1 || port > 65535) return null;
    const address = host.includes(':') ? `[${host}]` : host;
    try {
      const url = new URL(`http://${address}:${port}/`);
      return url.protocol === 'http:' ? url.href : null;
    } catch (_) { return null; }
  }

  function workerNameCell(row) {
    const name = ui.shortWorkerName(row);
    if (!row.dashboardUrl) return ui.cellLines(name, `${row.hardwareType || '—'} · ${row.hardwareModel || row.algorithm || '—'}`);
    const cell = ui.cellLines('', `${row.hardwareType || '—'} · ${row.hardwareModel || row.algorithm || '—'}`);
    const link = document.createElement('a');
    link.className = 'worker-dashboard-link';
    link.textContent = name;
    link.href = row.dashboardUrl;
    link.setAttribute('aria-label', t('open_worker_agent_dashboard', {worker: name, agent: row.agent}));
    cell.querySelector('strong').replaceWith(link);
    return cell;
  }

  function renderDashboardWorkers(data) {
    const root = $('network-worker-list');
    const kpis = $('network-worker-kpis');
    const note = $('network-workers-note');
    const rows = dashboardWorkers(data);
    const activeRows = rows.filter(row => row.status === 'MINING' || row.status === 'BENCHMARKING' || row.status === 'POWER_SWEEP' || row.status === 'ERROR');
    const totalWatts = activeRows.reduce((sum, row) => sum + (Number(row.watts) || 0), 0);
    const totalUsdPerDay = activeRows.reduce((sum, row) => sum + (Number(row.usdPerDay) || 0), 0);
    const miningRows = activeRows.filter(row => ['MINING', 'BENCHMARKING', 'POWER_SWEEP'].includes(row.status));
    const profitIsComplete = miningRows.length > 0 && miningRows.every(row => Number.isFinite(row.profitUsdPerDay));
    const totalProfitPerDay = profitIsComplete
      ? miningRows.reduce((sum, row) => sum + row.profitUsdPerDay, 0) : null;
    const localTariffFallbackCount = miningRows.filter(row => row.usedLocalTariff).length;
    const efficiencies = activeRows.map(row => row.revenuePerKwh).filter(Number.isFinite);
    const averageRevenuePerKwh = efficiencies.length
      ? efficiencies.reduce((sum, value) => sum + value, 0) / efficiencies.length : null;
    kpis.replaceChildren(
      ui.metric({label: 'active_workers', value: String(activeRows.length), detail: t('local_and_shared_workers')}),
      ui.metric({label: 'total_power', value: ui.power(totalWatts), detail: t('sum_of_running_workers')}),
      ui.metric({label: 'earnings', value: totalUsdPerDay > 0 ? t('value_day', {value: preferences.moneyFromUsd(totalUsdPerDay)}) : '—', detail: t('sum_of_running_workers')}),
      ui.metric({label: 'profit_per_day', value: totalProfitPerDay == null ? '—' : `≈ ${t('value_day', {value: preferences.moneyFromUsd(totalProfitPerDay)})}`, detail: localTariffFallbackCount
        ? t('local_tariff_approximation_count', {count: localTariffFallbackCount}) : t('estimated_profit_after_electricity')}),
      ui.metric({label: 'average_earnings_per_kwh', value: ui.moneyPerKwh(averageRevenuePerKwh), detail: t('average_of_workers_with_data')}));
    root.replaceChildren(ui.table({
      columns: [
        {label: 'worker', render: workerNameCell},
        {label: 'pc_agent', render: row => ui.cellLines(row.agent, row.local ? t('this_pc')
          : t(row.controlEnabled ? 'lan_control' : 'view_only'))},
        {label: 'status', render: row => ui.statusPill(
          pendingNetworkStarts.has(`${row.agentId}:${row.deviceId}`) && row.status !== 'MINING' ? 'STARTING' : row.status)},
        {label: 'hashrate', align: 'right', render: row => ui.hashrate(Number(row.hashrateHps))},
        {label: 'power_4606f4', align: 'right', render: row => ui.power(Number(row.watts))},
        {label: 'earnings', align: 'right', render: row => row.usdPerDay != null ? t('value_day', {value: preferences.moneyFromUsd(row.usdPerDay)}) : '—'},
        {label: 'estimated_profit_24h', align: 'right', render: row => {
          if (row.profitUsdPerDay == null) return '—';
          const cell = ui.cellLines(`≈ ${t('value_day', {value: preferences.moneyFromUsd(row.profitUsdPerDay)})}`,
            row.usedLocalTariff ? t('local_tariff_approximation') : '');
          return cell;
        }},
        {label: 'earnings_per_kwh', align: 'right', render: row => ui.moneyPerKwh(row.revenuePerKwh)},
        {label: 'action', align: 'right', render: row => {
          if (row.local) return null;
          const starting = row.status === 'STARTING' || pendingNetworkStarts.has(`${row.agentId}:${row.deviceId}`);
          const active = row.status === 'MINING' || row.status === 'BENCHMARKING' || row.status === 'POWER_SWEEP' || row.status === 'ERROR';
          const key = `${row.agentId}:${row.deviceId}`;
          const button = ui.element('button', `button ${active ? 'subtle' : 'primary'}`, starting ? 'miner_starting' : active ? 'pause' : 'start');
          button.type = 'button';
          button.disabled = !row.controlEnabled || row.controlAllowed !== true || !row.deviceId || pendingNetworkWorkers.has(key) || starting
            || !active && (row.configured === false || row.minerInstalled === false);
          button.title = !row.controlEnabled ? t('peer_worker_control_disabled') : '';
          button.addEventListener('click', event => {
            event.stopPropagation();
            void controlNetworkWorker(row, !active);
          });
          return button;
        }}
      ], rows, empty: 'no_local_or_shared_workers_available'
    }));
    const peerErrors = (networkAgents?.agents || []).filter(agent => !agent.own && agent.error);
    note.classList.toggle('error', networkActionError);
    if (networkActionMessage) note.textContent = networkActionMessage;
    else if (!networkAgents) note.textContent = t('searching_the_local_network_for_pc_agents');
    else if (peerErrors.length) note.textContent = t('some_pc_agents_are_not_sharing_live_data', {count: peerErrors.length});
    else if (!(networkAgents.agents || []).some(agent => !agent.own)) note.textContent = t('no_other_pc_agents_found_searching_the_local');
    else note.textContent = t('remote_workers_are_read_only');
  }

  function measurementCard(model, algorithm, agent = null) {
    const card = ui.element('article', 'dashboard-gpu-measurement-card');
    card.append(ui.element('strong', '', `${model} · ${algorithm}`));
    if (agent) {
      const owner = ui.element('p', 'dashboard-gpu-measurement-agent');
      owner.append(ui.element('span', '', `${t('pc_agent')}: `));
      const dashboard = peerDashboardUrl(agent);
      if (dashboard) {
        const link = ui.element('a', '', agent.name);
        link.href = dashboard;
        link.setAttribute('aria-label', `${t('open_agent_dashboard')} ${agent.name}`);
        owner.append(link);
      } else owner.append(ui.element('span', '', agent.name));
      card.append(owner);
    }
    return card;
  }

  function sampleProgress(card, count, required, label) {
    const progress = document.createElement('progress');
    progress.max = Math.max(1, Number(required) || 1);
    progress.value = Math.min(progress.max, Math.max(0, Number(count) || 0));
    progress.setAttribute('aria-label', t(label));
    card.append(progress);
  }

  function sweepPlan(run, key) {
    const limits = run.plannedLimits || [];
    if (!limits.length) return null;
    const details = ui.element('details', 'sweep-step-details');
    details.open = openSweepPlans.has(key);
    details.addEventListener('toggle', () => {
      if (details.open) openSweepPlans.add(key);
      else openSweepPlans.delete(key);
    });
    details.append(ui.element('summary', '', t('power_steps_count', {count: limits.length})));
    const completed = new Map((run.steps || []).map(step => [step.limitWatts, step]));
    const list = ui.element('ol', 'sweep-step-list');
    for (const limit of limits) {
      const step = completed.get(limit);
      const item = ui.element('li', `sweep-step ${step ? step.stable ? 'stable' : 'unstable' : 'planned'}`);
      item.append(ui.element('strong', '', `${limit} W`),
        ui.element('span', 'sweep-step-status', t(step ? step.stable ? 'stable' : 'unstable' : 'planned')));
      if (step?.medianHashrateHs > 0) item.append(ui.element('span', '', ui.hashrate(step.medianHashrateHs)));
      if (step?.avgPowerWatts > 0) item.append(ui.element('span', '', `${t('measured_power')}: ${Math.round(step.avgPowerWatts)} W`));
      if (step?.note) item.append(ui.element('small', '', ui.s(step.note)));
      list.append(item);
    }
    details.append(list);
    return details;
  }

  function renderGpuMeasurements() {
    const section = $('dashboard-gpu-measurements');
    const cards = $('dashboard-gpu-measurement-cards');
    const next = [];
    const appendSweep = (run, agent = null) => {
      const card = measurementCard(run.model, run.algorithm, agent);
      const required = run.samplesRequired ?? 12;
      const identity = run.mode === 'FULL' ? 'full_device_curve' : 'parallel_device_validation';
      const group = run.mode === 'FULL' && Number.isInteger(run.referenceBatch)
        ? ` · ${t('start_group_number', {number: run.referenceBatch + 1})}` : '';
      card.append(ui.element('p', 'muted', `${run.coin} · ${t(identity)}${group}`));
      if (run.detail) card.append(ui.element('p', '', ui.s(run.detail)));
      card.append(ui.element('p', 'muted', t('watts_w_samples_required_samples',
        {watts: run.limitWatts ?? '—', samples: run.samples ?? 0, required})));
      sampleProgress(card, run.samples, required, 'power_limit_sample_progress');
      card.append(ui.element('p', 'muted sweep-sample-stage', Number(run.samples) > 0
        ? t('collecting_power_limit_samples', {samples: run.samples, required})
        : t('waiting_for_first_power_limit_sample')));
      const plan = sweepPlan(run, `${agent?.agentId || 'local'}|${run.id}`);
      if (plan) card.append(plan);
      card.append(ui.element('span', 'tag ready', 'running'));
      next.push(card);
    };
    const appendBenchmark = (progress, agent = null) => {
      const count = progress.samples || 0;
      const required = progress.samplesRequired || 12;
      const card = measurementCard(progress.model, progress.algorithm, agent);
      card.append(ui.element('p', 'muted', t('benchmark_live_points', {count, required})));
      sampleProgress(card, count, required, 'dashboard_benchmark_sample_progress');
      card.append(ui.element('p', 'muted sweep-sample-stage', count > 0
        ? `${t('hashrate')}: ${ui.hashrate(progress.hashrateHs)} · ${t('power_4606f4')}: ${ui.power(progress.powerWatts)}`
        : t('benchmark_live_waiting')));
      card.append(ui.element('span', 'tag ready', 'benchmarking'));
      next.push(card);
    };
    for (const run of activeSweep?.runs || []) {
      if (run.status === 'RUNNING') appendSweep(run);
    }
    if (activeBenchmark?.running) {
      const gpuWorkers = new Map((overview?.stats?.workers || [])
        .filter(worker => worker.hardwareType === 'GPU').map(worker => [worker.deviceId, worker]));
      const traces = (activeBenchmark.liveSeries || []).filter(trace => trace.hardwareType === 'GPU');
      const ids = new Set([...(activeBenchmark.selectedWorkers || []).filter(id => gpuWorkers.has(id)),
        ...traces.map(trace => trace.deviceId)]);
      for (const id of ids) {
        const trace = traces.filter(item => item.deviceId === id).at(-1);
        const worker = gpuWorkers.get(id);
        const latest = trace?.points?.at(-1);
        appendBenchmark({model: trace?.hardwareModel || worker?.hardwareModel || id,
          algorithm: trace?.algorithm || worker?.currentAlgorithm || '—',
          samples: trace?.points?.length || 0, samplesRequired: 12,
          hashrateHs: latest?.hashrateHs, powerWatts: latest?.powerWatts});
      }
    }
    for (const agent of networkAgents?.agents || []) {
      if (agent.own || !agent.reachable) continue;
      for (const run of agent.activeSweeps || []) appendSweep(run, agent);
      for (const progress of agent.activeBenchmarks || []) appendBenchmark(progress, agent);
    }
    cards.replaceChildren(...next);
    section.hidden = !next.length;
  }

  async function controlNetworkWorker(row, enabled) {
    const key = `${row.agentId}:${row.deviceId}`;
    if (pendingNetworkWorkers.has(key)) return;
    pendingNetworkWorkers.add(key);
    if (enabled) pendingNetworkStarts.add(key);
    networkActionMessage = t(enabled ? 'starting_remote_worker' : 'pausing_remote_worker', {worker: row.name});
    networkActionError = false;
    if (overview) renderDashboardWorkers(overview);
    try {
      const response = await fetch(`/api/agent/local/network/agents/${encodeURIComponent(row.agentId)}/workers/${encodeURIComponent(row.deviceId)}?enabled=${enabled}`, {method: 'POST'});
      const result = await response.json().catch(() => null);
      if (!response.ok || !result?.applied) throw new Error(result?.error || 'peer_worker_action_failed');
      networkActionMessage = t(enabled ? 'remote_worker_started' : 'remote_worker_paused', {worker: row.name});
      await window.SolarMinerLive.refresh('network-agents');
    } catch (error) {
      networkActionMessage = t('remote_worker_action_failed_error', {error: t(error.message)});
      networkActionError = true;
    } finally {
      pendingNetworkWorkers.delete(key);
      pendingNetworkStarts.delete(key);
      if (overview) renderDashboardWorkers(overview);
      window.setTimeout(() => {
        networkActionMessage = ''; networkActionError = false;
        if (overview) renderDashboardWorkers(overview);
      }, 5000);
    }
  }

  function renderEnergy() {
    const root = $('energy-kpis');
    $('energy-settings').disabled = !energy;
    if (!energy) {
      $('energy-tariff-value').textContent = '—';
      root.replaceChildren(ui.element('div', energyError ? 'notice-line warn' : 'skeleton',
        energyError ? 'energy_data_could_not_be_loaded' : undefined));
      $('energy-note').textContent = energyError ? `${t('energy_api')} ${t(energyError)}` : '';
      return;
    }
    const peerEnergy = (networkAgents?.agents || [])
      .filter(agent => !agent.own && agent.reachable && agent.energy)
      .map(agent => agent.energy);
    const sources = [{settings: energy.settings, today: energy.today, last7Days: energy.last7Days}, ...peerEnergy];
    const aggregate = period => {
      const values = sources.map(source => source[period]).filter(Boolean);
      const costs = sources.map(source => source[period]
        ? preferences.convert(source[period].cost, source.settings?.currency) : null);
      const runtimeSeconds = values.reduce((sum, value) => sum + Number(value.runtimeSeconds || 0), 0);
      const measuredSeconds = values.reduce((sum, value) => sum + Number(value.measuredSeconds || 0), 0);
      return {
        kilowattHours: values.reduce((sum, value) => sum + Number(value.kilowattHours || 0), 0),
        cost: costs.every(Number.isFinite) ? costs.reduce((sum, value) => sum + value, 0) : null,
        measurementCoverage: runtimeSeconds > 0 ? Math.min(1, measuredSeconds / runtimeSeconds) : 0
      };
    };
    const today = aggregate('today');
    const last7Days = aggregate('last7Days');
    const activeWh = (energy.activeSessions || []).reduce((sum, session) => sum + Number(session.wattHours || 0), 0)
      + peerEnergy.reduce((sum, value) => sum + Number(value.activeWattHours || 0), 0);
    const activeSeconds = Math.max(
      (energy.activeSessions || []).reduce((maximum, session) => Math.max(maximum, Number(session.runtimeSeconds || 0)), 0),
      ...peerEnergy.map(value => Number(value.activeRuntimeSeconds || 0)), 0);
    const activeCount = energy.activeSessions.length
      + peerEnergy.reduce((sum, value) => sum + Number(value.activeSessionCount || 0), 0);
    const money = value => value == null ? '—' : preferences.money(value, preferences.currency);
    const tariff = preferences.convert(energy.settings.pricePerKwh, energy.settings.currency);
    const tariffCurrency = tariff == null ? energy.settings.currency : preferences.currency;
    $('energy-tariff-value').textContent = `${Number(energy.settings.pricePerKwh)
      .toLocaleString(preferences.locale, {minimumFractionDigits: 2, maximumFractionDigits: 4})} ${energy.settings.currency}/kWh`;
    root.replaceChildren(
      ui.metric({label: 'running_sessions', value: ui.kilowattHours(activeWh), detail: t('count_workers_duration', {count: activeCount, duration: ui.duration(activeSeconds)}), tone: activeCount ? 'ok' : ''}),
      ui.metric({label: 'today_1ab330', value: ui.kilowattHours(today.kilowattHours * 1000), detail: `${money(today.cost)} ${t('electricity_cost')}`}),
      ui.metric({label: '7_days', value: ui.kilowattHours(last7Days.kilowattHours * 1000, 2), detail: `${money(last7Days.cost)} ${t('electricity_cost')}`}),
      ui.metric({label: 'measurement_coverage', value: `${Math.round(today.measurementCoverage * 100)} %`, detail: t('missing_sensor_intervals_are_not_estimated')})
    );
    $('energy-note').textContent = peerEnergy.length
      ? t('network_energy_uses_each_agents_tariff', {count: peerEnergy.length})
      : t('component_consumption_from_available_cpu_pac',
        {tariff: Number(tariff == null ? energy.settings.pricePerKwh : tariff).toLocaleString(preferences.locale, {maximumFractionDigits: 4}), currency: tariffCurrency});
  }

  async function editEnergySettings() {
    if (!energy) return;
    const converted = preferences.convert(energy.settings.pricePerKwh, energy.settings.currency);
    const editCurrency = converted == null ? energy.settings.currency : preferences.currency;
    const value = prompt(t('electricity_rate_in_currency_per_kwh', {currency: editCurrency}), converted == null ? energy.settings.pricePerKwh : converted);
    if (value == null) return;
    const price = Number(String(value).replace(',', '.'));
    if (!Number.isFinite(price) || price < 0) return alert(t('enter_a_valid_positive_electricity_rate'));
    try {
      energy.settings = await ui.postJson('/api/agent/local/economics/settings', {pricePerKwh: price, currency: editCurrency});
      renderEnergy();
      if (overview) renderDashboardWorkers(overview);
      window.SolarMinerLive.refresh('energy');
    } catch (_) { alert(t('could_not_save_the_electricity_rate')); }
  }

  function render(data, isFresh = fresh, reconcilingSnapshot = false) {
    overview = data;
    fresh = isFresh;
    renderStatusStrip(data);
    renderAlerts(agentUi);
    renderDashboardWorkers(data);
    renderGpuMeasurements();
    renderEnergy();
    // A cached tab snapshot is deliberately painted before the live read. Its short
    // overview TTL may already have elapsed during normal page navigation; that is a
    // loading transition, not an outage. Keep the previous dashboard visible while the
    // bundled first paint replaces it, but retain the offline warning for real failures.
    ui.setConnection(reconcilingSnapshot ? 'connecting' : fresh ? 'online' : 'offline');
    ui.setUpdated(savedAt);
    if (!fresh && !reconcilingSnapshot) ui.notice('last_known_state_checking_current_data', {error: true, kind: 'connection'});
    else ui.clearNotice('connection');
  }

  // ------------------------------------------------------------------ live channels

  window.SolarMinerLive.channel('overview',
    {endpoint: '/api/agent/local/overview', maxAgeMs: 12000, transport: 'rest', pollMs: 10000})
    .subscribe((data, meta) => {
      if (!data) { fresh = false; if (overview) render(overview, false); return; }
      savedAt = Date.now();
      window.SolarMinerMiningCache?.write(data);
      const reconcilingSnapshot = meta.source === 'snapshot' && meta.stale && !meta.error;
      render(data, reconcilingSnapshot || !meta.stale, reconcilingSnapshot);
      // Alert derivation reads the overview again in the backend. It is useful secondary UI,
      // but must never compete with the first overview REST response on the critical path.
      if (!uiRequested && meta.source !== 'snapshot') {
        uiRequested = true;
        window.setTimeout(() => window.SolarMinerLive.refresh('ui'), 0);
      }
    });
  window.SolarMinerLive.channel('settings', {endpoint: '/api/agent/local/power-control/settings', maxAgeMs: 30000, transport: 'rest', pollMs: 30000})
    .subscribe((data, meta) => { settings = meta.error ? null : data; if (overview) render(overview); });
  window.SolarMinerLive.channel('node-assessment', {endpoint: '/api/agent/local/node-assessment', maxAgeMs: 30000, transport: 'rest', pollMs: 30000})
    .subscribe((data, meta) => { assessment = meta.error ? null : data; if (overview) render(overview); });
  window.SolarMinerLive.channel('telemetry', {endpoint: '/api/agent/local/telemetry', maxAgeMs: 8000})
    .subscribe((data, meta) => { telemetry = meta.error ? null : data; if (overview) render(overview); });
  window.SolarMinerLive.channel('energy', {endpoint: '/api/agent/local/economics', maxAgeMs: 30000, transport: 'rest', pollMs: 30000})
    .subscribe((data, meta) => {
      if (meta.error) {
        energy = null;
        energyError = meta.error.name === 'TimeoutError' ? 'request_timed_out'
          : /404/.test(meta.error.message) ? 'http_404_restart_or_update_the_pc_agent'
            : 'connection_failed';
      } else { energy = data; energyError = null; }
      renderEnergy();
      if (overview) renderDashboardWorkers(overview);
    });
  window.SolarMinerLive.channel('ui', {endpoint: '/api/agent/local/status/ui', maxAgeMs: 30000, transport: 'rest', initial: false})
    .subscribe((data, meta) => { agentUi = meta.error ? null : data; if (overview) renderAlerts(agentUi); });
  window.SolarMinerLive.channel('network-agents',
    {endpoint: '/api/agent/local/network/agents', maxAgeMs: 10000, transport: 'rest', pollMs: 5000})
    .subscribe((data, meta) => {
      networkAgents = meta.error ? null : data;
      if (overview) { renderDashboardWorkers(overview); renderEnergy(); }
      renderGpuMeasurements();
    });
  window.SolarMinerLive.channel('benchmarks',
    {endpoint: '/api/agent/local/benchmarks', maxAgeMs: 8000, persist: false})
    .subscribe((data, meta) => {
      activeBenchmark = !meta.error && !meta.stale && data?.running === true ? data : null;
      benchmarkRunning = Boolean(activeBenchmark);
      if (overview) renderDashboardWorkers(overview);
      renderGpuMeasurements();
    });
  window.SolarMinerLive.channel('efficiency',
    {endpoint: '/api/agent/local/efficiency', maxAgeMs: 8000, persist: false})
    .subscribe((data, meta) => {
      activeSweep = !meta.error && !meta.stale && data?.running === true ? data : null;
      sweepWorkerIds = activeSweep
        ? new Set((activeSweep.runs || []).filter(run => run.status === 'RUNNING').map(run => run.deviceId)) : new Set();
      if (overview) renderDashboardWorkers(overview);
      renderGpuMeasurements();
    });

  // ------------------------------------------------------------------ agent identity

  function renderAgentName() {
    const input = $('agent-name-input');
    // A live re-render must never overwrite what the operator is typing.
    if (!agentNameBusy && document.activeElement !== input) input.value = agentName || '';
    $('agent-name-save').disabled = agentNameBusy;
    const note = $('agent-name-state');
    note.textContent = agentNameState ? t(agentNameState) : '';
    note.classList.toggle('error', agentNameError);
  }
  async function loadAgentName() {
    try {
      agentName = (await ui.getJson('/api/agent/local/power-control/identity')).name || null;
      renderAgentName();
      if (overview) renderDashboardWorkers(overview);
    } catch (_) { /* The placeholder keeps the product default visible until the agent answers. */ }
  }
  $('agent-name-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (agentNameBusy) return;
    agentNameBusy = true; agentNameError = false; agentNameState = 'saving'; renderAgentName();
    try {
      const identity = await ui.postJson('/api/agent/local/power-control/identity', {name: $('agent-name-input').value.trim()});
      agentName = identity?.name || null;
      window.SolarMinerAgentIdentity?.apply(agentName);
      agentNameState = agentName ? 'saved_the_solarminer_node_shows_this_name'
        : 'name_removed_the_node_shows_solarminer_pc_ag';
      if (overview) renderDashboardWorkers(overview);
    } catch (error) { agentNameState = error.message; agentNameError = true; }
    finally { agentNameBusy = false; renderAgentName(); }
  });

  // ------------------------------------------------------------------ wiring

  $('refresh').addEventListener('click', () => window.SolarMinerLive.refreshAll());
  $('energy-settings').addEventListener('click', editEnergySettings);
  document.addEventListener('solarminer:preferences-changed', () => { if (overview) render(overview); });

  const cached = window.SolarMinerMiningCache?.read();
  if (cached) { savedAt = cached.savedAt; render(cached.overview, false); }
  loadAgentName();
})();
