// Dashboard: readiness first, then measurement, then money. One aggregate, pushed over SSE.
// No polling loop: overview/settings/node-assessment/telemetry/energy channels push, and the
// live-data watchdog re-reads a channel only when its push went silent. A channel that fails
// drops its derived state instead of keeping dead numbers on screen.
(() => {
  const ui = window.SolarMinerUI;
  const $ = ui.$;
  const t = ui.t;
  const preferences = window.SolarMinerPreferences;
  let overview, telemetry, settings, assessment, energy, energyError = null;
  let savedAt = null, fresh = true;

  function renderStatusStrip(data) {
    const strip = $('status-strip');
    strip.replaceChildren();
    const add = (label, value, tone, href) => {
      const item = document.createElement('span');
      item.className = 'strip-item';
      item.append(ui.element('span', '', `${label}: `));
      const pill = ui.statusPill(null, value);
      if (tone) pill.classList.add(tone);
      item.append(pill);
      if (href) { const link = ui.element('a', '', 'Öffnen'); link.href = href; item.append(link); }
      strip.append(item);
    };
    const proxy = data.proxy || {};
    add('Agent', !fresh ? 'nicht erreichbar' : 'verbunden',
      fresh ? 'tone-ok' : 'tone-bad');
    add('Mining-Verbindung', !proxy.reachable ? 'getrennt' : proxy.mode === 'standalone' ? 'lokaler Proxy' : 'Proxy im Netzwerk',
      proxy.reachable ? 'tone-ok' : 'tone-bad', '/proxy.html');
    add('Node', assessment?.connected ? 'verbunden' : assessment?.connected === false ? 'nicht verbunden' : 'unbekannt',
      assessment?.connected ? 'tone-ok' : '', '/workers.html');
    const workers = (data.stats?.workers || []);
    const errors = workers.filter(worker => worker.miningStatus === 'ERROR').length;
    if (errors) add('Meldungen', t('{count} Worker mit Fehler', {count: errors}), 'tone-bad', '/workers.html');
  }

  function renderAlerts(data) {
    const list = $('alerts');
    list.replaceChildren();
    const installed = (data.coins || []).filter(coin => coin.binaryAvailable && !coin.experimental);
    const unconfigured = installed.find(coin => !coin.configured);
    const workers = data.stats?.workers || [];
    const errors = workers.filter(worker => worker.miningStatus === 'ERROR');
    const enabledIds = settings ? ['cpu', ...(data.gpus || []).map(gpu => gpu.deviceId)]
      .filter(id => settings.workerExternalControl?.[id] !== false
        && (settings.workerCoins == null || (settings.workerCoins[id]
          || (id === 'cpu' ? 'none' : settings.workerCoins['*'] || 'none')) !== 'none')) : [];
    const items = [];
    if (!installed.length) items.push(['warn', 'Noch kein Miner installiert', 'Wähle in der Miner-Bibliothek einen passenden Build und installiere ihn.', '/mining.html', 'Miner installieren →']);
    else if (unconfigured) items.push(['warn', 'Einrichtung unvollständig', `${unconfigured.name}: ${t('Pool, Auszahlung oder Geräteauswahl fehlen.')}`, '/workers.html', 'Worker einrichten →']);
    if (!data.proxy?.reachable) items.push(['bad', 'Mining-Verbindung getrennt', 'Der Proxy ist nicht erreichbar; ohne ihn starten die Miner nicht.', '/proxy.html', 'Verbindung prüfen →']);
    if (errors.length) items.push(['bad', t('{count} Worker mit Fehler', {count: errors.length}), errors.map(worker => worker.workerDisplayName).join(', '), '/workers.html', 'Worker prüfen →']);
    if (settings?.externalControlEnabled && !enabledIds.length) items.push(['warn', 'Node-Steuerung erlaubt, aber kein Gerät freigegeben', 'Ordne im Worker-Bereich mindestens ein Gerät zu, damit der Node automatisch regeln darf.', '/workers.html', 'Worker öffnen →']);
    if (!items.length) items.push(['ok', 'Alles im erwarteten Bereich', 'Keine offene Aktion. Die Messwerte unten zeigen den laufenden Betrieb.', null, null]);
    for (const [tone, title, description, href, action] of items) {
      const row = ui.element('div', `notice-line ${tone}`);
      const copy = ui.element('div');
      copy.append(ui.element('strong', '', title), ui.element('div', '', description));
      row.append(copy);
      if (href) { const link = ui.element('a', '', action); link.href = href; row.append(link); }
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
        ['Hashrate', ui.hashrate(row.hashrateHps)], ['Leistung', row.watts ? ui.power(row.watts) : '—'],
        ['Temperatur', row.temperature.value != null ? ui.temperature(row.temperature.value) : '—'],
        ['Ertrag', usdPerDay != null ? t('≈ {value}/Tag', {value: preferences.moneyFromUsd(usdPerDay)}) : '—'],
        ['Ertrag pro kWh', ui.moneyPerKwh(ui.revenuePerKwh(usdPerDay, row.watts))]]) {
        const item = ui.element('div'); item.append(ui.element('span', '', label), ui.element('strong', '', value)); metrics.append(item);
      }
      card.append(metrics);
      const targetPool = poolTargets[row.coin] || row.pools?.[0];
      const pool = ui.element('p', 'live-worker-pool', targetPool ? `${targetPool} · ${t('Latenz')} ${ui.latency(row.latency)}` : t('Kein Pool gemeldet'));
      const session = energy?.activeSessions?.find(item => item.deviceId === row.deviceId);
      if (session) pool.append(ui.element('span', '', ` · ${t('Session')} ${ui.kilowattHours(Number(session.wattHours))}`));
      const link = ui.element('a', '', 'Worker öffnen →'); link.href = '/workers.html'; card.append(pool, link); return card;
    }));
    if (!rows.length) grid.append(ui.element('div', 'notice-line warn', 'Kein Worker läuft gerade. Richte Hardware im Bereich Worker ein oder starte eine vorhandene Zuweisung.'));
  }

  function renderEnergy() {
    const root = $('energy-kpis');
    $('energy-settings').disabled = !energy;
    if (!energy) {
      root.replaceChildren(ui.element('div', energyError ? 'notice-line warn' : 'skeleton',
        energyError ? 'Energiedaten konnten nicht geladen werden.' : undefined));
      $('energy-note').textContent = energyError ? `${t('Energie-API:')} ${t(energyError)}` : '';
      return;
    }
    const activeWh = (energy.activeSessions || []).reduce((sum, session) => sum + Number(session.wattHours || 0), 0);
    const activeSeconds = (energy.activeSessions || []).reduce((sum, session) => Math.max(sum, Number(session.runtimeSeconds || 0)), 0);
    const money = value => preferences.money(value, energy.settings.currency);
    const tariff = preferences.convert(energy.settings.pricePerKwh, energy.settings.currency);
    const tariffCurrency = tariff == null ? energy.settings.currency : preferences.currency;
    root.replaceChildren(
      ui.metric({label: 'Laufende Sessions', value: ui.kilowattHours(activeWh), detail: t('{count} Worker · {duration}', {count: energy.activeSessions.length, duration: ui.duration(activeSeconds)}), tone: energy.activeSessions.length ? 'ok' : ''}),
      ui.metric({label: 'Heute', value: ui.kilowattHours(Number(energy.today.kilowattHours) * 1000), detail: `${money(energy.today.cost)} ${t('Stromkosten')}`}),
      ui.metric({label: '7 Tage', value: ui.kilowattHours(Number(energy.last7Days.kilowattHours) * 1000, 2), detail: `${money(energy.last7Days.cost)} ${t('Stromkosten')}`}),
      ui.metric({label: 'Messabdeckung', value: `${Math.round(energy.today.measurementCoverage * 100)} %`, detail: 'Fehlende Sensorintervalle werden nicht geschätzt'})
    );
    $('energy-note').textContent = t('Komponentenverbrauch aus verfügbaren CPU-Package- und GPU-Board-Sensoren · Tarif {tariff} {currency}/kWh. Netzteil- und übrige Systemverluste können fehlen.',
      {tariff: Number(tariff == null ? energy.settings.pricePerKwh : tariff).toLocaleString(preferences.locale, {maximumFractionDigits: 4}), currency: tariffCurrency});
  }

  async function editEnergySettings() {
    if (!energy) return;
    const converted = preferences.convert(energy.settings.pricePerKwh, energy.settings.currency);
    const editCurrency = converted == null ? energy.settings.currency : preferences.currency;
    const value = prompt(t('Strompreis in {currency} pro kWh', {currency: editCurrency}), converted == null ? energy.settings.pricePerKwh : converted);
    if (value == null) return;
    const price = Number(String(value).replace(',', '.'));
    if (!Number.isFinite(price) || price < 0) return alert(t('Bitte gib einen gültigen positiven Strompreis ein.'));
    try {
      energy.settings = await ui.postJson('/api/agent/local/energy/settings', {pricePerKwh: price, currency: editCurrency});
      window.SolarMinerLive.refresh('energy');
    } catch (_) { alert(t('Stromtarif konnte nicht gespeichert werden.')); }
  }

  function render(data, isFresh = fresh) {
    overview = data;
    fresh = isFresh;
    renderStatusStrip(data);
    renderAlerts(data);
    renderLiveWorkers(data);
    renderEnergy();
    ui.setConnection(fresh ? 'online' : 'offline');
    ui.setUpdated(savedAt);
    if (!fresh) ui.notice('Letzter bekannter Stand · aktuelle Daten werden geprüft.', {error: true, kind: 'connection'});
    else ui.clearNotice('connection');
  }

  // ------------------------------------------------------------------ live channels

  window.SolarMinerLive.channel('overview',
    {endpoint: '/api/agent/local/overview', maxAgeMs: 6000})
    .subscribe((data, meta) => {
      if (!data) { fresh = false; if (overview) render(overview, false); return; }
      savedAt = Date.now();
      window.SolarMinerMiningCache?.write(data);
      render(data, !meta.stale);
    });
  window.SolarMinerLive.channel('settings', {endpoint: '/api/agent/local/power-control/settings', maxAgeMs: 8000})
    .subscribe((data, meta) => { settings = meta.error ? null : data; if (overview) render(overview); });
  window.SolarMinerLive.channel('node-assessment', {endpoint: '/api/agent/local/node-assessment', maxAgeMs: 15000})
    .subscribe((data, meta) => { assessment = meta.error ? null : data; if (overview) render(overview); });
  window.SolarMinerLive.channel('telemetry', {endpoint: '/api/agent/local/telemetry', maxAgeMs: 8000})
    .subscribe((data, meta) => { telemetry = meta.error ? null : data; if (overview) render(overview); });
  window.SolarMinerLive.channel('energy', {endpoint: '/api/agent/local/energy', maxAgeMs: 15000})
    .subscribe((data, meta) => {
      if (meta.error) {
        energy = null;
        energyError = meta.error.name === 'TimeoutError' ? 'Zeitüberschreitung'
          : /404/.test(meta.error.message) ? 'HTTP 404 · PC-Agent neu starten oder aktualisieren.'
            : 'Verbindung fehlgeschlagen';
      } else { energy = data; energyError = null; }
      renderEnergy();
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
    agentNameBusy = true; agentNameError = false; agentNameState = 'Speichern …'; renderAgentName();
    try {
      const identity = await ui.postJson('/api/agent/local/power-control/identity', {name: $('agent-name-input').value.trim()});
      agentName = identity?.name || null;
      window.SolarMinerAgentIdentity?.apply(agentName);
      agentNameState = agentName ? 'Gespeichert. Der SolarMiner Node zeigt diesen Namen.'
        : 'Name entfernt. Der Node zeigt wieder SolarMiner PC Agent.';
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
