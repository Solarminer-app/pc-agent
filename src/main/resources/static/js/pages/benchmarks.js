const $ = id => document.getElementById(id);
const make = (tag, cls, value) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (value != null) e.textContent = value;
    return e;
};
let actionBusy = false;
const t = window.SolarMinerI18n.t;
const s = window.SolarMinerI18n.s;
let lastResults = '';
let lastRenderRunning = null;
let lastUploadStatus = '';
let sharePromptShown = false;
let sharingKnown = false;
let savedBenchmarkSignature = null;
let measurementRequest = null;
let measurementSnapshot = null;
let measurementRevision = 0;
let previousBenchmarkRunning = null;
let previousSweepRunning = null;
let lastAutoStartSignature = '';
const openAlgorithmGroups = new Map();
const resultsPage = document.body.classList.contains('benchmark-results-page');
let availableWorkers = [];
let availableWorkerById = new Map();
let latestBenchmarkState = null;
let latestSweepState = null;
let sweepViewMode = 'live';
const selectedGpu = () => resultsPage ? $('comparison-gpu').value : null;
const forSelectedGpu = rows => resultsPage ? (rows || []).filter(row =>
    (row.deviceId || row.workerId) === selectedGpu()) : (rows || []);
if (resultsPage) {
    $('upload-status').previousElementSibling.hidden = true;
    for (const id of ['upload-status', 'periodic-upload-status', 'retry-upload']) $(id).hidden = true;
} else {
    const sharingStatus = make('div', 'benchmark-sharing-status');
    sharingStatus.append($('upload-status'), $('periodic-upload-status'), $('retry-upload'));
    document.querySelector('.sharing-details .panel').append(sharingStatus);
}

function algorithmGroupLabel(row) {
    const catalog = window.SolarMinerUI.coinCatalog;
    const known = catalog.find(coin => coin.id === row.coin)
        || catalog.find(coin => coin.id === window.SolarMinerUI.coinIdForAlgorithm(row.algorithm));
    return known?.algorithm
        || row.algorithm || t('benchmark_unknown_algorithm');
}

function mountAlgorithmGroups(root, rows, section) {
    const grouped = new Map();
    for (const row of rows) {
        const algorithm = algorithmGroupLabel(row);
        if (!grouped.has(algorithm)) grouped.set(algorithm, []);
        grouped.get(algorithm).push(row);
    }
    const ordered = [...grouped.entries()].sort(([left], [right]) => left.localeCompare(right, window.SolarMinerI18n.locale));
    root.replaceChildren();
    const containers = new Map();
    for (const [index, [algorithm, members]] of ordered.entries()) {
        const group = make('details', 'benchmark-algorithm-group');
        const key = `${section}|${algorithm}`;
        group.open = openAlgorithmGroups.get(key) ?? (ordered.length === 1 || index === 0);
        group.addEventListener('toggle', () => openAlgorithmGroups.set(key, group.open));
        const summary = make('summary', 'benchmark-algorithm-summary');
        summary.append(make('strong', '', algorithm),
            make('span', 'benchmark-algorithm-count', t(members.length === 1
                ? 'benchmark_algorithm_entry_one' : 'benchmark_algorithm_entries', {count: members.length})));
        const cards = make('div', 'benchmark-algorithm-cards');
        group.append(summary, cards);
        root.append(group);
        containers.set(algorithm, cards);
    }
    return containers;
}

function invalidateMeasurements() {
    measurementRevision++;
    measurementSnapshot = null;
    measurementRequest = null;
    comparisonCacheRevision++;
    comparisonCache.clear();
}

function readMeasurements() {
    if (measurementSnapshot) return Promise.resolve(measurementSnapshot);
    if (measurementRequest) return measurementRequest;
    const revision = measurementRevision;
    const request = fetch('/api/agent/local/measurements/profiles', {cache: 'no-store'})
        .then(response => { if (!response.ok) throw new Error(`HTTP ${response.status}`); return response.json(); })
        .then(snapshot => { if (revision === measurementRevision) measurementSnapshot = snapshot; return snapshot; })
        .finally(() => { if (measurementRequest === request) measurementRequest = null; });
    measurementRequest = request;
    return measurementRequest;
}

function renderSavedBenchmarks() {
    if (!resultsPage) return Promise.resolve();
    return loadSavedBenchmarks();
}

async function loadSavedBenchmarks() {
    const revision = measurementRevision;
    try {
        const snapshot = await readMeasurements();
        if (revision !== measurementRevision) return;
        if (resultsPage) updateComparisonDevices(snapshot);
        const profiles = forSelectedGpu(snapshot.benchmarks);
        $('overview-profiles').textContent = new Intl.NumberFormat(window.SolarMinerI18n.locale).format(profiles.length);
        const signature = JSON.stringify(profiles);
        if (signature === savedBenchmarkSignature) return;
        savedBenchmarkSignature = signature;
        const root = $('saved-benchmarks'); root.replaceChildren();
        if (!profiles.length) {
            root.append(make('p', 'empty', t('benchmark_no_saved_points')));
            appendStorageDiagnostic(root, snapshot.benchmarkStorage);
            return;
        }
        const groups = mountAlgorithmGroups(root, profiles, 'saved');
        for (const profile of profiles) {
            const card = make('article', 'worker-card');
            const origin = ['LIVE', 'BENCHMARK'].includes(profile.source) ? profile.source.toLowerCase() : 'benchmark';
            const target = profile.powerTargetWatts > 0
                ? `${profile.powerTargetWatts} W` : t('benchmark_target_not_recorded');
            card.append(make('small', 'measurement-origin', t(`coin_hopping_source_${origin}`)),
                make('strong', '', `${profile.hardwareModel || profile.workerId} · ${profile.coin} · ${profile.algorithm}${profile.minerSoftwareId ? ` · ${profile.minerSoftwareId}` : ''}`),
                make('p', 'muted', `${t('device_identity')}: ${profile.workerId || profile.deviceId}`),
                make('p', 'measurement-target', t('benchmark_profile_target', {target})),
                make('p', 'muted', t('hardware_count_samples', {hardware: profile.hardwareType, count: profile.observations})),
                make('p', '', `${t('hashrate')}: ${fmtRate(profile.hashrateHps)} · ${t('power_4606f4')}: ${profile.powerWatts} W · ${t('energy_per_hash')}: ${window.SolarMinerUI.efficiency(profile.hashrateHps, profile.powerWatts)}`));
            card.dataset.filter = [profile.hardwareModel || profile.model, profile.workerId || profile.deviceId, profile.coin, profile.algorithm, profile.minerSoftwareId].join(' ').toLocaleLowerCase();
            groups.get(algorithmGroupLabel(profile)).append(card);
        }
        applyProfileFilter();
    } catch (e) {
        if (revision !== measurementRevision) return;
        savedBenchmarkSignature = null;
        $('saved-benchmarks').replaceChildren(make('p', 'empty', t('could_not_load_saved_benchmark_profiles', {error: s(e.message)})));
    }
}

function appendStorageDiagnostic(root, status) {
    if (!status || !status.loadError) return;
    root.append(make('p', 'muted', t('profile_storage_diagnostic', {
        file: status.file,
        state: status.loadError || (status.exists ? t('file_readable') : t('file_not_created_yet')),
        count: status.profiles
    })));
}

function notice(text, error = false, kind = 'action') {
    const e = $('notice');
    e.hidden = false; e.dataset.kind = kind;
    e.className = `notice${error ? ' error' : ''}`;
    e.textContent = s(text);
    if (error && !resultsPage) { e.tabIndex = -1; e.focus(); }
}

function fmtRate(v) {
    return window.SolarMinerUI.hashrate(v);
}

function benchmarkPhase(phase) {
    const progress = String(phase || '').match(/^(.*?) · (\d+)\/(\d+) Messpunkte$/);
    return progress ? t('benchmark_sample_progress', {
        phase: s(progress[1]), samples: progress[2], required: progress[3]
    }) : s(phase);
}

async function comparison(row) {
    const params = new URLSearchParams({
        hardwareType: row.hardwareType,
        hardwareModel: row.hardwareModel,
        algorithm: row.algorithm
    });
    const response = await fetch(`/api/agent/local/benchmarks/match?${params}`, {cache: 'no-store'});
    if (!response.ok) return null;
    return response.json();
}

function renderChart(results, running) {
    const root = $('benchmark-chart');
    root.replaceChildren(); root.hidden = !results?.length;
    if (!results?.length) return;
    const head = make('div', 'benchmark-chart-head');
    head.append(make('strong', '', t('current_median_hashrate')), make('span', '', running ? t('benchmark_running') : t('results')));
    root.append(head);
    const algorithms = new Map();
    for (const row of results) {
        const algorithm = algorithmGroupLabel(row);
        if (!algorithms.has(algorithm)) algorithms.set(algorithm, []);
        algorithms.get(algorithm).push(row);
    }
    for (const [algorithm, rows] of [...algorithms.entries()].sort(([a], [b]) => a.localeCompare(b, window.SolarMinerI18n.locale))) {
        const section = make('section', 'benchmark-chart-series');
        section.append(make('h4', '', algorithm));
        const bars = make('div', 'benchmark-bar-list');
        const maximum = Math.max(...rows.map(row => Number(row.hashrateHs) || 0), 1);
        for (const row of rows) {
            const line = make('div', 'benchmark-bar-row');
            const label = make('span', 'benchmark-bar-label', `${row.hardwareModel}${row.deviceId ? ` · ${row.deviceId}` : ''}${row.minerSoftwareId ? ` · ${row.minerSoftwareId}` : ''}`);
            const track = make('div', 'benchmark-bar-track');
            track.setAttribute('role', 'progressbar'); track.setAttribute('aria-label', `${algorithm} · ${label.textContent}: ${fmtRate(row.hashrateHs)}`);
            track.setAttribute('aria-valuemin', '0'); track.setAttribute('aria-valuemax', String(maximum)); track.setAttribute('aria-valuenow', String(row.hashrateHs || 0));
            const fill = make('i', 'benchmark-bar-fill');
            fill.style.width = `${Math.max(0, Math.min(100, (Number(row.hashrateHs) || 0) / maximum * 100))}%`;
            track.append(fill); line.append(label, track, make('span', 'benchmark-bar-value', fmtRate(row.hashrateHs))); bars.append(line);
        }
        section.append(bars); root.append(section);
    }
}

