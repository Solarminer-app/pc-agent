const $ = id => document.getElementById(id);
const make = (tag, cls, value) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (value != null) e.textContent = value;
    return e;
};
let timer;
let actionBusy = false;
const t = window.SolarMinerI18n.t;
const s = window.SolarMinerI18n.s;
let lastResults = '';
let polling = false;
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

async function renderResults(results, running = false, phase = '') {
    const root = $('results');
    root.replaceChildren();
    if (!results?.length) {
        root.append(make('p', 'empty', running
            ? t('{phase} · Warte auf die ersten gültigen Hashrate-Messpunkte …', {phase: s(phase || 'Benchmark läuft')})
            : !phase || phase === 'Idle' ? t('Noch keine Messung. Wähle oben einen Messlauf.') : t('Keine gültigen Mining-Messwerte erfasst. Prüfe, ob Miner laufen und Hashrate liefern.')));
        return;
    }
    for (const row of results) {
        const card = make('article', 'worker-card');
        const info = make('div', '');
        info.append(make('strong', '', `${row.hardwareModel} · ${row.algorithm}`), make('p', 'muted', t('{hardware} · {count} Messpunkte', {hardware: row.hardwareType, count: row.observations})));
        info.append(make('p', '', `${t('Hashrate')}: ${fmtRate(row.hashrateHs)} · ${t('Leistung')}: ${row.powerWatts > 0 ? new Intl.NumberFormat(window.SolarMinerI18n.locale, {maximumFractionDigits: 0}).format(row.powerWatts) + ' W' : t('nicht verfügbar')} · ${t('Effizienz')}: ${window.SolarMinerMeasurements.efficiency(row.hashrateHs, row.powerWatts)}`));
        const peer = running ? null : await comparison(row);
        if (peer) {
            const delta = peer.medianHashrateHs > 0 ? (row.hashrateHs / peer.medianHashrateHs - 1) * 100 : null;
            info.append(make('p', 'muted', `${t('Vergleich')} (${peer.sampleCount} ${t('Geräte')}): ${t('Median')} ${fmtRate(peer.medianHashrateHs)}${peer.medianPowerWatts ? ` · ${Math.round(peer.medianPowerWatts)} W` : ''}${delta == null ? '' : ` · ${new Intl.NumberFormat(window.SolarMinerI18n.locale, {maximumFractionDigits: 1}).format(Math.abs(delta))} % ${t(delta >= 0 ? 'über' : 'unter')} ${t('dem Median')}`}`));
        } else info.append(make('p', 'muted', running
            ? t('Messwerte werden laufend aktualisiert; der Vergleich erscheint nach Abschluss.')
            : t('Noch kein veröffentlichter Vergleich für diese Hardware und diesen Algorithmus.')));
        card.append(info);
        root.append(card);
    }
}

async function poll() {
    if (polling) return;
    polling = true;
    try {
        const response = await fetch('/api/agent/local/benchmarks', {cache: 'no-store'});
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const state = await response.json();
        if ($('notice').dataset.kind === 'connection') $('notice').hidden = true;
        $('connection').className = 'badge online';
        $('connection').textContent = t('Agent verbunden');
        $('run-state').textContent = !state.phase || state.phase === 'Idle' ? t('Bereit') : s(state.phase);
        $('cancel').hidden = !state.running;
        $('progress-wrap').hidden = !state.running;
        $('run-live').disabled = $('run-installed').disabled = state.running || actionBusy;
        if (state.running) {
            $('upload-status').hidden = true;
            $('retry-upload').hidden = true;
        }
        if (state.running) {
            $('phase').textContent = t('{phase} · Schritt {step}/{total}', {phase: s(state.phase), step: state.phaseIndex, total: state.phaseCount});
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
            await renderResults(state.results, state.running, state.phase);
        } else if (!state.running && state.phase !== 'Idle' && !state.results?.length) {
            $('results').replaceChildren(make('p', 'empty', s(state.phase)));
            lastResults = signature;
        }
        if (!state.running) await pollUploadStatus();
    } catch (e) {
        $('connection').className = 'badge offline';
        $('connection').textContent = t('Agent nicht erreichbar');
        $('run-live').disabled = $('run-installed').disabled = true;
        notice('Benchmark-Status konnte nicht geladen werden. Vorhandene Ergebnisse bleiben sichtbar.', true, 'connection');
    } finally {
        polling = false;
    }
}

