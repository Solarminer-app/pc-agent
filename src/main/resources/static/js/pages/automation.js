(() => {
  const ui = window.SolarMinerUI;
  const $ = id => document.getElementById(id);
  const t = ui.t;
  let settings = null;
  let workers = [];
  let saving = false;

  const notice = (message, error = false) => {
    const box = $('notice'); box.textContent = t(message); box.className = `notice${error ? ' error' : ''}`; box.hidden = false;
  };
  const workerName = worker => worker.hardwareModel || worker.deviceId;

  function renderSummary() {
    const target = $('automation-summary'); target.replaceChildren();
    const enabled = Boolean(settings?.externalControlEnabled);
    const selected = workers.filter(worker => worker.coin !== 'none' && worker.externalControlEnabled).length;
    const add = (label, value, tone) => {
      const item = ui.element('span', 'strip-item'); item.append(ui.element('span', '', `${t(label)}: `));
      const pill = ui.statusPill(null, t(value)); if (tone) pill.classList.add(tone); item.append(pill); target.append(item);
    };
    add('Remote-Steuerung', enabled ? 'aktiv' : 'deaktiviert', enabled ? 'tone-ok' : 'tone-warn');
    add('Dev-Fee', enabled ? 'Node-Tarif' : 'Proxy-Tarif · 1 %', enabled ? 'tone-ok' : 'tone-warn');
    add('Freigegebene Worker', `${selected}/${workers.filter(worker => worker.coin !== 'none').length}`, selected ? 'tone-ok' : 'tone-warn');
  }

  function renderWorkers() {
    const target = $('automation-workers'); target.replaceChildren();
    const configured = workers.filter(worker => worker.coin !== 'none');
    $('worker-count').textContent = t('{count} konfigurierte Worker', {count: configured.length});
    if (!configured.length) {
      target.append(ui.element('p', 'muted', t('Noch keine Worker eingerichtet.')));
      return;
    }
    for (const worker of configured) {
      const card = ui.element('article', `automation-device${worker.externalControlEnabled ? '' : ' local-only'}`);
      const head = ui.element('div', 'automation-device-head');
      head.append(ui.element('strong', '', workerName(worker)), ui.element('span', `tag ${worker.externalControlEnabled ? 'ok' : ''}`, t(worker.externalControlEnabled ? 'Für Node freigegeben' : 'Nur lokal')));
      const description = ui.element('small', '', `${worker.hardwareType} · ${worker.coinName || worker.coin} · ${worker.algorithm || '—'}`);
      const row = ui.element('label', 'automation-switch');
      const input = document.createElement('input'); input.type = 'checkbox'; input.checked = Boolean(worker.externalControlEnabled); input.disabled = saving || !settings?.externalControlEnabled;
      row.append(input, ui.element('span', '', t('Diesen Worker für die Node freigeben')));
      const hint = ui.element('small', '', settings?.externalControlEnabled ? t('Die Node kann nur freigegebene Worker starten, pausieren und in ihre Planung aufnehmen.') : t('Aktiviere zuerst die globale Remote-Steuerung, um Worker freizugeben.'));
      input.addEventListener('change', () => updateWorker(worker, input));
      card.append(head, description, row, hint); target.append(card);
    }
  }

  function render() {
    $('global-control').checked = Boolean(settings?.externalControlEnabled);
    $('global-control').disabled = !settings || saving;
    renderSummary(); renderWorkers();
  }

  async function updateGlobal(input) {
    if (!settings || saving) return;
    const enabled = input.checked;
    saving = true; render();
    try {
      const response = await fetch('/api/agent/local/power-control/settings', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({
        dynamicPowerScalingEnabled: settings.dynamicPowerScalingEnabled,
        externalControlEnabled: enabled,
        workerExternalControl: settings.workerExternalControl || {}
      })});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      settings = await response.json();
      notice(enabled ? 'Remote-Steuerung aktiviert. Wähle nun die verfügbaren Worker aus.' : 'Remote-Steuerung deaktiviert. Die Dev-Fee nutzt wieder den Proxy-Tarif von 1 %.');
      await load();
    } catch (error) {
      notice(t('Remote-Steuerung konnte nicht geändert werden: {error}', {error: window.SolarMinerI18n.s(error.message)}), true);
    } finally { saving = false; render(); }
  }

  async function updateWorker(worker, input) {
    if (saving || !settings?.externalControlEnabled) return;
    const enabled = input.checked;
    saving = true; render();
    try {
      const response = await fetch(`/api/agent/local/power-control/workers/${encodeURIComponent(worker.deviceId)}/external-control?enabled=${enabled}`, {method: 'POST'});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      settings = await response.json();
      notice(enabled ? 'Worker für die Node freigegeben.' : 'Worker auf lokale Steuerung beschränkt.');
      await load();
    } catch (error) {
      notice(t('Worker-Freigabe konnte nicht geändert werden: {error}', {error: window.SolarMinerI18n.s(error.message)}), true);
    } finally { saving = false; render(); }
  }

  async function load() {
    try {
      const [nextSettings, nextWorkers] = await Promise.all([ui.getJson('/api/agent/local/power-control/settings'), ui.getJson('/api/agent/local/workers')]);
      settings = nextSettings; workers = nextWorkers;
      $('connection').className = 'badge online'; $('connection').textContent = t('Agent verbunden'); render();
    } catch (error) {
      $('connection').className = 'badge offline'; $('connection').textContent = t('Agent nicht erreichbar'); notice(t('Automatisierung konnte nicht geladen werden: {error}', {error: window.SolarMinerI18n.s(error.message)}), true);
    }
  }

  $('global-control').addEventListener('change', event => updateGlobal(event.target));
  $('refresh').addEventListener('click', load);
  load();
})();