let liveWorkerId = null;
let liveTabSignature = '';
let livePanelSignature = '';
if ($('benchmark-worker-tabs')) $('benchmark-worker-tabs').addEventListener('keydown', event => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    const buttons = [...$('benchmark-worker-tabs').querySelectorAll('[role="tab"]')];
    if (!buttons.length) return;
    event.preventDefault();
    const index = buttons.indexOf(document.activeElement);
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1
        : (index + (event.key === 'ArrowRight' ? 1 : -1) + buttons.length) % buttons.length;
    buttons[next].click();
    buttons[next].focus();
});
function liveWorkerLabel(id, series) {
    const worker = availableWorkerById.get(id);
    const model = worker?.hardwareModel || series?.hardwareModel || id;
    return `${model} · ${id}`;
}
function liveMetricChart(points, field, label, format) {
    const values = points.map(point => Number(point[field]) || 0);
    const maximum = Math.max(...values, 1) * 1.12;
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 560 180');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', t('benchmark_live_chart_aria', {metric: label, count: points.length}));
    const add = (tag, attributes, value) => {
        const node = document.createElementNS(ns, tag);
        for (const [key, attribute] of Object.entries(attributes)) node.setAttribute(key, String(attribute));
        if (value != null) node.textContent = value;
        svg.append(node);
        return node;
    };
    const x = index => 48 + index / 11 * 490;
    const y = value => 145 - value / maximum * 118;
    for (let line = 0; line <= 2; line++) {
        const value = maximum * (2 - line) / 2;
        const position = y(value);
        add('line', {x1:48, x2:538, y1:position, y2:position, class:'benchmark-live-grid'});
        add('text', {x:42, y:position + 4, 'text-anchor':'end', class:'benchmark-live-axis'}, format(value));
    }
    add('text', {x:538, y:169, 'text-anchor':'end', class:'benchmark-live-axis'},
        t('benchmark_live_sample_axis', {count: 12}));
    if (points.length > 1) {
        add('polyline', {points:values.map((value, index) => `${x(index)},${y(value)}`).join(' '),
            fill:'none', pathLength:1, class:'benchmark-live-line'});
    }
    for (const [index, value] of values.entries()) {
        const dot = add('circle', {cx:x(index), cy:y(value), r:4.5, class:'benchmark-live-point'});
        const title = document.createElementNS(ns, 'title');
        title.textContent = `${t('benchmark_live_sample_number', {number:index + 1})}: ${format(value)}`;
        dot.append(title);
    }
    return svg;
}
function renderBenchmarkLive(state) {
    const workspace = $('benchmark-live-workspace');
    if (!workspace) return;
    const series = state.liveSeries || [];
    const seriesByWorker = new Map();
    for (const trace of series) {
        if (!seriesByWorker.has(trace.deviceId)) seriesByWorker.set(trace.deviceId, []);
        seriesByWorker.get(trace.deviceId).push(trace);
    }
    const workerIds = [...new Set([...(state.selectedWorkers || []),
        ...series.map(row => row.deviceId), ...(state.results || []).map(row => row.deviceId)].filter(Boolean))]
        .sort((a, b) => liveWorkerLabel(a, seriesByWorker.get(a)?.[0])
            .localeCompare(liveWorkerLabel(b, seriesByWorker.get(b)?.[0]), window.SolarMinerI18n.locale));
    workspace.hidden = !workerIds.length || (!state.running && !(state.results || []).length);
    if (workspace.hidden) return;
    if (!workerIds.includes(liveWorkerId)) liveWorkerId = workerIds[0];
    const tabs = $('benchmark-worker-tabs');
    const tabSignature = JSON.stringify(workerIds);
    if (tabSignature !== liveTabSignature) {
        liveTabSignature = tabSignature;
        tabs.replaceChildren();
        for (const id of workerIds) {
            const button = make('button', 'benchmark-worker-tab');
            button.type = 'button'; button.setAttribute('role', 'tab'); button.dataset.workerId = id;
            button.id = `benchmark-worker-tab-${workerIds.indexOf(id)}`;
            button.setAttribute('aria-controls', 'benchmark-worker-panel');
            button.addEventListener('click', () => { liveWorkerId = id; livePanelSignature = ''; renderBenchmarkLive(latestBenchmarkState); });
            tabs.append(button);
        }
    }
    const panel = $('benchmark-worker-panel');
    for (const button of tabs.children) {
        const id = button.dataset.workerId;
        const traces = seriesByWorker.get(id) || [];
        const count = traces.at(-1)?.points?.length || 0;
        button.textContent = `${liveWorkerLabel(id, traces[0])} · ${count}/${12}`;
        button.setAttribute('aria-selected', String(id === liveWorkerId));
        button.tabIndex = id === liveWorkerId ? 0 : -1;
        button.classList.toggle('selected', id === liveWorkerId);
        if (id === liveWorkerId) panel.setAttribute('aria-labelledby', button.id);
    }
    const selectedSeries = seriesByWorker.get(liveWorkerId) || [];
    const signature = JSON.stringify([liveWorkerId, selectedSeries, state.running, state.phase]);
    if (signature === livePanelSignature) return;
    livePanelSignature = signature;
    panel.replaceChildren();
    if (!selectedSeries.length) {
        panel.append(make('p', 'empty', t(state.running ? 'benchmark_live_waiting' : 'benchmark_live_no_samples')));
        return;
    }
    for (const trace of selectedSeries) {
        const points = trace.points || [];
        const result = (state.results || []).find(row => row.deviceId === trace.deviceId
            && row.algorithm === trace.algorithm && row.minerSoftwareId === trace.minerSoftwareId);
        const card = make('article', 'benchmark-live-series');
        const heading = make('div', 'benchmark-live-series-head');
        heading.append(make('strong', '', `${trace.algorithm} · ${trace.minerSoftwareId || '—'}`),
            make('span', 'tag', t('benchmark_live_points', {count:points.length, required:12})));
        card.append(heading);
        if (result) card.append(make('p', 'benchmark-live-summary',
            `${t('current_median_hashrate')}: ${fmtRate(result.hashrateHs)} · ${t('power_4606f4')}: ${result.powerWatts > 0 ? Math.round(result.powerWatts) + ' W' : t('unavailable_6d094b')}`));
        const charts = make('div', 'benchmark-live-charts');
        const rate = make('div', 'benchmark-live-chart');
        rate.append(make('h3', '', t('hashrate')), liveMetricChart(points, 'hashrateHs', t('hashrate'), fmtRate));
        charts.append(rate);
        if (points.some(point => point.powerWatts > 0)) {
            const power = make('div', 'benchmark-live-chart');
            power.append(make('h3', '', t('power_4606f4')),
                liveMetricChart(points, 'powerWatts', t('power_4606f4'), value => `${Math.round(value)} W`));
            charts.append(power);
        }
        card.append(charts); panel.append(card);
    }
}

function renderAutoStartStatus(state) {
    const root = $('auto-start-status');
    if (!root) return;
    $('auto-start-mining').disabled = state.running || actionBusy;
    const signature = JSON.stringify([state.running, state.autoStartRequested, state.autoStartResults]);
    if (signature === lastAutoStartSignature) return;
    lastAutoStartSignature = signature;
    root.replaceChildren();
    root.hidden = !state.autoStartRequested;
    if (root.hidden) return;
    if (state.running) {
        root.append(make('p', 'muted', t('benchmark_auto_start_pending')));
        return;
    }
    const outcomes = state.autoStartResults || [];
    if (!outcomes.length) {
        root.append(make('p', 'muted', t('benchmark_auto_start_no_result')));
        return;
    }
    root.append(make('strong', '', t('benchmark_auto_start_result_heading')));
    const list = make('ul', 'benchmark-auto-start-list');
    for (const outcome of outcomes) {
        const item = make('li', outcome.status === 'STARTED' ? 'success' : 'error');
        item.append(make('strong', '', liveWorkerLabel(outcome.deviceId)),
            make('span', '', t(outcome.status === 'STARTED' ? 'benchmark_auto_start_accepted'
                : outcome.status === 'SKIPPED' ? 'benchmark_auto_start_skipped' : 'benchmark_auto_start_failed')));
        if (outcome.message) item.append(make('small', 'muted', s(outcome.message)));
        list.append(item);
    }
    root.append(list);
}

let resultRenderVersion = 0;
async function renderResults(results, running = false, phase = '') {
    const version = ++resultRenderVersion;
    const root = $('results');
    root.replaceChildren();
    results = forSelectedGpu(results);
    renderChart(results, running);
    if (!results?.length) {
        root.append(make('p', 'empty', running
            ? t('phase_waiting_for_the_first_valid_hashrate_s', {phase: benchmarkPhase(phase || 'benchmark_running')})
            : resultsPage ? t('benchmark_no_gpu_run_result')
                : !phase || phase === 'Idle' ? t('no_measurement_yet_choose_a_mode_above') : t('no_valid_mining_readings_recorded_check_that')));
        return;
    }
    const groups = mountAlgorithmGroups(root, results, 'current');
    const comparisons = running ? [] : results.map(comparison);
    for (const [index, row] of results.entries()) {
        const card = make('article', 'worker-card');
        const info = make('div', '');
        info.append(make('strong', '', `${row.hardwareModel} · ${row.algorithm}${row.minerSoftwareId ? ` · ${row.minerSoftwareId}` : ''}`), make('p', 'muted', t('hardware_count_samples', {hardware: row.hardwareType, count: row.observations})));
        if (row.deviceId) info.append(make('p', 'muted', `${t('device_identity')}: ${row.deviceId}`));
        info.append(make('p', '', `${t('hashrate')}: ${fmtRate(row.hashrateHs)} · ${t('power_4606f4')}: ${row.powerWatts > 0 ? new Intl.NumberFormat(window.SolarMinerI18n.locale, {maximumFractionDigits: 0}).format(row.powerWatts) + ' W' : t('unavailable_6d094b')} · ${t('energy_per_hash')}: ${window.SolarMinerUI.efficiency(row.hashrateHs, row.powerWatts)}`));
        card.append(info);
        groups.get(algorithmGroupLabel(row)).append(card);
        const peer = running ? null : await comparisons[index].catch(() => null);
        if (version !== resultRenderVersion || !card.isConnected) return;
        if (peer) {
            const delta = peer.medianHashrateHs > 0 ? (row.hashrateHs / peer.medianHashrateHs - 1) * 100 : null;
            info.append(make('p', 'muted', `${t('comparison')} (${peer.sampleCount} ${t('devices_4425de')}): ${t('median')} ${fmtRate(peer.medianHashrateHs)}${peer.medianPowerWatts ? ` · ${Math.round(peer.medianPowerWatts)} W` : ''}${delta == null ? '' : ` · ${new Intl.NumberFormat(window.SolarMinerI18n.locale, {maximumFractionDigits: 1}).format(Math.abs(delta))} % ${t(delta >= 0 ? 'above' : 'below')} ${t('the_median')}`}`));
        } else info.append(make('p', 'muted', running
            ? t('readings_update_continuously_comparison_appe')
            : t('no_published_comparison_for_this_hardware_an')));
    }
}