async function pollUploadStatus() {
    try {
        const response = await fetch('/api/agent/local/benchmarks/sharing/upload-status', {cache: 'no-store'});
        if (!response.ok) return;
        const statuses = await response.json();
        const signature = JSON.stringify(statuses);
        if (signature === lastUploadStatus) return;
        lastUploadStatus = signature;
        const manual = statuses.manual;
        const periodic = statuses.periodic;
        showUploadStatus('upload-status', manual, 'Benchmark-Upload');
        showUploadStatus('periodic-upload-status', periodic, 'Regelmäßiger Upload');
        $('retry-upload').hidden = manual.status !== 'FAILED';
        // The agent only reports NOT_SHARED with samples when a finished manual run kept its
        // measurements unsent; offer them once per run if the operator has sharing disabled.
        if (manual.status === 'NOT_SHARED' && manual.sampleCount > 0 && sharingKnown && !$('sharing').checked && !sharePromptShown) {
            sharePromptShown = true;
            $('share-prompt').showModal();
        }
    } catch (e) {
        // The benchmark result remains visible even if the local status request fails.
    }
}

function showUploadStatus(id, status, label) {
    const element = $(id);
    element.hidden = !status || status.status === 'IDLE';
    if (element.hidden) return;
    element.className = `notice${status.status === 'FAILED' ? ' error' : ''}`;
    element.textContent = `${t(label)}: ${s(status.message)}${status.sampleCount ? ` ${t('({count} Messwerte)', {count: status.sampleCount})}` : ''}`;
}

async function start(mode) {
    if (actionBusy) return; actionBusy = true;
    $('run-live').disabled = $('run-installed').disabled = true;
    try {
        const response = await fetch('/api/agent/local/benchmarks', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({mode})});
        if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.detail || body?.message || t('Benchmark konnte nicht gestartet werden.')); }
        notice('Messlauf gestartet. Du kannst ihn jederzeit abbrechen.');
        $('results').replaceChildren(make('p', 'empty', t('Messlauf gestartet …')));
    } catch (error) { notice(window.SolarMinerI18n.s(error.message), true); }
    finally { actionBusy = false; await poll(); }
}

$('run-live').addEventListener('click', () => start('LIVE'));
$('run-installed').addEventListener('click', () => start('INSTALLED'));
$('retry-upload').addEventListener('click', async () => {
    const button = $('retry-upload');
    button.disabled = true;
    try {
        const response = await fetch('/api/agent/local/benchmarks/sharing/retry-manual', {method: 'POST'});
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        lastUploadStatus = '';
        await pollUploadStatus();
    } catch (e) {
        notice('Der Upload konnte nicht erneut gestartet werden.', true);
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
        if (status.status === 'SENT') notice(t('Die Ergebnisse wurden einmalig anonym hochgeladen. Die Einstellung für regelmäßige Benchmarks bleibt unverändert.'));
        else if (status.status === 'FAILED') notice(t('Der anonyme Upload ist fehlgeschlagen. Du kannst ihn über „Upload erneut versuchen“ wiederholen.'), true);
        $('share-prompt').close();
        lastUploadStatus = '';
        await pollUploadStatus();
    } catch (e) {
        notice('Der anonyme Upload konnte nicht gestartet werden.', true);
        button.disabled = false;
    }
});
$('cancel').addEventListener('click', async () => {
    if (actionBusy) return; actionBusy = true; $('cancel').disabled = true;
    try { const response = await fetch('/api/agent/local/benchmarks/cancel', {method:'POST'}); if (!response.ok) throw new Error('Messlauf konnte nicht abgebrochen werden.'); notice('Abbruch angefordert. Der vorherige Mining-Zustand wird wiederhergestellt.'); }
    catch (error) { notice(s(error.message), true); }
    finally {actionBusy=false;$('cancel').disabled=false;await poll();}
});
$('refresh').addEventListener('click', poll);
function sharingSummary() { $('sharing-summary').textContent = t($('sharing').checked ? 'EINGESCHALTET' : 'AUSGESCHALTET'); }
$('sharing').addEventListener('change', async () => {
    const input = $('sharing'); input.disabled = true;
    try {
        const response = await fetch('/api/agent/local/benchmarks/sharing', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({enabled:input.checked})});
        if (!response.ok) throw new Error('Die Freigabe konnte nicht gespeichert werden.');
        $('sharing-note').textContent = t(input.checked ? 'Freigabe gespeichert. Aktive Miner werden regelmäßig übertragen.' : 'Freigabe deaktiviert. Der Widerruf wird beim nächsten Versandintervall übermittelt.');
    } catch (error) {input.checked=!input.checked;notice(s(error.message),true);}
    finally {input.disabled=false;sharingSummary();}
});
$('sharing').disabled = true;
fetch('/api/agent/local/benchmarks/sharing').then(async response => {
    if (!response.ok) throw new Error('Sharing unavailable');
    const value = await response.json(); if (typeof value !== 'boolean') throw new Error('Invalid consent');
    $('sharing').checked = value; $('sharing').disabled = false; sharingKnown = true; sharingSummary();
}).catch(() => {
    $('sharing-summary').textContent = t('Nicht verfügbar');
    $('sharing-note').textContent = t('Die Freigabe konnte nicht gelesen werden. Aktualisiere die Seite, bevor du sie änderst.');
});

