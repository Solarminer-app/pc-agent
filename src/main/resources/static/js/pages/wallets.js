// One clear payout location. Configuration endpoints remain the source of truth.
(() => {
  const $ = id => document.getElementById(id);
  const catalog = window.SolarMinerPoolCatalog;
  const t = window.SolarMinerI18n.t;
  let overview = null, busy = false;
  const gpuCoins = new Set(['pearl', 'ravencoin', 'ethereumclassic', 'decred', 'quantus']);

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
      const option = new Option(`${t('Aktuell:')} ${current}`, current); select.add(option);
    }
    for (const entry of entries) {
      const fee = entry.feePercent != null ? ` · ${entry.feePercent} % ${t('Poolgebühr')}` : '';
      select.add(new Option(`${entry.name} · ${t(entry.region)}${fee}`, entry.value));
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
    head.append(copy, element('span', `pill ${configuration?.wallet ? 'tone-ok' : 'tone-warn'}`, t(configuration?.wallet ? 'Wallet gespeichert' : 'Wallet fehlt')));
    form.append(head);
    const grid = element('div', 'wallet-fields');
    const pool = poolSelect(coin, configuration); pool.name = 'poolUrl';
    const wallet = document.createElement('input'); wallet.name = 'wallet'; wallet.required = true; wallet.autocomplete = 'off'; wallet.spellcheck = false;
    wallet.placeholder = `${coin.ticker}-Wallet`; wallet.value = configuration?.wallet || '';
    const worker = document.createElement('input'); worker.name = 'worker'; worker.required = true; worker.maxLength = 32; worker.pattern = '[A-Za-z0-9_\\-]{1,32}'; worker.value = configuration?.worker || 'pc';
    grid.append(field(t('Pool-Ziel'), pool), field(`${coin.ticker}-Wallet`, wallet, t('Eigene Auszahlungsadresse')), field(t('Worker'), worker, t('Optionaler Name für diesen PC')));
    form.append(grid);
    const foot = element('div', 'wallet-card-actions');
    if (!proxyUrl(coin)) foot.append(element('small', 'notice-line warn', t('Verbinde zuerst den SolarMiner-Proxy.')));
    const save = element('button', 'button primary', t('Wallet speichern')); save.type = 'submit'; save.disabled = !proxyUrl(coin);
    foot.append(save); form.append(foot);
    form.addEventListener('submit', event => saveWallet(event, coin, configuration));
    return form;
  }
  async function saveWallet(event, coin, configuration) {
    event.preventDefault(); if (busy || !event.currentTarget.reportValidity()) return;
    const form = event.currentTarget;
    const body = {poolUrl: form.elements.poolUrl.value, wallet: form.elements.wallet.value.trim(), worker: form.elements.worker.value.trim()};
    if (gpuCoins.has(coin.id)) {
      body.proxyUrl = proxyUrl(coin);
      body.devices = configuration?.devices && configuration.devices !== 'all'
        ? configuration.devices : (overview.gpus || []).map(gpu => `${gpu.vendor}:${gpu.index}`).join(',');
      if (!body.devices) return notice(`${coin.name}: ${t('Keine GPU erkannt.')}`, true);
    }
    busy = true; form.querySelector('button[type=submit]').disabled = true; notice(`${coin.name}: ${t('Wallet wird gespeichert …')}`);
    try {
      const response = await fetch(endpoint(coin), {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
      const result = await response.json().catch(() => null);
      if (!response.ok || result !== true) throw new Error(result?.message || `HTTP ${response.status}`);
      notice(`${coin.name}: ${t('Wallet und Pool-Ziel gespeichert.')}`); await load();
      window.dispatchEvent(new Event('solarminer:wallets-changed'));
    } catch (error) { notice(`${coin.name}: ${t('Speichern fehlgeschlagen:')} ${error.message}`, true); }
    finally { busy = false; form.querySelector('button[type=submit]').disabled = !proxyUrl(coin); }
  }
  function render() {
    const forms = $('wallet-forms'); forms.replaceChildren(...(overview.coins || []).map(card));
  }
  async function load() {
    try {
      const response = await fetch('/api/agent/local/overview', {cache: 'no-store'});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      overview = await response.json(); render();
      $('connection').className = 'badge online'; $('connection').textContent = t('Agent verbunden');
    } catch (error) {
      notice(`${t('Wallet-Daten konnten nicht geladen werden:')} ${error.message}`, true);
      $('connection').className = 'badge offline'; $('connection').textContent = t('Agent nicht erreichbar');
    }
  }
  $('refresh').addEventListener('click', load); load();
})();
