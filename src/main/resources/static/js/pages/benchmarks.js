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

function notice(text, error = false, kind = 'action') {
    const e = $('notice');
    e.hidden = false; e.dataset.kind = kind;
    e.className = `notice${error ? ' error' : ''}`;
    e.textContent = t(text);
}

function fmtRate(v) {
    return window.SolarMinerMeasurements.hashrate(v);
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
    const bars = make('div', 'benchmark-bar-list');
    const maximum = Math.max(...results.map(row => Number(row.hashrateHs) || 0), 1);
    for (const row of results) {
        const line = make('div', 'benchmark-bar-row');
        const label = make('span', 'benchmark-bar-label', `${row.hardwareModel} · ${row.algorithm}`);
        const track = make('div', 'benchmark-bar-track');
        track.setAttribute('role', 'progressbar'); track.setAttribute('aria-label', `${label.textContent}: ${fmtRate(row.hashrateHs)}`);
        track.setAttribute('aria-valuemin', '0'); track.setAttribute('aria-valuemax', String(maximum)); track.setAttribute('aria-valuenow', String(row.hashrateHs || 0));
        const fill = make('i', 'benchmark-bar-fill');
        fill.style.width = `${Math.max(0, Math.min(100, (Number(row.hashrateHs) || 0) / maximum * 100))}%`;
        track.append(fill); line.append(label, track, make('span', 'benchmark-bar-value', fmtRate(row.hashrateHs))); bars.append(line);
    }
    root.append(head, bars);
}

let resultRenderVersion = 0;
async function renderResults(results, running = false, phase = '') {
    const version = ++resultRenderVersion;
    const root = $('results');
    root.replaceChildren();
    renderChart(results, running);
    if (!results?.length) {
        root.append(make('p', 'empty', running
            ? t('phase_waiting_for_the_first_valid_hashrate_s', {phase: s(phase || 'benchmark_running')})
            : !phase || phase === 'Idle' ? t('no_measurement_yet_choose_a_mode_above') : t('no_valid_mining_readings_recorded_check_that')));
        return;
    }
    const comparisons = running ? [] : results.map(comparison);
    for (const [index, row] of results.entries()) {
        const card = make('article', 'worker-card');
        const info = make('div', '');
        info.append(make('strong', '', `${row.hardwareModel} · ${row.algorithm}`), make('p', 'muted', t('hardware_count_samples', {hardware: row.hardwareType, count: row.observations})));
        info.append(make('p', '', `${t('hashrate')}: ${fmtRate(row.hashrateHs)} · ${t('power_4606f4')}: ${row.powerWatts > 0 ? new Intl.NumberFormat(window.SolarMinerI18n.locale, {maximumFractionDigits: 0}).format(row.powerWatts) + ' W' : t('unavailable_6d094b')} · ${t('efficiency')}: ${window.SolarMinerMeasurements.efficiency(row.hashrateHs, row.powerWatts)}`));
        card.append(info);
        root.append(card);
        const peer = running ? null : await comparisons[index];
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
    if ($('notice').dataset.kind === 'connection') $('notice').hidden = true;
    $('connection').className = `badge ${meta.stale ? 'offline' : 'online'}`;
    $('connection').textContent = meta.stale ? t('connecting') : t('agent_connected');
    $('benchmark-updated').textContent = meta.at
        ? t('benchmark_updated_at', {time: new Intl.DateTimeFormat(window.SolarMinerI18n.locale, {hour: '2-digit', minute: '2-digit', second: '2-digit'}).format(meta.at)})
        : '';
    $('run-state').textContent = !state.phase || state.phase === 'Idle' ? t('ready') : s(state.phase);
    $('cancel').hidden = !state.running;
    $('progress-wrap').hidden = !state.running;
    $('run-live').disabled = $('run-installed').disabled = state.running || actionBusy;
    if (state.running) {
        $('upload-status').hidden = true;
        $('retry-upload').hidden = true;
    }
    if (state.running) {
        $('phase').textContent = t('phase_step_step_total', {phase: s(state.phase), step: state.phaseIndex, total: state.phaseCount});
        const count = state.phase.match(/·\s*(\d+)\/(\d+)\s*Messpunkte/);
        if (count) {
            $('progress').max = Number(count[2]);
            $('progress').value = Number(count[1]);
        } else {
            $('progress').removeAttribute('value');
        }
    }
    const signature = JSON.stringify(state.results || []);
    if (signature !== lastResults || state.running !== lastRenderRunning || (state.running && !state.results?.length)) {
        lastResults = signature;
        lastRenderRunning = state.running;
        renderResults(state.results, state.running, state.phase);
    } else if (!state.running && state.phase !== 'Idle' && !state.results?.length) {
        $('results').replaceChildren(make('p', 'empty', s(state.phase)));
        lastResults = signature;
    }
    if (!state.running) window.SolarMinerLive.refresh('benchmark-upload');
}

function connectionLost() {
    $('connection').className = 'badge offline';
    $('connection').textContent = t('agent_unavailable');
    $('run-live').disabled = $('run-installed').disabled = true;
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
    if (manual.status === 'NOT_SHARED' && manual.sampleCount > 0 && sharingKnown && !$('sharing').checked && !sharePromptShown) {
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
    if (actionBusy) return; actionBusy = true;
    $('run-live').disabled = $('run-installed').disabled = true;
    try {
        const response = await fetch('/api/agent/local/benchmarks', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({mode, coins: selectedCoins()})});
        if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.detail || body?.message || t('could_not_start_the_benchmark')); }
        notice('measurement_started_you_can_cancel_it_at_any');
        $('results').replaceChildren(make('p', 'empty', t('benchmark_started')));
    } catch (error) { notice(window.SolarMinerI18n.s(error.message), true); }
    finally { actionBusy = false; window.SolarMinerLive.refresh('benchmarks'); }
}