function applyBenchmarkState(state, meta = {}) {
    latestBenchmarkState = state;
    renderBenchmarkLive(state);
    renderAutoStartStatus(state);
    if (resultsPage) updateComparisonDevices(measurementSnapshot || {benchmarks: [], sweeps: []});
    $('overview-benchmark').textContent = state.running ? t('running') : t('ready');
    if (previousBenchmarkRunning === true && !state.running) { invalidateMeasurements(); renderSavedBenchmarks(); }
    previousBenchmarkRunning = state.running;
    if ($('notice').dataset.kind === 'connection') $('notice').hidden = true;
    $('connection').className = `badge ${meta.stale ? 'offline' : 'online'}`;
    $('connection').textContent = meta.stale ? t('connecting') : t('agent_connected');
    $('benchmark-updated').textContent = meta.at
        ? t('benchmark_updated_at', {time: new Intl.DateTimeFormat(window.SolarMinerI18n.locale, {hour: '2-digit', minute: '2-digit', second: '2-digit'}).format(meta.at)})
        : '';
    $('run-state').textContent = !state.phase || state.phase === 'Idle' ? t('ready') : benchmarkPhase(state.phase);
    $('cancel').hidden = !state.running;
    $('progress-wrap').hidden = !state.running;
    $('run-live').disabled = $('run-installed').disabled = state.running || actionBusy;
    if (state.running) {
        $('upload-status').hidden = true;
        $('retry-upload').hidden = true;
    }
    if (state.running) {
        $('phase').textContent = t('phase_step_step_total', {phase: benchmarkPhase(state.phase), step: state.phaseIndex, total: state.phaseCount});
        const count = state.phase.match(/·\s*(\d+)\/(\d+)\s*Messpunkte/);
        if (count) {
            $('progress').max = Number(count[2]);
            $('progress').value = Number(count[1]);
        } else {
            $('progress').removeAttribute('value');
        }
    }
    const signature = JSON.stringify(state.results || []);
    if (resultsPage && (signature !== lastResults || state.running !== lastRenderRunning || (state.running && !state.results?.length))) {
        lastResults = signature;
        lastRenderRunning = state.running;
        renderResults(state.results, state.running, state.phase);
    } else if (resultsPage && !state.running && state.phase !== 'Idle' && !state.results?.length) {
        $('results').replaceChildren(make('p', 'empty', benchmarkPhase(state.phase)));
        lastResults = signature;
    }
    if (!state.running) window.SolarMinerLive.refresh('benchmark-upload');
    if (state.running) document.querySelector('.benchmark-alternatives')?.setAttribute('open', '');
    updateMeasurementControls();
}

function connectionLost() {
    $('connection').className = 'badge offline';
    $('connection').textContent = t('agent_unavailable');
    latestBenchmarkState = null; updateMeasurementControls();
    notice('could_not_load_benchmark_status_existing_res', true, 'connection');
}

function applyUploadStatus(statuses) {
    if (!statuses) return;
    const signature = JSON.stringify(statuses);
    if (signature === lastUploadStatus) return;
    lastUploadStatus = signature;
    const manual = statuses.manual;
    const periodic = statuses.periodic;
    showUploadStatus('upload-status', manual, 'benchmark_upload');
    showUploadStatus('periodic-upload-status', periodic, 'periodic_upload');
    $('retry-upload').hidden = manual.status !== 'FAILED';
    // The agent only reports NOT_SHARED with samples when a finished manual run kept its
    // measurements unsent; offer them once per run if the operator has sharing disabled.
    if (!resultsPage && manual.status === 'NOT_SHARED' && manual.sampleCount > 0 && sharingKnown && !$('sharing').checked && !sharePromptShown) {
        sharePromptShown = true;
        $('share-prompt').showModal();
    }
}

function showUploadStatus(id, status, label) {
    const element = $(id);
    element.hidden = !status || status.status === 'IDLE';
    if (element.hidden) return;
    element.className = `notice${status.status === 'FAILED' ? ' error' : ''}`;
    element.textContent = `${t(label)}: ${s(status.message)}${status.sampleCount ? ` ${t('count_samples', {count: status.sampleCount})}` : ''}`;
}

async function start(mode) {
    if (resultsPage && catalogCoins.length && !document.querySelector('#coin-options input:checked')) {
        notice('benchmark_select_coin', true); return;
    }
    if (!requireWorkerSelection(false)) return;
    const gpuLimit = selectedGpuLimit();
    if (gpuLimit == null) return;
    if (actionBusy) return; actionBusy = true;
    if ($('auto-start-mining')) $('auto-start-mining').disabled = true;
    $('run-live').disabled = $('run-installed').disabled = true;
    updateMeasurementControls();
    try {
        const scope = await prepareMeasurementScope(false);
        const response = await fetch('/api/agent/local/benchmarks', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({mode, coins: scope.coins, workers: scope.workers, startMiningAfterBenchmark: mode === 'INSTALLED' && Boolean($('auto-start-mining')?.checked), maxConcurrentGpus: gpuLimit})});
        if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.detail || body?.message || t('could_not_start_the_benchmark')); }
        applyBenchmarkState(await response.json());
        notice('measurement_started_you_can_cancel_it_at_any');
        $('results').replaceChildren(make('p', 'empty', t('benchmark_started')));
    } catch (error) { notice(window.SolarMinerI18n.s(error.message), true); }
    finally { actionBusy = false; updateMeasurementControls(); window.SolarMinerLive.refresh('benchmarks'); }
}

const autoStartChoiceKey = 'solarminer.pc-agent.benchmark-auto-start';
if ($('auto-start-mining')) {
    try { $('auto-start-mining').checked = localStorage.getItem(autoStartChoiceKey) === 'true'; } catch (_) { }
    $('auto-start-mining').addEventListener('change', () => {
        try { localStorage.setItem(autoStartChoiceKey, String($('auto-start-mining').checked)); } catch (_) { }
    });
}

// The coin selection applies to both measurement runs (benchmark and power-limit sweep).
// Uninstalled coins stay disabled; a stored selection survives reloads so an operator can
// permanently exclude coins their cards cannot run (e.g. PearlHash on Pascal).
const coinSelectionKey = 'solarminer.pc-agent.benchmark-coins';
const workerSelectionKey = 'solarminer.pc-agent.benchmark-workers';
const gpuConcurrencyKey = 'solarminer.pc-agent.benchmark-gpu-concurrency';
const gpuConcurrencyInput = $('max-concurrent-gpus');
try {
    const stored = Number(localStorage.getItem(gpuConcurrencyKey));
    if (Number.isInteger(stored) && stored >= 1 && stored <= 64) gpuConcurrencyInput.value = String(stored);
} catch (_) { }
gpuConcurrencyInput.addEventListener('change', () => {
    if (gpuConcurrencyInput.checkValidity()) {
        try { localStorage.setItem(gpuConcurrencyKey, gpuConcurrencyInput.value); } catch (_) { }
    }
});

function selectedGpuLimit() {
    const value = Number(gpuConcurrencyInput.value);
    if (!gpuConcurrencyInput.checkValidity() || !Number.isInteger(value) || value < 1 || value > 64) {
        notice('benchmark_invalid_gpu_concurrency', true);
        gpuConcurrencyInput.focus();
        return null;
    }
    return value;
}
let catalogCoins = [];
let comparisonCatalog = [];
let workersLoaded = false;

function selectedWorkers() {
    return [...document.querySelectorAll('#worker-options input[data-hardware-type]:checked')].map(input => input.value);
}

function requireWorkerSelection(gpuOnly) {
    if (!workersLoaded) { notice('benchmark_worker_list_unavailable', true); return false; }
    if (!selectedWorkers().length) { notice('benchmark_select_worker', true); return false; }
    if (gpuOnly && ![...document.querySelectorAll('#worker-options input[data-hardware-type]:checked')]
        .some(input => input.dataset.hardwareType === 'GPU')) {
        notice('benchmark_select_gpu_for_sweep', true); return false;
    }
    return true;
}

function renderSelectionSummary() {
    if (resultsPage) return;
    const selected = selectedWorkers();
    let combinations = 0, pending = false;
    for (const card of document.querySelectorAll('.benchmark-worker-card')) {
        const checked = selected.includes(card.dataset.worker);
        const editor = card.querySelector('.benchmark-worker-policy');
        const count = editor.readyCombinations?.().length || 0;
        card.classList.toggle('selected', checked);
        card.querySelector('.benchmark-worker-summary').textContent = !checked ? t('bm_worker_unselected')
            : !editor.readPolicy ? t('bm_selection_loading') : t('bm_worker_ready', {count});
        if (checked) { combinations += count; pending ||= !editor.readPolicy; }
    }
    const gpus = selected.filter(id => availableWorkerById.get(id)?.hardwareType === 'GPU').length;
    $('overview-selected').textContent = String(selected.length);
    $('overview-combinations').textContent = String(combinations);
    $('selection-summary').textContent = pending ? t('bm_selection_loading')
        : t('bm_scope_summary', {workers: selected.length, gpus, combinations});
}

function updateMeasurementControls() {
    if (resultsPage) return;
    const running = Boolean(latestBenchmarkState?.running || latestSweepState?.running);
    const known = Boolean(latestBenchmarkState && latestSweepState);
    const locked = running || actionBusy || !known;
    const selected = selectedWorkers();
    const any = selected.length > 0;
    const gpu = selected.some(id => availableWorkerById.get(id)?.hardwareType === 'GPU');
    for (const id of ['run-live', 'run-installed']) $(id).disabled = locked || !workersLoaded || !any;
    for (const id of ['run-sweep', 'resume-sweep']) $(id).disabled = locked || !workersLoaded || !gpu;
    for (const input of document.querySelectorAll('#worker-selection input, #worker-selection button')) input.disabled = locked;
    $('auto-start-mining').disabled = locked;
    $('auto-tune-after-sweep').disabled = locked;
    $('measurement-lock-note').textContent = !known ? t('bm_measurement_initializing') : running ? t('bm_measurement_busy') : '';
}

async function prepareMeasurementScope(gpuOnly) {
    if (resultsPage) return {coins: selectedCoins(), workers: selectedWorkers()};
    const selected = selectedWorkers().filter(id => !gpuOnly || availableWorkerById.get(id)?.hardwareType === 'GPU');
    const editors = [];
    for (const id of selected) {
        const card = [...document.querySelectorAll('.benchmark-worker-card')].find(card => card.dataset.worker === id);
        const editor = card.querySelector('.benchmark-worker-policy');
        await editor.policyReady;
        const worker = availableWorkerById.get(id);
        if (!editor.savePolicy) throw new Error(t('bm_options_failed', {worker: worker.hardwareModel || id}));
        if (!editor.readyCombinations().length) throw new Error(t('bm_no_ready_combinations', {worker: worker.hardwareModel || id}));
        editors.push(editor);
    }
    notice('bm_save_before_start');
    for (const editor of editors) await editor.savePolicy();
    return {workers: selected, coins: [...new Set(editors.flatMap(editor => editor.readyCombinations().map(pair => pair.coin)))]};
}

document.addEventListener('benchmark-policy-change', () => { renderSelectionSummary(); updateMeasurementControls(); });
for (const [id, checked] of [['select-all-workers', true], ['clear-workers', false]]) $(id)?.addEventListener('click', () => {
    for (const input of document.querySelectorAll('#worker-options input[data-hardware-type]')) {
        if (input.checked !== checked) { input.checked = checked; input.dispatchEvent(new Event('change')); }
    }
});

