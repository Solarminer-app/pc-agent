(() => {
  const $ = id => document.getElementById(id);
  const t = window.SolarMinerUI.t;
  const devices = $('devices');
  let activeSession = null, pollTimer = null, savedProfiles = [];
  const node = (tag, className, text) => { const el = document.createElement(tag); if (className) el.className = className; if (text) el.textContent = text; return el; };
  const status = (message, error = false) => { const el = $('notice'); el.textContent = message; el.className = `notice${error ? ' error' : ''}`; el.hidden = !message; };
  const numberField = (label, value, min, max, disabled) => {
    const wrap = node('label', 'field'); wrap.append(node('span', '', label));
    const input = node('input'); input.type = 'number'; input.required = true; input.value = value ?? '';
    if (min != null) input.min = min; if (max != null) input.max = max; input.disabled = disabled;
    wrap.append(input); return {wrap, input};
  };
  function draw(rows) {
    devices.replaceChildren();
    if (!rows.length) { devices.append(node('p', 'muted', t('auto_tuning_no_devices'))); return; }
    for (const gpu of rows) {
      const card = node('article', 'panel');
      const head = node('div', 'panel-head'); const title = node('div');
      title.append(node('p', 'kicker', `${gpu.vendor} · ${gpu.deviceId}`), node('h2', '', gpu.model || gpu.deviceId));
      head.append(title); card.append(head);
      const pwr = gpu.powerLimitWatts == null ? '—' : `${gpu.powerLimitWatts} W`;
      card.append(node('p', 'muted', `${t('auto_tuning_power_limit')}: ${pwr} · ${gpu.measuredWatts == null ? '—' : `${Number(gpu.measuredWatts).toFixed(1)} W`}`));
      const caps = gpu.tuning;
      const details = node('p', 'muted', `${t('auto_tuning_core_supported')}: ${caps.coreClockControl ? '✓' : '—'} · ${t('auto_tuning_memory_supported')}: ${caps.memoryClockControl ? '✓' : '—'} · ${t('auto_tuning_voltage_unavailable')}`);
      card.append(details);
      const tuneButton = node('button', 'button primary', t('auto_tuning_start'));
      tuneButton.dataset.action = 'calibrate'; tuneButton.dataset.coin = gpu.coinId || ''; tuneButton.dataset.workerStatus = gpu.workerStatus || '';
      tuneButton.dataset.canTune = String(caps.coreClockControl || caps.memoryClockControl);
      tuneButton.type = 'button';
      tuneButton.disabled = !gpu.coinId || gpu.workerStatus !== 'MINING' || Boolean(activeSession?.running);
      tuneButton.addEventListener('click', async () => {
        tuneButton.disabled = true; status('');
        try { await send('/api/agent/local/gpu-tuning/start', {deviceId: gpu.deviceId, coinId: gpu.coinId}); await loadSession(); }
        catch (error) { status(t('auto_tuning_failed', {reason: t(error.message)}), true); tuneButton.disabled = false; }
      });
      card.append(tuneButton);
      const profiles = savedProfiles.filter(row => row.deviceId === gpu.deviceId && row.coinId === gpu.coinId)
        .sort((a, b) => a.powerLimitWatts - b.powerLimitWatts);
      if (profiles.length) {
        card.append(node('h3', '', t('auto_tuning_saved_matrix')));
        for (const profile of profiles) card.append(node('p', 'muted', t('auto_tuning_saved_tier', {
          software: profile.softwareId, version: profile.softwareVersion || '—', power: profile.powerLimitWatts,
          core: profile.coreMhz ?? '—', memory: profile.memoryMhz ?? '—',
          efficiency: Number(profile.efficiencyHashesPerWatt).toFixed(3)
        })));
      }
      const form = node('form', 'form-grid');
      const coreMin = numberField(t('auto_tuning_core_range'), caps.coreMinMhz, caps.coreMinMhz, caps.coreMaxMhz, !caps.coreClockControl);
      const coreMax = numberField(t('auto_tuning_core_range'), caps.coreMaxMhz, caps.coreMinMhz, caps.coreMaxMhz, !caps.coreClockControl);
      const memMin = numberField(t('auto_tuning_memory_range'), caps.memoryMinMhz, caps.memoryMinMhz, caps.memoryMaxMhz, !caps.memoryClockControl);
      const memMax = numberField(t('auto_tuning_memory_range'), caps.memoryMaxMhz, caps.memoryMinMhz, caps.memoryMaxMhz, !caps.memoryClockControl);
      coreMin.wrap.querySelector('span').textContent += ` · ${t('minimum')}`;
      coreMax.wrap.querySelector('span').textContent += ` · ${t('maximum')}`;
      memMin.wrap.querySelector('span').textContent += ` · ${t('minimum')}`;
      memMax.wrap.querySelector('span').textContent += ` · ${t('maximum')}`;
      form.append(coreMin.wrap, coreMax.wrap, memMin.wrap, memMax.wrap);
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
        } catch (error) { status(t('auto_tuning_failed', {reason: t(error.message)}), true); }
        finally { apply.disabled = !caps.coreClockControl && !caps.memoryClockControl; }
      });
      restore.addEventListener('click', async () => {
        restore.disabled = true;
        try { await send(`/api/agent/local/gpu-tuning/${encodeURIComponent(gpu.deviceId)}/restore`, {}); status(t('auto_tuning_restored')); await load(); }
        catch (error) { status(t('auto_tuning_failed', {reason: t(error.message)}), true); }
        finally { restore.disabled = false; }
      });
      card.append(form);
      const ranges = [];
      if (caps.coreClockControl) ranges.push(t('auto_tuning_range_hint', {min: caps.coreMinMhz, max: caps.coreMaxMhz}));
      if (caps.memoryClockControl) ranges.push(t('auto_tuning_range_hint', {min: caps.memoryMinMhz, max: caps.memoryMaxMhz}));
      if (ranges.length) card.append(node('p', 'muted', ranges.join(' · ')));
      if (caps.reason) card.append(node('p', 'notice-line', t(caps.reason)));
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
      const [response, profileResponse] = await Promise.all([
        fetch('/api/agent/local/gpu-tuning', {cache: 'no-store'}),
        fetch('/api/agent/local/gpu-tuning/profiles', {cache: 'no-store'})
      ]);
      if (!response.ok || !profileResponse.ok) throw new Error('gpu_clock_controls_unavailable');
      savedProfiles = await profileResponse.json(); draw(await response.json()); await loadSession();
    }
    catch (error) { status(t('auto_tuning_failed', {reason: t(error.message)}), true); }
  }
  async function loadSession() {
    try {
      const response = await fetch('/api/agent/local/gpu-tuning/session', {cache: 'no-store'});
      if (!response.ok) return;
      if (pollTimer) clearTimeout(pollTimer);
      const wasRunning = Boolean(activeSession?.running);
      activeSession = await response.json(); drawSession(); syncButtons();
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
    for (const row of activeSession.observations || []) {
      host.append(node('p', row.stable ? 'notice-line' : 'muted', t('auto_tuning_observation', {
        status: t(row.stable ? 'auto_tuning_stable' : 'auto_tuning_unstable'),
        hashrate: row.hashrateHs == null ? '—' : window.SolarMinerUI.hashrate(row.hashrateHs),
        watts: row.measuredWatts == null ? '—' : Number(row.measuredWatts).toFixed(1),
        efficiency: row.score > 0 ? Number(row.score).toFixed(3) : '—', samples: row.validSamples,
      }) + (row.observedCoreMhz == null ? '' : ` · ${t('auto_tuning_observed_clocks', {
        core: row.observedCoreMhz, memory: row.observedMemoryMhz ?? '—'
      })}`) + (row.detail ? ` · ${t(row.detail)}` : '')));
    }
    if (activeSession.result) host.append(node('p', 'notice-line', t('auto_tuning_result', {
      core: activeSession.result.coreMhz ?? '—', memory: activeSession.result.memoryMhz ?? '—',
      power: activeSession.result.powerLimitWatts, efficiency: Number(activeSession.result.efficiencyHashesPerWatt).toFixed(3)
    })));
    if (!activeSession.running && activeSession.phase !== 'auto_tuning_complete')
      host.append(node('p', 'notice error', t(activeSession.phase)));
  }
  $('refresh').addEventListener('click', load);
  if (window.SolarMinerWorkspace) window.SolarMinerWorkspace.init();
  load();
})();
