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
    add('remote_control', enabled ? 'enabled' : 'disabled', enabled ? 'tone-ok' : 'tone-warn');
    add('dev_fee', enabled ? 'node_tier' : 'proxy_tier_1', enabled ? 'tone-ok' : 'tone-warn');
    add('available_workers', `${selected}/${workers.filter(worker => worker.coin !== 'none').length}`, selected ? 'tone-ok' : 'tone-warn');
  }

  function renderWorkers() {
    const target = $('automation-workers'); target.replaceChildren();
    const configured = workers.filter(worker => worker.coin !== 'none');
    $('worker-count').textContent = t('count_configured_workers', {count: configured.length});
    if (!configured.length) {
      target.append(ui.element('p', 'muted', t('no_workers_configured_yet')));
      return;
    }
    for (const worker of configured) {
      const card = ui.element('article', `automation-device${worker.externalControlEnabled ? '' : ' local-only'}`);
      const head = ui.element('div', 'automation-device-head');
      head.append(ui.element('strong', '', workerName(worker)), ui.element('span', `tag ${worker.externalControlEnabled ? 'ok' : ''}`, t(worker.externalControlEnabled ? 'available_to_node' : 'local_only')));
      const description = ui.element('small', '', `${worker.hardwareType} · ${worker.coinName || worker.coin} · ${worker.algorithm || '—'}`);
      const row = ui.element('label', 'automation-switch');
      const input = document.createElement('input'); input.type = 'checkbox'; input.checked = Boolean(worker.externalControlEnabled); input.disabled = saving || !settings?.externalControlEnabled;
      row.append(input, ui.element('span', '', t('make_this_worker_available_to_the_node')));
      const hint = ui.element('small', '', settings?.externalControlEnabled ? t('the_node_can_start_pause_and_include_only_av') : t('enable_global_remote_control_first_to_make_w'));
      input.addEventListener('change', () => updateWorker(worker, input));
      card.append(head, description, row, hint); target.append(card);
    }
  }

  function render() {
    $('global-control').checked = Boolean(settings?.externalControlEnabled);
    $('global-control').disabled = !settings || saving;
    $('network-sharing').checked = Boolean(settings?.networkSharingEnabled);
    $('network-sharing').disabled = !settings || saving;
    $('wallet-target-sharing').checked = Boolean(settings?.walletTargetSharingEnabled);
    $('wallet-target-sharing').disabled = !settings || saving;
    renderSummary(); renderWorkers();
  }

  async function updateNetworkSharing(input) {
    if (!settings || saving) return;
    const enabled = input.checked;
    saving = true; render();
    try {
      const response = await fetch(`/api/agent/local/power-control/network-sharing?enabled=${enabled}`, {method: 'POST'});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      settings = await response.json();
      notice(enabled ? 'lan_visibility_enabled_searching_the_local_n' : 'lan_visibility_is_off_this_agent_shows_no');
      await load();
    } catch (error) {
      notice(t('could_not_change_lan_visibility_error', {error: window.SolarMinerI18n.s(error.message)}), true);
    } finally { saving = false; render(); }
  }
  async function updateWalletTargetSharing(input) {
    if (!settings || saving) return;
    const enabled = input.checked;
    saving = true; render();
    try {
      const response = await fetch(`/api/agent/local/power-control/wallet-target-sharing?enabled=${enabled}`, {method: 'POST'});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      settings = await response.json();
      notice(enabled ? 'wallet_target_sharing_enabled' : 'wallet_target_sharing_disabled');
    } catch (error) {
      notice(t('could_not_change_wallet_target_sharing_error', {error: window.SolarMinerI18n.s(error.message)}), true);
    } finally { saving = false; render(); }
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
      notice(enabled ? 'remote_control_enabled_now_choose_the_availa' : 'remote_control_disabled_the_dev_fee_uses_the');
      await load();
    } catch (error) {
      notice(t('could_not_change_remote_control_error', {error: window.SolarMinerI18n.s(error.message)}), true);
    } finally { saving = false; render(); }
  }

  async function updateWorker(worker, input) {
    if (saving || !settings?.externalControlEnabled) return;
    const enabled = input.checked;
    saving = true; render();
    try {
      const response = await fetch(`/api/agent/local/workers/${encodeURIComponent(worker.deviceId)}/external-control?enabled=${enabled}`, {method: 'POST'});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      settings = await response.json();
      notice(enabled ? 'worker_made_available_to_the_node' : 'worker_restricted_to_local_control');
      await load();
    } catch (error) {
      notice(t('could_not_change_worker_permission_error', {error: window.SolarMinerI18n.s(error.message)}), true);
    } finally { saving = false; render(); }
  }

  async function load() {
    try {
      const [nextSettings, nextWorkers] = await Promise.all([ui.getJson('/api/agent/local/power-control/settings'), ui.getJson('/api/agent/local/workers')]);
      settings = nextSettings; workers = nextWorkers;
      $('connection').className = 'badge online'; $('connection').textContent = t('agent_connected'); render();
    } catch (error) {
      $('connection').className = 'badge offline'; $('connection').textContent = t('agent_unavailable'); notice(t('could_not_load_automation_error', {error: window.SolarMinerI18n.s(error.message)}), true);
    }
  }

  $('global-control').addEventListener('change', event => updateGlobal(event.target));
  $('network-sharing').addEventListener('change', event => updateNetworkSharing(event.target));
  $('wallet-target-sharing').addEventListener('change', event => updateWalletTargetSharing(event.target));
  $('refresh').addEventListener('click', load);
  load();
})();