function renderWorkerOptions(workers) {
    availableWorkers = workers;
    availableWorkerById = new Map(workers.map(worker => [worker.deviceId, worker]));
    if (latestBenchmarkState) {
        renderBenchmarkLive(latestBenchmarkState);
        lastAutoStartSignature = '';
        renderAutoStartStatus(latestBenchmarkState);
    }
    if (resultsPage) updateComparisonDevices(measurementSnapshot || {benchmarks: [], sweeps: []});
    const root = $('worker-options');
    root.replaceChildren();
    let stored = [];
    try {
        const value = JSON.parse(localStorage.getItem(workerSelectionKey));
        if (Array.isArray(value)) stored = value;
    } catch (_) { }
    for (const worker of workers) {
        const card = make('article', 'benchmark-worker-card'); card.dataset.worker = worker.deviceId;
        const label = make('label', 'benchmark-worker-select');
        const input = make('input', ''); input.type = 'checkbox'; input.value = worker.deviceId;
        input.dataset.hardwareType = worker.hardwareType; input.checked = stored.includes(worker.deviceId);
        const text = make('span', '');
        text.append(make('strong', '', worker.hardwareModel || worker.hardwareType), make('small', 'muted', `${worker.hardwareType} · ${worker.deviceId}`));
        label.append(input, text); card.append(label);
        const summary = make('p', 'benchmark-worker-summary'); card.append(summary);
        const editor = make('div', 'benchmark-worker-policy');
        editor.setAttribute('role', 'group'); editor.setAttribute('aria-label', t('bm_worker_scope', {worker: `${worker.hardwareModel || worker.hardwareType} · ${worker.deviceId}`}));
        card.append(editor); root.append(card);
        let loading;
        const load = () => {
            editor.hidden = false;
            loading = window.SolarMinerBenchmarkPolicyEditor(editor, worker, {measurement: !resultsPage, retry: load});
            editor.policyReady = loading;
            loading.then(() => { renderSelectionSummary(); updateMeasurementControls(); });
            return loading;
        };
        input.addEventListener('change', () => {
            try { localStorage.setItem(workerSelectionKey, JSON.stringify(selectedWorkers())); } catch (_) { }
            if (input.checked && !loading) load();
            editor.hidden = !input.checked;
            renderSelectionSummary(); updateMeasurementControls();
        });
        editor.hidden = !input.checked;
        if (input.checked) load();
    }
    workersLoaded = true;
    renderSelectionSummary(); updateMeasurementControls();
}

fetch('/api/agent/local/workers', {cache: 'no-store'}).then(async response => {
    if (!response.ok) throw new Error('benchmark_worker_list_unavailable');
    renderWorkerOptions(await response.json());
}).catch(() => {
    workersLoaded = false;
    $('worker-options').replaceChildren(make('p', 'empty', t('benchmark_worker_list_unavailable')));
    updateMeasurementControls();
});

function storedCoinSelection() {
    try {
        const value = JSON.parse(localStorage.getItem(coinSelectionKey));
        return Array.isArray(value) ? value : null;
    } catch (_) { return null; }
}

function selectedCoins() {
    if (!resultsPage) return []; // The per-worker combinations are the measurement scope.
    const checked = [...document.querySelectorAll('#coin-options input:checked')].map(input => input.value);
    // All coins checked means "no restriction" — the agent keeps its full-run semantics.
    return checked.length === catalogCoins.length ? [] : checked;
}

function renderCoinOptions(catalog) {
    comparisonCatalog = catalog;
    catalogCoins = catalog.filter(entry => entry.binaryAvailable);
    const root = $('coin-options');
    if (!root) return;
    root.replaceChildren();
    if (!catalog.length) return;
    const stored = storedCoinSelection();
    for (const entry of catalog) {
        const label = make('label', 'benchmark-coin-option');
        const input = make('input', '');
        input.type = 'checkbox'; input.value = entry.id;
        input.disabled = !entry.binaryAvailable;
        input.checked = entry.binaryAvailable && (stored ? stored.includes(entry.id) : true);
        input.addEventListener('change', () => {
            try { localStorage.setItem(coinSelectionKey, JSON.stringify(
                [...document.querySelectorAll('#coin-options input:checked')].map(option => option.value))); } catch (_) { }
        });
        const text = make('span', '');
        text.append(make('strong', '', `${entry.name} · ${entry.device}`));
        text.append(make('small', 'muted', t(entry.binaryAvailable ? 'installed_lowercase' : 'not_installed')));
        label.append(input, text);
        root.append(label);
    }
    if (resultsPage) renderModeComparison();
}

fetch('/api/agent/local/miner-catalog', {cache: 'no-store'}).then(async response => {
    if (!response.ok) throw new Error('catalog_unavailable');
    renderCoinOptions(await response.json());
}).catch(() => {
    if ($('coin-selection')) $('coin-selection').hidden = true;
});

$('run-live').addEventListener('click', () => start('LIVE'));
$('run-installed').addEventListener('click', () => start('INSTALLED'));
$('retry-upload').addEventListener('click', async () => {
    const button = $('retry-upload');
    button.disabled = true;
    try {
        const response = await fetch('/api/agent/local/benchmarks/sharing/retry-manual', {method: 'POST'});
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        lastUploadStatus = '';
        window.SolarMinerLive.refresh('benchmark-upload');
    } catch (e) {
        notice('could_not_retry_the_upload', true);
    } finally {
        button.disabled = false;
    }
});
// Post-benchmark prompt: upload the retained samples once without changing the periodic
// sharing consent. The agent endpoint only accepts the batch it kept from the finished run.
$('share-prompt').addEventListener('submit', async (event) => {
    if (event.submitter?.value !== 'default') return;
    event.preventDefault();
    const button = $('share-prompt-upload');
    button.disabled = true;
    try {
        const response = await fetch('/api/agent/local/benchmarks/sharing/upload-manual', {method: 'POST'});
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const status = await response.json();
        if (status.status === 'SENT') notice(t('the_results_were_uploaded_once_anonymously_t'));
        else if (status.status === 'FAILED') notice(t('the_anonymous_upload_failed_you_can_retry_it'), true);
        // REST audit R8: the upload now runs in the background; the prompt reports the accepted
        // command and the benchmark-upload strip shows SENT/FAILED as soon as it lands.
        else if (status.status === 'UPLOADING') notice(t('uploading_benchmark_data'));
        $('share-prompt').close();
        lastUploadStatus = '';
        window.SolarMinerLive.refresh('benchmark-upload');
    } catch (e) {
        notice('the_anonymous_upload_could_not_be_started', true);
        button.disabled = false;
    }
});
$('cancel').addEventListener('click', async () => {
    if (actionBusy) return; actionBusy = true; $('cancel').disabled = true;
    try { const response = await fetch('/api/agent/local/benchmarks/cancel', {method:'POST'}); if (!response.ok) throw new Error('could_not_cancel_the_measurement'); notice('cancellation_requested_the_previous_mining_s'); }
    catch (error) { notice(s(error.message), true); }
    finally {actionBusy=false;$('cancel').disabled=false;window.SolarMinerLive.refresh('benchmarks');}
});
$('refresh').addEventListener('click', () => {
    invalidateMeasurements();
    window.SolarMinerLive.refresh('benchmarks');
    window.SolarMinerLive.refresh('efficiency');
    window.SolarMinerLive.refresh('benchmark-upload');
    renderSavedBenchmarks();
    renderProfiles();
    loadComparison();
});
function sharingSummary() { $('sharing-summary').textContent = t($('sharing').checked ? 'enabled_cfd095' : 'disabled_139955'); }
$('sharing').addEventListener('change', async () => {
    const input = $('sharing'); input.disabled = true;
    try {
        const response = await fetch('/api/agent/local/benchmarks/sharing', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({enabled:input.checked})});
        if (!response.ok) throw new Error('could_not_save_permission');
        $('sharing-note').textContent = t(input.checked ? 'permission_saved_active_miners_will_be_repor' : 'sharing_disabled_withdrawal_will_be_sent_at');
    } catch (error) {input.checked=!input.checked;notice(s(error.message),true);}
    finally {input.disabled=false;sharingSummary();}
});
$('sharing').disabled = true;
fetch('/api/agent/local/benchmarks/sharing').then(async response => {
    if (!response.ok) throw new Error('sharing_unavailable');
    const value = await response.json(); if (typeof value !== 'boolean') throw new Error('invalid_consent');
    $('sharing').checked = value; $('sharing').disabled = false; sharingKnown = true; sharingSummary();
}).catch(() => {
    $('sharing-summary').textContent = t('unavailable_2a5d2c');
    $('sharing-note').textContent = t('could_not_read_sharing_permissions_refresh_t');
});

function renderOptimization(optimization) {
    const root = $('optimization-runs');
    if (!root) return;
    root.hidden = !optimization?.enabled;
    root.replaceChildren();
    if (root.hidden) return;
    root.append(make('h3', '', t('bm_optimization_progress')));
    for (const row of optimization.runs || []) {
        const card = make('article', 'worker-card');
        card.append(make('strong', '', `${row.deviceId} · ${row.coin.toUpperCase()} · ${row.softwareId}`),
            make('p', '', `${s(row.state)} · ${s(row.detail)}`));
        if (row.state === 'RUNNING' && row.candidateCount > 0) {
            card.append(make('small', 'muted', t('bm_optimization_candidate', {
                current: row.candidateIndex, total: row.candidateCount,
                samples: row.sampleIndex, required: row.sampleCount
            })));
        }
        root.append(card);
    }
}
$('auto-tune-after-sweep')?.addEventListener('change', () => {
    if (latestSweepState) applySweepState(latestSweepState);
});

function applySweepState(state) {
        latestSweepState = state;
        if (resultsPage) updateComparisonDevices(measurementSnapshot || {benchmarks: [], sweeps: []});
        $('overview-sweep').textContent = state.running ? t('running') : t('ready');
        if (previousSweepRunning === true && !state.running) { invalidateMeasurements(); renderProfiles(); }
        previousSweepRunning = state.running;
        $('sweep-state').textContent = state.running ? t('running') : (!state.phase || state.phase === 'Idle' ? t('ready') : s(state.phase));
        $('sweep-cancel').hidden = !state.running;
        $('sweep-progress-wrap').hidden = !state.running;
        const resumable = !state.running && (state.runs || []).length > 0
            && ((state.runs || []).some(run => run.status !== 'COMPLETE')
                || state.optimization?.enabled && state.optimization.runs?.some(run => run.state !== 'COMPLETE'));
        $('resume-sweep').hidden = !resumable;
        $('resume-sweep').disabled = state.running || actionBusy;
        $('run-sweep').textContent = t(resumable ? 'restart_from_scratch' : resultsPage ? 'start_power_limit_test' : $('auto-tune-after-sweep').checked ? 'bm_optimization_start' : 'bm_sweep_primary');
        $('run-sweep').disabled = state.running || actionBusy;
        if (state.running) {
            $('sweep-phase').textContent = t('done_total_devices_complete_phase',
                {done: state.phaseIndex, total: state.phaseCount, phase: s(state.phase)});
            $('sweep-progress').max = Math.max(1, state.phaseCount);
            $('sweep-progress').value = state.phaseIndex;
            $('sweep-remaining').textContent = state.secondsRemaining == null
                ? t('calculating_eta') : t('approximate_time_remaining_time', {time: formatDuration(state.secondsRemaining)});
        }
        renderOptimization(state.optimization);
        renderSweepRuns(forSelectedGpu(state.runs), state.running);
        if (resultsPage) renderSweepView();
        else {
            $('sweep-live-chart').hidden = !state.running && !(state.runs || []).some(run => run.steps?.length);
            if (!$('sweep-live-chart').hidden) renderLiveSweepChart(state.runs || [], state.running);
        }
    updateMeasurementControls();
}

