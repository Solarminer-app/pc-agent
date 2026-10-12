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

  function renderLiveWorkers(data) {
    const grid = $('live-worker-grid');
    const rows = ui.operatingRows(ui.workerRows(data, telemetry));
    const poolTargets = Object.fromEntries(ui.poolRows(data).map(row => [row.coin, row.poolUrl]));
    const coinHashrates = Object.fromEntries((data.coins || []).map(coin => [coin.id,
      rows.filter(row => row.coin === coin.id).reduce((sum, row) => sum + row.hashrateHps, 0)]));
    grid.replaceChildren(...rows.map(row => {
      const card = ui.element('article', `live-worker-card ${row.status === 'ERROR' ? 'error' : ''}`);
      const head = ui.element('div', 'live-worker-head');
      const identity = ui.element('div');
      identity.append(ui.element('strong', '', ui.shortWorkerName(row)), ui.element('span', '', `${row.coinName} · ${row.algorithm}`));
      head.append(identity, ui.statusPill(row.status)); card.append(head);
      const forecast = (data.earnings || []).find(item => item.coin === row.coin && item.available);
      const ratio = forecast && coinHashrates[row.coin] > 0 ? row.hashrateHps / coinHashrates[row.coin] : 0;
      const usdPerDay = forecast ? forecast.usdPerDay * ratio : null;
      const metrics = ui.element('div', 'live-worker-metrics');
      for (const [label, value] of [
        ['hashrate', ui.hashrate(row.hashrateHps)], ['power_4606f4', row.watts ? ui.power(row.watts) : '—'],
        ['temperature', row.temperature.value != null ? ui.temperature(row.temperature.value) : '—'],
        ['earnings', usdPerDay != null ? t('value_day', {value: preferences.moneyFromUsd(usdPerDay)}) : '—'],
        ['earnings_per_kwh', ui.moneyPerKwh(ui.revenuePerKwh(usdPerDay, row.watts))]]) {
        const item = ui.element('div'); item.append(ui.element('span', '', label), ui.element('strong', '', value)); metrics.append(item);
      }
      card.append(metrics);
      const targetPool = poolTargets[row.coin] || row.pools?.[0];
      const pool = ui.element('p', 'live-worker-pool', targetPool ? `${targetPool} · ${t('latency')} ${ui.latency(row.latency)}` : t('no_pool_reported'));
      const session = energy?.activeSessions?.find(item => item.deviceId === row.deviceId);
      if (session) pool.append(ui.element('span', '', ` · ${t('session')} ${ui.kilowattHours(Number(session.wattHours))}`));
      const link = ui.element('a', '', 'open_worker'); link.href = '/workers.html'; card.append(pool, link); return card;
    }));
    if (!rows.length) grid.append(ui.element('div', 'notice-line warn', 'no_worker_is_running_configure_hardware_in_w'));
  }

  function renderNetworkAgents() {
    const grid = $('network-agents-grid');
    const note = $('network-agents-note');
    if (!networkAgents) {
      grid.replaceChildren(ui.element('div', 'skeleton'));
      note.textContent = t('searching_the_local_network_for_pc_agents');
      return;
    }
    const peers = (networkAgents.agents || []).filter(agent => !agent.own);
    if (!peers.length) {
      grid.replaceChildren();
      note.textContent = t('no_other_pc_agents_found_searching_the_local');
      return;
    }
    note.textContent = networkAgents.sharingEnabled
      ? t('another_pc_agent_shares_only_read_only_wo')
      : t('lan_visibility_off_peers_stay_listed_without');
    grid.replaceChildren(...peers.map(agent => {
      const card = ui.element('article', `network-agent-card ${agent.reachable ? '' : 'error'}`);
      const head = ui.element('div', 'live-worker-head');
      const identity = ui.element('div');
      identity.append(ui.element('strong', '', agent.name || t('worker')),
        ui.element('span', '', `${agent.host}:${agent.dashboardPort}`));
      head.append(identity, (() => {
        const pill = ui.statusPill(null, t(agent.reachable ? 'connected_e0e3b1' : 'unavailable_9da3a4'));
        pill.classList.add(agent.reachable ? 'tone-ok' : 'tone-bad');
        return pill;
      })());
      card.append(head);
      if (agent.error) card.append(ui.element('p', 'network-agent-error', window.SolarMinerI18n.s(agent.error)));
      const totals = agent.totals || {};
      const metrics = ui.element('div', 'live-worker-metrics');
      for (const [label, value] of [
        ['worker', String(totals.workerCount || 0)],
        ['hashrate', ui.hashrate(totals.hashrateHps || 0)],
        ['power_4606f4', totals.watts ? ui.power(totals.watts) : '—'],
        ['earnings', totals.usdPerDay ? t('value_day', {value: preferences.moneyFromUsd(totals.usdPerDay)}) : '—']]) {
        const item = ui.element('div'); item.append(ui.element('span', '', label), ui.element('strong', '', value)); metrics.append(item);
      }
      card.append(metrics);
      const rows = (agent.workers || []).slice(0, 6);
      if (rows.length) {
        const list = ui.element('div', 'network-agent-workers');
        for (const row of rows) {
          const line = ui.element('p', 'network-agent-worker');
          line.append(ui.element('strong', '', row.name || t('worker')),
            ui.element('span', '', ` · ${row.hardwareType || '—'} · ${row.algorithm || '—'} · ${ui.hashrate(row.hashrateHps || 0)}`));
          list.append(line);
        }
        card.append(list);
      }
      if (agent.reachable) {
        const link = ui.element('a', '', t('open_agent_dashboard'));
        link.href = `http://${agent.host}:${agent.dashboardPort}/`;
        link.target = '_blank'; link.rel = 'noopener';
        card.append(link);
      }
      return card;
    }));
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
    renderLiveWorkers(data);
    renderEnergy();
    renderNetworkAgents();
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
      renderNetworkAgents();
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