// The coin selection applies to both measurement runs (benchmark and power-limit sweep).
// Uninstalled coins stay disabled; a stored selection survives reloads so an operator can
// permanently exclude coins their cards cannot run (e.g. PearlHash on Pascal).
const coinSelectionKey = 'solarminer.pc-agent.benchmark-coins';
let catalogCoins = [];

function storedCoinSelection() {
    try {
        const value = JSON.parse(localStorage.getItem(coinSelectionKey));
        return Array.isArray(value) ? value : null;
    } catch (_) { return null; }
}

function selectedCoins() {
    const checked = [...document.querySelectorAll('#coin-options input:checked')].map(input => input.value);
    // All coins checked means "no restriction" — the agent keeps its full-run semantics.
    return checked.length === catalogCoins.length ? [] : checked;
}

function renderCoinOptions(catalog) {
    catalogCoins = catalog.filter(entry => entry.binaryAvailable);
    const root = $('coin-options');
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
            try { localStorage.setItem(coinSelectionKey, JSON.stringify(selectedCoins())); } catch (_) { }
        });
        const text = make('span', '');
        text.append(make('strong', '', `${entry.name} · ${entry.device}`));
        text.append(make('small', 'muted', t(entry.binaryAvailable ? 'installed_lowercase' : 'not_installed')));
        label.append(input, text);
        root.append(label);
    }
}