let selectedSweepRunId = null;
let lastLiveSweepSignature = null;
function renderSweepView() {
    const liveButton = $('sweep-view-live');
    const savedButton = $('sweep-view-saved');
    if (!liveButton || !savedButton) return;
    for (const [button, mode] of [[liveButton, 'live'], [savedButton, 'saved']]) {
        const active = sweepViewMode === mode;
        button.classList.toggle('selected', active);
        button.setAttribute('aria-pressed', String(active));
    }
    if (sweepViewMode === 'live') {
        renderLiveSweepChart(forSelectedGpu(latestSweepState?.runs || []), Boolean(latestSweepState?.running));
        return;
    }
    const runs = forSelectedGpu(measurementSnapshot?.sweeps || []).map(profile => ({
        ...profile,
        id: `saved|${profile.deviceId}|${profile.algorithm}|${profile.minerSoftwareId || ''}|${profile.coin}`,
        plannedLimits: (profile.steps || []).map(step => step.limitWatts)
    }));
    renderLiveSweepChart(runs, false, true);
}
function renderLiveSweepChart(runs, running, saved = false) {
    const root = $('sweep-live-chart');
    if (!root) return;
    const signature = JSON.stringify({running, saved, selectedSweepRunId, runs: runs.map(run =>
        ({id: run.id, status: run.status, limits: run.plannedLimits, steps: run.steps}))});
    if (signature === lastLiveSweepSignature) return;
    lastLiveSweepSignature = signature;
    const available = runs.map(run => ({run, points: (run.steps || [])
        .filter(step => step.stable && step.avgPowerWatts > 0 && step.medianHashrateHs > 0)
        .map(step => ({x: step.limitWatts, y: step.medianHashrateHs / step.avgPowerWatts, step}))
        .sort((a, b) => a.x - b.x)})).filter(line => line.points.length);
    root.replaceChildren();
    if (!available.length) {
        root.append(make('div', 'sweep-chart-empty', saved
            ? t('benchmark_sweep_no_saved_curve')
            : t(running ? 'benchmark_sweep_waiting_points' : 'benchmark_sweep_no_live_curve')));
        return;
    }
    const groupKey = line => saved ? `${line.run.coin}|${line.run.algorithm}` : line.run.id;
    const groups = [...new Map(available.map(line => [groupKey(line), {
        key: groupKey(line), run: line.run, lines: available.filter(candidate => groupKey(candidate) === groupKey(line))
    }])).values()];
    const selectedGroup = groups.find(group => group.key === selectedSweepRunId)
        || groups.find(group => group.lines.some(line => line.run.status === 'RUNNING')) || groups[0];
    selectedSweepRunId = selectedGroup.key;
    const lines = saved
        ? [...new Map(selectedGroup.lines.map(line => [line.run.minerSoftwareId || line.run.softwareId || 'legacy', line]))
            .values()].map(representative => {
                const softwareId = representative.run.minerSoftwareId || representative.run.softwareId || 'legacy';
                const groupedPoints = selectedGroup.lines
                    .filter(line => (line.run.minerSoftwareId || line.run.softwareId || 'legacy') === softwareId)
                    .flatMap(line => line.points)
                    .sort((a, b) => a.x - b.x);
                const points = [...new Map(groupedPoints.map(point => [point.x, point])).values()];
                return {...representative, points};
            })
        : selectedGroup.lines;
    if (groups.length > 1) {
        const tabs = make('div', 'sweep-chart-tabs');
        for (const group of groups) {
            const button = make('button', `sweep-algorithm-tab${group.key === selectedSweepRunId ? ' selected' : ''}`,
                saved ? `${group.run.coin.toUpperCase()} · ${algorithmGroupLabel(group.run)}`
                    : `${algorithmGroupLabel(group.run)} · ${group.run.model}`);
            button.type = 'button'; button.setAttribute('aria-pressed', String(group.key === selectedSweepRunId));
            button.addEventListener('click', () => { selectedSweepRunId = group.key; renderLiveSweepChart(runs, running, saved); });
            tabs.append(button);
        }
        root.append(tabs);
    }
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 760 330');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', t('benchmark_sweep_curve_aria'));
    const add = (tag, attrs, label) => {
        const node = document.createElementNS(ns, tag);
        for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
        if (label != null) node.textContent = label;
        svg.append(node); return node;
    };
    const all = lines.flatMap(line => line.points);
    const plannedLimits = lines.flatMap(line => line.run.plannedLimits || []);
    const xMin = Math.min(...all.map(point => point.x), ...plannedLimits);
    const xMax = Math.max(...all.map(point => point.x), ...plannedLimits);
    const yMax = Math.max(...all.map(point => point.y)) * 1.18;
    const left = 78, right = 730, top = 22, bottom = 268;
    const x = value => left + (value - xMin) / Math.max(1, xMax - xMin) * (right - left);
    const y = value => bottom - value / Math.max(1, yMax) * (bottom - top);
    for (let index = 0; index <= 4; index++) {
        const lineY = top + index * (bottom - top) / 4;
        add('line', {x1:left, x2:right, y1:lineY, y2:lineY, class:'sweep-grid-line'});
        add('text', {x:left - 12, y:lineY + 4, class:'sweep-axis-label', 'text-anchor':'end'}, fmtRate(yMax * (4-index)/4));
    }
    add('text', {x:(left+right)/2, y:310, class:'sweep-axis-label', 'text-anchor':'middle'}, t('power_target_watts'));
    add('text', {x:18, y:148, class:'sweep-axis-label', 'text-anchor':'middle', transform:`rotate(${-90} 18 148)`}, t('benchmark_hashes_per_watt'));
    const limits = [...new Set(plannedLimits)].sort((a,b) => a-b);
    for (const [index, limit] of limits.entries()) {
        add('line', {x1:x(limit), x2:x(limit), y1:top, y2:bottom, class:'sweep-grid-vertical'});
        if (index % Math.max(1, Math.ceil(limits.length / 8)) === 0 || index === limits.length - 1)
            add('text', {x:x(limit), y:bottom + 21, class:'sweep-axis-label', 'text-anchor':'middle'}, `${limit}`);
    }
    const palette = ['#8fe9b5', '#79bdff', '#f6c375', '#bc9bff', '#f3939b'];
    for (const [index, line] of lines.entries()) {
        const color = palette[index % palette.length];
        if (line.points.length > 1) add('polyline', {
            points:line.points.map(point => `${x(point.x)},${y(point.y)}`).join(' '),
            fill:'none', stroke:color, 'stroke-width':'3.5', 'stroke-linecap':'round', 'stroke-linejoin':'round',
            class:'sweep-curve-line'
        });
        for (const point of line.points) {
            const circle = add('circle', {cx:x(point.x), cy:y(point.y), r:6, fill:color, class:'sweep-curve-point'});
            const title = document.createElementNS(ns, 'title');
            const step = point.step;
            title.textContent = `${line.run.minerSoftwareId || line.run.softwareId || t('miner_software')} · ${algorithmGroupLabel(line.run)} · ${line.run.model} · ${line.run.coin} · ${point.x} W · ${fmtRate(step.medianHashrateHs)} · ${Math.round(step.avgPowerWatts)} W · ${fmtRate(point.y)} / W`;
            circle.append(title);
        }
    }
    root.append(svg);
    const legend = make('div', 'sweep-chart-legend');
    for (const [index, line] of lines.entries()) {
        const software = line.run.minerSoftwareId || line.run.softwareId || t('miner_software');
        const item = make('span', '', `${software} · ${line.run.model}`);
        item.style.setProperty('--series-color', palette[index % palette.length]);
        legend.append(item);
    }
    root.append(legend);
}

let lastProfileSignature = null;
let lastSweepRunSignature = null;
// The run list re-renders on every poll; remember which disclosures the operator opened
// so a re-render never collapses their view mid-run.
const openSweepGroups = new Map();
const openSweepRows = new Set();
const openSweepStepDetails = new Set();

function formatDuration(seconds) {
    const value = Math.max(0, Math.round(Number(seconds) || 0));
    const hours = Math.floor(value / 3600);
    const minutes = Math.floor((value % 3600) / 60);
    const rest = value % 60;
    if (hours) return `${hours} h ${minutes} min`;
    if (minutes) return `${minutes} min ${rest} s`;
    return `${rest} s`;
}

function renderSweepRuns(runs, sweepRunning) {
    const signature = JSON.stringify({runs, sweepRunning});
    if (signature === lastSweepRunSignature) return;
    lastSweepRunSignature = signature;
    const root = $('sweep-runs');
    root.hidden = !runs.length;
    root.replaceChildren();
    if (!runs.length) return;
    const statusLabel = {QUEUED: 'planned', RUNNING: 'running', COMPLETE: 'complete', FAILED: 'failed', SKIPPED: 'skipped', CANCELLED: 'cancelled'};
    const isTerminal = status => status === 'COMPLETE' || status === 'FAILED' || status === 'SKIPPED' || status === 'CANCELLED';
    // Only live runs get a full card. Queued and finished runs stay in compact groups;
    // finished step histories expand while the sweep is active so measured limits remain visible.
    for (const run of runs.filter(r => r.status === 'RUNNING')) root.append(liveRunCard(run, statusLabel));
    const waiting = runs.filter(r => r.status === 'QUEUED');
    const finished = runs.filter(r => isTerminal(r.status));
    // Keep queued work visible while active device curves are finishing.
    const hasRunningRun = runs.some(r => r.status === 'RUNNING');
    if (waiting.length) root.append(runGroup(t('waiting_runs_count', {count: waiting.length}), waiting, statusLabel, false, !hasRunningRun));
    if (finished.length) root.append(runGroup(t('completed_runs_count', {count: finished.length}), finished, statusLabel,
        true, sweepRunning, sweepRunning));
}

function runIdentity(run) {
    const wave = run.mode === 'FULL' && Number.isInteger(run.referenceBatch)
        ? ` · ${t('start_group_number', {number: run.referenceBatch + 1})}` : '';
    return `${run.coin} · ${t(run.mode === 'FULL' ? 'full_device_curve' : 'parallel_device_validation')}${wave}`;
}

function stepList(limits, steps) {
    const completed = new Map((steps || []).map(step => [step.limitWatts, step]));
    const list = make('ol', 'sweep-step-list');
    for (const limit of limits) {
        const step = completed.get(limit);
        const item = make('li', `sweep-step${step ? step.stable ? ' stable' : ' unstable' : ' planned'}`);
        item.append(make('strong', '', `${limit} W`), make('span', 'sweep-step-status', t(step ? step.stable ? 'stable' : 'unstable' : 'planned')));
        if (step?.medianHashrateHs > 0)
            item.append(make('span', '', fmtRate(step.medianHashrateHs)));
        if (step?.avgPowerWatts > 0)
            item.append(make('span', '', t('measured_power') + ': ' + Math.round(step.avgPowerWatts) + ' W'));
        if (step?.note) item.append(make('small', '', s(step.note)));
        list.append(item);
    }
    return list;
}

function runPlanDisclosure(run) {
    const limits = run.plannedLimits || [];
    if (!limits.length) return null;
    const details = make('details', 'sweep-step-details');
    details.open = openSweepStepDetails.has(run.id);
    details.addEventListener('toggle', () => {
        if (details.open) openSweepStepDetails.add(run.id);
        else openSweepStepDetails.delete(run.id);
    });
    details.append(make('summary', '', t('power_steps_count', {count: limits.length})), stepList(limits, run.steps));
    return details;
}

