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
    $('catalog-installed-count').textContent = t('{count} installiert', {count: packages.filter(item => item.installed).length});
    $('catalog-available-count').textContent = t('{count} verfügbar', {count: packages.length});
    const grid = $('software-grid'); grid.replaceChildren();
    if (!list.length) { grid.append(el('p', 'table-empty', 'Keine Miner-Software passt zu diesem Filter.')); return; }
    for (const item of list) {
      const card = el('article', `software-card${item.installed ? ' installed' : ''}`);
      const head = el('div', 'software-card__head');
      const identity = el('div'); identity.append(el('p', 'kicker', item.device), el('h3', '', item.name));
      const status = el('span', `pill ${item.installed ? 'tone-ok' : item.selectable ? '' : 'tone-warn'}`,
        item.downloadStatus === 'DOWNLOADING' ? 'Wird installiert' : item.installed ? 'Installiert' : item.selectable ? 'Installierbar' : 'Nicht integriert');
      head.append(identity, status); card.append(head);
      const supported = el('div', 'software-support');
      [...new Set(item.variants.map(entry => `${coinName(entry)} · ${entry.algorithm}`))]
        .forEach(value => supported.append(el('span', 'software-chip', value)));
      card.append(supported);
      const facts = el('dl', 'software-facts');
      const fact = (name, value) => { facts.append(el('dt', '', name), el('dd', '', value)); };
      fact('Entwicklergebühr', item.developerFeePercent == null ? '—' : `${item.developerFeePercent} %`);
      fact('Plattform', item.device);
      fact('Projekt', item.projectUrl || '—');
      card.append(facts);
      const copy = el('p', 'muted', item.installed ? 'Die Software ist bereit und kann einem Worker zugewiesen werden.' : 'Installation erfolgt aus der verifizierten offiziellen Release-Quelle.');
      if (item.downloadDetail || item.unavailableReason) copy.textContent = window.SolarMinerI18n.s(item.downloadDetail || item.unavailableReason);
      card.append(copy);
      const actions = el('div', 'software-actions');
      if (item.projectUrl) { const link = el('a', 'button subtle', 'Projektseite ↗'); link.href = item.projectUrl; link.target = '_blank'; link.rel = 'noopener noreferrer'; actions.append(link); }
      if (item.installed) {
        const workers = el('a', 'button primary', 'Worker zuweisen'); workers.href = '/workers.html'; actions.append(workers);
        const remove = el('button', 'button subtle', 'Deinstallieren'); remove.type = 'button'; remove.disabled = busy;
        remove.addEventListener('click', () => mutate(`/api/agent/local/${item.coins[0]}/remove`, `${item.name} entfernen?`, 'Software wurde entfernt.'));
        actions.append(remove);
      } else if (item.selectable) {
        const install = el('button', 'button primary', item.downloadStatus === 'DOWNLOADING' ? 'Installation läuft …' : 'Installieren');
        install.type = 'button'; install.disabled = busy || item.downloadStatus === 'DOWNLOADING';
        install.addEventListener('click', () => mutate(`/api/agent/local/${item.coins[0]}/miners/${item.id}/download`, null, 'Installation wurde gestartet.'));
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
    } catch (error) { notice(t('Aktion fehlgeschlagen: {error}', {error: window.SolarMinerI18n.s(error.message)}), true); }
    finally { busy = false; render(); }
  }

  async function load() {
    try {
      const response = await fetch('/api/agent/local/miner-options', {cache: 'no-store'});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      packages = group(await response.json()); render();
      $('connection').className = 'badge online'; $('connection').textContent = t('Agent verbunden');
    } catch (error) {
      notice(t('Miner-Katalog konnte nicht geladen werden: {error}', {error: window.SolarMinerI18n.s(error.message)}), true);
      $('connection').className = 'badge offline'; $('connection').textContent = t('Agent nicht erreichbar');
    }
  }

  $('software-search').addEventListener('input', event => { search = event.target.value.trim().toLowerCase(); render(); });
  for (const button of document.querySelectorAll('[data-device]')) button.addEventListener('click', () => {
    filter = button.dataset.device; document.querySelectorAll('[data-device]').forEach(node => node.classList.toggle('selected', node === button)); render();
  });
  $('refresh').addEventListener('click', load);
  load(); setInterval(() => { if (!document.hidden) load(); }, 5000);
})();
