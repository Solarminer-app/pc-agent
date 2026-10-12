// One clear payout location. Configuration endpoints remain the source of truth.
(() => {
  const $ = id => document.getElementById(id);
  const catalog = window.SolarMinerPoolCatalog;
  const t = window.SolarMinerI18n.t;
  let overview = null, busy = false, importedTargets = new Map(), loadingPeer = null, networkPeers = [];
  // Phase 5c: GPU/CPU device kind comes from the backend coin catalog (closed Coin enum).
  const coinRow = id => (window.SolarMinerCoinCatalog || []).find(row => row.id === id) || null;
  const isGpuCoin = id => coinRow(id) ? coinRow(id).device !== 'CPU' : ['pearl', 'ravencoin', 'ethereumclassic', 'decred', 'quantus'].includes(id);

  function notice(message, error = false) {
    const node = $('notice'); node.textContent = message; node.className = `notice${error ? ' error' : ''}`; node.hidden = !message;
  }
  function configFor(coin) {
    if (coin.id === 'monero') return overview.moneroConfiguration;
    if (coin.id === 'pearl') return overview.pearlConfiguration;
    return overview.gpuCoins?.[coin.id]?.configuration;
  }
  function endpoint(coin) { return `/api/agent/local/${coin.id}/configuration`; }
  function proxyUrl(coin) { return overview.proxy?.[`${coin.id}Url`] || ''; }
  function element(tag, className, text) { const node = document.createElement(tag); node.className = className || ''; if (text != null) node.textContent = text; return node; }

  function field(label, control, hint) {
    const wrap = element('label', 'wallet-field');
    wrap.append(element('span', 'field-label', label), control);
    if (hint) wrap.append(element('small', 'muted', hint));
    return wrap;
  }
  function poolSelect(coin, configuration) {
    const select = document.createElement('select'); select.className = 'pool-select';
    const current = configuration?.poolUrl || '';
    const entries = catalog.candidates(coin.id);
    if (current && !entries.some(entry => entry.value === current)) {
      const option = new Option(`${t('current')} ${current}`, current); select.add(option);
    }
    for (const entry of entries) {
      const fee = entry.feePercent != null ? ` · ${entry.feePercent} % ${t('pool_fee')}` : '';
      select.add(new Option(`${t(entry.name)} · ${t(entry.region)}${fee}`, entry.value));
    }
    select.value = current || entries[0]?.value || '';
    return select;
  }
  function card(coin) {
    const configuration = configFor(coin);
    const form = element('form', 'panel wallet-card');
    form.dataset.coin = coin.id;
    const head = element('div', 'panel-head');
    const copy = element('div'); copy.append(element('p', 'kicker', `${coin.device} · ${coin.algorithm}`), element('h2', '', `${coin.name} · ${coin.ticker}`));
    head.append(copy, element('span', `pill ${configuration?.wallet ? 'tone-ok' : 'tone-warn'}`, t(configuration?.wallet ? 'wallet_saved' : 'wallet_missing')));
    form.append(head);
    const grid = element('div', 'wallet-fields');
    const pool = poolSelect(coin, configuration); pool.name = 'poolUrl';
    const wallet = document.createElement('input'); wallet.name = 'wallet'; wallet.required = true; wallet.autocomplete = 'off'; wallet.spellcheck = false;
    wallet.placeholder = `${coin.ticker}-Wallet`; wallet.value = configuration?.wallet || '';
    const worker = document.createElement('input'); worker.name = 'worker'; worker.required = true; worker.maxLength = 32; worker.pattern = '[A-Za-z0-9_\\-]{1,32}'; worker.value = configuration?.worker || 'pc';
    grid.append(field(t('pool_destination'), pool), field(`${coin.ticker}-Wallet`, wallet, t('your_payout_address')), field(t('worker'), worker, t('optional_name_for_this_pc')));
    form.append(grid);
    const foot = element('div', 'wallet-card-actions');
    if (!proxyUrl(coin)) foot.append(element('small', 'notice-line warn', t('connect_the_solarminer_proxy_first')));
    const save = element('button', 'button primary', t('save_wallet')); save.type = 'submit'; save.disabled = !proxyUrl(coin);
    const imported = importedTargets.get(coin.id);
    if (imported) {
      const apply = element('button', 'button subtle', t('use_imported_wallet_target')); apply.type = 'button';
      apply.addEventListener('click', () => {
        pool.value = imported.poolUrl || pool.value;
        wallet.value = imported.wallet || '';
        worker.value = imported.worker || worker.value;
        notice(t('wallet_target_applied_save_to_confirm', {coin: coin.name}));
      });
      foot.append(apply);
    }
    foot.append(save); form.append(foot);
    form.addEventListener('submit', event => saveWallet(event, coin, configuration));
    return form;
  }
  async function saveWallet(event, coin, configuration) {
    event.preventDefault(); if (busy || !event.currentTarget.reportValidity()) return;
    const form = event.currentTarget;
    const body = {poolUrl: form.elements.poolUrl.value, wallet: form.elements.wallet.value.trim(), worker: form.elements.worker.value.trim()};
    if (isGpuCoin(coin.id)) {
      body.proxyUrl = proxyUrl(coin);
      body.devices = configuration?.devices && configuration.devices !== 'all'
        ? configuration.devices : (overview.gpus || []).map(gpu => `${gpu.vendor}:${gpu.index}`).join(',');
      if (!body.devices) return notice(`${coin.name}: ${t('no_gpu_detected')}`, true);
    }
    busy = true; form.querySelector('button[type=submit]').disabled = true; notice(`${coin.name}: ${t('saving_wallet')}`);
    try {
      const response = await fetch(endpoint(coin), {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
      const result = await response.json().catch(() => null);
      if (!response.ok || result !== true) throw new Error(result?.message || `HTTP ${response.status}`);
      notice(`${coin.name}: ${t('wallet_and_pool_destination_saved')}`); await load();
      window.dispatchEvent(new Event('solarminer:wallets-changed'));
    } catch (error) { notice(t('coin_could_not_save_error', {coin: coin.name, error: window.SolarMinerI18n.s(error.message)}), true); }
    finally { busy = false; form.querySelector('button[type=submit]').disabled = !proxyUrl(coin); }
  }
  function render() {
    const forms = $('wallet-forms'); forms.replaceChildren(...(overview.coins || []).map(card));
  }
  function renderPeers(peers = []) {
    const target = $('wallet-import-peers'); target.replaceChildren();
    const available = peers.filter(peer => !peer.own);
    if (!available.length) {
      target.append(element('p', 'muted', t('no_other_pc_agents_found_on_the_lan')));
      return;
    }
    for (const peer of available) {
      const row = element('article', 'wallet-import-peer');
      const details = element('div');
      details.append(element('strong', '', peer.name || peer.host), element('small', 'muted', peer.host || t('agent_unavailable')));
      const load = element('button', 'button subtle', loadingPeer === peer.agentId ? t('loading_wallet_targets') : t('load_wallet_targets'));
      load.type = 'button'; load.disabled = Boolean(loadingPeer) || !peer.reachable;
      load.addEventListener('click', () => loadPeerTargets(peer));
      row.append(details, load); target.append(row);
    }
  }
  async function loadPeerTargets(peer) {
    loadingPeer = peer.agentId; renderPeers(networkPeers);
    try {
      const data = await window.SolarMinerUI.getJson(`/api/agent/local/network/agents/${encodeURIComponent(peer.agentId)}/wallet-targets`);
      if (!data.available) throw new Error(data.error || t('wallet_targets_not_available_from_this_agent'));
      importedTargets = new Map((data.targets || []).map(target => [target.coin, target]));
      render();
      notice((data.targets || []).length ? t('wallet_targets_loaded_choose_a_coin_to_apply', {agent: peer.name || peer.host}) : t('the_selected_agent_has_no_saved_wallet_targets'));
    } catch (error) {
      notice(t('could_not_load_wallet_targets_error', {error: window.SolarMinerI18n.s(error.message)}), true);
    } finally { loadingPeer = null; renderPeers(networkPeers); }
  }
  async function load() {
    if (!window.SolarMinerCoinCatalog) {
      try { window.SolarMinerCoinCatalog = await window.SolarMinerUI.cachedCoinCatalog(); }
      catch (_) { /* the fallback list inside isGpuCoin keeps the page usable */ }
    }
    // P7: seed instantly from the tab's last "overview" snapshot (pushed by the overview/
    // proxy pages through live-data.js) so the forms render before the REST round-trip.
    // The REST read below stays the source of truth and re-renders on arrival.
    if (!overview) {
      const seeded = window.SolarMinerLive.snapshot('overview');
      if (seeded) { overview = seeded; render(); }
    }
    try {
      const [nextOverview, network] = await Promise.all([
        window.SolarMinerUI.getJson('/api/agent/local/overview'),
        window.SolarMinerUI.getJson('/api/agent/local/network/agents').catch(() => null)
      ]);
      overview = nextOverview; networkPeers = network?.agents || []; render(); renderPeers(networkPeers);
      $('connection').className = 'badge online'; $('connection').textContent = t('agent_connected');
    } catch (error) {
      if (!overview) {
        notice(t('could_not_load_wallet_data_error', {error: window.SolarMinerI18n.s(error.message)}), true);
        $('connection').className = 'badge offline'; $('connection').textContent = t('agent_unavailable');
      }
    }
  }
  $('refresh').addEventListener('click', load); load();
})();