function liveRunCard(run, statusLabel) {
    const card = make('article', 'worker-card sweep-live-card');
    const info = make('div', '');
    info.append(make('strong', '', `${run.model} · ${run.algorithm}`), make('p', 'muted', runIdentity(run)));
    if (run.detail) info.append(make('p', '', s(run.detail)));
    info.append(make('p', 'muted', t('watts_w_samples_required_samples',
        {watts: run.limitWatts ?? '—', samples: run.samples, required: run.samplesRequired})));
    const sampleProgress = document.createElement('progress');
    sampleProgress.className = 'sweep-sample-progress';
    sampleProgress.max = Math.max(1, Number(run.samplesRequired) || 1);
    sampleProgress.value = Math.min(sampleProgress.max, Math.max(0, Number(run.samples) || 0));
    sampleProgress.setAttribute('aria-label', t('power_limit_sample_progress'));
    info.append(sampleProgress);
    info.append(make('p', 'muted sweep-sample-stage', Number(run.samples) > 0
        ? t('collecting_power_limit_samples', {samples: run.samples, required: run.samplesRequired})
        : t('waiting_for_first_power_limit_sample')));
    const steps = runPlanDisclosure(run);
    if (steps) info.append(steps);
    card.append(info, make('span', 'tag ready', t(statusLabel[run.status] || run.status)));
    return card;
}

function runGroup(label, runs, statusLabel, defaultOpen, forceOpen = false, expandFinishedHistory = false) {
    const group = make('details', 'sweep-run-group');
    const key = label.replace(/\(\d+\)/, '');
    group.open = forceOpen || (openSweepGroups.has(key) ? openSweepGroups.get(key) : defaultOpen);
    group.addEventListener('toggle', () => openSweepGroups.set(key, group.open));
    group.append(make('summary', '', label));
    const rows = make('div', 'sweep-run-rows');
    for (const run of runs) rows.append(runRow(run, statusLabel, expandFinishedHistory));
    group.append(rows);
    return group;
}

function runRow(run, statusLabel, expandFinishedHistory) {
    const row = make('details', 'sweep-run-row');
    const terminal = run.status === 'COMPLETE' || run.status === 'FAILED'
        || run.status === 'SKIPPED' || run.status === 'CANCELLED';
    row.open = (terminal && (run.steps || []).length > 0 && expandFinishedHistory) || openSweepRows.has(run.id);
    row.addEventListener('toggle', () => {
        if (row.open) openSweepRows.add(run.id); else openSweepRows.delete(run.id);
    });
    const summary = make('summary', '');
    summary.append(make('strong', '', `${run.model} · ${run.coin} · ${run.algorithm}`));
    const best = run.status === 'COMPLETE' && run.limitWatts != null
        ? make('span', 'sweep-run-best', `${run.limitWatts} W`) : null;
    if (best) summary.append(best);
    summary.append(make('span', `tag${run.status === 'COMPLETE' ? ' ready' : run.status === 'FAILED' ? ' blocked' : ''}`,
        t(statusLabel[run.status] || run.status)));
    row.append(summary);
    const body = make('div', 'sweep-run-body');
    body.append(make('p', '', `${runIdentity(run)} · ${run.deviceId}`));
    if (run.detail) body.append(make('p', '', s(run.detail)));
    if ((run.plannedLimits || []).length) body.append(stepList(run.plannedLimits, run.steps));
    row.append(body);
    return row;
}

function renderProfiles() {
    if (!resultsPage) return Promise.resolve();
    return loadProfiles();
}

async function loadProfiles() {
    const revision = measurementRevision;
    try {
        const snapshot = await readMeasurements();
        if (revision !== measurementRevision) return;
        if (resultsPage) updateComparisonDevices(snapshot);
        const profiles = forSelectedGpu(snapshot.sweeps);
        $('overview-efficiency').textContent = new Intl.NumberFormat(window.SolarMinerI18n.locale).format(profiles.length);
        renderSweepView();
        const signature = JSON.stringify(profiles);
        if (signature === lastProfileSignature) return;
        lastProfileSignature = signature;
        const root = $('sweep-results');
        root.replaceChildren();
        if (!profiles.length) {
            root.append(make('p', 'empty', t('no_efficiency_profile_yet_start_a_test_to_fi')));
            appendStorageDiagnostic(root, snapshot.sweepStorage);
            return;
        }
        const groups = mountAlgorithmGroups(root, profiles, 'sweep');
        for (const profile of profiles) {
            const card = make('article', 'worker-card');
            const info = make('div', '');
            info.append(make('strong', '', `${profile.model} · ${profile.algorithm}${profile.minerSoftwareId ? ` · ${profile.minerSoftwareId}` : ''}`),
                make('p', 'muted', t('coin_tested_date', {coin: profile.coin, date: new Intl.DateTimeFormat(window.SolarMinerI18n.locale, {dateStyle: 'medium', timeStyle: 'short'}).format(new Date(profile.testedAt))})));
            if (profile.bestStableWatts != null) {
                info.append(make('p', '', t('best_stable_value_watts_w_hashrate_power_w_e', {watts: profile.bestStableWatts, hashrate: fmtRate(profile.bestHashrateHs), power: Math.round(profile.bestPowerWatts), efficiency: window.SolarMinerUI.efficiency(profile.bestHashrateHs, profile.bestPowerWatts)})));
            } else info.append(make('p', '', t('no_stable_power_limit_found')));
            const deleteButton = make('button', 'button subtle', t('delete_saved_efficiency_profile'));
            deleteButton.type = 'button';
            deleteButton.addEventListener('click', () => deleteSavedProfile(profile, deleteButton));
            info.append(deleteButton);
            const chart = efficiencyChart(profile);
            if (chart) info.append(chart);
            const limits = (profile.steps || []).map(step => step.limitWatts);
            if (limits.length) {
                const details = make('details', 'sweep-step-details');
                details.append(make('summary', '', t('power_steps_count', {count: limits.length})),
                    stepList(limits, profile.steps));
                info.append(details);
            }
            card.append(info);
            card.dataset.filter = [profile.model, profile.deviceId, profile.coin, profile.algorithm, profile.minerSoftwareId].join(' ').toLocaleLowerCase();
            groups.get(algorithmGroupLabel(profile)).append(card);
        }
        applyProfileFilter();
    } catch (e) {
        if (revision !== measurementRevision) return;
        lastProfileSignature = null;
        $('sweep-results').replaceChildren(make('p', 'empty', t('could_not_load_efficiency_profiles', {error: s(e.message)})));
    }
}

async function deleteSavedProfile(profile, button) {
    if (!window.confirm(t('confirm_delete_saved_efficiency_profile', {model: profile.model, algorithm: profile.algorithm}))) return;
    button.disabled = true;
    try {
        const response = await fetch('/api/agent/local/efficiency/profiles', {
            method: 'DELETE', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(profile)
        });
        if (!response.ok) throw new Error('could_not_delete_saved_efficiency_profile');
        invalidateMeasurements();
        refreshGpuResults();
        notice('saved_efficiency_profile_deleted');
    } catch (error) {
        button.disabled = false;
        notice(error.message || 'could_not_delete_saved_efficiency_profile', true);
    }
}

function efficiencyChart(profile) {
    const points = (profile.steps || []).filter(step => Number(step.avgPowerWatts) > 0 && Number(step.medianHashrateHs) > 0)
        .map(step => ({power: Number(step.avgPowerWatts), rate: Number(step.medianHashrateHs), stable: Boolean(step.stable)}));
    if (!points.length) return null;
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 640 300');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', t('efficiency_curve_for_hardware', {hardware: profile.model, algorithm: profile.algorithm}));
    svg.classList.add('efficiency-curve');
    const add = (tag, attrs, text) => {
        const node = document.createElementNS(ns, tag);
        for (const [key, value] of Object.entries(attrs || {})) node.setAttribute(key, String(value));
        if (text != null) node.textContent = text;
        svg.append(node); return node;
    };
    const left = 76, right = 612, top = 24, bottom = 238;
    const xs = points.map(p => p.power), ys = points.map(p => p.rate);
    const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
    const xPad = (maxX - minX) * 0.08 || Math.max(1, maxX * 0.08);
    const yPad = (maxY - minY) * 0.12 || Math.max(1, maxY * 0.08);
    const x0 = minX - xPad, x1 = maxX + xPad, y0 = Math.max(0, minY - yPad), y1 = maxY + yPad;
    const x = v => left + (v - x0) / (x1 - x0) * (right - left);
    const y = v => bottom - (v - y0) / (y1 - y0) * (bottom - top);
    for (let i = 0; i <= 4; i++) {
        const gy = top + i * (bottom - top) / 4;
        add('line', {x1:left, y1:gy, x2:right, y2:gy, stroke:'#ffffff20'});
        add('text', {x:left - 9, y:gy + 4, fill:'currentColor', 'font-size':11, 'text-anchor':'end'}, fmtRate(y1 - i * (y1-y0)/4));
        const gx = left + i * (right - left) / 4;
        add('line', {x1:gx, y1:top, x2:gx, y2:bottom, stroke:'#ffffff12'});
        add('text', {x:gx, y:bottom + 19, fill:'currentColor', 'font-size':11, 'text-anchor':'middle'}, `${Math.round(x0 + i * (x1-x0)/4)} W`);
    }
    add('line', {x1:left, y1:bottom, x2:right, y2:bottom, stroke:'#9aadc3'});
    add('line', {x1:left, y1:top, x2:left, y2:bottom, stroke:'#9aadc3'});
    add('text', {x:(left+right)/2, y:286, fill:'currentColor', 'font-size':12, 'text-anchor':'middle'}, t('power_consumption_watts'));
    const yLabel = add('text', {x:16, y:(top+bottom)/2, fill:'currentColor', 'font-size':12, 'text-anchor':'middle', transform:`rotate(-90 16 ${(top+bottom)/2})`}, t('hashrate'));
    const ordered = [...points].sort((a,b) => a.power-b.power);
    add('polyline', {points:ordered.map(p => `${x(p.power)},${y(p.rate)}`).join(' '), fill:'none', stroke:'#83bfff', 'stroke-width':3, 'stroke-linejoin':'round'});
    for (const point of points) {
        const circle = add('circle', {cx:x(point.power), cy:y(point.rate), r:point.stable ? 5 : 4, fill:point.stable ? '#8ee4b4' : '#efaa8b', stroke:'#111b2b', 'stroke-width':2});
        const title = document.createElementNS(ns, 'title');
        title.textContent = `${Math.round(point.power)} W · ${fmtRate(point.rate)} · ${t(point.stable ? 'stable' : 'unstable')}`;
        circle.append(title);
    }
    if (profile.bestStableWatts != null && profile.bestHashrateHs > 0) {
        add('circle', {cx:x(profile.bestPowerWatts || profile.bestStableWatts), cy:y(profile.bestHashrateHs), r:9, fill:'none', stroke:'#f6c26e', 'stroke-width':3});
        add('text', {x:right, y:top + 13, fill:'#f6c26e', 'font-size':11, 'text-anchor':'end'}, t('sweet_spot'));
    }
    return svg;
}

