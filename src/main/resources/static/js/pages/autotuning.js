(() => {
  const $ = id => document.getElementById(id);
  const t = window.SolarMinerUI.t;
  const efficiencyRate = value => {
    if (!(value > 0) || !Number.isFinite(Number(value))) return '—';
    let scaled = Number(value), unit = 'H/s/W';
    for (const next of ['kH/s/W', 'MH/s/W', 'GH/s/W', 'TH/s/W']) {
      if (scaled < 1000) break;
      scaled /= 1000; unit = next;
    }
    return `${new Intl.NumberFormat(window.SolarMinerI18n.locale || 'de-DE', {maximumFractionDigits: 3}).format(scaled)} ${unit}`;
  };
  const devices = $('devices');
  let activeSession = null, pollTimer = null, savedProfiles = [], measurementHistory = [], earnings = [];
  let runMeasurements = [], loadedRunKey = null;
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
          dot.append(svgNode('title', {}, `${new Date(row.at).toLocaleTimeString(window.SolarMinerI18n.locale || 'de-DE')} · ${metric.display ? metric.display(point.value) : `${metricNumber(point.value)} ${metric.unit}`} · ${t('auto_tuning_candidate', {core: row.observedCoreMhz ?? row.coreMhz ?? '—', memory: row.observedMemoryMhz ?? row.memoryMhz ?? '—', power: row.powerLimitWatts})}`));
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
  const numberField = (label, value, min, max, disabled, shortLabel) => {
    const wrap = node('label', 'field'); wrap.append(node('span', '', shortLabel || label));
    const input = node('input'); input.type = 'number'; input.required = true; input.value = value ?? '';
    input.setAttribute('aria-label', label);
    if (min != null) input.min = min; if (max != null) input.max = max; input.disabled = disabled;
    wrap.append(input); return {wrap, input};
  };
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
      const capabilities = node('div', 'tuning-capabilities');
      for (const [label, supported] of [[t('auto_tuning_core_supported'), caps.coreClockControl], [t('auto_tuning_memory_supported'), caps.memoryClockControl]]) {
        capabilities.append(node('span', `tuning-capability ${supported ? 'available' : 'unavailable'}`, `${supported ? '✓' : '—'} ${label}`));
      }
      card.append(capabilities);
      const calibration = node('div', 'tuning-calibration');
      const calibrationCopy = node('div'); calibrationCopy.append(node('strong', '', t('auto_tuning_calibration_title')), node('p', '', t('auto_tuning_calibration_hint')));
      const prerequisite = !gpu.coinId ? 'auto_tuning_requires_gpu_coin'
        : gpu.workerStatus !== 'MINING' ? 'auto_tuning_requires_active_worker'
          : !caps.coreClockControl && !caps.memoryClockControl ? 'gpu_clock_controls_unavailable' : null;
      if (prerequisite) calibrationCopy.append(node('p', 'tuning-prerequisite', t(prerequisite)));
      calibration.append(calibrationCopy);
      const tuneButton = node('button', 'button primary', t('auto_tuning_start'));
      tuneButton.dataset.action = 'calibrate'; tuneButton.dataset.coin = gpu.coinId || ''; tuneButton.dataset.workerStatus = gpu.workerStatus || '';
      tuneButton.dataset.canTune = String(caps.coreClockControl || caps.memoryClockControl);
      tuneButton.type = 'button';
      tuneButton.disabled = Boolean(prerequisite) || Boolean(activeSession?.running);
      tuneButton.addEventListener('click', async () => {
        tuneButton.disabled = true; status('');
        try { await send('/api/agent/local/gpu-tuning/start', {deviceId: gpu.deviceId, coinId: gpu.coinId}); await loadSession(); }
        catch (error) { reportError(error, gpu.vendor); tuneButton.disabled = false; }
      });
      calibration.append(tuneButton); card.append(calibration);
      const profiles = savedProfiles.filter(row => row.deviceId === gpu.deviceId && row.coinId === gpu.coinId)
        .sort((a, b) => String(b.calibratedAt || '').localeCompare(String(a.calibratedAt || '')));
      if (profiles.length) {
        const saved = node('div', 'tuning-profiles'); saved.append(node('h4', '', t('auto_tuning_saved_profiles', {count: profiles.length})));
        for (const profile of profiles.slice(0, 6)) saved.append(node('p', '', t('auto_tuning_saved_tier', {
          software: profile.softwareId, version: profile.softwareVersion || '—', power: profile.powerLimitWatts,
          core: profile.coreMhz ?? '—', memory: profile.memoryMhz ?? '—',
          efficiency: efficiencyRate(profile.efficiencyHashesPerWatt),
          fiat: fiatPerKwh(profile.coinId, profile.hashrateHs, profile.measuredWatts)
        })));
        card.append(saved);
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
            status: t(result.stable ? 'auto_tuning_stable' : 'auto_tuning_unstable'),
            power: result.powerLimitWatts, core: result.coreMhz ?? '—',
            memory: result.memoryMhz ?? '—', efficiency: score,
            fiat: fiatPerKwh(result.coinId, result.hashrateHs, result.measuredWatts)
          }) + (result.reason ? ` · ${t(result.reason)}` : '')));
        }
        const all = node('a', '', t('auto_tuning_history_export'));
        all.href = `/api/agent/local/gpu-tuning/measurements?deviceId=${encodeURIComponent(gpu.deviceId)}&limit=0`;
        saved.append(all); card.append(saved);
      }
      const form = node('form', 'tuning-form');
      form.append(node('h4', '', t('auto_tuning_manual_title')));
      const coreMin = numberField(`${t('auto_tuning_core_range')} · ${t('minimum')}`, caps.coreMinMhz, caps.coreMinMhz, caps.coreMaxMhz, !caps.coreClockControl, t('minimum'));
      const coreMax = numberField(`${t('auto_tuning_core_range')} · ${t('maximum')}`, caps.coreMaxMhz, caps.coreMinMhz, caps.coreMaxMhz, !caps.coreClockControl, t('maximum'));
      const memMin = numberField(`${t('auto_tuning_memory_range')} · ${t('minimum')}`, caps.memoryMinMhz, caps.memoryMinMhz, caps.memoryMaxMhz, !caps.memoryClockControl, t('minimum'));
      const memMax = numberField(`${t('auto_tuning_memory_range')} · ${t('maximum')}`, caps.memoryMaxMhz, caps.memoryMinMhz, caps.memoryMaxMhz, !caps.memoryClockControl, t('maximum'));
      const rangeGroup = (name, minField, maxField, supported, hint) => {
        const group = node('div', 'tuning-range-group');
        const heading = node('div', 'tuning-range-heading'); heading.append(node('strong', '', name), node('span', '',
          supported ? hint : minField.input.value && maxField.input.value ? `${hint} · ${t('auto_tuning_not_supported')}` : t('auto_tuning_not_supported')));
        const fields = node('div', 'tuning-range-fields'); fields.append(minField.wrap, maxField.wrap);
        group.append(heading, fields); return group;
      };
      form.append(rangeGroup(t('auto_tuning_core_range'), coreMin, coreMax, caps.coreClockControl, t('auto_tuning_range_hint', {min: caps.coreMinMhz, max: caps.coreMaxMhz})),
        rangeGroup(t('auto_tuning_memory_range'), memMin, memMax, caps.memoryClockControl, t('auto_tuning_range_hint', {min: caps.memoryMinMhz, max: caps.memoryMaxMhz})));
      const actions = node('div', 'button-row');
      const apply = node('button', 'button primary', t('auto_tuning_apply')); apply.type = 'submit';
      const restore = node('button', 'button subtle', t('auto_tuning_restore')); restore.type = 'button';
      apply.dataset.action = 'apply'; restore.dataset.action = 'restore';
      apply.dataset.unsupported = String(!caps.coreClockControl && !caps.memoryClockControl);
      restore.disabled = !caps.coreClockControl && !caps.memoryClockControl;
      apply.disabled = (!caps.coreClockControl && !caps.memoryClockControl) || Boolean(activeSession?.running);
      form.append(actions); actions.append(apply, restore);
      form.addEventListener('submit', async event => {
        event.preventDefault(); status(''); apply.disabled = true;
        try {
          await send(`/api/agent/local/gpu-tuning/${encodeURIComponent(gpu.deviceId)}/apply`, {
            coreMinMhz: caps.coreClockControl ? Number(coreMin.input.value) : null,
            coreMaxMhz: caps.coreClockControl ? Number(coreMax.input.value) : null,
            memoryMinMhz: caps.memoryClockControl ? Number(memMin.input.value) : null,
            memoryMaxMhz: caps.memoryClockControl ? Number(memMax.input.value) : null
          }); status(t('auto_tuning_saved')); await load();
        } catch (error) { reportError(error, gpu.vendor); }
        finally { apply.disabled = !caps.coreClockControl && !caps.memoryClockControl; }
      });
      restore.addEventListener('click', async () => {
        restore.disabled = true;
        try { await send(`/api/agent/local/gpu-tuning/${encodeURIComponent(gpu.deviceId)}/restore`, {}); status(t('auto_tuning_restored')); await load(); }
        catch (error) { reportError(error, gpu.vendor); }
        finally { restore.disabled = false; }
      });
      card.append(form);
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
  async function send(url, body) {
    const response = await fetch(url, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
    if (!response.ok) { const json = await response.json().catch(() => ({})); throw new Error(json.message || json.detail || 'gpu_clock_write_failed'); }
    return response.json();
  }
  async function load() {
    try {
      const [response, profileResponse, historyResponse, earningsResponse] = await Promise.all([
        fetch('/api/agent/local/gpu-tuning', {cache: 'no-store'}),
        fetch('/api/agent/local/gpu-tuning/profiles', {cache: 'no-store'}),
        fetch('/api/agent/local/gpu-tuning/measurement-summary', {cache: 'no-store'}),
        fetch('/api/agent/local/earnings', {cache: 'no-store'})
      ]);
      if (!response.ok || !profileResponse.ok || !historyResponse.ok) throw new Error('gpu_clock_controls_unavailable');
      [savedProfiles, measurementHistory] = await Promise.all([profileResponse.json(), historyResponse.json()]);
      earnings = earningsResponse.ok ? await earningsResponse.json() : [];
      draw(await response.json()); await loadSession();
    }
    catch (error) { reportError(error); }
  }
  async function loadSession() {
    try {
      const response = await fetch('/api/agent/local/gpu-tuning/session', {cache: 'no-store'});
      if (!response.ok) return;
      if (pollTimer) clearTimeout(pollTimer);
      const wasRunning = Boolean(activeSession?.running);
      activeSession = await response.json();
      const runKey = `${activeSession.deviceId || ''}:${activeSession.startedAt || ''}`;
      if (runKey !== loadedRunKey) { runMeasurements = []; loadedRunKey = runKey; }
      drawSession(); syncButtons();
      if (activeSession.deviceId) {
        const measurementsResponse = await fetch(`/api/agent/local/gpu-tuning/measurements?deviceId=${encodeURIComponent(activeSession.deviceId)}&limit=3000`, {cache: 'no-store'});
        if (measurementsResponse.ok) {
          const all = await measurementsResponse.json();
          runMeasurements = all.filter(row => row.type === 'SAMPLE' && row.coinId === activeSession.coinId
            && row.at && new Date(row.at).getTime() >= new Date(activeSession.startedAt).getTime());
          drawSession();
        }
      }
      if (wasRunning && !activeSession.running) { await load(); return; }
      if (activeSession.running) pollTimer = setTimeout(loadSession, 2500);
    } catch (_) { /* The device inventory remains available if a progress refresh fails. */ }
  }
  function syncButtons() {
    const running = Boolean(activeSession?.running);
    for (const card of devices.children) {
      const tune = card.querySelector('[data-action="calibrate"]');
      if (tune) tune.disabled = running || !tune.dataset.coin || tune.dataset.workerStatus !== 'MINING' || tune.dataset.canTune !== 'true';
      const apply = card.querySelector('[data-action="apply"]');
      if (apply) apply.disabled = running || apply.dataset.unsupported === 'true';
      const restore = card.querySelector('[data-action="restore"]');
      if (restore) restore.disabled = running || card.querySelector('[data-action="apply"]')?.dataset.unsupported === 'true';
    }
  }
  function drawSession() {
    const host = $('session-state'); host.replaceChildren();
    if (!activeSession || activeSession.phase === 'idle') return;
    const phase = t(activeSession.phase);
    const header = node('div', 'panel-head');
    header.append(node('h2', '', phase));
    if (activeSession.running) {
      const cancel = node('button', 'button subtle', t('auto_tuning_cancel')); cancel.type = 'button';
      cancel.addEventListener('click', async () => { cancel.disabled = true; await send('/api/agent/local/gpu-tuning/cancel', {}); await loadSession(); });
      header.append(cancel);
    }
    host.append(header);
    if (activeSession.candidateCount > 0) {
      const label = t('auto_tuning_progress', {current: activeSession.candidateIndex, total: activeSession.candidateCount,
        model: activeSession.model || activeSession.deviceId, coin: t(activeSession.coinId || '')});
      host.append(node('p', 'muted', label));
      const progress = document.createElement('progress'); progress.max = activeSession.candidateCount; progress.value = activeSession.candidateIndex || 0;
      progress.setAttribute('aria-label', label); host.append(progress);
      host.append(node('p', 'muted', activeSession.currentPowerWatts == null ? t('auto_tuning_waiting_for_hashrate')
        : `${t('auto_tuning_candidate', {core: activeSession.currentCoreMhz ?? '—', memory: activeSession.currentMemoryMhz ?? '—', power: activeSession.currentPowerWatts ?? '—'})} · ${t('auto_tuning_sample_progress', {current: activeSession.sampleIndex, total: activeSession.sampleCount})}`));
      if (activeSession.running) {
        const remaining = Math.max(0, (activeSession.candidateCount - activeSession.candidateIndex) * activeSession.sampleCount
          + (activeSession.sampleCount - activeSession.sampleIndex));
        host.append(node('p', 'muted', t('auto_tuning_eta', {minutes: Math.ceil(remaining * 5 / 60)})));
      }
    }
    if (runMeasurements.length) renderRunCharts(host, runMeasurements, activeSession.coinId);
    for (const row of activeSession.observations || []) {
      host.append(node('p', row.stable ? 'notice-line' : 'muted', t('auto_tuning_observation', {
        status: t(row.stable ? 'auto_tuning_stable' : 'auto_tuning_unstable'),
        hashrate: row.hashrateHs == null ? '—' : window.SolarMinerUI.hashrate(row.hashrateHs),
        watts: row.measuredWatts == null ? '—' : Number(row.measuredWatts).toFixed(1),
        efficiency: row.score > 0 ? efficiencyRate(row.score) : '—', samples: row.validSamples,
        fiat: fiatPerKwh(activeSession.coinId, row.hashrateHs, row.measuredWatts),
      }) + ` · ${t('auto_tuning_candidate', {
        core: row.coreMhz ?? '—', memory: row.memoryMhz ?? '—', power: row.powerWatts
      })}` + (row.observedCoreMhz == null ? '' : ` · ${t('auto_tuning_observed_clocks', {
        core: row.observedCoreMhz, memory: row.observedMemoryMhz ?? '—'
      })}`) + (row.detail ? ` · ${t(row.detail)}` : '')));
    }
    if (activeSession.result) host.append(node('p', 'notice-line', t('auto_tuning_result', {
      core: activeSession.result.coreMhz ?? '—', memory: activeSession.result.memoryMhz ?? '—',
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
  if (window.SolarMinerWorkspace) window.SolarMinerWorkspace.init();
  load();
})();
