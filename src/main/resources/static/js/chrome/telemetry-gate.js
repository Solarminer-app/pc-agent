// Windows sensor-access banner. Reads the shared "telemetry" SSE channel instead of issuing its
// own 1.5 s poll of /telemetry — the page's telemetry view and this banner share one feed.
(() => {
  const gate = document.getElementById('lhm-gate');
  const retryButton = document.getElementById('gate-retry');
  if (!gate || !retryButton) return;
  const t = window.SolarMinerI18n.t;
  const s = window.SolarMinerI18n.s;
  let retrying = false;

  function apply(data) {
    if (!data) return;
    const windows = (data.platform || '').toLowerCase().includes('windows');
    const ready = !windows || data.sensorServiceStatus === 'available';
    gate.hidden = ready;
    if (windows && !ready) {
      const starting = data.sensorServiceStatus === 'starting';
      document.getElementById('gate-copy').textContent = t(starting
        ? 'Bitte bestätige die Windows-Sicherheitsabfrage. Der PC-Agent wartet auf die Administratorfreigabe.'
        : 'LibreHardwareMonitor läuft nicht. Windows benötigt eine Administratorfreigabe, um den Hardware-Monitor neu zu starten.');
      document.getElementById('gate-detail').textContent = s(data.sensorServiceDetail || '');
      retryButton.disabled = retrying || starting;
      retryButton.textContent = t(starting ? 'Warte auf Windows-Freigabe …' : 'LibreHardwareMonitor neu starten');
    }
  }

  retryButton.addEventListener('click', async () => {
    if (retrying) return;
    retrying = true;
    retryButton.disabled = true;
    retryButton.textContent = t('Windows-Freigabe wird angefordert …');
    try {
      await window.SolarMinerUI.postJson('/api/agent/local/telemetry/restart');
    } catch (error) {
      document.getElementById('gate-detail').textContent =
        t('Neustart konnte nicht angefordert werden: {error}', {error: error.message});
    } finally {
      retrying = false;
      window.SolarMinerLive.refresh('telemetry');
    }
  });

  window.SolarMinerLive.channel('telemetry',
    {endpoint: '/api/agent/local/telemetry', maxAgeMs: 10000}).subscribe(apply);
})();