async function startSweep(mode = 'restart') {
    if (resultsPage && catalogCoins.length && !document.querySelector('#coin-options input:checked')) {
        notice('benchmark_select_coin', true); return;
    }
    if (!requireWorkerSelection(true)) return;
    const gpuLimit = selectedGpuLimit();
    if (gpuLimit == null) return;
    if (actionBusy) return; actionBusy = true;
    $('run-sweep').disabled = true;
    $('resume-sweep').disabled = true;
    updateMeasurementControls();
    try {
        const scope = await prepareMeasurementScope(true);
        const response = await fetch(`/api/agent/local/efficiency?mode=${encodeURIComponent(mode)}`,
            {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({coins: scope.coins, workers: scope.workers, maxConcurrentGpus: gpuLimit,
                autoTuneAfterSweep: mode === 'resume' && latestSweepState?.optimization != null
                    ? latestSweepState.optimization.enabled : Boolean($('auto-tune-after-sweep')?.checked)})});
        if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.message || body?.detail || 'could_not_start_the_efficiency_sweep'); }
        applySweepState(await response.json());
        notice(mode === 'resume'
            ? 'efficiency_sweep_resumed_with_completed_resu'
            : 'efficiency_sweep_restarted_power_limits_are');
    } catch (error) { notice(s(error.message), true); }
    finally { actionBusy = false; updateMeasurementControls(); window.SolarMinerLive.refresh('efficiency'); }
}

$('run-sweep').addEventListener('click', () => startSweep('restart'));
$('resume-sweep').addEventListener('click', () => startSweep('resume'));
$('sweep-cancel').addEventListener('click', async () => {
    if (actionBusy) return; actionBusy = true; $('sweep-cancel').disabled = true;
    try { const response = await fetch('/api/agent/local/efficiency/cancel', {method: 'POST'}); if (!response.ok) throw new Error('could_not_cancel_the_sweep'); notice('cancellation_requested_power_limits_and_mine'); }
    catch (error) { notice(s(error.message), true); }
    finally { actionBusy = false; $('sweep-cancel').disabled = false; window.SolarMinerLive.refresh('efficiency'); }
});


