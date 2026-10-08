(() => {
  const t = window.SolarMinerI18n.t;
  const shell = document.querySelector('.shell'), header = document.querySelector('.topbar');
  if (!shell || !header) return;
  const make = (tag, cls, value) => {
    const el = document.createElement(tag); el.className = cls || '';
    if (value !== undefined) el.textContent = t(value);
    return el;
  };
  const groups = [
    ['BETRIEB', [['/', 'Dashboard', 'home'], ['/mining.html', 'Miner-Software', 'mining'], ['/wallets.html', 'Wallets', 'wallet'], ['/workers.html', 'Worker', 'worker']]],
    ['OPTIMIERUNG', [['/hardware.html', 'Leistungsgrenzen', 'hardware'], ['/benchmarks.html', 'Benchmarks', 'benchmarks']]],
    ['SYSTEM', [['/telemetry.html', 'Sensoren', 'telemetry'], ['/proxy.html', 'Verbindung', 'proxy']]]
  ];
  const paths = {
    home: 'M3 10 12 3l9 7v10H3Z M9 20v-7h6v7',
    mining: 'M4 7h16v10H4Z M8 3v4m8-4v4M8 17v4m8-4v4M8 11h1m6 0h1',
    wallet: 'M5 5h14v14H5Z M8 9h8m-8 4h5m-5 4h8',
    worker: 'M4 5h16v14H4Z M8 9h8v6H8Z M9 2v3m6-3v3M9 19v3m6-3v3',
    hardware: 'M4 7h16M4 17h16M9 4v6m6 4v6',
    benchmarks: 'M5 20V11m7 9V4m7 16v-6',
    telemetry: 'M3 12h4l3-7 4 14 3-7h4',
    proxy: 'M3 7h17m-4-4 4 4-4 4M21 17H4m4-4-4 4 4 4'
  };
  const sidebar = make('aside', 'app-sidebar'); sidebar.id = 'app-navigation';
  const closeMenu = make('button', 'app-menu-close', '×'); closeMenu.type = 'button'; closeMenu.setAttribute('aria-label', t('Navigation schließen')); sidebar.append(closeMenu);
  sidebar.append(header.querySelector('.brand'));
  const subtitle = make('p', 'app-instance', 'Dein lokaler Mining-Agent'); sidebar.append(subtitle);
  // The operator label is what the SolarMiner Node lists this machine under, so it belongs in the
  // persistent navigation rather than only in the dashboard form.
  const applyIdentity = name => { subtitle.textContent = name || t('Dein lokaler Mining-Agent'); };
  window.SolarMinerAgentIdentity = { apply: applyIdentity };
  fetch('/api/agent/local/power-control/identity', {cache: 'no-store'})
    .then(response => response.ok ? response.json() : null)
    .then(identity => { if (identity?.name) applyIdentity(identity.name); })
    .catch(() => { /* The default label stays until the agent answers. */ });
  const nav = make('nav', 'app-nav'); nav.setAttribute('aria-label', t('Hauptnavigation'));
  const pathname = location.pathname === '/index.html' ? '/' : location.pathname;
  let currentLabel = 'Dashboard', currentGroup = 'BETRIEB';
  for (const [group, links] of groups) {
    const section = make('div', 'app-nav-group'); section.append(make('p', 'app-nav-label', group));
    for (const [href, title, icon] of links) {
      const link = make('a', 'app-nav-link'); link.href = href;
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true');
      const path = document.createElementNS(svg.namespaceURI, 'path'); path.setAttribute('d', paths[icon]); svg.append(path);
      link.append(svg, make('span', '', title));
      if (pathname === href) { link.classList.add('selected'); link.setAttribute('aria-current', 'page'); currentLabel = title; currentGroup = group; }
      section.append(link);
    }
    nav.append(section);
  }
  sidebar.append(nav);
  const foot = make('div', 'app-sidebar-foot'); foot.append(make('span', 'app-local-dot'), make('span', '', 'Auf diesem PC · lokal im Netzwerk')); sidebar.append(foot);
  shell.before(sidebar);
  header.querySelector('.nav')?.remove();
  const menu = make('button', 'app-menu', '☰'); menu.type = 'button'; menu.setAttribute('aria-label', t('Navigation öffnen'));
  menu.setAttribute('aria-controls', sidebar.id); menu.setAttribute('aria-expanded', 'false');
  const crumb = make('div', 'app-breadcrumb'); crumb.append(make('span', '', currentGroup), make('strong', '', currentLabel));
  header.prepend(menu, crumb);
  const main = document.querySelector('main'); main.id ||= 'main-content';
  const skip = make('a', 'skip-link', 'Zum Inhalt springen'); skip.href = '#main-content'; document.body.prepend(skip);
  const backdrop = make('button', 'app-backdrop'); backdrop.type = 'button'; backdrop.setAttribute('aria-label', t('Navigation schließen')); backdrop.hidden = true; sidebar.after(backdrop);
  const mobile = matchMedia('(max-width: 900px)');
  const setOpen = open => {
    sidebar.classList.toggle('open', open); backdrop.hidden = !open;
    menu.setAttribute('aria-expanded', String(open));
    document.body.classList.toggle('navigation-open', open);
    sidebar.inert = mobile.matches && !open;
    shell.inert = open && mobile.matches;
    if (open) sidebar.querySelector('[aria-current]')?.focus(); else menu.focus();
  };
  sidebar.inert = mobile.matches;
  menu.addEventListener('click', () => setOpen(menu.getAttribute('aria-expanded') !== 'true'));
  backdrop.addEventListener('click', () => setOpen(false));
  closeMenu.addEventListener('click', () => setOpen(false));
  document.addEventListener('keydown', event => {
    if (menu.getAttribute('aria-expanded') !== 'true') return;
    if (event.key === 'Escape') { event.preventDefault(); setOpen(false); }
    if (event.key === 'Tab') {
      const links = [...sidebar.querySelectorAll('a, button')];
      if (event.shiftKey && document.activeElement === links[0]) { event.preventDefault(); links.at(-1).focus(); }
      else if (!event.shiftKey && document.activeElement === links.at(-1)) { event.preventDefault(); links[0].focus(); }
    }
  });
  mobile.addEventListener('change', () => { sidebar.inert = mobile.matches && !sidebar.classList.contains('open'); if (!mobile.matches) { shell.inert = false; backdrop.hidden = true; sidebar.classList.remove('open'); menu.setAttribute('aria-expanded', 'false'); document.body.classList.remove('navigation-open'); } });
  const connectionNotice = make('p', 'app-connection-notice', 'Verbindung zum Agent unterbrochen. Angezeigte Werte können veraltet sein.');
  connectionNotice.id = 'app-connection-notice'; connectionNotice.setAttribute('role', 'status'); connectionNotice.hidden = true;
  header.after(connectionNotice);
  const connection = document.getElementById('connection');
  new MutationObserver(() => { connectionNotice.hidden = !connection.classList.contains('offline'); document.body.classList.toggle('agent-offline', !connectionNotice.hidden); }).observe(connection, {attributes: true, attributeFilter: ['class']});
  // Balances are global context. Keep the compact strip directly below the page header on
  // operational pages, matching the Solar-Miner-Node shell instead of hiding money at the footer.
  const wallets = document.getElementById('wallet-strip');
  if (wallets) wallets.classList.add('wallet-strip--global');
})();
