/* Independent operations UI. Bounded DOM, keyed rows, cancellable reads, explicit write jobs. */
(() => {
  const {t, locale, has} = window.OperationsI18n;
  const $ = id => document.getElementById(id);
  const api = '/api/agent/local/operations';
  const key = row => JSON.stringify([row.agentId, row.rowId || `device:${row.deviceId}`]);
  const number = (value, digits = 0) => new Intl.NumberFormat(locale, {maximumFractionDigits: digits}).format(value);
  const make = (tag, cls, value) => {
    const node = document.createElement(tag); if (cls) node.className = cls;
    if (value != null) node.textContent = value;
    return node;
  };
  const text = (node, value) => { if (node.textContent !== String(value)) node.textContent = value; };
  const label = value => has(value) ? t(value) : t('unknown');
  const measurement = (value, unit, digits = 1) => Number.isFinite(value) ? `${number(value, digits)} ${unit}` : '—';
  function hash(value) {
    if (!Number.isFinite(value)) return '—';
    const scales = [[1e12, 'TH/s'], [1e9, 'GH/s'], [1e6, 'MH/s'], [1e3, 'kH/s'], [1, 'H/s']];
    const [scale, unit] = scales.find(([minimum]) => value >= minimum) || scales.at(-1);
    return measurement(value / scale, unit, 2);
  }
  const time = at => new Date(at).getTime();
  const ageSeconds = at => at ? Math.max(0, Math.floor((Date.now() - time(at)) / 1000)) : null;
  const sourceStale = row => row.stale || !row.collectedAt || !Number.isFinite(time(row.collectedAt))
    || time(row.collectedAt) > Date.now() + 5000 || Date.now() - time(row.collectedAt) > (row.own ? 8000 : 15000);
  const sourceAge = at => at ? t('source_age', {seconds: ageSeconds(at)}) : t('no_source');
  const rows = new Map(), machines = new Map(), selected = new Set(), jobs = new Map(), jobNodes = new Map();
  const parameters = new URLSearchParams(location.search);
  const filter = {q: parameters.get('q')?.slice(0, 200) || '', agent: parameters.get('agent') || '',
    state: parameters.get('state') || '', page: Math.max(0, Number(parameters.get('page')) || 0), size: 50};
  let workspace = null, lastSuccess = 0, online = false, submitting = false, selectedDetail = null;
  let readController = null, readVersion = 0, timer = null, failureCount = 0, searchTimer = null;
  let detailController = null, detailValues = new Map(), detailError = null, detailRow = null, detailReturn = null;
  let software = [], softwareLoaded = false, softwareBusy = false, softwareUi = null, softwareTimer = null;
  let detailUnavailable = false;
  let jobsTimer = null, jobPolling = false, agentSignature = '', lastJobSignature = '';
  const notices = message => { text($('notice'), message); $('notice').hidden = !message; };

  function pending(row) {
    return [...jobs.values()].some(job => job.results?.some(result => key(result.target) === key(row)
      && ['queued', 'running'].includes(result.state)));
  }
  function blocked(row, action) {
    if (!online) return t('read_failed');
    if (sourceStale(row)) return t('console_stale');
    if (submitting || pending(row)) return t('console_already_pending');
    if (row.status === 'STARTING') return t('worker_start_in_progress');
    const reason = action === 'start' ? row.startBlocked : row.pauseBlocked;
    return reason ? label(reason) : '';
  }
  function bindButton(button, row, action) {
    const reason = blocked(row, action);
    const already = action === 'start' && row.status === 'MINING'
      || action === 'pause' && !['MINING', 'ERROR'].includes(row.status);
    button.disabled = Boolean(reason || already);
    button.title = reason;
  }
  function updateSelection() {
    text($('selection'), t('selected', {count: selected.size}));
    const chosen = (workspace?.workers || []).filter(row => selected.has(key(row)));
    $('start-selected').disabled = !chosen.length || chosen.some(row => blocked(row, 'start') || row.status === 'MINING');
    $('pause-selected').disabled = !chosen.length || chosen.some(row => blocked(row, 'pause') || !['MINING', 'ERROR'].includes(row.status));
    const eligible = (workspace?.workers || []).filter(row => !blocked(row, 'start') || !blocked(row, 'pause'));
    $('select-page').disabled = !eligible.length;
    $('select-page').checked = eligible.length > 0 && eligible.every(row => selected.has(key(row)));
    $('select-page').indeterminate = selected.size > 0 && !$('select-page').checked;
    for (const [id, view] of rows) {
      view.select.checked = selected.has(id);
      view.root.classList.toggle('selected', selected.has(id));
    }
  }
  function newRow(row) {
    const root = make('tr');
    const cells = Array.from({length: 9}, (_, index) => make('td', [5, 6, 7].includes(index) ? 'numeric' : ''));
    const select = make('input'); select.type = 'checkbox'; cells[0].append(select);
    const name = make('button', 'worker-name'); name.type = 'button';
    const device = make('span', 'secondary'); cells[1].append(name, device);
    const agent = make('span'), age = make('span', 'secondary'); cells[2].append(agent, age);
    const state = make('span', 'state'), issue = make('span', 'secondary'); cells[3].append(state, issue);
    const algorithm = make('span'), coin = make('span', 'secondary'); cells[4].append(algorithm, coin);
    const action = make('button', 'row-action'); action.type = 'button'; cells[8].append(action);
    root.append(...cells);
    const view = {root, cells, select, name, device, agent, age, state, issue, algorithm, coin, action, row};
    select.addEventListener('change', () => { select.checked ? selected.add(key(view.row)) : selected.delete(key(view.row)); updateSelection(); });
    name.addEventListener('click', () => openDetail(view.row, name));
    action.addEventListener('click', () => submit([view.row], view.row.status === 'MINING' ? 'pause' : 'start'));
    return view;
  }
  function paintRow(view, row) {
    view.row = row;
    const agent = workspace.agents.find(item => item.agentId === row.agentId);
    const name = row.model || row.deviceId;
    text(view.name, name); view.name.setAttribute('aria-label', `${t('details')} · ${name}`);
    text(view.device, row.deviceId || '—'); view.name.disabled = !row.deviceId;
    view.select.setAttribute('aria-label', t('select_worker', {name: `${agent?.name || row.agentId} · ${name} · ${row.deviceId}`}));
    view.select.disabled = Boolean(blocked(row, 'start') && blocked(row, 'pause'));
    text(view.agent, agent?.name || row.agentId);
    text(view.age, row.own ? `${t('local')} · ${sourceAge(row.collectedAt)}` : sourceAge(row.collectedAt)); view.age.title = sourceAge(row.collectedAt);
    text(view.state, label(row.status)); view.state.className = `state ${has(row.status) ? row.status : ''}`;
    const issue = sourceStale(row) ? 'stale_badge' : row.sweeping ? 'sweeping' : row.benchmarking ? 'benchmarking' : row.startBlocked;
    text(view.issue, issue ? label(issue) : ''); view.issue.title = issue ? label(issue) : '';
    view.issue.classList.toggle('stale-text', sourceStale(row));
    text(view.algorithm, row.algorithm || '—');
    text(view.coin, row.coin && row.coin !== 'none' ? row.coin : t('unassigned'));
    view.coin.title = t('assigned_coin');
    text(view.cells[5], sourceStale(row) ? '—' : hash(row.hashrateHps));
    text(view.cells[6], sourceStale(row) ? '—' : measurement(row.watts, 'W'));
    text(view.cells[7], sourceStale(row) ? '—' : measurement(row.temperature, '°C'));
    const action = row.status === 'MINING' ? 'pause' : 'start';
    text(view.action, pending(row) ? t('running') : t(action)); bindButton(view.action, row, action);
  }
  function paint() {
    if (!workspace) return;
    text($('active'), number(workspace.summary.active));
    text($('total'), t('of_total', {count: number(workspace.summary.total)}));
    text($('watts'), workspace.summary.reportingWorkers ? measurement(workspace.summary.reportedWatts, 'W', 0) : '—');
    text($('coverage'), t('coverage', {count: number(workspace.summary.reportingWorkers)}));
    text($('issues'), number(workspace.summary.attention)); text($('agents-count'), number(workspace.agents.length));
    const ids = new Set(workspace.workers.map(key));
    for (const [id, view] of rows) if (!ids.has(id)) { view.root.remove(); rows.delete(id); selected.delete(id); }
    let previous = null;
    for (const row of workspace.workers) {
      const id = key(row);
      if (!rows.has(id)) rows.set(id, newRow(row));
      const view = rows.get(id); paintRow(view, row);
      // Keep existing nodes in place: keyboard focus survives unchanged ordering.
      const expected = previous ? previous.nextElementSibling : $('worker-rows').firstElementChild;
      if (expected !== view.root) $('worker-rows').insertBefore(view.root, expected);
      previous = view.root;
    }
    const signature = JSON.stringify(workspace.agents.map(agent => [agent.agentId, agent.name]));
    if (signature !== agentSignature) {
      agentSignature = signature;
      const options = [new Option(t('all_agents'), ''), ...workspace.agents.map(agent => new Option(agent.name || agent.agentId, agent.agentId))];
      if (filter.agent && !workspace.agents.some(agent => agent.agentId === filter.agent)) options.push(new Option(filter.agent, filter.agent));
      $('agent-filter').replaceChildren(...options); $('agent-filter').value = filter.agent;
    }
    text($('page-info'), t('page', {page: workspace.page + 1, pages: workspace.pages, count: number(workspace.total)}));
    $('previous').disabled = workspace.page === 0; $('next').disabled = workspace.page + 1 >= workspace.pages;
    $('empty').hidden = workspace.workers.length > 0 || workspace.agents.some(agent => agent.own && !agent.collectedAt);
    $('workers').setAttribute('aria-busy', String(workspace.agents.some(agent => agent.own && !agent.collectedAt)));
    paintMachines(); updateSelection(); paintAge();
    if (selectedDetail) {
      const current = workspace.workers.find(row => key(row) === selectedDetail);
      if (current) { detailRow = current; detailUnavailable = false; paintDetail(); }
      else refreshDetail();
    }
  }
  function paintAge() {
    const warm = workspace?.agents.some(agent => agent.own && !agent.collectedAt);
    text($('connection'), online ? t('connected') : lastSuccess ? t('offline') : t('loading'));
    $('connection').className = `connection ${online ? 'live' : lastSuccess ? 'offline' : ''}`;
    text($('updated'), warm ? t('loading') : lastSuccess ? t('age', {seconds: ageSeconds(lastSuccess)}) : t('loading'));
    if (!workspace) return;
    for (const view of rows.values()) paintRow(view, view.row);
    updateSelection();
    if (detailRow) paintDetail();
  }
  function paintMachines() {
    const ids = new Set(workspace.agents.map(agent => agent.agentId));
    for (const [id, view] of machines) if (!ids.has(id)) { view.root.remove(); machines.delete(id); }
    for (const agent of workspace.agents) {
      let view = machines.get(agent.agentId);
      if (!view) {
        const root = make('article', 'machine'), scope = make('p', 'eyebrow'), name = make('h3'), count = make('p', 'muted');
        const age = make('p', 'muted'), issue = make('p', 'issue'), button = make('button', '', t('inspect_agent'));
        button.type = 'button'; button.addEventListener('click', () => {
          filter.agent = agent.agentId; $('agent-filter').value = agent.agentId; changeFilter(); $('workers').scrollIntoView();
        });
        root.append(scope, name, count, age, issue, button); $('machine-list').append(root);
        view = {root, scope, name, count, age, issue}; machines.set(agent.agentId, view);
      }
      text(view.scope, t(agent.own ? 'local' : 'network')); text(view.name, agent.name || agent.agentId);
      text(view.count, t('workers_count', {count: agent.workerCount})); text(view.age, sourceAge(agent.collectedAt));
      text(view.issue, agent.issue ? label(agent.issue) : sourceStale(agent) ? t('console_stale') : '');
    }
  }
  function saveUrl() {
    const params = new URLSearchParams();
    for (const [name, value] of Object.entries(filter)) if (value && name !== 'size') params.set(name, value);
    history.replaceState(null, '', `${location.pathname}${params.size ? `?${params}` : ''}${location.hash}`);
  }
  function changeFilter() {
    filter.q = $('search').value; filter.state = $('state-filter').value; filter.page = 0;
    selected.clear(); saveUrl(); load();
  }
  async function load() {
    clearTimeout(timer); readController?.abort();
    if (document.hidden) return;
    const version = ++readVersion, controller = new AbortController(); readController = controller;
    $('workers').setAttribute('aria-busy', 'true');
    const timeout = setTimeout(() => controller.abort(), 8000);
    try {
      const response = await fetch(`${api}/workspace?${new URLSearchParams(filter)}`, {cache: 'no-store', signal: controller.signal});
      if (!response.ok) throw new Error('read_failed');
      const value = await response.json();
      if (version !== readVersion) return;
      workspace = value; filter.page = value.page; lastSuccess = Date.now(); online = true; failureCount = 0;
      if ($('notice').dataset.transport === 'true') { notices(''); $('notice').dataset.transport = ''; }
      paint();
    } catch (_) {
      if (version !== readVersion || document.hidden) return;
      online = false; failureCount++; notices(t('read_failed')); $('notice').dataset.transport = 'true'; paintAge();
    } finally {
      clearTimeout(timeout);
      if (version === readVersion && !document.hidden) {
        $('workers').setAttribute('aria-busy', 'false'); readController = null;
        timer = setTimeout(load, Math.min(2000 * 2 ** Math.min(failureCount, 4), 30000));
      }
    }
  }
  function detailSection(title, fields) {
    const section = make('section', 'detail-section'), dl = make('dl'); section.append(make('h3', '', t(title)), dl);
    for (const field of fields) {
      const value = make('dd'); detailValues.set(field, value);
      dl.append(make('dt', '', t(field)), value);
    }
    return section;
  }
  function openDetail(row, trigger) {
    selectedDetail = key(row); detailRow = row; detailReturn = trigger; detailValues = new Map(); detailUnavailable = false; softwareUi = null;
    const identity = detailSection('identity', ['device', 'hardware', 'assigned_coin', 'algorithm', 'miner', 'mode', 'pool']);
    const telemetry = detailSection('telemetry', ['status', 'hashrate', 'power', 'temperature', 'accepted', 'rejected']);
    telemetry.append(make('p', 'muted', t('shares_note')));
    const note = make('p', 'detail-warning'); detailError = note;
    $('detail-content').replaceChildren(identity, telemetry, make('p', 'footnote', t('peer_detail')), note);
    if (row.own) buildSoftwareForm();
    paintDetail(); $('detail').showModal(); refreshDetail(); if (row.own) loadSoftware();
  }
  function paintDetail() {
    if (!detailRow || !selectedDetail) return;
    const row = detailRow, stale = sourceStale(row);
    const agent = workspace?.agents.find(item => item.agentId === row.agentId);
    text($('detail-title'), row.model || row.deviceId); text($('detail-agent'), `${agent?.name || row.agentId} · ${sourceAge(row.collectedAt)}`);
    const values = {device: row.deviceId, hardware: row.hardwareType, assigned_coin: row.coin === 'none' ? t('unassigned') : row.coin,
      algorithm: row.algorithm, miner: row.miner, mode: row.powerMode ? label(row.powerMode) : null, pool: row.pool,
      status: label(row.status), hashrate: stale ? '—' : hash(row.hashrateHps), power: stale ? '—' : measurement(row.watts, 'W'),
      temperature: stale ? '—' : measurement(row.temperature, '°C'), accepted: row.acceptedShares == null || stale ? '—' : number(row.acceptedShares),
      rejected: row.rejectedShares == null || stale ? '—' : number(row.rejectedShares)};
    for (const [name, value] of Object.entries(values)) text(detailValues.get(name), value ?? '—');
    bindButton($('detail-start'), row, 'start'); bindButton($('detail-pause'), row, 'pause');
    const reason = detailUnavailable ? t('detail_failed') : blocked(row, 'start');
    if (detailUnavailable) { $('detail-start').disabled = true; $('detail-pause').disabled = true; }
    paintSoftware(); text(detailError, reason); detailError.hidden = !reason;
  }
  async function refreshDetail() {
    if (!selectedDetail || detailController) return;
    const expected = selectedDetail, controller = new AbortController(); detailController = controller;
    const timeout = setTimeout(() => controller.abort(), 8000);
    const {agentId: agent, deviceId: device} = detailRow;
    try {
      const response = await fetch(`${api}/worker?${new URLSearchParams({agent, device})}`, {cache: 'no-store', signal: controller.signal});
      if (!response.ok) throw new Error('detail_failed');
      const row = await response.json();
      if (selectedDetail === expected) { detailRow = row; detailUnavailable = false; paintDetail(); }
    } catch (_) {
      if (selectedDetail === expected) {
        detailUnavailable = true; text(detailError, t('detail_failed')); detailError.hidden = false;
        $('detail-start').disabled = true; $('detail-pause').disabled = true;
      }
    } finally { clearTimeout(timeout); if (detailController === controller) detailController = null; }
  }
  function saveJobs() {
    try { sessionStorage.setItem('solarminer.operations.jobs', JSON.stringify([...jobs.keys()].slice(-8))); } catch (_) {}
  }
  function paintJobs() {
    const signature = JSON.stringify([...jobs.values()]);
    if (signature === lastJobSignature) return;
    lastJobSignature = signature;
    const pending = [...jobs.values()].flatMap(job => job.results || []).filter(result => ['queued', 'running'].includes(result.state)).length;
    $('activity').setAttribute('aria-label', pending ? `${t('activity')} · ${t('running')} ${pending}` : t('activity'));
    if (jobs.size) $('jobs').querySelector('.activity-empty')?.remove();
    const finished = [...jobs.values()].filter(job => job.complete || job.lost);
    for (const job of finished.slice(0, Math.max(0, finished.length - 8))) {
      jobs.delete(job.id); jobNodes.get(job.id)?.root.remove(); jobNodes.delete(job.id);
    }
    for (const job of [...jobs.values()].reverse()) {
      let view = jobNodes.get(job.id);
      if (!view) {
        const root = make('article', 'job'), header = make('div', 'job-head'), title = make('strong'), progress = make('span', 'muted');
        progress.setAttribute('role', 'status'); progress.setAttribute('aria-live', 'polite');
        header.append(title, progress); const list = make('div', 'job-results'); root.append(header, list);
        $('jobs').prepend(root); view = {root, title, progress, list, results: new Map()}; jobNodes.set(job.id, view);
      }
      text(view.title, label(job.action)); text(view.progress, job.lost ? t('job_lost')
        : t('job_progress', {done: job.results.filter(result => ['succeeded', 'failed'].includes(result.state)).length, total: job.results.length}));
      for (const result of job.results || []) {
        const id = key(result.target); let item = view.results.get(id);
        if (!item) {
          const root = make('div', 'result'), name = make('span'), state = make('span', 'state'), reason = make('span', 'reason');
          root.append(name, state, reason); view.list.append(root); item = {root, name, state, reason, diagnostic: null}; view.results.set(id, item);
        }
        text(item.name, `${workspace?.agents.find(agent => agent.agentId === result.target.agentId)?.name || result.target.agentId} · ${result.target.deviceId}`);
        text(item.state, label(result.state)); item.state.className = `state ${result.state}`;
        text(item.reason, result.error ? has(result.error) ? t(result.error) : t('console_action_failed') : '');
        item.reason.hidden = !result.error;
        if (result.detail || result.error && !has(result.error)) {
          if (!item.diagnostic) {
            const detail = make('details', 'reason'), code = make('code');
            detail.append(make('summary', '', t('diagnostic')), code); item.root.append(detail); item.diagnostic = code;
          }
          text(item.diagnostic, result.detail || result.error);
        }
      }
    }
    saveJobs();
    for (const view of rows.values()) paintRow(view, view.row);
    updateSelection(); if (detailRow) paintDetail();
  }
  async function submit(chosen, action, assignment = null) {
    if (submitting || !chosen.length || chosen.some(row => action === 'assign'
      ? !online || !row.own || sourceStale(row) || pending(row) || row.status === 'STARTING' : blocked(row, action))) return;
    submitting = true; paintAge();
    let timeout; const controller = new AbortController();
    timeout = setTimeout(() => controller.abort(), 10000);
    try {
      const response = await fetch(`${api}/commands`, {method: 'POST', headers: {'Content-Type': 'application/json'},
        signal: controller.signal, body: JSON.stringify({action, assignment, targets: chosen.map(({agentId, deviceId}) => ({agentId, deviceId}))})});
      const result = await response.json();
      if (!response.ok) { notices(has(result.error) ? t(result.error) : t('console_action_failed')); return; }
      jobs.set(result.id, result); saveJobs(); selected.clear(); notices(''); paintJobs(); scheduleJobs();
    } catch (_) { notices(t('command_unknown')); }
    finally { clearTimeout(timeout); submitting = false; paintAge(); load(); }
  }
  function scheduleJobs() {
    clearTimeout(jobsTimer);
    if (!document.hidden && [...jobs.values()].some(job => !job.complete && !job.lost)) jobsTimer = setTimeout(pollJobs, 1000);
  }
  async function pollJobs() {
    if (jobPolling || document.hidden) return;
    jobPolling = true;
    try {
      await Promise.all([...jobs.values()].filter(job => !job.complete && !job.lost).map(async job => {
        try {
          const response = await fetch(`${api}/commands/${encodeURIComponent(job.id)}`, {cache: 'no-store', signal: AbortSignal.timeout(8000)});
          if (response.status === 404) { jobs.set(job.id, {...job, lost: true}); return; }
          if (response.ok) jobs.set(job.id, await response.json());
        } catch (_) { /* Keep pending until an explicit server result is available; never repeat writes. */ }
      }));
      paintJobs();
    } finally { jobPolling = false; scheduleJobs(); }
  }

  function buildSoftwareForm() {
    const section = make('section', 'detail-section'), form = make('form', 'software-form');
    section.append(make('h3', '', t('software')), form);
    const description = make('label', '', t('choose_software')), select = make('select');
    select.id = 'software-choice'; description.htmlFor = select.id;
    const actions = make('div', 'software-actions'), install = make('button', '', t('install')), assign = make('button', 'primary', t('assign'));
    assign.type = 'submit'; install.type = 'button'; actions.append(install, assign);
    const status = make('p', 'software-status'); status.setAttribute('role', 'status');
    form.append(description, select, actions, status, make('p', 'muted', t('assignment_hint')));
    $('detail-content').append(section);
    const toolsLink = make('a', 'comparison', t('tools_link')); toolsLink.href = `/console/tools.html?device=${encodeURIComponent(detailRow.deviceId)}#hardware`; section.append(toolsLink);
    softwareUi = {select, install, assign, status, signature: ''};
    select.addEventListener('change', paintSoftware);
    form.addEventListener('submit', event => {
      event.preventDefault(); const option = software.find(item => `${item.coin}/${item.id}` === select.value);
      if (!option || !detailRow || assign.disabled) return;
      submit([detailRow], 'assign', {coin: option.coin, minerSoftwareId: option.id});
    });
    install.addEventListener('click', async () => {
      const option = software.find(item => `${item.coin}/${item.id}` === select.value);
      if (!option || install.disabled) return;
      softwareBusy = true; paintSoftware();
      try {
        const response = await fetch(`${api}/configuration/software`, {method: 'POST', headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({coin: option.coin, minerSoftwareId: option.id}), signal: AbortSignal.timeout(10000)});
        const result = await response.json(); notices(has(result.status || result.error) ? t(result.status || result.error) : t('console_install_rejected'));
      } catch (_) { notices(t('settings_saved_check')); }
      finally { softwareBusy = false; loadSoftware(); }
    });
  }
  function paintSoftware() {
    if (!softwareUi || !detailRow) return;
    const candidates = software.filter(option => option.hardware?.includes(detailRow.hardwareType));
    const signature = JSON.stringify(candidates);
    if (softwareUi.signature !== signature) {
      softwareUi.signature = signature;
      const previous = softwareUi.select.value;
      softwareUi.select.replaceChildren(new Option(t('choose_software'), ''), ...candidates.map(option =>
        new Option(`${option.coinName} · ${option.name} · ${t(option.installed ? 'installed' : 'not_installed')}${option.experimental ? ` · ${t('experimental')}` : ''}`, `${option.coin}/${option.id}`)));
      softwareUi.select.value = previous;
    }
    const selected = candidates.find(option => `${option.coin}/${option.id}` === softwareUi.select.value);
    softwareUi.install.disabled = !selected || selected.installed || !selected.installable || softwareBusy || selected.downloadStatus === 'DOWNLOADING';
    softwareUi.assign.disabled = !selected || !selected.installed || !selected.selectable || softwareBusy || submitting
      || !online || sourceStale(detailRow) || pending(detailRow) || detailRow.status === 'STARTING' || detailUnavailable;
    text(softwareUi.status, !softwareLoaded ? t('configuration_unavailable') : !selected ? ''
      : selected.unavailableReason ? label(selected.unavailableReason) : has(selected.downloadStatus) ? t(selected.downloadStatus) : !selected.selectable ? t('software_not_ready') : t(selected.installed ? 'installed' : 'not_installed'));
  }
  async function loadSoftware() {
    clearTimeout(softwareTimer);
    if (!selectedDetail || !softwareUi || document.hidden) return;
    try {
      const response = await fetch(`${api}/configuration/software`, {cache: 'no-store', signal: AbortSignal.timeout(8000)});
      if (!response.ok) throw new Error('configuration_unavailable');
      software = await response.json(); softwareLoaded = true;
    } catch (_) { softwareLoaded = false; }
    paintSoftware();
    if (softwareUi && !document.hidden) softwareTimer = setTimeout(loadSoftware, 5000);
  }
  async function loadSettings() {
    try {
      const response = await fetch(`${api}/configuration`, {cache: 'no-store', signal: AbortSignal.timeout(8000)});
      if (!response.ok) throw new Error('configuration_unavailable');
      const value = await response.json();
      $('agent-name').value = value.name || ''; $('sharing').checked = value.sharing; $('external-control').checked = value.externalControl;
      for (const id of ['agent-name', 'sharing', 'external-control', 'settings-save']) $(id).disabled = false;
      text($('settings-status'), '');
    } catch (_) { text($('settings-status'), t('configuration_unavailable')); }
  }
  $('settings-form').addEventListener('submit', async event => {
    event.preventDefault(); if ($('settings-save').disabled) return;
    $('settings-save').disabled = true;
    try {
      const response = await fetch(`${api}/configuration`, {method: 'PUT', headers: {'Content-Type': 'application/json'},
        signal: AbortSignal.timeout(10000), body: JSON.stringify({name: $('agent-name').value,
          sharing: $('sharing').checked, externalControl: $('external-control').checked})});
      const result = await response.json(); text($('settings-status'), has(result.error || result.status) ? t(result.error || result.status) : t('console_settings_partial'));
      if (response.ok) load();
    } catch (_) { text($('settings-status'), t('settings_saved_check')); }
    finally { $('settings-save').disabled = false; }
  });
  $('search').value = filter.q; $('state-filter').value = filter.state;
  $('filters').addEventListener('submit', event => { event.preventDefault(); clearTimeout(searchTimer); changeFilter(); });
  $('search').addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(changeFilter, 250); });
  $('agent-filter').addEventListener('change', () => { filter.agent = $('agent-filter').value; changeFilter(); });
  $('state-filter').addEventListener('change', changeFilter);
  $('reset').addEventListener('click', () => { filter.agent = ''; $('agent-filter').value = ''; $('search').value = ''; $('state-filter').value = ''; changeFilter(); });
  for (const [id, delta] of [['previous', -1], ['next', 1]]) $(id).addEventListener('click', () => { filter.page += delta; selected.clear(); saveUrl(); load(); });
  $('refresh').addEventListener('click', load);
  $('select-page').addEventListener('change', () => {
    selected.clear();
    if ($('select-page').checked) for (const row of workspace?.workers || []) if (!blocked(row, 'start') || !blocked(row, 'pause')) selected.add(key(row));
    updateSelection();
  });
  $('clear-selection').addEventListener('click', () => { selected.clear(); updateSelection(); });
  for (const action of ['start', 'pause']) {
    $(`${action}-selected`).addEventListener('click', () => submit(workspace.workers.filter(row => selected.has(key(row))), action));
    $(`detail-${action}`).addEventListener('click', () => { if (detailRow) submit([detailRow], action); });
  }
  $('close-detail').addEventListener('click', () => $('detail').close());
  $('detail').addEventListener('close', () => { clearTimeout(softwareTimer); softwareUi = null; selectedDetail = null; detailRow = null; detailController?.abort(); detailController = null; detailReturn?.focus(); });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { clearTimeout(timer); clearTimeout(jobsTimer); readVersion++; readController?.abort(); }
    else { load(); scheduleJobs(); if (softwareUi) loadSoftware(); }
  });
  window.addEventListener('pageshow', event => { if (event.persisted) { load(); scheduleJobs(); } });
  window.addEventListener('online', load);
  setInterval(() => { if (!document.hidden) { paintAge(); if (workspace) paintMachines(); } }, 1000);
  try {
    const saved = JSON.parse(sessionStorage.getItem('solarminer.operations.jobs') || '[]');
    if (Array.isArray(saved)) for (const id of saved.slice(-8)) if (typeof id === 'string' && /^[\w-]{1,80}$/.test(id)) jobs.set(id, {id, action: 'action', results: [], complete: false});
  } catch (_) {}
  load(); loadSettings(); scheduleJobs();
})();