// The network comparison uses the same measured candidates as the operating modes,
// but never changes an assignment or a power target.
let comparisonDevices = '';
let comparisonRequest = 0;
let comparisonRenderedDevice = '';
const comparisonCache = new Map();
const comparisonRequests = new Map();
const COMPARISON_CACHE_MS = 30_000;
let comparisonCacheRevision = 0;
let modeComparisonCandidates = [];
let modeComparisonTariff = null;
let modeComparisonPower = null;
function tunedSettings(point) {
    const profile = point?.tunedProfile;
    if (!profile) return '';
    return t('bm_tuned_settings', {version: profile.softwareVersion,
        core: profile.coreMhz == null ? '—' : `${profile.coreMhz} MHz`,
        memory: profile.memoryOffsetMhz != null ? `${profile.memoryOffsetMhz >= 0 ? '+' : ''}${profile.memoryOffsetMhz} MHz (P${profile.memoryOffsetPstate ?? '—'})`
            : profile.memoryMhz == null ? '—' : `${profile.memoryMhz} MHz`});
}
function renderModeComparison(candidates = modeComparisonCandidates, tariff = modeComparisonTariff,
                              power = modeComparisonPower) {
    if (!resultsPage) return;
    modeComparisonCandidates = candidates;
    modeComparisonTariff = tariff;
    modeComparisonPower = power;
    const coinSelect = $('mode-comparison-coin');
    const minerSelect = $('mode-comparison-miner');
    const comparison = $('mode-comparison-cards');
    const context = $('mode-comparison-context');
    const same = $('mode-comparison-same');
    same.hidden = true;
    const usable = candidates.filter(row => row.coin && row.minerSoftwareId && Number(row.hashrateHps) > 0
        && Number(row.measuredWatts) > 0 && Number(row.usdPerKwh) > 0);
    const gpuCatalog = comparisonCatalog.filter(entry => entry.device !== 'CPU');
    const coins = [...new Set(usable.map(row => row.coin))]
        .sort((a, b) => a.localeCompare(b, window.SolarMinerI18n.locale));
    const worker = availableWorkerById.get(selectedGpu());
    const previousCoin = coinSelect.value;
    coinSelect.replaceChildren();
    for (const coin of coins) {
        const entry = gpuCatalog.find(item => item.id === coin)
            || window.SolarMinerUI.coinCatalog.find(item => item.id === coin);
        coinSelect.add(new Option(entry?.algorithm ? `${entry.name} · ${entry.algorithm}` : entry?.name || coin, coin));
    }
    coinSelect.value = coins.includes(previousCoin) ? previousCoin : coins.includes(worker?.coin) ? worker.coin : coins[0] || '';
    coinSelect.disabled = !coins.length;
    const catalogEntry = gpuCatalog.find(entry => entry.id === coinSelect.value);
    const miners = [...new Set(usable.filter(row => row.coin === coinSelect.value)
        .map(row => row.minerSoftwareId))].sort();
    const previousMiner = minerSelect.value;
    minerSelect.replaceChildren();
    for (const miner of miners) {
        const option = catalogEntry?.miners?.find(item => item.id === miner);
        minerSelect.add(new Option(option?.name || miner, miner));
    }
    minerSelect.value = miners.includes(previousMiner) ? previousMiner
        : miners.includes(worker?.minerSoftwareId) ? worker.minerSoftwareId
            : miners.includes(catalogEntry?.selectedMinerId) ? catalogEntry.selectedMinerId : miners[0] || '';
    minerSelect.disabled = !miners.length;
    comparison.replaceChildren();
    if (!selectedGpu()) {
        context.textContent = '';
        comparison.append(make('p', 'empty', t('worker_mode_comparison_no_gpu')));
        return;
    }
    if (!coins.length) {
        context.textContent = '';
        comparison.append(make('p', 'empty', t('worker_mode_comparison_no_points')));
        return;
    }
    const rate = tariff?.settings;
    const manualStatus = power?.gpus?.find(gpu => gpu.deviceId === selectedGpu());
    const manualTarget = Number(manualStatus?.userMaxPowerLimitWatts);
    const manualTargetKnown = Number.isInteger(manualTarget) && manualTarget > 0;
    context.textContent = (rate?.pricePerKwh != null
        ? t('worker_mode_comparison_rate', {rate: new Intl.NumberFormat(window.SolarMinerI18n.locale,
            {maximumFractionDigits: 4}).format(Number(rate.pricePerKwh)), currency: rate.currency})
        : t('worker_mode_comparison_rate_unavailable')) + ' · ' + (manualTargetKnown
        ? t('worker_mode_comparison_manual_target', {watts: manualTarget})
        : t('worker_mode_comparison_manual_unavailable'));
    const points = usable.filter(row => row.coin === coinSelect.value && row.minerSoftwareId === minerSelect.value);
    const exactManual = manualTargetKnown ? points.find(row => !row.tunedProfile && Number(row.limitWatts) === manualTarget) : null;
    const sweepProxy = manualTargetKnown && !exactManual ? points.filter(row => row.source === 'SWEEP'
        && Number(row.limitWatts) < manualTarget)
        .reduce((best, row) => !best || Number(row.limitWatts) > Number(best.limitWatts) ? row : best, null) : null;
    const manual = exactManual || sweepProxy;
    const efficiency = points.reduce((best, row) => !best || row.hashrateHps / row.measuredWatts
        > best.hashrateHps / best.measuredWatts ? row : best, null);
    const profit = points.filter(row => Number.isFinite(row.profitUsdPerDay))
        .reduce((best, row) => !best || row.profitUsdPerDay > best.profitUsdPerDay ? row : best, null);
    const ui = window.SolarMinerUI;
    const prefs = window.SolarMinerPreferences;
    const number = (value, digits = 2) => new Intl.NumberFormat(window.SolarMinerI18n.locale,
        {maximumFractionDigits: digits}).format(value);
    const modes = [
        {title: 'worker_mode_manual', point: manual, missing: manualTargetKnown
            ? t('worker_mode_comparison_manual_unmeasured', {watts: manualTarget})
            : t('worker_mode_comparison_manual_unavailable'), note: sweepProxy
            ? t('worker_mode_comparison_manual_sweep_proxy', {target: manualTarget, measured: sweepProxy.limitWatts}) : null},
        {title: 'worker_mode_efficiency', point: efficiency, missing: t('worker_mode_comparison_no_points')},
        {title: 'worker_mode_profit_day', point: profit, missing: t(points.length
            ? 'worker_mode_comparison_no_profit' : 'worker_mode_comparison_no_points')}
    ];
    const table = make('table', 'worker-mode-table');
    table.setAttribute('aria-label', t('worker_mode_comparison_title'));
    const head = make('thead');
    const heading = make('tr');
    const metricHeading = make('th', '', t('worker_mode_comparison_metric'));
    metricHeading.scope = 'col';
    heading.append(metricHeading);
    for (const mode of modes) {
        const cell = make('th', '', t(mode.title));
        cell.scope = 'col';
        heading.append(cell);
    }
    head.append(heading);
    const body = make('tbody');
    const cost = point => rate?.pricePerKwh != null && Number.isFinite(Number(rate.pricePerKwh))
        ? `≈ ${new Intl.NumberFormat(window.SolarMinerI18n.locale, {style: 'currency', currency: rate.currency,
            maximumFractionDigits: 3}).format(point.measuredWatts * 24 / 1000 * Number(rate.pricePerKwh))}` : '—';
    const rows = [
        ['hashrate', point => fmtRate(point.hashrateHps)],
        ['worker_mode_comparison_hashes_watt', point => number(point.hashrateHps / point.measuredWatts, 1)],
        ['worker_mode_comparison_limit', point => `${point.limitWatts} W`],
        ['worker_mode_comparison_measured_power', point => ui.power(point.measuredWatts)],
        ['worker_mode_comparison_energy_day', point => `${number(point.measuredWatts * 24 / 1000, 3)} kWh`],
        ['worker_mode_comparison_cost_day', cost],
        ['worker_mode_comparison_gross_kwh', point => ui.moneyPerKwh(point.usdPerKwh)],
        ['worker_mode_comparison_gross_day', point => `≈ ${prefs.moneyFromUsd(point.usdPerDay)}`],
        ['worker_mode_comparison_net_day', point => point.profitUsdPerDay == null ? '—'
            : `≈ ${prefs.moneyFromUsd(point.profitUsdPerDay)}`]
    ];
    for (const [label, value] of rows) {
        const row = make('tr', label === 'worker_mode_comparison_net_day' ? 'worker-mode-profit-row' : '');
        const header = make('th', '', t(label));
        header.scope = 'row';
        row.append(header);
        for (const mode of modes) row.append(make('td', '', mode.point ? value(mode.point) : '—'));
        body.append(row);
    }
    const sourceRow = make('tr', 'worker-mode-source-row');
    const sourceHeader = make('th', '', t('worker_mode_comparison_measurement'));
    sourceHeader.scope = 'row';
    sourceRow.append(sourceHeader);
    for (const mode of modes) {
        const cell = make('td');
        if (!mode.point) cell.append(make('span', 'worker-mode-missing', mode.missing));
        else {
            const measured = new Intl.DateTimeFormat(window.SolarMinerI18n.locale,
                {dateStyle: 'medium', timeStyle: 'short'}).format(new Date(mode.point.measuredAt));
            cell.append(make('span', '', t('worker_mode_comparison_source', {
                source: t(`coin_hopping_source_${mode.point.source?.toLowerCase() || 'live'}`), time: measured
            })));
            if (mode.point.tunedProfile) cell.append(make('span', 'worker-mode-proxy-note', tunedSettings(mode.point)));
            if (mode.note) cell.append(make('span', 'worker-mode-proxy-note', mode.note));
        }
        sourceRow.append(cell);
    }
    body.append(sourceRow);
    table.append(head, body);
    comparison.append(table);
    same.hidden = !(efficiency && profit && efficiency.limitWatts === profit.limitWatts
        && JSON.stringify(efficiency.tunedProfile || null) === JSON.stringify(profit.tunedProfile || null));
}
function updateComparisonDevices(snapshot) {
    const devices = new Map();
    if (resultsPage) {
        for (const worker of availableWorkers.filter(worker => worker.hardwareType === 'GPU'))
            devices.set(worker.deviceId, worker.hardwareModel || worker.deviceId);
        for (const row of latestBenchmarkState?.results || [])
            if (row.hardwareType === 'GPU') devices.set(row.deviceId, row.hardwareModel || row.deviceId);
        for (const run of latestSweepState?.runs || [])
            if (run.deviceId) devices.set(run.deviceId, run.model || run.deviceId);
    }
    for (const profile of snapshot.sweeps || []) {
        if (resultsPage || (profile.steps || []).some(step => step.stable && step.medianHashrateHs > 0 && step.avgPowerWatts > 0))
            devices.set(profile.deviceId, profile.model || profile.deviceId);
    }
    for (const profile of snapshot.benchmarks || []) {
        if ((resultsPage && profile.hardwareType === 'GPU') ||
            (profile.powerTargetWatts > 0 && profile.observations >= 12 && profile.hashrateHps > 0 && profile.powerWatts > 0))
            devices.set(profile.workerId, profile.hardwareModel || profile.workerId);
    }
    const signature = JSON.stringify([...devices]);
    if (signature === comparisonDevices) return;
    comparisonDevices = signature;
    const select = $('comparison-gpu');
    const previous = select.value;
    select.replaceChildren();
    if (resultsPage && !devices.size) select.add(new Option(t('benchmark_no_gpu_available'), ''));
    for (const [id, model] of [...devices].sort((a,b) => a[1].localeCompare(b[1], window.SolarMinerI18n.locale)))
        select.add(new Option(`${model} · ${id}`, id));
    const requested = new URLSearchParams(location.search).get('gpu');
    if (devices.has(previous)) select.value = previous;
    else if (resultsPage && devices.has(requested)) select.value = requested;
    select.disabled = !devices.size;
    if (resultsPage && previous !== select.value) refreshGpuResults();
    loadComparison();
}
function refreshGpuResults() {
    savedBenchmarkSignature = null;
    lastProfileSignature = null;
    lastSweepRunSignature = null;
    lastLiveSweepSignature = null;
    if (latestBenchmarkState) renderResults(latestBenchmarkState.results, latestBenchmarkState.running, latestBenchmarkState.phase);
    if (latestSweepState) {
        renderSweepRuns(forSelectedGpu(latestSweepState.runs), latestSweepState.running);
        renderSweepView();
    }
    renderSavedBenchmarks();
    renderProfiles();
}
if (resultsPage) {
    $('sweep-view-live').addEventListener('click', () => {
        sweepViewMode = 'live'; selectedSweepRunId = null; lastLiveSweepSignature = null; renderSweepView();
    });
    $('sweep-view-saved').addEventListener('click', () => {
        sweepViewMode = 'saved'; selectedSweepRunId = null; lastLiveSweepSignature = null; renderSweepView();
        renderProfiles();
    });
}
async function loadComparison() {
    if (!resultsPage) return;
    const id = ++comparisonRequest;
    const deviceId = $('comparison-gpu').value;
    const chart = $('comparison-chart');
    if (comparisonRenderedDevice !== deviceId) {
        comparisonRenderedDevice = deviceId;
        chart.replaceChildren();
        $('comparison-recommendation').textContent = '';
        $('comparison-updated').textContent = '';
        $('mode-comparison-coin').value = '';
        $('mode-comparison-miner').value = '';
        renderModeComparison([], null, null);
    }
    if (!deviceId) {
        chart.replaceChildren();
        $('comparison-recommendation').textContent = '';
        $('comparison-updated').textContent = '';
        chart.append(make('p', 'empty', t('benchmark_comparison_no_device')));
        renderModeComparison([], null, null);
        return;
    }
    try {
        let entry = comparisonCache.get(deviceId);
        let candidates;
        if (entry && entry.expiresAt > Date.now()) {
            candidates = entry.candidates;
        } else {
            let request = comparisonRequests.get(deviceId);
            if (!request) {
                const cacheRevision = comparisonCacheRevision;
                request = fetch(`/api/agent/local/benchmarks/comparison?${new URLSearchParams({deviceId})}`, {cache: 'no-store'})
                    .then(response => { if (!response.ok) throw new Error('benchmark_comparison_load_failed'); return response.json(); })
                    .then(value => {
                        if (cacheRevision === comparisonCacheRevision)
                            comparisonCache.set(deviceId, {candidates: value, expiresAt: Date.now() + COMPARISON_CACHE_MS});
                        return value;
                    })
                    .finally(() => comparisonRequests.delete(deviceId));
                comparisonRequests.set(deviceId, request);
            }
            candidates = await request;
        }
        if (id !== comparisonRequest) return;
        comparisonRenderedDevice = deviceId;
        chart.replaceChildren();
        $('comparison-recommendation').textContent = '';
        $('comparison-updated').textContent = '';
        renderModeComparison(candidates, null, null);
        const read = path => fetch(path, {cache: 'no-store', signal: AbortSignal.timeout(5000)})
            .then(response => response.ok ? response.json() : null).catch(() => null);
        Promise.all([read('/api/agent/local/economics'), read('/api/agent/local/power-control')])
            .then(([tariff, power]) => { if (id === comparisonRequest) renderModeComparison(candidates, tariff, power); });
        if (!candidates.length) {
            chart.append(make('p', 'empty', t('coin_hopping_no_candidates')));
            return;
        }
        const money = value => window.SolarMinerPreferences.moneyFromUsd(value);
        const maximum = Math.max(...candidates.map(row => row.usdPerKwh));
        $('comparison-recommendation').textContent = t('coin_hopping_recommended_point', {
            coin: candidates[0].coin.toUpperCase(), miner: candidates[0].minerSoftwareId || '—', watts: candidates[0].limitWatts
        });
        $('comparison-updated').textContent = t('benchmark_updated_at', {time: new Intl.DateTimeFormat(window.SolarMinerI18n.locale, {hour: '2-digit', minute: '2-digit'}).format(new Date())});
        for (const [index, candidate] of candidates.entries()) {
            const row = make('div', `hopping-row${index === 0 ? ' recommended' : ''}`);
            const track = make('div', 'hopping-track');
            const fill = make('i', 'hopping-fill');
            fill.style.width = `${Math.max(2, candidate.usdPerKwh / maximum * 100)}%`;
            track.append(fill);
            const measured = new Intl.DateTimeFormat(window.SolarMinerI18n.locale, {dateStyle:'medium', timeStyle:'short'}).format(new Date(candidate.measuredAt));
            if (candidate.tunedProfile) row.append(make('small', 'muted', tunedSettings(candidate)));
            row.append(make('strong', '', `${candidate.coin.toUpperCase()} · ${candidate.minerSoftwareId || '—'} · ${candidate.limitWatts} W`), track,
                make('span', '', `${money(candidate.usdPerKwh)} / kWh`),
                make('small', 'hopping-meta', t('coin_hopping_point_detail', {
                    source: t(`coin_hopping_source_${candidate.source?.toLowerCase() || 'live'}`),
                    rate: fmtRate(candidate.hashrateHps), power: Math.round(candidate.measuredWatts), time: measured
                })));
            chart.append(row);
        }
    } catch (_) {
        if (id === comparisonRequest) {
            chart.replaceChildren(make('p', 'empty', t('benchmark_comparison_load_failed')));
            $('comparison-recommendation').textContent = '';
            $('comparison-updated').textContent = '';
            renderModeComparison([], null, null);
        }
    }
}
function applyProfileFilter() {
    const query = $('profile-filter').value.trim().toLocaleLowerCase();
    for (const group of document.querySelectorAll('#saved-benchmarks .benchmark-algorithm-group, #sweep-results .benchmark-algorithm-group')) {
        let visible = 0;
        for (const card of group.querySelectorAll('.worker-card')) {
            card.hidden = Boolean(query) && !card.dataset.filter.includes(query);
            if (!card.hidden) visible++;
        }
        group.hidden = visible === 0;
        if (query && visible) group.open = true;
    }
}
$('comparison-gpu').addEventListener('change', () => {
    if (resultsPage) {
        const url = new URL(location.href);
        url.searchParams.set('gpu', selectedGpu());
        history.replaceState(null, '', url);
        refreshGpuResults();
    }
    loadComparison();
});
if (resultsPage) {
    $('mode-comparison-coin').addEventListener('change', () => renderModeComparison());
    $('mode-comparison-miner').addEventListener('change', () => renderModeComparison());
}
$('profile-filter').addEventListener('input', applyProfileFilter);
document.addEventListener('solarminer:preferences-changed', loadComparison);

// Phase 5c: measurement state arrives over the shared SSE stream instead of a 1 s
// three-fetch polling loop (benchmarks + efficiency + upload-status). The live-data
// watchdog re-reads a channel over REST only when its push went silent.
// P7: the benchmarks/efficiency channels carry live run-state (a stale "running" card is
// worse than a blank moment), so they opt out of snapshot persistence.
window.SolarMinerLive.channel('benchmarks', {endpoint: '/api/agent/local/benchmarks', maxAgeMs: 4000, persist: false})
    .subscribe((state, meta) => state ? applyBenchmarkState(state, meta) : (meta.error ? connectionLost() : null));
window.SolarMinerLive.channel('efficiency', {endpoint: '/api/agent/local/efficiency', maxAgeMs: 4000, persist: false})
    .subscribe(state => { if (state) applySweepState(state); });
window.SolarMinerLive.channel('benchmark-upload', {endpoint: '/api/agent/local/benchmarks/sharing/upload-status', maxAgeMs: 8000})
    .subscribe(applyUploadStatus);
// Load durable local data directly at page startup; it must not depend on an SSE
// status update arriving first.
renderSavedBenchmarks();
renderProfiles();
// Ordinary mining contributes new operating points without a benchmark transition.
// Keep the durable library fresh while the page remains open.
if (resultsPage) setInterval(() => {
    invalidateMeasurements();
    renderSavedBenchmarks();
    renderProfiles();
    loadComparison();
}, 60_000);
window.SolarMinerLive.start();