async function pollSweep() {
    try {
        const response = await fetch('/api/agent/local/efficiency', {cache: 'no-store'});
        if (!response.ok) return;
        const state = await response.json();
        $('sweep-state').textContent = state.running ? t('Läuft') : (!state.phase || state.phase === 'Idle' ? t('Bereit') : s(state.phase));
        $('sweep-cancel').hidden = !state.running;
        $('sweep-progress-wrap').hidden = !state.running;
        const resumable = !state.running && (state.runs || []).length > 0
            && (state.runs || []).some(run => run.status !== 'COMPLETE');
        $('resume-sweep').hidden = !resumable;
        $('resume-sweep').disabled = state.running || actionBusy;
        $('run-sweep').textContent = t(resumable ? 'Komplett neu starten' : 'Power-Limit-Test starten');
        $('run-sweep').disabled = state.running || actionBusy;
        if (state.running) {
            $('sweep-phase').textContent = t('{done}/{total} Geräte abgeschlossen · {phase}',
                {done: state.phaseIndex, total: state.phaseCount, phase: s(state.phase)});
            $('sweep-progress').max = Math.max(1, state.phaseCount);
            $('sweep-progress').value = state.phaseIndex;
            $('sweep-remaining').textContent = state.secondsRemaining == null
                ? t('ETA wird berechnet …') : t('Ungefähre Restzeit: {time}', {time: formatDuration(state.secondsRemaining)});
        }
        renderSweepRuns(state.runs || []);
        await renderProfiles();
    } catch (e) {
        // The benchmark page stays usable when the sweep status request fails.
    }
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
    const statusLabel = {QUEUED: 'Geplant', RUNNING: 'Läuft', COMPLETE: 'Fertig', FAILED: 'Fehlgeschlagen', SKIPPED: 'Übersprungen', CANCELLED: 'Abgebrochen'};
    const isTerminal = status => status === 'COMPLETE' || status === 'FAILED' || status === 'SKIPPED' || status === 'CANCELLED';
    // Only live runs get a full card; queued and finished runs collapse into compact
    // disclosure rows so a long plan never buries the run that is actually measuring.
    for (const run of runs.filter(r => r.status === 'RUNNING')) root.append(liveRunCard(run, statusLabel));
    const waiting = runs.filter(r => r.status === 'QUEUED');
    const finished = runs.filter(r => isTerminal(r.status));
    if (waiting.length) root.append(runGroup(t('Wartende Läufe ({count})', {count: waiting.length}), waiting, statusLabel, false));
    if (finished.length) root.append(runGroup(t('Abgeschlossene Läufe ({count})', {count: finished.length}), finished, statusLabel, true));
}

