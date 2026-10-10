// Physical hardware inventory, assignment and live control. Installed software is managed elsewhere.
(() => {
  const ui = window.SolarMinerUI;
  const $ = id => document.getElementById(id);
  const t = ui.t;
  const s = ui.s;
  const preferences = window.SolarMinerPreferences;
  let workers = [], miners = [], energy = null, sensors = null;
  let search = '', coin = null, hardware = 'all', selected = null;
  const pendingWorkers = new Set();
  let loading = false, catalogReady = false;
  let consoleTimer = null, consoleOffset = 0, consoleRunId = '', consoleId = null, consoleSession = 0;
  let consoleDecoder = new TextDecoder();

  const notice = (message, error = false, translated = false) => {
    const node = $('notice'); node.textContent = translated ? message : t(message); node.className = `notice${error ? ' error' : ''}`; node.hidden = !message;
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
      wrap.append(ui.element('span', 'cell-sub', worker.coin === 'none' ? 'Freie Kapazität' : `${worker.algorithm} · ${worker.configured ? worker.poolUrl ? 'Pool eingerichtet' : 'Standardroute' : t('SolarMiner-Standardziel wird beim Start geprüft')}`)); return wrap;
    }},
    {label: 'Status', key: 'status', width: '11%', render: worker => ui.statusPill(worker.status)},
    {label: 'Live', key: 'telemetry', width: '16%', render: worker => {
      if (worker.status !== 'MINING') return null;
      const wrap = ui.element('div'); wrap.append(ui.element('strong', '', ui.hashrate(hps(worker))));
      wrap.append(ui.element('span', 'cell-sub', `${watts(worker) ? ui.power(watts(worker)) : 'Leistung —'} · ${temperature(worker) != null ? ui.temperature(temperature(worker)) : 'Temperatur —'}`)); return wrap;
    }},
    {label: 'Session-Energie', key: 'energy', width: '18%', render: energyCell},
    {label: 'Aktion', key: 'status', width: '18%', align: 'right', render: worker => {
      const wrap = ui.element('div', 'row-actions');
      const configure = ui.element('button', 'button subtle', worker.coin === 'none' ? 'Einrichten' : 'Ändern'); configure.type = 'button'; configure.disabled = !catalogReady || pendingWorkers.has(worker.deviceId);
      configure.addEventListener('click', event => { event.stopPropagation(); openEditor(worker); }); wrap.append(configure);
      if (worker.coin !== 'none') {
        const running = worker.status === 'MINING'; const toggle = ui.element('button', `button ${running ? 'subtle' : 'primary'}`, running ? 'Pausieren' : 'Starten');
        toggle.type = 'button'; toggle.disabled = pendingWorkers.has(worker.deviceId) || !worker.minerInstalled;
        toggle.addEventListener('click', event => { event.stopPropagation(); action(worker, running ? 'pause' : 'start'); }); wrap.append(toggle);
      }
      return wrap;
    }}
  ];

  function renderTable() {
    const list = filtered(); $('worker-count').textContent = t('{count} Worker', {count: list.length});
    $('worker-hidden').hidden = list.length === workers.length;
    if (!$('worker-hidden').hidden) $('worker-hidden').textContent = t('{count} ausgeblendet · Filter zurücksetzen', {count: workers.length - list.length});
    $('idle-devices').hidden = !workers.some(worker => worker.coin === 'none');
    if (!$('idle-devices').hidden) $('idle-devices').textContent = t('{count} Komponenten sind noch keinem Mining-Profil zugewiesen.', {count: workers.filter(worker => worker.coin === 'none').length});
    $('worker-table').replaceChildren(ui.table({columns, rows: list, empty: 'Keine Worker passen zu den Filtern.', onRow: openConsole}));
  }

  function render() { renderStatus(); renderKpis(); renderScope(); renderTable(); }

  function compatibleMiners(coinId) { return miners.filter(item => item.coin === coinId && item.selectable); }
  function populateMinerSelect(coinId, selectedId) {
    const select = $('worker-miner'); select.replaceChildren();
    const coinMiners = miners.filter(item => item.coin === coinId);
    for (const miner of coinMiners) {
      const option = document.createElement('option'); option.value = miner.id;
      option.textContent = `${miner.name}${!miner.selectable ? ` · ${t('derzeit nicht zuweisbar')}` : miner.installed ? '' : ` · ${t('nicht installiert')}`}`;
      option.disabled = !miner.selectable || !miner.installed; select.append(option);
    }
    if (selectedId && [...select.options].some(option => option.value === selectedId && !option.disabled)) select.value = selectedId;
    const chosen = coinMiners.find(miner => miner.id === select.value);
    $('worker-miner-help').textContent = coinId === 'none' ? t('Keine Software erforderlich.')
      : chosen && !chosen.selectable ? s(chosen.unavailableReason || 'Diese Miner-Software ist für den Coin derzeit nicht freigegeben.')
      : chosen?.installed ? t('Installiert und einsatzbereit.')
      : t('Installiere zuerst eine kompatible Software im Bereich Miner-Software.');
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
      option.textContent = id === 'none' ? t('Nicht zugewiesen') : t('{coin} · {algorithm}{unavailable}', {
        coin: coinTicker(id),
        algorithm: {monero:'RandomX',pearl:'PearlHash',ravencoin:'KAWPOW',ethereumclassic:'ETCHash',decred:'BLAKE3',quantus:'QPoW (Poseidon2)'}[id],
        unavailable: unavailable ? ` · ${t('nicht freigegeben')}` : ''
      });
      option.disabled = unavailable;
      if (reason) option.title = s(reason);
      coinSelect.append(option);
    }
    coinSelect.value = worker.coin; populateMinerSelect(worker.coin, worker.minerSoftwareId);
    updatePoolSummary(worker.coin, worker.poolUrl, worker.configured);
    coinSelect.onchange = () => { populateMinerSelect(coinSelect.value, null); updatePoolSummary(coinSelect.value, null, false); };
    $('worker-editor').showModal();
  }

  function workerConsoleId(worker) {
    if (worker.coin === 'none') return null;
    if (worker.hardwareType === 'CPU') return worker.coin;
    return `${worker.coin}-${worker.vendor}-${worker.index}`;
  }

  function stopConsolePolling() {
    if (consoleTimer) clearTimeout(consoleTimer);
    consoleTimer = null;
  }

  function closeConsole() {
    stopConsolePolling();
    consoleSession += 1;
    consoleId = null;
    if ($('worker-console').open) $('worker-console').close();
  }

  function decodedChunk(data, final = false) {
    if (!data) return consoleDecoder.decode(undefined, {stream: !final});
    const raw = atob(data), bytes = Uint8Array.from(raw, character => character.charCodeAt(0));
    return consoleDecoder.decode(bytes, {stream: !final});
  }

  async function pollConsole(session) {
    if (session !== consoleSession || !consoleId || !$('worker-console').open) return;
    try {
      let more = true;
      while (more && session === consoleSession && consoleId && $('worker-console').open) {
        const response = await fetch(`/api/agent/local/console/${encodeURIComponent(consoleId)}?offset=${consoleOffset}`, {cache: 'no-store'});
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const chunk = await response.json();
        if (session !== consoleSession) return;
        if (consoleRunId && chunk.runId && chunk.runId !== consoleRunId) {
          consoleOffset = 0; consoleDecoder = new TextDecoder(); $('worker-console-output').textContent = '';
          continue;
        }
        if (chunk.runId) consoleRunId = chunk.runId;
        const output = $('worker-console-output');
        const pinned = output.scrollTop + output.clientHeight >= output.scrollHeight - 24;
        output.textContent += decodedChunk(chunk.data);
        consoleOffset = Number(chunk.nextOffset || 0);
        more = Boolean(chunk.hasMore);
        if (pinned) output.scrollTop = output.scrollHeight;
      }
      $('worker-console-notice').hidden = true;
    } catch (error) {
      const message = $('worker-console-notice');
      message.textContent = t('Konsolenausgabe konnte nicht geladen werden: {error}', {error: s(error.message)});
      message.hidden = false;
    } finally {
      if (session === consoleSession && consoleId && $('worker-console').open)
        consoleTimer = setTimeout(() => pollConsole(session), 1000);
    }
  }

  function openConsole(worker) {
    stopConsolePolling();
    consoleSession += 1;
    const session = consoleSession;
    consoleId = workerConsoleId(worker); consoleOffset = 0; consoleRunId = ''; consoleDecoder = new TextDecoder();
    $('worker-console-title').textContent = t('Miner-Konsole · {worker}', {worker: worker.hardwareModel});
    $('worker-console-meta').textContent = `${worker.coinName || coinTicker(worker.coin)} · ${worker.algorithm || '—'} · ${worker.deviceId}`;
    $('worker-console-output').textContent = '';
    const download = $('worker-console-download');
    download.hidden = !consoleId;
    if (consoleId) download.href = `/api/agent/local/console/${encodeURIComponent(consoleId)}/download`;
    const message = $('worker-console-notice');
    message.hidden = Boolean(consoleId);
    message.textContent = consoleId ? '' : t('Weise diesem Worker zuerst einen Coin zu. Danach erscheint hier seine Minerausgabe.');
    $('worker-console').showModal();
    if (consoleId) pollConsole(session);
  }

  function updatePoolSummary(coinId, poolUrl, configured) {
    const box = $('worker-pool-summary'); box.replaceChildren();
    if (coinId === 'none') { box.textContent = t('Die Komponente bleibt frei und kann nicht gestartet werden.'); return; }
    const text = ui.element('span', '', configured ? `${t('Pool')}: ${poolUrl || t('SolarMiner-Standardroute')}` : t('Ohne eigene Wallet wird beim Start das SolarMiner-Standardziel geprüft. Die gesamte Auszahlung geht dann dorthin.'));
    const link = ui.element('a', '', 'Wallet & Pool bearbeiten →'); link.href = '/wallets.html'; box.append(text, link);
  }

  async function save(event) {
    event.preventDefault();
    if (event.submitter?.value === 'cancel') { $('worker-editor').close(); return; }
    if (!selected || pendingWorkers.has(selected.deviceId)) return;
    const worker = selected;
    const assignment = {
      coin: $('worker-coin').value, minerSoftwareId: $('worker-coin').value === 'none' ? null : $('worker-miner').value
    };
    pendingWorkers.add(worker.deviceId);
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
      renderTable();
    }
  }

  async function action(worker, command) {
    if (pendingWorkers.has(worker.deviceId)) return;
    pendingWorkers.add(worker.deviceId);
    renderTable();
    try {
      const response = await fetch(`/api/agent/local/workers/${encodeURIComponent(worker.deviceId)}/${command}`, {method: 'POST'});
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.detail || body?.message || `HTTP ${response.status}`);
      }
      if (await response.json() !== true) throw new Error(t('Die Worker-Aktion wurde nicht angewendet. Prüfe die Miner-Konsole.'));
      notice(command === 'start' ? `${worker.hardwareModel} wurde gestartet.` : `${worker.hardwareModel} wurde pausiert.`); await load();
    } catch (error) { notice(`${t('Worker-Aktion fehlgeschlagen:')} ${window.SolarMinerI18n.s(error.message)}`, true, true); await load(); }
    finally {
      pendingWorkers.delete(worker.deviceId);
      renderTable();
    }
  }

  async function load() {
    if (loading) return;
    loading = true;
    try {
      // Render the inventory as soon as it arrives. Miner discovery may take longer and is
      // needed only for assignment, not for showing or starting existing workers.
      workers = await ui.getJson('/api/agent/local/workers');
      $('connection').className = 'badge online'; $('connection').textContent = t('Agent verbunden'); render();
      try {
        miners = await ui.getJson('/api/agent/local/miner-options');
        catalogReady = true;
        renderTable();
      } catch (error) {
        catalogReady = false;
        renderTable();
        notice(t('Miner-Katalog konnte nicht geladen werden: {error}', {error: s(error.message)}), true, true);
      }
      try {
        const response = await fetch('/api/agent/local/energy', {cache: 'no-store', signal: AbortSignal.timeout(8000)});
        if (response.ok) { energy = await response.json(); renderKpis(); renderTable(); }
      } catch (_) { /* Energy is optional for the worker inventory. */ }
    } catch (error) {
      $('connection').className = 'badge offline'; $('connection').textContent = t('Agent nicht erreichbar');
      notice(`Worker konnten nicht geladen werden: ${error.message}`, true);
    } finally {
      loading = false;
    }
  }

  for (const button of document.querySelectorAll('[data-hardware]')) button.addEventListener('click', () => {
    hardware = button.dataset.hardware; document.querySelectorAll('[data-hardware]').forEach(node => node.classList.toggle('selected', node === button)); renderTable();
  });
  $('worker-hidden').addEventListener('click', () => { search = ''; coin = null; hardware = 'all'; document.querySelectorAll('[data-hardware]').forEach(node => node.classList.toggle('selected', node.dataset.hardware === 'all')); render(); });
  $('worker-form').addEventListener('submit', save);
  $('worker-console-close').addEventListener('click', closeConsole);
  $('worker-console').addEventListener('close', stopConsolePolling);
  $('refresh').addEventListener('click', load);
  document.addEventListener('solarminer:preferences-changed', () => { if (workers.length) render(); });
  load(); setInterval(() => { if (!document.hidden && !$('worker-editor').open && !$('worker-console').open) load(); }, 5000);
})();