fetch('/api/agent/local/miner-catalog', {cache: 'no-store'}).then(async response => {
    if (!response.ok) throw new Error('catalog_unavailable');
    renderCoinOptions(await response.json());
}).catch(() => {
    $('coin-selection').hidden = true;
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
    window.SolarMinerLive.refresh('benchmarks');
    window.SolarMinerLive.refresh('efficiency');
    window.SolarMinerLive.refresh('benchmark-upload');
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

function applySweepState(state) {
        $('sweep-state').textContent = state.running ? t('running') : (!state.phase || state.phase === 'Idle' ? t('ready') : s(state.phase));
        $('sweep-cancel').hidden = !state.running;
        $('sweep-progress-wrap').hidden = !state.running;
        const resumable = !state.running && (state.runs || []).length > 0
            && (state.runs || []).some(run => run.status !== 'COMPLETE');
        $('resume-sweep').hidden = !resumable;
        $('resume-sweep').disabled = state.running || actionBusy;
        $('run-sweep').textContent = t(resumable ? 'restart_from_scratch' : 'start_power_limit_test');
        $('run-sweep').disabled = state.running || actionBusy;
        if (state.running) {
            $('sweep-phase').textContent = t('done_total_devices_complete_phase',
                {done: state.phaseIndex, total: state.phaseCount, phase: s(state.phase)});
            $('sweep-progress').max = Math.max(1, state.phaseCount);
            $('sweep-progress').value = state.phaseIndex;
            $('sweep-remaining').textContent = state.secondsRemaining == null
                ? t('calculating_eta') : t('approximate_time_remaining_time', {time: formatDuration(state.secondsRemaining)});
        }
        renderSweepRuns(state.runs || []);
        renderProfiles();
}

let lastProfileSignature = null;
let lastSweepRunSignature = null;
// The run list re-renders on every poll; remember which disclosures the operator opened
// so a re-render never collapses their view mid-run.
const openSweepGroups = new Map();
const openSweepRows = new Set();

function formatDuration(seconds) {
    const value = Math.max(0, Math.round(Number(seconds) || 0));
    const hours = Math.floor(value / 3600);
    const minutes = Math.floor((value % 3600) / 60);
    const rest = value % 60;
    if (hours) return `${hours} h ${minutes} min`;
    if (minutes) return `${minutes} min ${rest} s`;
    return `${rest} s`;
}

function renderSweepRuns(runs) {
    const signature = JSON.stringify(runs);
    if (signature === lastSweepRunSignature) return;
    lastSweepRunSignature = signature;
    const root = $('sweep-runs');
    root.hidden = !runs.length;
    root.replaceChildren();
    if (!runs.length) return;
    const statusLabel = {QUEUED: 'planned', RUNNING: 'running', COMPLETE: 'complete', FAILED: 'failed', SKIPPED: 'skipped', CANCELLED: 'cancelled'};
    const isTerminal = status => status === 'COMPLETE' || status === 'FAILED' || status === 'SKIPPED' || status === 'CANCELLED';
    // Only live runs get a full card; queued and finished runs collapse into compact
    // disclosure rows so a long plan never buries the run that is actually measuring.
    for (const run of runs.filter(r => r.status === 'RUNNING')) root.append(liveRunCard(run, statusLabel));
    const waiting = runs.filter(r => r.status === 'QUEUED');
    const finished = runs.filter(r => isTerminal(r.status));
    if (waiting.length) root.append(runGroup(t('waiting_runs_count', {count: waiting.length}), waiting, statusLabel, false));
    if (finished.length) root.append(runGroup(t('completed_runs_count', {count: finished.length}), finished, statusLabel, true));
}

function runIdentity(run) {
    const wave = run.mode === 'FULL' && Number.isInteger(run.referenceBatch)
        ? ` · ${t('start_group_number', {number: run.referenceBatch + 1})}` : '';
    return `${run.coin} · ${t(run.mode === 'FULL' ? 'reference_curve' : 'parallel_device_validation')}${wave}`;
}

function runPlanLine(run) {
    const completed = new Map((run.steps || []).map(step => [step.limitWatts, step]));
    return (run.plannedLimits || []).map(limit => {
        const step = completed.get(limit);
        if (!step) return `${limit} W ○`;
        const measurement = step.medianHashrateHs ? ` · ${fmtRate(step.medianHashrateHs)} · ${Math.round(step.avgPowerWatts)} W` : '';
        const reason = step.note ? ` — ${s(step.note)}` : '';
        return `${limit} W ${step.stable ? '✓' : '✗'}${measurement}${reason}`;
    }).join('  ·  ');
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
    const steps = runPlanLine(run);
    if (steps) info.append(make('p', 'muted', steps));
    card.append(info, make('span', 'tag ready', t(statusLabel[run.status] || run.status)));
    return card;
}

function runGroup(label, runs, statusLabel, defaultOpen) {
    const group = make('details', 'sweep-run-group');
    const key = label.replace(/\(\d+\)/, '');
    group.open = openSweepGroups.has(key) ? openSweepGroups.get(key) : defaultOpen;
    group.addEventListener('toggle', () => openSweepGroups.set(key, group.open));
    group.append(make('summary', '', label));
    const rows = make('div', 'sweep-run-rows');
    for (const run of runs) rows.append(runRow(run, statusLabel));
    group.append(rows);
    return group;
}

function runRow(run, statusLabel) {
    const row = make('details', 'sweep-run-row');
    row.open = openSweepRows.has(run.id);
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
    const steps = runPlanLine(run);
    if (steps) body.append(make('p', '', steps));
    row.append(body);
    return row;
}

async function renderProfiles() {
    try {
        const response = await fetch('/api/agent/local/efficiency/profiles', {cache: 'no-store'});
        if (!response.ok) return;
        const profiles = await response.json();
        const signature = JSON.stringify(profiles);
        if (signature === lastProfileSignature) return;
        lastProfileSignature = signature;
        const root = $('sweep-results');
        root.replaceChildren();
        if (!profiles.length) {
            root.append(make('p', 'empty', t('no_efficiency_profile_yet_start_a_test_to_fi')));
            return;
        }
        for (const profile of profiles) {
            const card = make('article', 'worker-card');
            const info = make('div', '');
            info.append(make('strong', '', `${profile.model} · ${profile.algorithm}`),
                make('p', 'muted', t('coin_tested_date', {coin: profile.coin, date: new Intl.DateTimeFormat(window.SolarMinerI18n.locale, {dateStyle: 'medium', timeStyle: 'short'}).format(new Date(profile.testedAt))})));
            if (profile.bestStableWatts != null) {
                info.append(make('p', '', t('best_stable_value_watts_w_hashrate_power_w_e', {watts: profile.bestStableWatts, hashrate: fmtRate(profile.bestHashrateHs), power: Math.round(profile.bestPowerWatts), efficiency: window.SolarMinerMeasurements.efficiency(profile.bestHashrateHs, profile.bestPowerWatts)})));
            } else info.append(make('p', '', t('no_stable_power_limit_found')));
            const steps = (profile.steps || []).map(step =>
                `${step.limitWatts} W ${step.stable ? '✓' : '✗'}${step.medianHashrateHs ? ` (${fmtRate(step.medianHashrateHs)} · ${Math.round(step.avgPowerWatts)} W)` : ''}${step.note ? ` — ${s(step.note)}` : ''}`).join(' · ');
            if (steps) info.append(make('p', 'muted', steps));
            card.append(info);
            root.append(card);
        }
    } catch (e) {
        // Profiles remain hidden until the local store is reachable again.
    }
}

async function startSweep(mode = 'restart') {
    if (actionBusy) return; actionBusy = true;
    $('run-sweep').disabled = true;
    $('resume-sweep').disabled = true;
    try {
        const response = await fetch(`/api/agent/local/efficiency?mode=${encodeURIComponent(mode)}`,
            {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({coins: selectedCoins()})});
        if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.message || body?.detail || 'could_not_start_the_efficiency_sweep'); }
        notice(mode === 'resume'
            ? 'efficiency_sweep_resumed_with_completed_resu'
            : 'efficiency_sweep_restarted_power_limits_are');
    } catch (error) { notice(s(error.message), true); }
    finally { actionBusy = false; window.SolarMinerLive.refresh('efficiency'); }
}

$('run-sweep').addEventListener('click', () => startSweep('restart'));
$('resume-sweep').addEventListener('click', () => startSweep('resume'));
$('sweep-cancel').addEventListener('click', async () => {
    if (actionBusy) return; actionBusy = true; $('sweep-cancel').disabled = true;
    try { const response = await fetch('/api/agent/local/efficiency/cancel', {method: 'POST'}); if (!response.ok) throw new Error('could_not_cancel_the_sweep'); notice('cancellation_requested_power_limits_and_mine'); }
    catch (error) { notice(s(error.message), true); }
    finally { actionBusy = false; $('sweep-cancel').disabled = false; window.SolarMinerLive.refresh('efficiency'); }
});

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
window.SolarMinerLive.start();
