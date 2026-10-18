// Physical hardware inventory, assignment and live control. Installed software is managed elsewhere.
(() => {
  const ui = window.SolarMinerUI;
  const $ = id => document.getElementById(id);
  const t = ui.t;
  const s = ui.s;
  const preferences = window.SolarMinerPreferences;
  let workers = [], miners = [], energy = null, earnings = [], sensors = null;
  let benchmarkRunning = false, sweepWorkerIds = new Set();
  let search = '', coin = null, hardware = 'all', selected = null;
  const pendingWorkers = new Set();
  const pendingStarts = new Set();
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
  const dailyProfit = worker => {
    if (worker.status !== 'MINING' || !energy?.settings || !(watts(worker) > 0)) return null;
    const coin = runningCoin(worker) || worker.coin;
    const forecast = earnings.find(item => item.coin === coin && item.available);
    const grossUsd = ui.workerUsdPerDay(hps(worker), forecast);
    const tariff = preferences.convert(energy.settings.pricePerKwh, energy.settings.currency);
    if (!(grossUsd > 0) || !Number.isFinite(tariff)) return null;
    const gross = preferences.convert(grossUsd, 'USD');
    if (!Number.isFinite(gross)) return null;
    return gross - watts(worker) * 24 / 1000 * tariff;
  };
  const duration = seconds => {
    const hours = Math.floor((seconds || 0) / 3600), minutes = Math.floor(((seconds || 0) % 3600) / 60);
    return hours ? `${hours} h ${minutes} min` : `${minutes} min`;
  };
  // Phase 5c: ticker/algorithm identities come from the backend coin catalog (closed Coin
  // enum); the UI keeps no coin table of its own. "none" is the UI's unassigned pseudo-coin.
  const coinRow = id => (window.SolarMinerCoinCatalog || []).find(row => row.id === id) || null;
  const coinTicker = id => id === 'none' ? 'unassigned' : (coinRow(id)?.ticker || id);
  const coinAlgorithm = id => coinRow(id)?.algorithm || '';
  const runningCoin = worker => {
    const algorithm = String(worker.telemetry?.currentAlgorithm || '').toLowerCase();
    return (window.SolarMinerCoinCatalog || []).find(row => String(row.algorithm || '').toLowerCase() === algorithm)?.id || null;
  };

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
      const item = ui.element('span', 'strip-item'); item.append(ui.element('span', '', `${t(label)}: `));
      const pill = ui.statusPill(null, value); if (tone) pill.classList.add(tone); item.append(pill); strip.append(item);
    };
    add('Agent', 'connected_e0e3b1', 'tone-ok');
    add('Zugewiesen', `${workers.filter(worker => worker.coin !== 'none').length}/${workers.length}`,
      workers.some(worker => worker.coin === 'none') ? 'tone-warn' : 'tone-ok');
    const broken = workers.filter(worker => worker.coin !== 'none' && (!worker.minerInstalled || !worker.configured)).length;
    if (broken) add('Handlungsbedarf', t('count_workers', {count: broken}), 'tone-bad');
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
      ui.metric({label: 'worker_active', value: `${running.length}/${workers.length}`, detail: 'running_detected_components', tone: running.length ? 'ok' : ''}),
      ui.metric({label: 'power_4606f4', value: totalWatts ? ui.power(totalWatts) : '—', detail: 'available_sensor_readings_only'}),
      ui.metric({label: 'algorithms', value: algorithms.size ? String(algorithms.size) : '—', detail: [...algorithms].map(([name, value]) => `${name} ${ui.hashrate(value)}`).join(' · ') || 'no_hashrate'}),
      ui.metric({label: 'highest_temperature', value: temps.length ? ui.temperature(Math.max(...temps)) : '—', detail: 'miner_or_host_sensor'}),
      ui.metric({label: 'energy_today', value: today ? `${Number(today.kilowattHours).toLocaleString(window.SolarMinerI18n.locale, {maximumFractionDigits: 3})} kWh` : '—', detail: today ? t('percent_measurement_coverage', {percent: Math.round(today.measurementCoverage * 100)}) : 'loading_energy_journal'}),
      ui.metric({label: 'electricity_cost_today', value: today ? money(today.cost) : '—', detail: t('rate_price_currency_kwh', {price: energy ? Number(tariff == null ? energy.settings.pricePerKwh : tariff).toLocaleString(preferences.locale, {maximumFractionDigits: 4}) : '—', currency: tariffCurrency || ''})})
    );
  }

  function renderScope() {
    const bar = ui.element('div', 'scope-bar');
    const chips = ui.element('div', 'scope-chips');
    const scopeIds = [...(window.SolarMinerCoinCatalog || []).map(row => row.id), 'none'];
    for (const id of scopeIds) {
      const button = ui.element('button', `scope-chip${coin === id ? ' selected' : ''}`, coinTicker(id)); button.type = 'button';
      if (workers.some(worker => worker.coin === id && worker.status === 'MINING')) button.classList.add('running');
      button.addEventListener('click', () => { coin = coin === id ? null : id; render(); }); chips.append(button);
    }
    const input = ui.element('input', 'scope-search'); input.type = 'search'; input.placeholder = t('search_worker_device_coin_or_miner'); input.value = search;
    input.addEventListener('input', event => { search = event.target.value.trim().toLowerCase(); renderTable(); });
    const count = ui.element('span', 'scope-count', t('count_workers', {count: filtered().length}));
    bar.append(chips, input, count); $('scope-bar').replaceChildren(bar);
  }

  function energyCell(worker) {
    const session = activeEnergy(worker);
    if (!session) return null;
    const wrap = ui.element('div');
    wrap.append(ui.element('strong', '', `${(Number(session.wattHours) / 1000).toLocaleString(window.SolarMinerI18n.locale, {maximumFractionDigits: 3})} kWh`));
    wrap.append(ui.element('span', 'cell-sub', t('duration_percent_measured', {duration: duration(session.runtimeSeconds), percent: Math.round(session.runtimeSeconds ? session.measuredSeconds / session.runtimeSeconds * 100 : 0)})));
    return wrap;
  }

  const columns = [
    {label: 'component', key: 'hardwareModel', width: '19%', render: worker => {
      const wrap = ui.element('div');
      const model = ui.element('strong', '', worker.hardwareModel);
      if (worker.hardwareType === 'GPU') {
        model.title = t('worker_hardware_details', {
          model: worker.hardwareModel || worker.deviceId,
          vendor: worker.vendor || 'GPU',
          index: worker.index ?? '—',
          device: worker.deviceId
        });
      }
      wrap.append(model);
      wrap.append(ui.element('span', 'cell-sub', worker.deviceId)); return wrap;
    }},
    {label: 'assignment', key: 'coinName', width: '18%', render: worker => {
      const wrap = ui.element('div');
      if (worker.status === 'MINING' && worker.telemetry) {
        const actualCoin = runningCoin(worker);
        wrap.append(ui.element('strong', '', worker.telemetry.workerDisplayName || `${actualCoin ? coinTicker(actualCoin) : t('worker_active')}`));
        wrap.append(ui.element('span', 'cell-sub', t('currently_running_algorithm', {algorithm: worker.telemetry.currentAlgorithm || '—'})));
        if (actualCoin && actualCoin !== worker.coin)
          wrap.append(ui.element('span', 'cell-sub', t('configured_assignment_coin', {coin: worker.coin === 'none' ? t('unassigned_4cfcb8') : worker.coinName})));
        else
          wrap.append(ui.element('span', 'cell-sub', worker.coin === 'none' ? t('free_capacity') : `${worker.configured ? worker.poolUrl ? t('pool_configured') : t('default_route') : t('solarminer_default_payout_will_be_checked_at')}`));
      } else {
      wrap.append(ui.element('strong', '', worker.coin === 'none' ? t('unassigned_4cfcb8') : `${worker.coinName} · ${worker.minerSoftwareName || t('no_miner')}`));
      wrap.append(ui.element('span', 'cell-sub', worker.coin === 'none' ? t('free_capacity') : `${worker.algorithm} · ${worker.configured ? worker.poolUrl ? t('pool_configured') : t('default_route') : t('solarminer_default_payout_will_be_checked_at')}`));
      }
      if (worker.coin !== 'none') {
        const labels = {MANUAL: 'worker_mode_manual', EFFICIENCY: 'worker_mode_efficiency', MAX_HASHRATE: 'worker_mode_max_hashrate',
          REVENUE_PER_KWH: 'worker_mode_revenue', COIN_REVENUE_PER_KWH: 'worker_mode_coin_revenue',
          PROFIT_PER_DAY: 'worker_mode_profit_day', COIN_PROFIT_PER_DAY: 'worker_mode_coin_profit_day'};
        wrap.append(ui.element('span', 'cell-sub', t(labels[worker.powerMode] || labels.MANUAL)));
      }
      return wrap;
    }},
    {label: 'status_bae7d5', key: 'status', width: '11%', render: worker => ui.statusPill(
      pendingStarts.has(worker.deviceId) && worker.status !== 'MINING' ? 'STARTING'
        : worker.status === 'MINING' ? sweepWorkerIds.has(worker.deviceId) ? 'POWER_SWEEP' : benchmarkRunning ? 'BENCHMARKING' : 'MINING' : worker.status)},
    {label: 'Live', key: 'telemetry', width: '16%', render: worker => {
      if (worker.status !== 'MINING') return null;
      const wrap = ui.element('div'); wrap.append(ui.element('strong', '', ui.hashrate(hps(worker))));
      wrap.append(ui.element('span', 'cell-sub', `${watts(worker) ? ui.power(watts(worker)) : t('power_ce610e')} · ${temperature(worker) != null ? ui.temperature(temperature(worker)) : t('temperature_2500e7')}`)); return wrap;
    }},
    {label: 'session_energy', key: 'energy', width: '13%', render: energyCell},
    {label: 'estimated_profit_24h', key: 'profit', width: '15%', align: 'right', render: worker => {
      const profit = dailyProfit(worker);
      const wrap = ui.element('div');
      wrap.append(ui.element('strong', '', profit == null ? '—' : money(profit)));
      wrap.append(ui.element('span', 'cell-sub', t('estimated_profit_24h_detail')));
      return wrap;
    }},
    {label: 'action', key: 'status', width: '15%', align: 'right', render: worker => {
      const wrap = ui.element('div', 'row-actions');
      const configure = ui.element('button', 'button subtle', worker.coin === 'none' ? 'set_up' : 'change'); configure.type = 'button'; configure.disabled = !catalogReady || pendingWorkers.has(worker.deviceId);
      configure.addEventListener('click', event => { event.stopPropagation(); openEditor(worker); }); wrap.append(configure);
      const running = worker.status === 'MINING';
      const actualCoin = running ? runningCoin(worker) : null;
      const controlCoin = actualCoin || worker.coin;
      if (running || worker.coin !== 'none') {
        const actualMinerId = actualCoin ? miners.find(candidate => candidate.coin === actualCoin)?.id : worker.minerSoftwareId;
        const miner = miners.find(candidate => candidate.coin === controlCoin && candidate.id === actualMinerId);
        if (miner?.downloadStatus === 'UPDATE_REQUIRED') {
          const update = ui.element('button', 'button primary', 'update_srbminer'); update.type = 'button';
          update.disabled = pendingWorkers.has(worker.deviceId);
          update.addEventListener('click', event => { event.stopPropagation(); updateMiner(worker, miner); }); wrap.append(update);
        } else {
          const starting = worker.status === 'STARTING' || pendingStarts.has(worker.deviceId);
          const toggle = ui.element('button', `button ${running ? 'subtle' : 'primary'}`, starting ? 'miner_starting' : running ? 'pause' : 'start');
          toggle.type = 'button'; toggle.disabled = pendingWorkers.has(worker.deviceId) || starting || (!running && (worker.coin === 'none' || !worker.minerInstalled));
          toggle.addEventListener('click', event => { event.stopPropagation(); action(worker, running ? 'pause' : 'start'); }); wrap.append(toggle);
        }
      }
      const bootLabel = ui.element('label', 'worker-boot-option');
      const bootCheckbox = document.createElement('input'); bootCheckbox.type = 'checkbox';
      bootCheckbox.checked = worker.startOnBoot === true;
      bootCheckbox.setAttribute('aria-description', t('worker_start_on_boot_help'));
      bootCheckbox.disabled = pendingWorkers.has(worker.deviceId) || (worker.coin === 'none' && !bootCheckbox.checked);
      bootCheckbox.addEventListener('click', event => event.stopPropagation());
      bootCheckbox.addEventListener('change', event => { event.stopPropagation(); void setStartOnBoot(worker, bootCheckbox.checked); });
      bootLabel.append(bootCheckbox, ui.element('span', '', 'worker_start_on_boot'));
      bootLabel.title = t('worker_start_on_boot_help');
      bootLabel.addEventListener('click', event => event.stopPropagation());
      wrap.append(bootLabel);
      if (worker.startOnBoot && worker.manuallyStopped)
        wrap.append(ui.element('span', 'cell-sub', 'worker_boot_paused_by_user'));
      return wrap;
    }}
  ];

  function renderTable() {
    const list = filtered(); $('worker-count').textContent = t('count_workers', {count: list.length});
    $('worker-hidden').hidden = list.length === workers.length;
    if (!$('worker-hidden').hidden) $('worker-hidden').textContent = t('count_hidden_reset_filters', {count: workers.length - list.length});
    $('idle-devices').hidden = !workers.some(worker => worker.coin === 'none');
    if (!$('idle-devices').hidden) $('idle-devices').textContent = t('count_components_are_not_assigned_to_a_minin', {count: workers.filter(worker => worker.coin === 'none').length});
    $('worker-table').replaceChildren(ui.table({columns, rows: list, empty: 'no_workers_match_the_filters', onRow: openConsole}));
  }

  function render() { renderStatus(); renderKpis(); renderScope(); renderTable(); }

  function compatibleMiners(coinId) { return miners.filter(item => item.coin === coinId && item.selectable); }
  function compatibleWithWorker(miner, worker = selected) {
    if (!worker) return false;
    return worker.hardwareType === 'CPU' ? miner.device === 'CPU'
      : (miner.compatibleGpuVendors || []).includes(String(worker.vendor || '').toUpperCase());
  }
  function populateMinerSelect(coinId, selectedId) {
    const select = $('worker-miner'); select.replaceChildren();
    const coinMiners = miners.filter(item => item.coin === coinId);
    for (const miner of coinMiners) {
      const option = document.createElement('option'); option.value = miner.id;
      const hardwareCompatible = compatibleWithWorker(miner);
      option.textContent = `${miner.name}${!hardwareCompatible ? ` · ${t('not_compatible_with_this_gpu')}` : !miner.selectable ? ` · ${t('currently_unavailable_for_assignment')}` : miner.installed ? '' : ` · ${t('not_installed')}`}`;
      option.disabled = !hardwareCompatible || !miner.selectable || !miner.installed; select.append(option);
    }
    if (selectedId && [...select.options].some(option => option.value === selectedId && !option.disabled)) select.value = selectedId;
    const chosen = coinMiners.find(miner => miner.id === select.value);
    $('worker-miner-help').textContent = coinId === 'none' ? t('no_software_required')
      : chosen && !compatibleWithWorker(chosen) ? t('select_a_miner_compatible_with_this_gpu')
      : chosen && !chosen.selectable ? s(chosen.unavailableReason || 'this_miner_software_is_currently_not_releas')
      : chosen?.installed ? t('installed_and_ready')
      : t('install_compatible_software_in_the_miner_sof');
    $('worker-miner-install').hidden = coinId === 'none' || !chosen?.selectable
      || !compatibleWithWorker(chosen) || Boolean(chosen?.installed);
    $('worker-save').disabled = (coinId !== 'none' && (!chosen || !chosen.selectable || !chosen.installed
      || !compatibleWithWorker(chosen)))
      || (coinId === 'none' && $('worker-power-mode').value !== 'MANUAL');
    select.disabled = coinId === 'none';
  }

  function openEditor(worker) {
    selected = worker;
    $('worker-editor-title').textContent = worker.hardwareModel;
    $('worker-editor-hardware').textContent = `${worker.hardwareType} · ${worker.deviceId}`;
    const coinSelect = $('worker-coin'); coinSelect.replaceChildren();
    const catalog = window.SolarMinerCoinCatalog || [];
    const ids = ['none', ...catalog.filter(row => worker.hardwareType === 'CPU'
      ? row.device === 'CPU' : row.device !== 'CPU').map(row => row.id)];
    for (const id of ids) {
      const option = document.createElement('option'); option.value = id;
      const coinMiners = miners.filter(miner => miner.coin === id);
      const compatible = coinMiners.filter(miner => compatibleWithWorker(miner));
      const unavailable = id !== 'none' && (compatible.length === 0 || !compatible.some(miner => miner.selectable));
      const reason = compatible.length === 0 ? t('no_miner_compatible_with_this_gpu')
        : compatible.find(miner => !miner.selectable)?.unavailableReason;
      option.textContent = id === 'none' ? t('unassigned_4cfcb8') : t('coin_algorithmunavailable', {
        coin: coinTicker(id),
        algorithm: coinAlgorithm(id),
        unavailable: unavailable ? ` · ${compatible.length ? t('not_enabled') : t('not_compatible_with_this_gpu')}` : ''
      });
      option.disabled = unavailable;
      if (reason) option.title = s(reason);
      coinSelect.append(option);
    }
    coinSelect.value = worker.coin; populateMinerSelect(worker.coin, worker.minerSoftwareId);
    $('worker-power-mode').value = worker.powerMode || 'MANUAL';
    $('worker-power-mode').querySelector('[value="COIN_REVENUE_PER_KWH"]').disabled = worker.hardwareType === 'CPU';
    $('worker-power-mode').querySelector('[value="MAX_HASHRATE"]').disabled = worker.hardwareType === 'CPU';
    $('worker-power-mode').querySelector('[value="PROFIT_PER_DAY"]').disabled = worker.hardwareType === 'CPU';
    $('worker-power-mode').querySelector('[value="COIN_PROFIT_PER_DAY"]').disabled = worker.hardwareType === 'CPU';
    updatePoolSummary(worker.coin, worker.poolUrl, worker.configured);
    coinSelect.onchange = () => { populateMinerSelect(coinSelect.value, null); updatePoolSummary(coinSelect.value, null, false);
      if (coinSelect.value === 'none') $('worker-power-mode').value = 'MANUAL'; };
    $('worker-power-mode').onchange = () => populateMinerSelect(coinSelect.value, $('worker-miner').value);
    $('worker-editor').showModal();
  }

  function workerConsoleId(worker) {
    const coin = worker.status === 'MINING' ? runningCoin(worker) || worker.coin : worker.coin;
    if (coin === 'none') return null;
    if (worker.hardwareType === 'CPU') return worker.coin;
    return `${coin}-${worker.vendor}-${worker.index}`;
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
      message.textContent = t('console_output_could_not_be_loaded_error', {error: s(error.message)});
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
    $('worker-console-title').textContent = t('miner_console_worker', {worker: worker.hardwareModel});
    $('worker-console-meta').textContent = `${worker.coinName || t(coinTicker(worker.coin))} · ${worker.algorithm || '—'} · ${worker.deviceId}`;
    $('worker-console-output').textContent = '';
    const download = $('worker-console-download');
    download.hidden = !consoleId;
    if (consoleId) download.href = `/api/agent/local/console/${encodeURIComponent(consoleId)}/download`;
    const message = $('worker-console-notice');
    message.hidden = Boolean(consoleId);
    message.textContent = consoleId ? '' : t('assign_a_coin_to_this_worker_first_its_miner');
    $('worker-console').showModal();
    if (consoleId) pollConsole(session);
  }

  function updatePoolSummary(coinId, poolUrl, configured) {
    const box = $('worker-pool-summary'); box.replaceChildren();
    if (coinId === 'none') { box.textContent = t('this_component_remains_unassigned_and_cannot'); return; }
    const text = ui.element('span', '', configured ? `${t('pool')}: ${poolUrl || t('solarminer_default_route')}` : t('without_your_own_wallet_the_solarminer_defau'));
    const link = ui.element('a', '', 'edit_wallet_pool'); link.href = '/wallets.html'; box.append(text, link);
  }

  async function save(event) {
    event.preventDefault();
    if (event.submitter?.value === 'cancel') { $('worker-editor').close(); return; }
    if (!selected || pendingWorkers.has(selected.deviceId)) return;
    const worker = selected;
    const assignment = {
      coin: $('worker-coin').value, minerSoftwareId: $('worker-coin').value === 'none' ? null : $('worker-miner').value
    };
    const resumeAfterAssignment = worker.status === 'MINING' && assignment.coin !== 'none'
      && (assignment.coin !== worker.coin || assignment.minerSoftwareId !== worker.minerSoftwareId);
    const powerMode = $('worker-power-mode').value;
    pendingWorkers.add(worker.deviceId);
    $('worker-editor').close();
    selected = null;
    renderTable();
    let saved = false;
    try {
      const response = await fetch(`/api/agent/local/workers/${encodeURIComponent(worker.deviceId)}/assignment`, {
        method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(assignment)
      });
      if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.message || body?.detail || `HTTP ${response.status}`); }
      saved = true;
      if (powerMode !== (worker.powerMode || 'MANUAL')) {
        const modeResponse = await fetch(`/api/agent/local/workers/${encodeURIComponent(worker.deviceId)}/power-mode`, {
          method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({mode: powerMode})
        });
        if (!modeResponse.ok) { const body = await modeResponse.json().catch(() => null); throw new Error(body?.detail || body?.message || `HTTP ${modeResponse.status}`); }
      }
      if (resumeAfterAssignment) {
        const startResponse = await fetch(`/api/agent/local/workers/${encodeURIComponent(worker.deviceId)}/start`, {method: 'POST'});
        if (!startResponse.ok) { const body = await startResponse.json().catch(() => null); throw new Error(body?.message || body?.detail || `HTTP ${startResponse.status}`); }
        if (await startResponse.json() !== true) throw new Error(t('the_worker_action_was_not_applied_check_the'));
      }
      notice(resumeAfterAssignment ? t('worker_started', {worker: worker.hardwareModel}) : t('assignment_saved'));
      await load();
    } catch (error) {
      notice(saved ? `${t('worker_action_failed')} ${s(error.message)}` : t('assignment_failed_error', {error: s(error.message)}), true, true);
      await load();
    }
    finally {
      pendingWorkers.delete(worker.deviceId);
      renderTable();
    }
  }

  async function setStartOnBoot(worker, enabled) {
    if (pendingWorkers.has(worker.deviceId)) return;
    pendingWorkers.add(worker.deviceId); renderTable();
    try {
      const response = await fetch(`/api/agent/local/workers/${encodeURIComponent(worker.deviceId)}/start-on-boot?enabled=${enabled}`, {method: 'POST'});
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.message || body?.detail || `HTTP ${response.status}`);
      }
      notice(enabled ? 'worker_boot_enabled' : 'worker_boot_disabled');
      await load();
    } catch (error) {
      notice(t('worker_action_failed') + ' ' + s(error.message), true, true);
      await load();
    } finally {
      pendingWorkers.delete(worker.deviceId); renderTable();
    }
  }

  async function action(worker, command) {
    if (pendingWorkers.has(worker.deviceId)) return;
    pendingWorkers.add(worker.deviceId);
    if (command === 'start') pendingStarts.add(worker.deviceId);
    renderTable();
    try {
      const response = await fetch(`/api/agent/local/workers/${encodeURIComponent(worker.deviceId)}/${command}`, {method: 'POST'});
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.detail || body?.message || `HTTP ${response.status}`);
      }
      if (await response.json() !== true) throw new Error(t('the_worker_action_was_not_applied_check_the'));
      notice(t(command === 'start' ? 'worker_started' : 'worker_paused', {worker: worker.hardwareModel}), false, true); await load();
    } catch (error) { notice(`${t('worker_action_failed')} ${window.SolarMinerI18n.s(error.message)}`, true, true); await load(); }
    finally {
      pendingWorkers.delete(worker.deviceId);
      pendingStarts.delete(worker.deviceId);
      renderTable();
    }
  }

  async function updateMiner(worker, miner) {
    if (pendingWorkers.has(worker.deviceId)) return;
    pendingWorkers.add(worker.deviceId); renderTable();
    try {
      const response = await fetch(`/api/agent/local/${encodeURIComponent(worker.coin)}/miners/${encodeURIComponent(miner.id)}/download`, {method: 'POST'});
      if (!response.ok || await response.json() !== true) throw new Error(`HTTP ${response.status}`);
      notice('srbminer_update_started'); await load();
    } catch (error) { notice(t('action_failed_error', {error: s(error.message)}), true, true); }
    finally { pendingWorkers.delete(worker.deviceId); renderTable(); }
  }

  async function loadMinerCatalog() {
    try {
      miners = await ui.getJson('/api/agent/local/miner-options');
      catalogReady = true;
      renderTable();
    } catch (error) {
      catalogReady = false;
      renderTable();
      notice(t('could_not_load_miner_catalog_error', {error: s(error.message)}), true, true);
    }
  }

  // Worker run state stays live over SSE. The energy journal and miner catalog are REST
  // snapshots; they change slowly or after an explicit action.
  function load() {
    const workerRefresh = window.SolarMinerLive.refresh('workers');
    window.SolarMinerLive.refresh('energy');
    loadMinerCatalog();
    loadCoinCatalog();
    return workerRefresh;
  }

  async function loadCoinCatalog() {
    if (window.SolarMinerCoinCatalog) { render(); return; }
    try {
      window.SolarMinerCoinCatalog = await ui.cachedCoinCatalog();
      render();
    } catch (_) { /* Scope chips fall back to nothing until the catalog arrives. */ }
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
  window.SolarMinerLive.channel('workers', {endpoint: '/api/agent/local/workers', maxAgeMs: 8000})
    .subscribe((list, meta) => {
      if (list) {
        // Modal controls keep their own selection. Live worker rows must still update while
        // the editor or console is open, otherwise an active miner can appear stale indefinitely.
        workers = list;
        $('connection').className = 'badge online'; $('connection').textContent = t('agent_connected');
        render();
      } else if (meta.error) {
        $('connection').className = 'badge offline'; $('connection').textContent = t('agent_unavailable');
        notice(t('could_not_load_workers_error', {error: s(meta.error.message || '')}), true, true);
      }
    });
  window.SolarMinerLive.channel('energy', {endpoint: '/api/agent/local/economics', maxAgeMs: 30000, transport: 'rest', pollMs: 30000})
    .subscribe(value => { if (value) { energy = value; renderKpis(); renderTable(); } });
  window.SolarMinerLive.channel('earnings', {endpoint: '/api/agent/local/earnings', maxAgeMs: 60000, transport: 'rest', pollMs: 60000})
    .subscribe(value => { if (value) { earnings = value; renderTable(); } });
  window.SolarMinerLive.channel('benchmarks', {endpoint: '/api/agent/local/benchmarks', maxAgeMs: 8000, persist: false})
    .subscribe((value, meta) => {
      benchmarkRunning = !meta.error && !meta.stale && value?.running === true;
      if (workers.length) renderTable();
    });
  window.SolarMinerLive.channel('efficiency', {endpoint: '/api/agent/local/efficiency', maxAgeMs: 8000, persist: false})
    .subscribe((value, meta) => {
      sweepWorkerIds = !meta.error && !meta.stale && value?.running === true
        ? new Set((value.runs || []).map(run => run.deviceId)) : new Set();
      if (workers.length) renderTable();
    });
  window.SolarMinerLive.start();
  load();
})();
