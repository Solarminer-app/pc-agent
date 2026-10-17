(() => {
  const t = window.SolarMinerI18n.t;
  const version = document.getElementById('update-version');
  const state = document.getElementById('update-state');
  const progress = document.getElementById('update-progress');
  const action = document.getElementById('update-action');
  const guide = document.getElementById('update-guide');
  const command = document.getElementById('update-command');
  if (!version || !state || !progress || !action || !guide || !command) return;
  let last = null;
  let checking = false;
  let waitingForRestart = false;
  let completed = false;
  let guideOpen = false;

  async function api(path = '', method = 'GET') {
    const response = await fetch(`/api/agent/local/update${path}`, {method, cache: 'no-store'});
    if (!response.ok) throw new Error('update_request_failed');
    return response.json();
  }

  function render(s) {
    last = s;
    version.textContent = t('update_current_version', {version: s.currentTag === 'unknown' ? t('unknown') : s.currentTag,
      channel: t(s.channel === 'beta' ? 'beta_channel' : 'stable_channel')});
    progress.hidden = !['downloading', 'verifying', 'restarting'].includes(s.phase);
    if (!progress.hidden) {
      if (s.percent >= 0 && s.phase === 'downloading') progress.value = s.percent;
      else progress.removeAttribute('value');
    }
    if (s.phase === 'restarting') waitingForRestart = true;
    if (s.phase === 'failed') { waitingForRestart = false; sessionStorage.removeItem('solarminer.update.target'); }
    if (waitingForRestart && s.currentTag === sessionStorage.getItem('solarminer.update.target')) {
      waitingForRestart = false;
      completed = true;
      sessionStorage.removeItem('solarminer.update.target');
      state.textContent = t('update_complete');
    } else if (completed) state.textContent = t('update_complete');
    else if (s.phase === 'failed') state.textContent = t(s.error || 'update_request_failed');
    else if (['checking', 'downloading', 'verifying', 'restarting'].includes(s.phase)) state.textContent = t(`update_${s.phase}`);
    else if (s.updateAvailable) state.textContent = t('update_available', {version: s.availableTag});
    else if (s.currentTag === 'unknown') state.textContent = t('update_version_unknown');
    else state.textContent = t('update_current');

    action.hidden = !s.updateAvailable || ['downloading', 'verifying', 'restarting', 'checking'].includes(s.phase);
    action.disabled = false;
    guide.hidden = !(guideOpen && s.deployment === 'docker' && s.updateAvailable);
    if (s.deployment === 'docker') {
      action.textContent = t('show_docker_update_steps');
      const overlay = ['nvidia', 'amd'].includes(s.dockerGpu) ? ` -f ${s.dockerGpu}.yml` : '';
      const image = s.channel === 'beta' ? 'verdox/solar-miner-pc-agent:latest-beta' : 'verdox/solar-miner-pc-agent:latest';
      const env = `PC_AGENT_IMAGE=${image} `;
      command.textContent = `${env}docker compose -f compose.yml${overlay} pull pc-agent\n${env}docker compose -f compose.yml${overlay} up -d pc-agent`;
    } else if (s.automatic) action.textContent = t('install_update_and_restart');
    else action.textContent = t('open_update_locally');
  }

  action.addEventListener('click', async () => {
    if (!last) return;
    if (last.deployment === 'docker') { guideOpen = true; guide.hidden = false; return; }
    if (!last.automatic) { state.textContent = t('open_update_locally_detail'); return; }
    action.disabled = true;
    sessionStorage.setItem('solarminer.update.target', last.availableTag);
    try { render(await api('/start', 'POST')); waitingForRestart = true; }
    catch (_) { sessionStorage.removeItem('solarminer.update.target'); state.textContent = t('update_request_failed'); action.disabled = false; }
  });

  async function poll() {
    try { render(await api()); }
    catch (_) { if (waitingForRestart) state.textContent = t('update_reconnecting'); }
  }
  async function check() {
    if (checking) return;
    checking = true;
    try { render(await api('/check', 'POST')); }
    catch (_) { state.textContent = t('update_check_failed'); }
    finally { checking = false; }
  }
  if (sessionStorage.getItem('solarminer.update.target')) waitingForRestart = true;
  poll().then(check);
  setInterval(poll, 1500);
})();
