// Shows a blocking gate only while the downloaded Stratum proxy is not ready. The agent ships no
// proxy build, so every start first resolves and downloads the published release JAR.
// State arrives over SSE and is checked directly while this blocking gate is visible.
(() => {
  const t = window.SolarMinerI18n.t;

  const gate = document.createElement('div');
  gate.id = 'proxy-gate';
  gate.className = 'gate';
  gate.hidden = true;
  gate.setAttribute('role', 'status');
  gate.setAttribute('aria-live', 'polite');
  const card = document.createElement('section');
  card.className = 'gate-card';
  const kicker = document.createElement('p');
  kicker.className = 'kicker';
  kicker.textContent = t('mining_proxy');
  const title = document.createElement('h2');
  const copy = document.createElement('p');
  title.textContent = t('preparing_the_mining_proxy');
  copy.textContent = t('the_pc_agent_is_looking_for_the_newest_proxy');
  const bar = document.createElement('div');
  bar.className = 'gate-progress';
  bar.setAttribute('hidden', '');
  const barFill = document.createElement('span');
  bar.append(barFill);
  const detail = document.createElement('p');
  detail.className = 'muted';
  const logLabel = document.createElement('p');
  logLabel.className = 'proxy-gate-log-label';
  logLabel.textContent = t('proxy_log');
  const log = document.createElement('pre');
  log.className = 'proxy-gate-log';
  log.hidden = true;
  log.setAttribute('aria-live', 'off');
  const retry = document.createElement('button');
  retry.type = 'button';
  retry.className = 'button primary';
  retry.textContent = t('try_again');
  retry.setAttribute('hidden', '');
  card.append(kicker, title, copy, bar, detail, logLabel, log, retry);
  gate.append(card);
  document.body.append(gate);

  let retrying = false, closed = false, unsubscribe = null, logOffset = 0, logTimer = null, statusTimer = null;

  function close() {
    if (closed) return;
    closed = true;
    gate.remove();
    unsubscribe?.();
    if (logTimer) window.clearInterval(logTimer);
    if (statusTimer) window.clearInterval(statusTimer);
  }

  function appendLog(data) {
    if (!data) return;
    log.hidden = false;
    log.textContent += data;
    log.scrollTop = log.scrollHeight;
  }

  async function refreshLog() {
    if (closed) return;
    try {
      const response = await fetch('/api/agent/local/proxy/gate/log?offset=' + encodeURIComponent(logOffset),
        {cache: 'no-store'});
      if (!response.ok) return;
      const chunk = await response.json();
      appendLog(chunk.data);
      logOffset = chunk.nextOffset;
      if (chunk.hasMore) window.setTimeout(refreshLog, 0);
    } catch (_) {
      // The state channel provides the user-visible agent connection error; retry on the next tick.
    }
  }

  function show(state, percent, version, message) {
    gate.hidden = false;
    const downloading = state === 'downloading';
    title.textContent = t(state === 'failed' ? 'mining_proxy_not_ready' : 'preparing_the_mining_proxy');
    copy.textContent = t(state === 'checking' ? 'the_pc_agent_is_looking_for_the_newest_proxy'
      : downloading ? 'the_solarminer_stratum_proxy_is_downloading'
        : state === 'starting' ? 'the_local_mining_proxy_is_starting'
          : state === 'running' ? 'the_local_mining_proxy_is_starting'
            : 'the_solarminer_stratum_proxy_could_not_be_do');
    bar.hidden = !downloading;
    if (downloading) barFill.style.width = Math.max(2, Math.min(100, percent)) + '%';
    detail.textContent = (version ? t('version_version', {version}) + ' · ' : '')
      + (window.SolarMinerI18n.s(message) || t('this_pc_cannot_mine_without_a_proxy'));
    retry.hidden = state !== 'failed' || retrying;
  }

  unsubscribe = window.SolarMinerLive.channel('proxy-gate',
    {endpoint: '/api/agent/local/proxy/gate', maxAgeMs: 10000})
    .subscribe((gateState, meta) => {
      if (closed) return;
      if (gateState?.ready) { close(); return; }
      if ((!gateState || !gateState.state) && meta.error) {
        // No snapshot ever arrived and the transport is failing: the agent itself is unreachable.
        show('failed', 0, '', t('the_pc_agent_is_unreachable'));
        return;
      }
      if (gateState?.state) show(gateState.state, gateState.percent, gateState.version, gateState.detail);
    });

  // Re-read after a quiet or disconnected SSE stream. Without starting the shared watchdog here,
  // an old "starting" snapshot could keep this blocking overlay open after the proxy is ready.
  window.SolarMinerLive.start();
  window.SolarMinerLive.refresh('proxy-gate');
  statusTimer = window.setInterval(() => {
    if (!document.hidden) window.SolarMinerLive.refresh('proxy-gate');
  }, 2000);
  refreshLog();
  logTimer = window.setInterval(refreshLog, 750);

  retry.addEventListener('click', async () => {
    if (retrying) return;
    retrying = true;
    retry.disabled = true;
    retry.textContent = t('retrying');
    try {
      await window.SolarMinerUI.postJson('/api/agent/local/proxy/gate/retry');
    } catch (error) {
      detail.textContent = t('retry_failed_error', {error: window.SolarMinerI18n.s(error.message)});
    } finally {
      retrying = false;
      retry.disabled = false;
      retry.textContent = t('try_again');
    }
    window.SolarMinerLive.refresh('proxy-gate');
  });
})();
