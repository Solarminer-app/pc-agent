// Dashboard: readiness first, then measurement, then money. One aggregate, pushed over SSE.
// No polling loop: overview/settings/node-assessment/telemetry/energy channels push, and the
// live-data watchdog re-reads a channel only when its push went silent. A channel that fails
// drops its derived state instead of keeping dead numbers on screen.
(() => {
  const ui = window.SolarMinerUI;
  const $ = ui.$;
  const t = ui.t;
  const preferences = window.SolarMinerPreferences;
  let overview, telemetry, settings, assessment, energy, agentUi = null, energyError = null, uiRequested = false;
  let networkAgents = null;
  let savedAt = null, fresh = true;

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
    const localRows = ui.operatingRows(ui.workerRows(data, telemetry)).map(row => {
      const forecast = (data.earnings || []).find(item => item.coin === row.coin && item.available);
      const usdPerDay = ui.workerUsdPerDay(row.hashrateHps, forecast);
      return {...row, agent: agentName || t('this_pc_agent'), local: true, usdPerDay,
        revenuePerKwh: ui.revenuePerKwh(usdPerDay, row.watts)};
    });
    const remoteRows = (networkAgents?.agents || []).filter(agent => !agent.own && agent.reachable)
      .flatMap(agent => (agent.workers || []).filter(row => row.status === 'MINING' || row.status === 'ERROR').map(row => {
        const forecast = (agent.earnings || []).find(item => item.coin === row.coin);
        const usdPerDay = ui.workerUsdPerDay(Number(row.hashrateHps), forecast);
        return {...row, agent: agent.name || t('pc_agent'), local: false, usdPerDay,
          revenuePerKwh: ui.revenuePerKwh(usdPerDay, Number(row.watts))};
      }));
    return [...localRows, ...remoteRows];
  }

  function renderDashboardWorkers(data) {
    const root = $('network-worker-list');
    const kpis = $('network-worker-kpis');
    const note = $('network-workers-note');
    const rows = dashboardWorkers(data);
    const totalWatts = rows.reduce((sum, row) => sum + (Number(row.watts) || 0), 0);
    const totalUsdPerDay = rows.reduce((sum, row) => sum + (Number(row.usdPerDay) || 0), 0);
    const efficiencies = rows.map(row => row.revenuePerKwh).filter(Number.isFinite);
    const averageRevenuePerKwh = efficiencies.length
      ? efficiencies.reduce((sum, value) => sum + value, 0) / efficiencies.length : null;
    kpis.replaceChildren(
      ui.metric({label: 'active_workers', value: String(rows.length), detail: t('local_and_shared_workers')}),
      ui.metric({label: 'total_power', value: ui.power(totalWatts), detail: t('sum_of_running_workers')}),
      ui.metric({label: 'earnings', value: totalUsdPerDay > 0 ? t('value_day', {value: preferences.moneyFromUsd(totalUsdPerDay)}) : '—', detail: t('sum_of_running_workers')}),
      ui.metric({label: 'average_earnings_per_kwh', value: ui.moneyPerKwh(averageRevenuePerKwh), detail: t('average_of_workers_with_data')}));
    root.replaceChildren(ui.table({
      columns: [
        {label: 'worker', render: row => ui.cellLines(ui.shortWorkerName(row), `${row.hardwareType || '—'} · ${row.hardwareModel || row.algorithm || '—'}`)},
        {label: 'pc_agent', render: row => ui.cellLines(row.agent, row.local ? t('this_pc') : t('read_only'))},
        {label: 'status', render: row => ui.statusPill(row.status)},
        {label: 'hashrate', align: 'right', render: row => ui.hashrate(Number(row.hashrateHps))},
        {label: 'power_4606f4', align: 'right', render: row => ui.power(Number(row.watts))},
        {label: 'earnings', align: 'right', render: row => row.usdPerDay != null ? t('value_day', {value: preferences.moneyFromUsd(row.usdPerDay)}) : '—'},
        {label: 'earnings_per_kwh', align: 'right', render: row => ui.moneyPerKwh(row.revenuePerKwh)}
      ], rows, empty: 'no_worker_is_running_configure_hardware_in_w'
    }));
    const peerErrors = (networkAgents?.agents || []).filter(agent => !agent.own && agent.error);
    if (!networkAgents) note.textContent = t('searching_the_local_network_for_pc_agents');
    else if (peerErrors.length) note.textContent = t('some_pc_agents_are_not_sharing_live_data', {count: peerErrors.length});
    else if (!(networkAgents.agents || []).some(agent => !agent.own)) note.textContent = t('no_other_pc_agents_found_searching_the_local');
    else note.textContent = t('remote_workers_are_read_only');
  }

  function renderEnergy() {
    const root = $('energy-kpis');
    $('energy-settings').disabled = !energy;
    if (!energy) {
      root.replaceChildren(ui.element('div', energyError ? 'notice-line warn' : 'skeleton',
        energyError ? 'energy_data_could_not_be_loaded' : undefined));
      $('energy-note').textContent = energyError ? `${t('energy_api')} ${t(energyError)}` : '';
      return;
    }
    const activeWh = (energy.activeSessions || []).reduce((sum, session) => sum + Number(session.wattHours || 0), 0);
    const activeSeconds = (energy.activeSessions || []).reduce((sum, session) => Math.max(sum, Number(session.runtimeSeconds || 0)), 0);
    const money = value => preferences.money(value, energy.settings.currency);
    const tariff = preferences.convert(energy.settings.pricePerKwh, energy.settings.currency);
    const tariffCurrency = tariff == null ? energy.settings.currency : preferences.currency;
    root.replaceChildren(
      ui.metric({label: 'running_sessions', value: ui.kilowattHours(activeWh), detail: t('count_workers_duration', {count: energy.activeSessions.length, duration: ui.duration(activeSeconds)}), tone: energy.activeSessions.length ? 'ok' : ''}),
      ui.metric({label: 'today_1ab330', value: ui.kilowattHours(Number(energy.today.kilowattHours) * 1000), detail: `${money(energy.today.cost)} ${t('electricity_cost')}`}),
      ui.metric({label: '7_days', value: ui.kilowattHours(Number(energy.last7Days.kilowattHours) * 1000, 2), detail: `${money(energy.last7Days.cost)} ${t('electricity_cost')}`}),
      ui.metric({label: 'measurement_coverage', value: `${Math.round(energy.today.measurementCoverage * 100)} %`, detail: t('missing_sensor_intervals_are_not_estimated')})
    );
    $('energy-note').textContent = t('component_consumption_from_available_cpu_pac',
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
      window.SolarMinerLive.refresh('energy');
    } catch (_) { alert(t('could_not_save_the_electricity_rate')); }
  }

  function render(data, isFresh = fresh, reconcilingSnapshot = false) {
    overview = data;
    fresh = isFresh;
    renderStatusStrip(data);
    renderAlerts(agentUi);
    renderDashboardWorkers(data);
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
    });
  window.SolarMinerLive.channel('ui', {endpoint: '/api/agent/local/status/ui', maxAgeMs: 30000, transport: 'rest', initial: false})
    .subscribe((data, meta) => { agentUi = meta.error ? null : data; if (overview) renderAlerts(agentUi); });
  window.SolarMinerLive.channel('network-agents',
    {endpoint: '/api/agent/local/network/agents', maxAgeMs: 30000, transport: 'rest', pollMs: 30000})
    .subscribe((data, meta) => {
      networkAgents = meta.error ? null : data;
      if (overview) renderDashboardWorkers(overview);
    });

  // ------------------------------------------------------------------ agent identity

  let agentName = null, agentNameBusy = false, agentNameState = '', agentNameError = false;
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