function runIdentity(run) {
    const wave = run.mode === 'FULL' && Number.isInteger(run.referenceBatch)
        ? ` · ${t('Startgruppe {number}', {number: run.referenceBatch + 1})}` : '';
    return `${run.coin} · ${t(run.mode === 'FULL' ? 'Referenzkurve' : 'Parallele Gerätevalidierung')}${wave}`;
}

function runPlanLine(run) {
    const completed = new Map((run.steps || []).map(step => [step.limitWatts, step]));
    return (run.plannedLimits || []).map(limit => {
        const step = completed.get(limit);
        if (!step) return `${limit} W ○`;
        const measurement = step.medianHashrateHs ? ` · ${fmtRate(step.medianHashrateHs)} · ${Math.round(step.avgPowerWatts)} W` : '';
        return `${limit} W ${step.stable ? '✓' : '✗'}${measurement}`;
    }).join('  ·  ');
}

function liveRunCard(run, statusLabel) {
    const card = make('article', 'worker-card sweep-live-card');
    const info = make('div', '');
    info.append(make('strong', '', `${run.model} · ${run.algorithm}`), make('p', 'muted', runIdentity(run)));
    if (run.detail) info.append(make('p', '', s(run.detail)));
    info.append(make('p', 'muted', t('{watts} W · {samples}/{required} Messpunkte',
        {watts: run.limitWatts ?? '—', samples: run.samples, required: run.samplesRequired})));
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
            root.append(make('p', 'empty', t('Noch kein Effizienz-Profil. Starte einen Test, um stabile Power-Limits zu ermitteln.')));
            return;
        }
        for (const profile of profiles) {
            const card = make('article', 'worker-card');
            const info = make('div', '');
            info.append(make('strong', '', `${profile.model} · ${profile.algorithm}`),
                make('p', 'muted', t('{coin} · getestet {date}', {coin: profile.coin, date: new Intl.DateTimeFormat(window.SolarMinerI18n.locale, {dateStyle: 'medium', timeStyle: 'short'}).format(new Date(profile.testedAt))})));
            if (profile.bestStableWatts != null) {
                info.append(make('p', '', t('Bester stabiler Wert: {watts} W · {hashrate} · {power} W · {efficiency}', {watts: profile.bestStableWatts, hashrate: fmtRate(profile.bestHashrateHs), power: Math.round(profile.bestPowerWatts), efficiency: window.SolarMinerMeasurements.efficiency(profile.bestHashrateHs, profile.bestPowerWatts)})));
            } else info.append(make('p', '', t('Kein stabiler Power-Limit-Wert gefunden.')));
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
        const response = await fetch(`/api/agent/local/efficiency?mode=${encodeURIComponent(mode)}`, {method: 'POST'});
        if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.message || body?.detail || 'Der Effizienz-Sweep konnte nicht gestartet werden.'); }
        notice(mode === 'resume'
            ? 'Effizienz-Sweep wird mit den bereits abgeschlossenen Ergebnissen fortgesetzt.'
            : 'Effizienz-Sweep neu gestartet. Leistungsgrenzen werden schrittweise gesenkt und nach dem Lauf wiederhergestellt.');
    } catch (error) { notice(s(error.message), true); }
    finally { actionBusy = false; await pollSweep(); }
}

$('run-sweep').addEventListener('click', () => startSweep('restart'));
$('resume-sweep').addEventListener('click', () => startSweep('resume'));
$('sweep-cancel').addEventListener('click', async () => {
    if (actionBusy) return; actionBusy = true; $('sweep-cancel').disabled = true;
    try { const response = await fetch('/api/agent/local/efficiency/cancel', {method: 'POST'}); if (!response.ok) throw new Error('Der Sweep konnte nicht abgebrochen werden.'); notice('Abbruch angefordert. Leistungsgrenzen und Miner-Zustand werden wiederhergestellt.'); }
    catch (error) { notice(s(error.message), true); }
    finally { actionBusy = false; $('sweep-cancel').disabled = false; await pollSweep(); }
});

poll();
pollSweep();
timer = setInterval(() => {if (!document.hidden) { poll(); pollSweep(); }}, 1000);
