(() => {
  const $ = id => document.getElementById(id);
  const t = window.SolarMinerI18n.t;
  let packages = [], filter = 'all', search = '', busy = false;

  const notice = (message, error = false) => {
    const node = $('notice'); node.textContent = t(message); node.className = `notice${error ? ' error' : ''}`; node.hidden = !message;
  };
  const el = (tag, className, text) => {
    const node = document.createElement(tag); node.className = className || ''; if (text != null) node.textContent = t(text); return node;
  };

  function group(options) {
    const grouped = new Map();
    for (const option of options) {
      const item = grouped.get(option.id) || {...option, coins: [], algorithms: [], variants: []};
      item.coins.push(option.coin); item.algorithms.push(option.algorithm); item.variants.push(option);
      item.installed ||= option.installed;
      if (option.downloadStatus === 'DOWNLOADING') item.downloadStatus = option.downloadStatus;
      if (option.downloadDetail) item.downloadDetail = option.downloadDetail;
      grouped.set(option.id, item);
    }
    return [...grouped.values()];
  }

  // Coin display names arrive with the catalog data; no coin table lives in the UI.
  const coinName = option => option.coinName || option.coin;
  function render() {
    const list = packages.filter(item => {
      const device = item.device.includes('CPU') ? 'CPU' : 'GPU';
      const haystack = [item.name, ...item.variants.map(coinName), ...item.algorithms].join(' ').toLowerCase();
      return (filter === 'all' || filter === device) && (!search || haystack.includes(search));
    });
    $('catalog-installed-count').textContent = t('count_installed', {count: packages.filter(item => item.installed).length});
    $('catalog-available-count').textContent = t('count_available', {count: packages.length});
    const grid = $('software-grid'); grid.replaceChildren();
    if (!list.length) { grid.append(el('p', 'table-empty', 'no_miner_software_matches_this_filter')); return; }
    for (const item of list) {
      const card = el('article', `software-card${item.installed ? ' installed' : ''}`);
      const head = el('div', 'software-card__head');
      const identity = el('div'); identity.append(el('p', 'kicker', item.device), el('h3', '', item.name));
      const status = el('span', `pill ${item.installed ? 'tone-ok' : item.selectable ? '' : 'tone-warn'}`,
        item.downloadStatus === 'DOWNLOADING' ? 'installing_d0dbee' : item.installed ? 'installed_ff9a99' : item.selectable ? 'installable' : 'not_integrated');
      head.append(identity, status); card.append(head);
      const supported = el('div', 'software-support');
      [...new Set(item.variants.map(entry => `${coinName(entry)} · ${entry.algorithm}`))]
        .forEach(value => supported.append(el('span', 'software-chip', value)));
      card.append(supported);
      const facts = el('dl', 'software-facts');
      const fact = (name, value) => { facts.append(el('dt', '', name), el('dd', '', value)); };
      fact('developer_fee', item.developerFeePercent == null ? '—' : `${item.developerFeePercent} %`);
      fact('platform', item.device);
      fact('project', item.projectUrl || '—');
      card.append(facts);
      const copy = el('p', 'muted', item.installed ? 'the_software_is_ready_and_can_be_assigned_to' : 'installation_uses_the_verified_official_rele');
      if (item.downloadDetail || item.unavailableReason) copy.textContent = window.SolarMinerI18n.s(item.downloadDetail || item.unavailableReason);
      card.append(copy);
      const actions = el('div', 'software-actions');
      if (item.projectUrl) { const link = el('a', 'button subtle', 'project_page'); link.href = item.projectUrl; link.target = '_blank'; link.rel = 'noopener noreferrer'; actions.append(link); }
      if (item.installed) {
        const workers = el('a', 'button primary', 'assign_workers'); workers.href = '/workers.html'; actions.append(workers);
        const remove = el('button', 'button subtle', 'uninstall'); remove.type = 'button'; remove.disabled = busy;
        remove.addEventListener('click', () => mutate(`/api/agent/local/${item.coins[0]}/remove`, `${item.name} entfernen?`, 'software_removed'));
        actions.append(remove);
      } else if (item.selectable) {
        const install = el('button', 'button primary', item.downloadStatus === 'DOWNLOADING' ? 'installation_in_progress' : 'install');
        install.type = 'button'; install.disabled = busy || item.downloadStatus === 'DOWNLOADING';
        install.addEventListener('click', () => mutate(`/api/agent/local/${item.coins[0]}/miners/${item.id}/download`, null, 'installation_started'));
        actions.append(install);
      }
      card.append(actions); grid.append(card);
    }
  }

  async function mutate(path, confirmation, success) {
    if (busy || confirmation && !confirm(t(confirmation))) return;
    busy = true; render();
    try {
      const response = await fetch(path, {method: 'POST'});
      if (!response.ok || await response.json() !== true) throw new Error(`HTTP ${response.status}`);
      notice(success); await load();
    } catch (error) { notice(t('action_failed_error', {error: window.SolarMinerI18n.s(error.message)}), true); }
    finally { busy = false; render(); }
  }

  // Phase 5c: miner-options (installed-file checks, server tick 5 s) arrive over the
  // shared SSE stream; the watchdog re-reads only after a silent push.
  function applyMinerOptions(options) {
    packages = group(options || []); render();
    $('connection').className = 'badge online'; $('connection').textContent = t('agent_connected');
  }
  function load() { window.SolarMinerLive.refresh('miner-options'); }

  $('software-search').addEventListener('input', event => { search = event.target.value.trim().toLowerCase(); render(); });
  for (const button of document.querySelectorAll('[data-device]')) button.addEventListener('click', () => {
    filter = button.dataset.device; document.querySelectorAll('[data-device]').forEach(node => node.classList.toggle('selected', node === button)); render();
  });
  $('refresh').addEventListener('click', load);
  window.SolarMinerLive.channel('miner-options', {endpoint: '/api/agent/local/miner-options', maxAgeMs: 30000, transport: 'rest', pollMs: 30000})
    .subscribe((options, meta) => {
      if (options) applyMinerOptions(options);
      else if (meta.error) {
        notice(t('could_not_load_miner_catalog_error', {error: window.SolarMinerI18n.s(meta.error.message || '')}), true);
        $('connection').className = 'badge offline'; $('connection').textContent = t('agent_unavailable');
      }
    });
  window.SolarMinerLive.start();
  load();
})();
