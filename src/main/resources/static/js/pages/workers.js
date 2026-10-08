// Physical hardware inventory, assignment and live control. Installed software is managed elsewhere.
(() => {
  const ui = window.SolarMinerUI;
  const $ = id => document.getElementById(id);
  const t = ui.t;
  const preferences = window.SolarMinerPreferences;
  let workers = [], miners = [], energy = null, sensors = null, controlSettings = null;
  let search = '', coin = null, hardware = 'all', settingsBusy = false, selected = null;
  const pendingWorkers = new Set();
  let loadRevision = 0;

  const notice = (message, error = false) => {
    const node = $('notice'); node.textContent = t(message); node.className = `notice${error ? ' error' : ''}`; node.hidden = !message;
  };
  const hps = worker => Number(worker.telemetry?.terahashPerSecond || 0) * 1e12;
  const watts = worker => Number(worker.telemetry?.approximatedPowerUsageWatts) > 0 ? Number(worker.telemetry.approximatedPowerUsageWatts) : null;
  const temperature = worker => {
    const direct = Number(worker.telemetry?.temperatureCelsius || 0);
    if (direct > 0) return direct;
    const metric = worker.hardwareType === 'CPU' ? sensors?.metrics?.['cpu.temperature']
      : sensors?.metrics?.[`gpu.${String(worker.vendor || '').toLowerCase()}.${worker.index}.temperature`];
    return metric?.available && Number(metric.value) > 0 ? Number(metric.value) : null;
  };
  const activeEnergy = worker => energy?.activeSessions?.find(session => session.deviceId === worker.deviceId);
  const money = value => preferences.money(value, energy?.settings?.currency || preferences.currency);
  const duration = seconds => {
    const hours = Math.floor((seconds || 0) / 3600), minutes = Math.floor(((seconds || 0) % 3600) / 60);
    return hours ? `${hours} h ${minutes} min` : `${minutes} min`;
  };
  const coinTicker = id => ({monero: 'XMR', pearl: 'PRL', ravencoin: 'RVN', ethereumclassic: 'ETC', decred: 'DCR', quantus: 'QTC', none: 'Frei'})[id] || id;

  function filtered() {
    return workers.filter(worker => {
      if (coin && worker.coin !== coin) return false;
      if (hardware !== 'all' && worker.hardwareType !== hardware) return false;
      const haystack = [worker.hardwareModel, worker.deviceId, worker.coinName, worker.algorithm, worker.minerSoftwareName].join(' ').toLowerCase();
      return !search || haystack.includes(search);
    });
  }

  function renderStatus() {
    const strip = $('status-strip'); strip.replaceChildren();
    const add = (label, value, tone) => {
      const item = ui.element('span', 'strip-item'); item.append(ui.element('span', '', `${label}: `));
      const pill = ui.statusPill(null, value); if (tone) pill.classList.add(tone); item.append(pill); strip.append(item);
    };
    add('Agent', 'verbunden', 'tone-ok');
    add('Zugewiesen', `${workers.filter(worker => worker.coin !== 'none').length}/${workers.length}`,
      workers.some(worker => worker.coin === 'none') ? 'tone-warn' : 'tone-ok');
    add('Node-Automatik', controlSettings?.externalControlEnabled ? 'erlaubt' : 'gesperrt',
      controlSettings?.externalControlEnabled ? 'tone-ok' : 'tone-warn');
    const broken = workers.filter(worker => worker.coin !== 'none' && (!worker.minerInstalled || !worker.configured)).length;
    if (broken) add('Handlungsbedarf', `${broken} Worker`, 'tone-bad');
  }

  function renderKpis() {
    const running = workers.filter(worker => worker.status === 'MINING');
    const totalWatts = running.reduce((sum, worker) => sum + (watts(worker) || 0), 0);
    const algorithms = new Map();
    for (const worker of running) algorithms.set(worker.algorithm, (algorithms.get(worker.algorithm) || 0) + hps(worker));
    const temps = running.map(temperature).filter(value => value != null);
    const today = energy?.today;
    const tariff = energy ? preferences.convert(energy.settings.pricePerKwh, energy.settings.currency) : null;
    const tariffCurrency = tariff == null ? energy?.settings?.currency : preferences.currency;
    $('kpi-strip').replaceChildren(
      ui.metric({label: 'Worker aktiv', value: `${running.length}/${workers.length}`, detail: 'Laufend / erkannte Komponenten', tone: running.length ? 'ok' : ''}),
      ui.metric({label: 'Leistung', value: totalWatts ? ui.power(totalWatts) : '—', detail: 'Nur verfügbare Sensorwerte'}),
      ui.metric({label: 'Algorithmen', value: algorithms.size ? String(algorithms.size) : '—', detail: [...algorithms].map(([name, value]) => `${name} ${ui.hashrate(value)}`).join(' · ') || 'Keine Hashrate'}),
      ui.metric({label: 'Höchste Temperatur', value: temps.length ? ui.temperature(Math.max(...temps)) : '—', detail: 'Miner- oder Host-Sensor'}),
      ui.metric({label: 'Energie heute', value: today ? `${Number(today.kilowattHours).toLocaleString(window.SolarMinerI18n.locale, {maximumFractionDigits: 3})} kWh` : '—', detail: today ? `${Math.round(today.measurementCoverage * 100)} % Messabdeckung` : 'Journal wird geladen'}),
      ui.metric({label: 'Stromkosten heute', value: today ? money(today.cost) : '—', detail: `Tarif ${energy ? Number(tariff == null ? energy.settings.pricePerKwh : tariff).toLocaleString(preferences.locale, {maximumFractionDigits: 4}) : '—'} ${tariffCurrency || ''}/kWh`})
    );
  }

  function renderScope() {
    const bar = ui.element('div', 'scope-bar');
    const chips = ui.element('div', 'scope-chips');
    for (const id of ['monero', 'pearl', 'ravencoin', 'ethereumclassic', 'decred', 'quantus', 'none']) {
      const button = ui.element('button', `scope-chip${coin === id ? ' selected' : ''}`, coinTicker(id)); button.type = 'button';
      if (workers.some(worker => worker.coin === id && worker.status === 'MINING')) button.classList.add('running');
      button.addEventListener('click', () => { coin = coin === id ? null : id; render(); }); chips.append(button);
    }
    const input = ui.element('input', 'scope-search'); input.type = 'search'; input.placeholder = t('Worker, Gerät, Coin oder Miner suchen'); input.value = search;
    input.addEventListener('input', event => { search = event.target.value.trim().toLowerCase(); renderTable(); });
    const count = ui.element('span', 'scope-count', `${filtered().length} Worker`);
    bar.append(chips, input, count); $('scope-bar').replaceChildren(bar);
  }

  function energyCell(worker) {
    const session = activeEnergy(worker);
    if (!session) return null;
    const wrap = ui.element('div');
    wrap.append(ui.element('strong', '', `${(Number(session.wattHours) / 1000).toLocaleString(window.SolarMinerI18n.locale, {maximumFractionDigits: 3})} kWh`));
    wrap.append(ui.element('span', 'cell-sub', `${duration(session.runtimeSeconds)} · ${Math.round(session.runtimeSeconds ? session.measuredSeconds / session.runtimeSeconds * 100 : 0)} % gemessen`));
    return wrap;
  }

  const columns = [
    {label: 'Komponente', key: 'hardwareModel', width: '19%', render: worker => {
      const wrap = ui.element('div'); wrap.append(ui.element('strong', '', worker.hardwareModel));
      wrap.append(ui.element('span', 'cell-sub', worker.deviceId)); return wrap;
    }},
    {label: 'Zuweisung', key: 'coinName', width: '18%', render: worker => {
      const wrap = ui.element('div');
      wrap.append(ui.element('strong', '', worker.coin === 'none' ? 'Nicht zugewiesen' : `${worker.coinName} · ${worker.minerSoftwareName || 'kein Miner'}`));
      wrap.append(ui.element('span', 'cell-sub', worker.coin === 'none' ? 'Freie Kapazität' : `${worker.algorithm} · ${worker.configured ? worker.poolUrl ? 'Pool eingerichtet' : 'Standardroute' : 'Pool fehlt'}`)); return wrap;
    }},
    {label: 'Status', key: 'status', width: '11%', render: worker => ui.statusPill(worker.status)},
    {label: 'Live', key: 'telemetry', width: '16%', render: worker => {
      if (worker.status !== 'MINING') return null;
      const wrap = ui.element('div'); wrap.append(ui.element('strong', '', ui.hashrate(hps(worker))));
      wrap.append(ui.element('span', 'cell-sub', `${watts(worker) ? ui.power(watts(worker)) : 'Leistung —'} · ${temperature(worker) != null ? ui.temperature(temperature(worker)) : 'Temperatur —'}`)); return wrap;
    }},
    {label: 'Session-Energie', key: 'energy', width: '15%', render: energyCell},
    {label: 'Steuerung', key: 'externalControlEnabled', width: '10%', render: worker => worker.coin === 'none' ? '—' : worker.externalControlEnabled ? 'Für Node freigegeben' : 'Lokal'},
    {label: 'Aktion', key: 'status', width: '16%', align: 'right', render: worker => {
      const wrap = ui.element('div', 'row-actions');
      const configure = ui.element('button', 'button subtle', worker.coin === 'none' ? 'Einrichten' : 'Ändern'); configure.type = 'button'; configure.disabled = pendingWorkers.has(worker.deviceId);
      configure.addEventListener('click', event => { event.stopPropagation(); openEditor(worker); }); wrap.append(configure);
      if (worker.coin !== 'none') {
        const running = worker.status === 'MINING'; const toggle = ui.element('button', `button ${running ? 'subtle' : 'primary'}`, running ? 'Pausieren' : 'Starten');
        toggle.type = 'button'; toggle.disabled = pendingWorkers.has(worker.deviceId) || !worker.minerInstalled || !worker.configured;
        toggle.addEventListener('click', event => { event.stopPropagation(); action(worker, running ? 'pause' : 'start'); }); wrap.append(toggle);
      }
      return wrap;
    }}
  ];

  function renderTable() {
    const list = filtered(); $('worker-count').textContent = t(`${list.length} Worker`);
    $('worker-hidden').hidden = list.length === workers.length;
    if (!$('worker-hidden').hidden) $('worker-hidden').textContent = t(`${workers.length - list.length} ausgeblendet · Filter zurücksetzen`);
    $('idle-devices').hidden = !workers.some(worker => worker.coin === 'none');
    if (!$('idle-devices').hidden) $('idle-devices').textContent = t(`${workers.filter(worker => worker.coin === 'none').length} Komponenten sind noch keinem Mining-Profil zugewiesen.`);
    $('worker-table').replaceChildren(ui.table({columns, rows: list, empty: 'Keine Worker passen zu den Filtern.', onRow: openEditor}));
  }

  function render() { renderStatus(); renderKpis(); renderScope(); renderTable(); }

  function compatibleMiners(coinId) { return miners.filter(item => item.coin === coinId && item.selectable); }
  function populateMinerSelect(coinId, selectedId) {
    const select = $('worker-miner'); select.replaceChildren();
    const coinMiners = miners.filter(item => item.coin === coinId);
    for (const miner of coinMiners) {
      const option = document.createElement('option'); option.value = miner.id;
      option.textContent = `${miner.name}${!miner.selectable ? ' · derzeit nicht zuweisbar' : miner.installed ? '' : ' · nicht installiert'}`;
      option.disabled = !miner.selectable || !miner.installed; select.append(option);
    }
    if (selectedId && [...select.options].some(option => option.value === selectedId && !option.disabled)) select.value = selectedId;
    const chosen = coinMiners.find(miner => miner.id === select.value);
    $('worker-miner-help').textContent = coinId === 'none' ? 'Keine Software erforderlich.'
      : chosen && !chosen.selectable ? (chosen.unavailableReason || 'Diese Miner-Software ist für den Coin derzeit nicht freigegeben.')
      : chosen?.installed ? 'Installiert und einsatzbereit.'
      : 'Installiere zuerst eine kompatible Software im Bereich Miner-Software.';
    $('worker-miner-install').hidden = coinId === 'none' || !chosen?.selectable || Boolean(chosen?.installed);
    $('worker-save').disabled = coinId !== 'none' && (!chosen || !chosen.selectable || !chosen.installed);
    select.disabled = coinId === 'none';
  }

  function openEditor(worker) {
    selected = worker;
    $('worker-editor-title').textContent = worker.hardwareModel;
    $('worker-editor-hardware').textContent = `${worker.hardwareType} · ${worker.deviceId}`;
    const coinSelect = $('worker-coin'); coinSelect.replaceChildren();
    const ids = worker.hardwareType === 'CPU' ? ['none', 'monero'] : ['none', 'pearl', 'ravencoin', 'ethereumclassic', 'decred', 'quantus'];
    for (const id of ids) {
      const option = document.createElement('option'); option.value = id;
      const coinMiners = miners.filter(miner => miner.coin === id);
      const unavailable = id !== 'none' && coinMiners.length > 0 && !coinMiners.some(miner => miner.selectable);
      const reason = coinMiners.find(miner => !miner.selectable)?.unavailableReason;
      option.textContent = id === 'none' ? 'Nicht zugewiesen'
        : `${coinTicker(id)} · ${{monero:'RandomX',pearl:'PearlHash',ravencoin:'KAWPOW',ethereumclassic:'ETCHash',decred:'BLAKE3',quantus:'QPoW (Poseidon2)'}[id]}${unavailable ? ' · nicht freigegeben' : ''}`;
      option.disabled = unavailable;
      if (reason) option.title = reason;
      coinSelect.append(option);
    }
    coinSelect.value = worker.coin; populateMinerSelect(worker.coin, worker.minerSoftwareId);
    $('worker-node-control').checked = worker.externalControlEnabled;
    updatePoolSummary(worker.coin, worker.poolUrl, worker.configured);
    coinSelect.onchange = () => { populateMinerSelect(coinSelect.value, null); updatePoolSummary(coinSelect.value, null, false); };
    $('worker-editor').showModal();
  }

  function updatePoolSummary(coinId, poolUrl, configured) {
    const box = $('worker-pool-summary'); box.replaceChildren();
    if (coinId === 'none') { box.textContent = t('Die Komponente bleibt frei und kann nicht gestartet werden.'); return; }
    const text = ui.element('span', '', configured ? `Pool: ${poolUrl || 'SolarMiner-Standardroute'}` : 'Vor dem Start muss für diesen Coin ein Pool eingerichtet werden.');
    const link = ui.element('a', '', 'Wallet & Pool bearbeiten →'); link.href = '/wallets.html'; box.append(text, link);
  }

  async function save(event) {
    event.preventDefault();
    if (event.submitter?.value === 'cancel') { $('worker-editor').close(); return; }
    if (!selected || settingsBusy || pendingWorkers.has(selected.deviceId)) return;
    const worker = selected;
    const assignment = {
      coin: $('worker-coin').value, minerSoftwareId: $('worker-coin').value === 'none' ? null : $('worker-miner').value,
      externalControlEnabled: $('worker-node-control').checked
    };
    pendingWorkers.add(worker.deviceId);
    $('global-node-control').disabled = true;
    $('worker-editor').close();
    selected = null;
    renderTable();
    try {
      const response = await fetch(`/api/agent/local/workers/${encodeURIComponent(worker.deviceId)}/assignment`, {
        method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(assignment)
      });
      if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.message || body?.detail || `HTTP ${response.status}`); }
      notice('Zuweisung gespeichert. Ein laufender vorheriger Worker wurde sicher angehalten.'); await load();
    } catch (error) { notice(`Zuweisung fehlgeschlagen: ${error.message}`, true); }
    finally {
      pendingWorkers.delete(worker.deviceId);
      $('global-node-control').disabled = !controlSettings || settingsBusy || pendingWorkers.size > 0;
      renderTable();
    }
  }

  async function action(worker, command) {
    if (pendingWorkers.has(worker.deviceId)) return;
    pendingWorkers.add(worker.deviceId);
    $('global-node-control').disabled = true;
    renderTable();
    try {
      const response = await fetch(`/api/agent/local/workers/${encodeURIComponent(worker.deviceId)}/${command}`, {method: 'POST'});
      if (!response.ok || await response.json() !== true) throw new Error(`HTTP ${response.status}`);
      notice(command === 'start' ? `${worker.hardwareModel} wurde gestartet.` : `${worker.hardwareModel} wurde pausiert.`); await load();
    } catch (error) { notice(`Worker-Aktion fehlgeschlagen: ${error.message}`, true); }
    finally {
      pendingWorkers.delete(worker.deviceId);
      $('global-node-control').disabled = !controlSettings || settingsBusy || pendingWorkers.size > 0;
      renderTable();
    }
  }

  async function load() {
    const revision = ++loadRevision;
    try {
      // Hardware and catalog are the only data required to assign a worker. Never let an
      // optional sensor endpoint keep the complete table (and its assignment actions) empty.
      const [workerResponse, minerResponse] = await Promise.all([
        fetch('/api/agent/local/workers', {cache: 'no-store'}),
        fetch('/api/agent/local/miner-options', {cache: 'no-store'})
      ]);
      if (!workerResponse.ok || !minerResponse.ok) throw new Error(`HTTP ${workerResponse.status}/${minerResponse.status}`);
      const [nextWorkers, nextMiners] = await Promise.all([workerResponse.json(), minerResponse.json()]);
      if (revision !== loadRevision) return;
      workers = nextWorkers; miners = nextMiners;
      $('connection').className = 'badge online'; $('connection').textContent = t('Agent verbunden'); render();

      const optional = await Promise.allSettled([
        fetch('/api/agent/local/energy', {cache: 'no-store'}).then(response => response.ok ? response.json() : null),
        fetch('/api/agent/local/power-control/settings', {cache: 'no-store'}).then(response => response.ok ? response.json() : null)
      ]);
      if (revision !== loadRevision) return;
      if (optional[0].status === 'fulfilled' && optional[0].value) energy = optional[0].value;
      if (optional[1].status === 'fulfilled' && optional[1].value) controlSettings = optional[1].value;
      $('global-node-control').disabled = !controlSettings || settingsBusy || pendingWorkers.size > 0;
      $('global-node-control').checked = Boolean(controlSettings?.externalControlEnabled);
      render();
    } catch (error) {
      if (revision !== loadRevision) return;
      $('connection').className = 'badge offline'; $('connection').textContent = t('Agent nicht erreichbar');
      notice(`Worker konnten nicht geladen werden: ${error.message}`, true);
    }
  }

  for (const button of document.querySelectorAll('[data-hardware]')) button.addEventListener('click', () => {
    hardware = button.dataset.hardware; document.querySelectorAll('[data-hardware]').forEach(node => node.classList.toggle('selected', node === button)); renderTable();
  });
  $('worker-hidden').addEventListener('click', () => { search = ''; coin = null; hardware = 'all'; document.querySelectorAll('[data-hardware]').forEach(node => node.classList.toggle('selected', node.dataset.hardware === 'all')); render(); });
  $('worker-form').addEventListener('submit', save);
  $('global-node-control').disabled = true;
  $('global-node-control').addEventListener('change', async event => {
    if (!controlSettings || settingsBusy || pendingWorkers.size > 0) {
      event.target.checked = Boolean(controlSettings?.externalControlEnabled);
      return;
    }
    settingsBusy = true; event.target.disabled = true;
    try {
      const response = await fetch('/api/agent/local/power-control/settings', {method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({
        dynamicPowerScalingEnabled: controlSettings.dynamicPowerScalingEnabled,
        externalControlEnabled: event.target.checked,
        workerExternalControl: controlSettings.workerExternalControl || {}, workerCoins: controlSettings.workerCoins || {}
      })});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      controlSettings = await response.json(); notice(event.target.checked ? 'Node-Automatik global erlaubt.' : 'Node-Automatik global gesperrt.'); renderStatus();
    } catch (error) { event.target.checked = !event.target.checked; notice(`Node-Automatik konnte nicht geändert werden: ${error.message}`, true); }
    finally { settingsBusy = false; event.target.disabled = false; }
  });
  $('refresh').addEventListener('click', load);
  document.addEventListener('solarminer:preferences-changed', () => { if (workers.length) render(); });
  load(); setInterval(() => { if (!document.hidden && !$('worker-editor').open) load(); }, 5000);
})();
