// Shows a blocking gate only while the downloaded Stratum proxy is not ready. The agent ships no
// proxy build, so every start first resolves and downloads the published release JAR.
// State arrives over the shared "proxy-gate" SSE channel; the only REST call left is the manual retry.
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
  kicker.textContent = t('MINING-PROXY');
  const title = document.createElement('h2');
  const copy = document.createElement('p');
  title.textContent = t('Mining-Proxy wird vorbereitet');
  copy.textContent = t('Der PC-Agent sucht die neueste Proxy-Version auf GitHub.');
  const bar = document.createElement('div');
  bar.className = 'gate-progress';
  bar.setAttribute('hidden', '');
  const barFill = document.createElement('span');
  bar.append(barFill);
  const detail = document.createElement('p');
  detail.className = 'muted';
  const retry = document.createElement('button');
  retry.type = 'button';
  retry.className = 'button primary';
  retry.textContent = t('Erneut versuchen');
  retry.setAttribute('hidden', '');
  card.append(kicker, title, copy, bar, detail, retry);
  gate.append(card);
  document.body.append(gate);

  let retrying = false, closed = false, unsubscribe = null;

  function close() {
    if (closed) return;
    closed = true;
    gate.remove();
    unsubscribe?.();
  }

  function show(state, percent, version, message) {
    gate.hidden = false;
    const downloading = state === 'downloading';
    title.textContent = t(state === 'failed' ? 'Mining-Proxy nicht bereit' : 'Mining-Proxy wird vorbereitet');
    copy.textContent = t(state === 'checking' ? 'Der PC-Agent sucht die neueste Proxy-Version auf GitHub.'
      : downloading ? 'Der SolarMiner-Stratum-Proxy wird heruntergeladen.'
        : state === 'starting' ? 'Der lokale Mining-Proxy wird gestartet.'
          : state === 'running' ? 'Der lokale Mining-Proxy wird gestartet.'
            : 'Der SolarMiner-Stratum-Proxy konnte nicht geladen oder gestartet werden.');
    bar.hidden = !downloading;
    if (downloading) barFill.style.width = Math.max(2, Math.min(100, percent)) + '%';
    detail.textContent = (version ? t('Version {version}', {version}) + ' · ' : '')
      + (message || t('Ohne Proxy kann dieser PC nicht minen.'));
    retry.hidden = state !== 'failed' || retrying;
  }

  unsubscribe = window.SolarMinerLive.channel('proxy-gate',
    {endpoint: '/api/agent/local/proxy-gate', maxAgeMs: 10000})
    .subscribe((gateState, meta) => {
      if (closed) return;
      if (gateState?.ready) { close(); return; }
      if ((!gateState || !gateState.state) && meta.error) {
        // No snapshot ever arrived and the transport is failing: the agent itself is unreachable.
        show('failed', 0, '', t('Der PC-Agent ist nicht erreichbar.'));
        return;
      }
      if (gateState?.state) show(gateState.state, gateState.percent, gateState.version, gateState.detail);
    });

  // Re-read after a quiet or disconnected SSE stream. Without starting the shared watchdog here,
  // an old "starting" snapshot could keep this blocking overlay open after the proxy is ready.
  window.SolarMinerLive.start();

  retry.addEventListener('click', async () => {
    if (retrying) return;
    retrying = true;
    retry.disabled = true;
    retry.textContent = t('Erneuter Versuch läuft …');
    try {
      await window.SolarMinerUI.postJson('/api/agent/local/proxy-gate/retry');
    } catch (error) {
      detail.textContent = t('Erneuter Versuch fehlgeschlagen: {error}', {error: error.message});
    } finally {
      retrying = false;
      retry.disabled = false;
      retry.textContent = t('Erneut versuchen');
    }
    window.SolarMinerLive.refresh('proxy-gate');
  });
})();
