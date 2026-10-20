(() => {
  const $ = id => document.getElementById(id);
  const t = window.SolarMinerUI.t;
  const memorySetting = row => row.memoryOffsetMhz == null
    ? t('auto_tuning_memory_clock', {clock: row.memoryMhz ?? '—'})
    : t('auto_tuning_memory_offset', {offset: `${row.memoryOffsetMhz > 0 ? '+' : ''}${row.memoryOffsetMhz}`});
  const efficiencyRate = value => {
    if (!(value > 0) || !Number.isFinite(Number(value))) return '—';
    let scaled = Number(value), unit = 'H/s/W';
    for (const next of ['kH/s/W', 'MH/s/W', 'GH/s/W', 'TH/s/W']) {
      if (scaled < 1000) break;
      scaled /= 1000; unit = next;
    }
    return `${new Intl.NumberFormat(window.SolarMinerI18n.locale || 'de-DE', {maximumFractionDigits: 3}).format(scaled)} ${unit}`;
  };
  const devices = $('devices'), chartHost = $('session-charts');
  let activeSession = null, activeSessions = [], pollTimer = null, savedProfiles = [], measurementHistory = [], earnings = [];
  let tuningRows = [], chartDeviceId = null, lastSessionKey = null;
  const concurrencyInput = $('max-concurrent-auto-tuners');
  const deviceSamples = new Map();
  const fiatPerKwh = (coinId, hashrateHs, watts) => {
    const forecast = earnings.find(row => row.coin === coinId);
    if (!forecast?.available || forecast.stale || !(forecast.usdPerDay > 0)
      || !(forecast.hashrateHps > 0) || !(hashrateHs > 0) || !(watts > 0)) return '—';
    const usdPerDay = forecast.usdPerDay * hashrateHs / forecast.hashrateHps;
    const usdPerKwh = usdPerDay / (watts * 24 / 1000);
    const value = window.SolarMinerPreferences?.convert(usdPerKwh, 'USD');
    const currency = value == null ? 'USD' : window.SolarMinerPreferences.currency;
    const amount = value == null ? usdPerKwh : value;
    return `${new Intl.NumberFormat(window.SolarMinerI18n.locale || 'de-DE', {maximumFractionDigits: 4}).format(amount)} ${currency}`;
  };
  const node = (tag, className, text) => { const el = document.createElement(tag); if (className) el.className = className; if (text) el.textContent = text; return el; };
  const svgNode = (tag, attrs, content) => {
    const el = document.createElementNS('http://www.w3.org/2000/svg', tag);
    for (const [key, value] of Object.entries(attrs)) el.setAttribute(key, String(value));
    if (content != null) el.textContent = content;
    return el;
  };
  const metricNumber = value => new Intl.NumberFormat(window.SolarMinerI18n.locale || 'de-DE', {maximumFractionDigits: 2}).format(value);
  const money = value => value == null || !Number.isFinite(Number(value)) ? '—'
    : `${new Intl.NumberFormat(window.SolarMinerI18n.locale || 'de-DE', {style: 'currency', currency: 'USD', maximumFractionDigits: 2}).format(value)}`;
  function renderRunCharts(host, samples, coinId) {
    const section = node('section', 'tuning-graphs');
    const title = node('div', 'tuning-graphs-heading');
    title.append(node('h3', '', t('auto_tuning_graph_title')), node('p', '', t('auto_tuning_graph_hint', {count: samples.length})));
    section.append(title);
    const grid = node('div', 'tuning-graph-grid');
    const values = [
      {key: 'observedCoreMhz', label: 'auto_tuning_graph_core', unit: 'MHz', color: '#81b7ff'},
      {key: 'observedMemoryMhz', label: 'auto_tuning_graph_memory', unit: 'MHz', color: '#b99aff'},
      {key: 'temperatureC', label: 'auto_tuning_graph_temperature', unit: '°C', color: '#ff9b72'},
      {key: 'measuredWatts', label: 'auto_tuning_graph_power', unit: 'W', color: '#ffd166'},
      {key: 'hashrateHs', label: 'auto_tuning_graph_hashrate', unit: 'H/s', color: '#71dfb0', display: value => window.SolarMinerUI.hashrate(value)},
      {key: 'fiatPerKwh', label: 'auto_tuning_graph_fiat', unit: `${window.SolarMinerPreferences?.convert(1, 'USD') == null ? 'USD' : window.SolarMinerPreferences.currency}/kWh`, color: '#f08ac2', format: value => {
        const forecast = earnings.find(row => row.coin === coinId);
        if (!forecast?.available || forecast.stale || !(forecast.usdPerDay > 0) || !(forecast.hashrateHps > 0)) return null;
        const usd = forecast.usdPerDay * value.hashrateHs / forecast.hashrateHps / (value.measuredWatts * 24 / 1000);
        const converted = window.SolarMinerPreferences?.convert(usd, 'USD');
        return converted == null ? usd : converted;
      }},
    ];
    for (const metric of values) {
      const points = samples.map((row, index) => ({row, index, value: metric.format ? metric.format(row) : Number(row[metric.key])}))
        .filter(point => Number.isFinite(point.value) && point.value >= 0);
      const card = node('article', 'tuning-graph-card');
      card.append(node('h4', '', t(metric.label)));
      const svg = svgNode('svg', {viewBox: '0 0 520 184', role: 'img', 'aria-label': t('auto_tuning_graph_aria', {metric: t(metric.label), count: points.length})});
      if (points.length) {
        const min = Math.min(...points.map(point => point.value));
        const max = Math.max(...points.map(point => point.value));
        const span = max - min || Math.max(Math.abs(max) * .1, 1);
        const low = Math.max(0, min - span * .12), high = max + span * .12;
        const x = index => 58 + (points.length === 1 ? .5 : index / (points.length - 1)) * 442;
        const y = value => 145 - (value - low) / (high - low) * 112;
        for (let line = 0; line <= 2; line++) {
          const value = high - (high - low) * line / 2, pos = y(value);
          svg.append(svgNode('line', {x1: 58, x2: 500, y1: pos, y2: pos, class: 'tuning-graph-gridline'}));
          svg.append(svgNode('text', {x: 51, y: pos + 4, 'text-anchor': 'end', class: 'tuning-graph-axis'}, metric.display ? metric.display(value) : metricNumber(value)));
        }
        svg.append(svgNode('polyline', {points: points.map(point => `${x(point.index)},${y(point.value)}`).join(' '), fill: 'none', stroke: metric.color, class: 'tuning-graph-line'}));
        for (const point of points) {
          const dot = svgNode('circle', {cx: x(point.index), cy: y(point.value), r: points.length > 80 ? 2.2 : 3.3, fill: metric.color, class: 'tuning-graph-point'});
          const row = point.row;
          dot.append(svgNode('title', {}, `${new Date(row.at).toLocaleTimeString(window.SolarMinerI18n.locale || 'de-DE')} · ${metric.display ? metric.display(point.value) : `${metricNumber(point.value)} ${metric.unit}`} · ${t('auto_tuning_candidate', {core: row.observedCoreMhz ?? row.coreMhz ?? '—', memory: memorySetting(row), power: row.powerLimitWatts})}`));
          svg.append(dot);
        }
        svg.append(svgNode('text', {x: 58, y: 170, class: 'tuning-graph-axis'}, t('auto_tuning_graph_first')),
          svgNode('text', {x: 500, y: 170, 'text-anchor': 'end', class: 'tuning-graph-axis'}, t('auto_tuning_graph_latest')));
        const latest = points[points.length - 1];
        card.append(svg, node('strong', 'tuning-graph-current', metric.display ? metric.display(latest.value) : `${metricNumber(latest.value)} ${metric.unit}`.trim()));
      } else {
        card.append(node('p', 'tuning-graph-empty', t('auto_tuning_graph_unavailable')));
      }
      grid.append(card);
    }
    section.append(grid); host.append(section);
  }
  const adviceKey = (reason, vendor) => {
    const key = String(reason || '');
    if (key.includes('gpu_clock_permission_denied')) return 'auto_tuning_help_permission';
    if (key.includes('auto_tuning_gpu_reset_failed') || key.includes('auto_tuning_gpu_requires_attention'))
      return 'auto_tuning_help_gpu_reset';
    if (key.includes('gpu_clock_restore_failed')) return 'auto_tuning_help_restore';
    if (key.includes('gpu_tuning_recovery_save_failed')) return 'auto_tuning_help_recovery_file';
    if (key.includes('gpu_clock_target_out_of_range') || key.includes('gpu_tuning_candidate_rejected')) return 'auto_tuning_help_range';
    if (key.includes('gpu_clock_write_failed') || key.includes('auto_tuning_apply_failed')) return 'auto_tuning_help_write';
    if (key.includes('auto_tuning_clock_readback_unavailable') || key.includes('auto_tuning_clock_not_reached')) return 'auto_tuning_help_readback';
    if (key.includes('gpu_clock_controls_unavailable') || key.includes('gpu_memory_clock_control_unavailable'))
      return String(vendor || '').toUpperCase() === 'NVIDIA' ? 'auto_tuning_help_nvidia_driver' : 'auto_tuning_help_driver';
    if (key.includes('gpu_memory_clock_lock_requires_write_test')) return 'auto_tuning_help_provisional';
    return null;
  };
  const status = (message, error = false, advice = null) => {
    const el = $('notice'); el.replaceChildren(); el.className = `notice${error ? ' error' : ''}`; el.hidden = !message;
    if (message) el.append(node('span', '', message));
    if (advice) el.append(node('p', 'tuning-advice', t(advice)));
  };
  const reportError = (error, vendor) => status(t('auto_tuning_failed', {reason: t(error.message)}), true, adviceKey(error.message, vendor));
  let autoTuningDevices = new Set();
  function draw(rows) {
    devices.replaceChildren();
    if (!rows.length) { devices.append(node('p', 'muted', t('auto_tuning_no_devices'))); return; }
    for (const gpu of rows) {
      const card = node('article', 'panel tuning-card');
      const head = node('div', 'tuning-card-head'); const title = node('div', 'tuning-card-title');
      title.append(node('span', 'tuning-vendor', gpu.vendor || t('auto_tuning_gpu_label')), node('h3', '', gpu.model || gpu.deviceId), node('small', 'tuning-device-id', gpu.deviceId));
      head.append(title); card.append(head);
      const pwr = gpu.powerLimitWatts == null ? '—' : `${gpu.powerLimitWatts} W`;
      const stats = node('div', 'tuning-stats');
      for (const [label, value] of [[t('auto_tuning_power_limit'), pwr], [t('auto_tuning_measured_power'), gpu.measuredWatts == null ? '—' : `${Number(gpu.measuredWatts).toFixed(1)} W`]]) {
        const stat = node('div', 'tuning-stat'); stat.append(node('span', '', label), node('strong', '', value)); stats.append(stat);
      }
      card.append(stats);
      const caps = gpu.tuning;
      const rtx20 = gpu.vendor?.toUpperCase() === 'NVIDIA' && /RTX\s*20\d{2}/i.test(gpu.model || '');
      const capabilities = node('div', 'tuning-capabilities');
      for (const [label, supported] of [[t('auto_tuning_core_supported'), caps.coreClockControl],
        ...(!rtx20 ? [[t('auto_tuning_memory_supported'), caps.memoryClockControl || gpu.memoryOffsetControl]] : [])]) {
        capabilities.append(node('span', `tuning-capability ${supported ? 'available' : 'unavailable'}`, `${supported ? '✓' : '—'} ${label}`));
      }
      card.append(capabilities);
      if (rtx20) card.append(node('p', 'muted', t('auto_tuning_rtx20_offset_hint')));
      const selectionTitle = node('h4', 'tuning-selection-title', t('auto_tuning_selection_title'));
      const selectionHint = node('p', 'muted', t('auto_tuning_selection_hint'));
      const selectionBox = node('div', 'tuning-selection');
      const selected = gpu.selection || {core: true, memory: true, power: true};
      const fields = [
        ['core', 'auto_tuning_tune_core', caps.coreClockControl],
        ['memory', 'auto_tuning_tune_memory', caps.memoryClockControl || gpu.memoryOffsetControl],
        ['power', 'auto_tuning_tune_power', gpu.powerControl]
      ];
      for (const [key, labelKey, supported] of fields) {
        const label = node('label', 'tuning-selection-option');
        const input = node('input'); input.type = 'checkbox'; input.checked = Boolean(selected[key]);
        input.disabled = !supported;
        input.addEventListener('change', async () => {
          const next = {...selected, [key]: input.checked};
          if (!next.core && !next.memory && !next.power) {
            input.checked = true; status(t('auto_tuning_selection_required'), true); return;
          }
          try {
            const response = await fetch(`/api/agent/local/gpu-tuning/${encodeURIComponent(gpu.deviceId)}/selection`, {
              method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(next)
            });
            if (!response.ok) {
              const body = await response.json().catch(() => ({}));
              throw new Error(body.message || body.detail || 'auto_tuning_selection_save_failed');
            }
            gpu.selection = await response.json(); Object.assign(selected, gpu.selection);
            status(t('auto_tuning_selection_saved'));
          } catch (error) {
            input.checked = !input.checked;
            status(t('auto_tuning_selection_save_error', {error: t(error.message)}), true);
          }
        });
        label.append(input, node('span', '', t(labelKey))); selectionBox.append(label);
      }
      const goalLabel = node('label', 'tuning-selection-option');
      const goal = node('select');
      goal.append(new Option(t('auto_tuning_goal_efficiency'), 'EFFICIENCY'),
        new Option(t('auto_tuning_goal_profit'), 'PROFIT_PER_DAY'));
      goal.value = selected.goal || 'EFFICIENCY';
      const profitAvailable = Boolean(gpu.comparison?.profit);
      goal.addEventListener('change', async () => {
        const previous = selected.goal || 'EFFICIENCY';
        const next = {...selected, goal: goal.value};
        try {
          const response = await fetch(`/api/agent/local/gpu-tuning/${encodeURIComponent(gpu.deviceId)}/selection`, {
            method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(next)
          });
          if (!response.ok) {
            const body = await response.json().catch(() => ({}));
            throw new Error(body.message || body.detail || 'auto_tuning_selection_save_failed');
          }
          gpu.selection = await response.json(); Object.assign(selected, gpu.selection);
          status(t('auto_tuning_selection_saved'));
        } catch (error) {
          goal.value = previous;
          status(t(error.message || 'auto_tuning_selection_save_error'), true);
        }
      });
      goalLabel.append(node('span', '', t('auto_tuning_goal_label')), goal);
      selectionBox.append(goalLabel, node('p', 'muted', profitAvailable ? t('auto_tuning_goal_profit_hint') : t('auto_tuning_profit_unavailable')));
      const tuneNow = node('button', 'button tuning-run-selected', t('auto_tuning_run_selected'));
      tuneNow.type = 'button'; tuneNow.disabled = !gpu.coinId || gpu.workerStatus !== 'MINING';
      tuneNow.addEventListener('click', async () => {
        tuneNow.disabled = true;
        try {
          await send('/api/agent/local/gpu-tuning/start', {deviceId: gpu.deviceId, coinId: gpu.coinId});
          status(t('auto_tuning_run_selected_started'));
          await loadSession();
        } catch (error) { reportError(error, gpu.vendor); }
        finally { tuneNow.disabled = !gpu.coinId || gpu.workerStatus !== 'MINING'; }
      });
      selectionBox.append(tuneNow);
      card.append(selectionTitle, selectionHint, selectionBox);
      const mode = node('p', 'tuning-mode-state', t(autoTuningDevices.has(gpu.deviceId)
        ? 'auto_tuning_mode_active' : 'auto_tuning_mode_inactive'));
      card.append(mode);
      const profiles = savedProfiles.filter(row => row.deviceId === gpu.deviceId && row.coinId === gpu.coinId)
        .sort((a, b) => String(b.calibratedAt || '').localeCompare(String(a.calibratedAt || '')));
      if (profiles.length) {
        const saved = node('div', 'tuning-profiles'); saved.append(node('h4', '', t('auto_tuning_saved_profiles', {count: profiles.length})));
        for (const profile of profiles.slice(0, 6)) saved.append(node('p', '', t('auto_tuning_saved_tier', {
          software: profile.softwareId, version: profile.softwareVersion || '—', power: profile.powerLimitWatts,
          core: profile.coreMhz ?? '—', memory: memorySetting(profile),
          efficiency: efficiencyRate(profile.efficiencyHashesPerWatt),
          fiat: fiatPerKwh(profile.coinId, profile.hashrateHs, profile.measuredWatts)
        })));
        card.append(saved);
      }
      if (gpu.comparison?.efficiency) {
        const comparison = node('div', 'tuning-profiles tuning-profile-comparison');
        comparison.append(node('h4', '', t('auto_tuning_goal_comparison')));
        for (const [key, projection] of [['auto_tuning_goal_efficiency_card', gpu.comparison.efficiency],
          ['auto_tuning_goal_profit_card', gpu.comparison.profit]]) {
          if (!projection) continue;
          const profile = projection.profile;
          comparison.append(node('p', '', `${t(key)} · ${profile.powerLimitWatts} W · ${efficiencyRate(profile.efficiencyHashesPerWatt)} · ${t('auto_tuning_goal_revenue_value', {amount: money(projection.grossUsdPerDay)})} · ${t('auto_tuning_goal_cost_value', {amount: money(projection.electricityUsdPerDay)})} · ${t('auto_tuning_goal_profit_value', {amount: money(projection.profitUsdPerDay)})}`));
        }
        card.append(comparison);
      }
      const history = measurementHistory.find(row => row.deviceId === gpu.deviceId);
      if (history) {
        const saved = node('div', 'tuning-profiles');
        saved.append(node('h4', '', t('auto_tuning_history_title')),
          node('p', '', t('auto_tuning_history_counts', {samples: history.samples,
            stable: history.stable, unstable: history.unstable})));
        for (const result of [...history.recent].reverse()) {
          const score = result.hashrateHs > 0 && result.measuredWatts > 0
            ? efficiencyRate(result.hashrateHs / result.measuredWatts) : '—';
          saved.append(node('p', '', t('auto_tuning_history_point', {
            status: `${t(result.type === 'SEARCH_RESULT' ? 'auto_tuning_search_attempt' : 'auto_tuning_confirmation_attempt')} · ${t(result.stable ? 'auto_tuning_stable' : 'auto_tuning_unstable')}`,
            power: result.powerLimitWatts, core: result.coreMhz ?? '—',
            memory: memorySetting(result), efficiency: score,
            fiat: fiatPerKwh(result.coinId, result.hashrateHs, result.measuredWatts)
          }) + (result.reason ? ` · ${t(result.reason)}` : '')));
        }
        const all = node('a', '', t('auto_tuning_history_export'));
        all.href = `/api/agent/local/gpu-tuning/measurements?deviceId=${encodeURIComponent(gpu.deviceId)}&limit=0`;
        saved.append(all); card.append(saved);
      }
      const clearData = node('button', 'button subtle tuning-clear-data', t('auto_tuning_clear_data'));
      clearData.type = 'button';
      clearData.setAttribute('aria-label', t('auto_tuning_clear_data_for', {device: gpu.model || gpu.deviceId}));
      clearData.addEventListener('click', () => { void clearCalibrationData(gpu, clearData); });
      card.append(clearData);
      if (caps.reason) {
        const reason = node('div', 'tuning-reason'); reason.append(node('p', '', t(caps.reason)));
        const advice = adviceKey(caps.reason, gpu.vendor);
        if (advice) reason.append(node('p', 'tuning-advice', t(advice)));
        card.append(reason);
      } else if (String(gpu.vendor).toUpperCase() === 'NVIDIA' && !caps.memoryClockControl && caps.memoryMinMhz != null) {
        const reason = node('div', 'tuning-reason');
        reason.append(node('p', '', t('auto_tuning_memory_read_only')), node('p', 'tuning-advice', t('auto_tuning_help_nvidia_driver')));
        card.append(reason);
      }
      devices.append(card);
    }
  }
  async function clearCalibrationData(gpu, button) {
    if (!window.confirm(t('auto_tuning_clear_confirm', {device: gpu.model || gpu.deviceId}))) return;
    button.disabled = true;
    try {
      const response = await fetch(`/api/agent/local/gpu-tuning/${encodeURIComponent(gpu.deviceId)}/calibration-data`, {method: 'DELETE'});
      if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.message || body.detail || 'auto_tuning_clear_failed');
      }
      savedProfiles = savedProfiles.filter(row => row.deviceId !== gpu.deviceId);
      measurementHistory = measurementHistory.filter(row => row.deviceId !== gpu.deviceId);
      deviceSamples.delete(gpu.deviceId);
      status(t('auto_tuning_clear_success'));
      await load();
    } catch (error) {
      status(t('auto_tuning_clear_error', {error: t(error.message)}), true);
      button.disabled = false;
    }
  }
  function selectChartDevice(deviceId) {
    chartDeviceId = deviceId;
    void drawTuningCharts(true);
  }
  async function samplesForDevice(deviceId) {
    const response = await fetch(`/api/agent/local/gpu-tuning/measurements?deviceId=${encodeURIComponent(deviceId)}&limit=3000`, {cache: 'no-store'});
    if (!response.ok) throw new Error('gpu_clock_controls_unavailable');
    const all = await response.json();
    const latest = all.filter(row => row.type === 'SAMPLE').at(-1);
    const runId = latest?.runId;
    const samples = all.filter(row => row.type === 'SAMPLE' && runId && row.runId === runId);
    return {samples, coinId: latest?.coinId};
  }
  async function drawTuningCharts(refreshSelected = false) {
    chartHost.replaceChildren();
    const runningDeviceIds = new Set(activeSessions.filter(session => session.running).map(session => session.deviceId));
    const active = tuningRows.filter(row => row.powerMode === 'AUTO_TUNING' && runningDeviceIds.has(row.deviceId));
    if (!active.length) return;
    if (!active.some(row => row.deviceId === chartDeviceId)) {
      chartDeviceId = active.find(row => row.deviceId === activeSession?.deviceId)?.deviceId || active[0].deviceId;
    }
    const index = active.findIndex(row => row.deviceId === chartDeviceId);
    const selected = active[index];
    const section = node('section', 'panel tuning-worker-chart');
    const heading = node('div', 'tuning-worker-chart-heading');
    const label = node('div');
    label.append(node('p', 'kicker', t('auto_tuning_worker_history_eyebrow')),
      node('h2', '', t('auto_tuning_worker_of', {worker: selected.model || selected.deviceId, current: index + 1, total: active.length})));
    const controls = node('div', 'tuning-worker-chart-controls');
    const previous = node('button', 'button subtle', t('auto_tuning_previous_worker'));
    previous.type = 'button'; previous.disabled = active.length < 2;
    previous.setAttribute('aria-label', t('auto_tuning_previous_worker'));
    previous.addEventListener('click', () => selectChartDevice(active[(index - 1 + active.length) % active.length].deviceId));
    const next = node('button', 'button subtle', t('auto_tuning_next_worker'));
    next.type = 'button'; next.disabled = active.length < 2;
    next.setAttribute('aria-label', t('auto_tuning_next_worker'));
    next.addEventListener('click', () => selectChartDevice(active[(index + 1) % active.length].deviceId));
    controls.append(previous, next); heading.append(label, controls); section.append(heading);
    const currentRun = activeSession?.deviceId === selected.deviceId && activeSession?.running;
    if (currentRun) {
      const progress = t('auto_tuning_progress', {current: activeSession.candidateIndex, total: activeSession.candidateCount,
        model: activeSession.model || activeSession.deviceId, coin: t(activeSession.coinId || '')});
      section.append(node('p', 'muted', progress));
    }
    section.append(node('p', 'muted', t('auto_tuning_worker_history_device', {device: selected.deviceId})));
    const cached = deviceSamples.get(selected.deviceId);
    if (cached?.samples?.length) renderRunCharts(section, cached.samples, cached.coinId);
    else section.append(node('p', 'tuning-graph-empty', t('auto_tuning_no_worker_measurements')));
    chartHost.append(section);
    if (cached && !refreshSelected) return;
    try {
      const loaded = await samplesForDevice(selected.deviceId);
      if (chartDeviceId !== selected.deviceId) return;
      deviceSamples.set(selected.deviceId, loaded);
      const charts = section.querySelector('.tuning-graphs');
      if (charts) charts.remove();
      const empty = section.querySelector('.tuning-graph-empty');
      if (empty) empty.remove();
      if (loaded.samples.length) renderRunCharts(section, loaded.samples, loaded.coinId);
      else section.append(node('p', 'tuning-graph-empty', t('auto_tuning_no_worker_measurements')));
    } catch (_) {
      if (chartDeviceId === selected.deviceId && !cached?.samples?.length) {
        const empty = section.querySelector('.tuning-graph-empty');
        if (empty) empty.textContent = t('auto_tuning_measurements_unavailable');
      }
    }
  }
  async function send(url, body) {
    const response = await fetch(url, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
    if (!response.ok) { const json = await response.json().catch(() => ({})); throw new Error(json.message || json.detail || 'gpu_clock_write_failed'); }
    return response.json();
  }
  async function load() {
    try {
      const [response, profileResponse, historyResponse, earningsResponse, concurrencyResponse] = await Promise.all([
        fetch('/api/agent/local/gpu-tuning', {cache: 'no-store'}),
        fetch('/api/agent/local/gpu-tuning/profiles', {cache: 'no-store'}),
        fetch('/api/agent/local/gpu-tuning/measurement-summary', {cache: 'no-store'}),
        fetch('/api/agent/local/earnings', {cache: 'no-store'}),
        fetch('/api/agent/local/gpu-tuning/concurrency', {cache: 'no-store'})
      ]);
      if (!response.ok || !profileResponse.ok || !historyResponse.ok) throw new Error('gpu_clock_controls_unavailable');
      [savedProfiles, measurementHistory] = await Promise.all([profileResponse.json(), historyResponse.json()]);
      earnings = earningsResponse.ok ? await earningsResponse.json() : [];
      if (concurrencyResponse.ok && concurrencyInput) concurrencyInput.value = (await concurrencyResponse.json()).maxConcurrent;
      const rows = await response.json();
      tuningRows = rows;
      autoTuningDevices = new Set(rows.filter(row => row.powerMode === 'AUTO_TUNING').map(row => row.deviceId));
      draw(rows); await loadSession();
    }
    catch (error) { reportError(error); }
  }
  async function loadSession() {
    try {
      const response = await fetch('/api/agent/local/gpu-tuning/sessions', {cache: 'no-store'});
      if (!response.ok) return;
      if (pollTimer) clearTimeout(pollTimer);
      const wasRunning = activeSessions.some(session => session.running);
      activeSessions = await response.json();
      activeSession = activeSessions.find(session => session.running)
        || activeSessions.at(-1) || null;
      const devicesResponse = await fetch('/api/agent/local/gpu-tuning', {cache: 'no-store'});
      if (devicesResponse.ok) {
        const rows = await devicesResponse.json();
        tuningRows = rows;
        const next = new Set(rows.filter(row => row.powerMode === 'AUTO_TUNING').map(row => row.deviceId));
        if (next.size !== autoTuningDevices.size || [...next].some(id => !autoTuningDevices.has(id))) {
          autoTuningDevices = next;
          draw(rows);
        }
      }
      const runKey = activeSessions.map(session => `${session.deviceId || ''}:${session.startedAt || ''}`).join('|');
      if (runKey !== lastSessionKey) {
        lastSessionKey = runKey;
        if (activeSession.deviceId) deviceSamples.delete(activeSession.deviceId);
        if (!chartDeviceId) chartDeviceId = activeSession.deviceId;
      }
      drawSession();
      if (activeSession.deviceId && chartDeviceId === activeSession.deviceId) deviceSamples.delete(activeSession.deviceId);
      await drawTuningCharts();
      const anyRunning = activeSessions.some(session => session.running);
      if (wasRunning && !anyRunning) { await load(); return; }
      if (anyRunning) pollTimer = setTimeout(loadSession, 2500);
    } catch (_) { /* The device inventory remains available if a progress refresh fails. */ }
  }
  function drawSession() {
    const host = $('session-state'); host.replaceChildren();
    if (!activeSession?.running) return;
    const phase = t(activeSession.phase);
    const header = node('div', 'panel-head');
    header.append(node('h2', '', phase));
    host.append(header);
    if (activeSession.candidateCount > 0) {
      const label = t('auto_tuning_progress', {current: activeSession.candidateIndex, total: activeSession.candidateCount,
        model: activeSession.model || activeSession.deviceId, coin: t(activeSession.coinId || '')});
      host.append(node('p', 'muted', label));
      const progress = document.createElement('progress'); progress.max = activeSession.candidateCount; progress.value = activeSession.candidateIndex || 0;
      progress.setAttribute('aria-label', label); host.append(progress);
      host.append(node('p', 'muted', activeSession.currentPowerWatts == null ? t('auto_tuning_waiting_for_hashrate')
        : `${t('auto_tuning_candidate', {core: activeSession.currentCoreMhz ?? '—', memory: memorySetting({memoryMhz: activeSession.currentMemoryMhz, memoryOffsetMhz: activeSession.currentMemoryOffsetMhz}), power: activeSession.currentPowerWatts ?? '—'})} · ${t('auto_tuning_sample_progress', {current: activeSession.sampleIndex, total: activeSession.sampleCount})}`));
      if (activeSession.running) {
        const futureCandidates = Math.max(0, activeSession.candidateCount - activeSession.candidateIndex);
        const confirmationExtra = activeSession.phase === 'auto_tuning_phase_confirming'
          ? 0 : Math.min(6, futureCandidates) * 16;
        const remaining = Math.max(0, futureCandidates * activeSession.sampleCount + confirmationExtra
          + (activeSession.sampleCount - activeSession.sampleIndex));
        host.append(node('p', 'muted', t('auto_tuning_eta', {minutes: Math.ceil(remaining * 5 / 60)})));
      }
    }
    for (const row of activeSession.observations || []) {
      host.append(node('p', row.stable ? 'notice-line' : 'muted', t('auto_tuning_observation', {
        status: t(row.stable ? 'auto_tuning_stable' : 'auto_tuning_unstable'),
        hashrate: row.hashrateHs == null ? '—' : window.SolarMinerUI.hashrate(row.hashrateHs),
        watts: row.measuredWatts == null ? '—' : Number(row.measuredWatts).toFixed(1),
        efficiency: row.score > 0 ? efficiencyRate(row.score) : '—', samples: row.validSamples,
        fiat: fiatPerKwh(activeSession.coinId, row.hashrateHs, row.measuredWatts),
      }) + ` · ${t('auto_tuning_candidate', {
        core: row.coreMhz ?? '—', memory: memorySetting(row), power: row.powerWatts
      })}` + (row.observedCoreMhz == null ? '' : ` · ${t('auto_tuning_observed_clocks', {
        core: row.observedCoreMhz, memory: row.observedMemoryMhz ?? '—'
      })}`) + (row.detail ? ` · ${t(row.detail)}` : '')));
    }
    if (activeSession.result) host.append(node('p', 'notice-line', t('auto_tuning_result', {
      core: activeSession.result.coreMhz ?? '—', memory: memorySetting(activeSession.result),
      power: activeSession.result.powerLimitWatts, efficiency: efficiencyRate(activeSession.result.efficiencyHashesPerWatt),
      fiat: fiatPerKwh(activeSession.coinId, activeSession.result.hashrateHs, activeSession.result.measuredWatts)
    })));
    if (!activeSession.running && activeSession.phase !== 'auto_tuning_complete') {
      host.append(node('p', 'notice error', t(activeSession.phase)));
      const advice = adviceKey(activeSession.phase);
      if (advice) host.append(node('p', 'tuning-advice', t(advice)));
    }
  }
  $('refresh').addEventListener('click', load);
  concurrencyInput?.addEventListener('change', async () => {
    const maxConcurrent = Number(concurrencyInput.value);
    try {
      const response = await fetch('/api/agent/local/gpu-tuning/concurrency', {
        method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({maxConcurrent})
      });
      if (!response.ok) throw new Error('auto_tuning_concurrency_invalid');
      concurrencyInput.value = (await response.json()).maxConcurrent;
      status(t('auto_tuning_concurrency_saved'));
    } catch (error) {
      status(t(error.message || 'auto_tuning_concurrency_invalid'), true);
      await load();
    }
  });
  if (window.SolarMinerWorkspace) window.SolarMinerWorkspace.init();
  load();
})();
