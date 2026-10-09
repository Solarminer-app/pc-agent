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
    e.textContent = text;
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
            ? `${phase || 'Benchmark läuft'} · Warte auf die ersten gültigen Hashrate-Messpunkte …`
            : !phase || phase === 'Idle' ? 'Noch keine Messung. Wähle oben einen Messlauf.' : 'Keine gültigen Mining-Messwerte erfasst. Prüfe, ob Miner laufen und Hashrate liefern.'));
        return;
    }
    for (const row of results) {
        const card = make('article', 'worker-card');
        const info = make('div', '');
        info.append(make('strong', '', `${row.hardwareModel} · ${row.algorithm}`), make('p', 'muted', `${row.hardwareType} · ${row.observations} Messpunkte`));
        info.append(make('p', '', `${t('Hashrate')}: ${fmtRate(row.hashrateHs)} · ${t('Leistung')}: ${row.powerWatts > 0 ? new Intl.NumberFormat(window.SolarMinerI18n.locale, {maximumFractionDigits: 0}).format(row.powerWatts) + ' W' : t('nicht verfügbar')} · ${t('Effizienz')}: ${window.SolarMinerMeasurements.efficiency(row.hashrateHs, row.powerWatts)}`));
        const peer = running ? null : await comparison(row);
        if (peer) {
            const delta = peer.medianHashrateHs > 0 ? (row.hashrateHs / peer.medianHashrateHs - 1) * 100 : null;
            info.append(make('p', 'muted', `${window.SolarMinerI18n.language === 'de' ? 'Vergleich' : 'Comparison'} (${peer.sampleCount} ${window.SolarMinerI18n.language === 'de' ? 'Geräte' : 'devices'}): Median ${fmtRate(peer.medianHashrateHs)}${peer.medianPowerWatts ? ` · ${Math.round(peer.medianPowerWatts)} W` : ''}${delta == null ? '' : ` · ${new Intl.NumberFormat(window.SolarMinerI18n.locale, {maximumFractionDigits: 1}).format(Math.abs(delta))} % ${t(delta >= 0 ? 'über' : 'unter')} ${window.SolarMinerI18n.language === 'de' ? 'dem Median' : 'median'}`}`));
        } else info.append(make('p', 'muted', running
            ? 'Messwerte werden laufend aktualisiert; der Vergleich erscheint nach Abschluss.'
            : 'Noch kein veröffentlichter Vergleich für diese Hardware und diesen Algorithmus.'));
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
        $('connection').textContent = 'Agent verbunden';
        $('run-state').textContent = t(state.phase === 'Idle' ? 'Bereit' : state.phase || 'Bereit');
        $('cancel').hidden = !state.running;
        $('progress-wrap').hidden = !state.running;
        $('run-live').disabled = $('run-installed').disabled = state.running || actionBusy;
        if (state.running) {
            $('upload-status').hidden = true;
            $('retry-upload').hidden = true;
        }
        if (state.running) {
            $('phase').textContent = `${state.phase} · Schritt ${state.phaseIndex}/${state.phaseCount}`;
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
            $('results').replaceChildren(make('p', 'empty', state.phase));
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
    element.textContent = `${label}: ${status.message}${status.sampleCount ? ` (${status.sampleCount} Geräte)` : ''}`;
}

async function start(mode) {
    if (actionBusy) return; actionBusy = true;
    $('run-live').disabled = $('run-installed').disabled = true;
    try {
        const response = await fetch('/api/agent/local/benchmarks', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({mode})});
        if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.message || body?.detail || 'Benchmark konnte nicht gestartet werden.'); }
        notice('Messlauf gestartet. Du kannst ihn jederzeit abbrechen.');
        $('results').replaceChildren(make('p', 'empty', t('Messlauf gestartet …')));
    } catch (error) { notice(error.message, true); }
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
    catch (error) { notice(error.message, true); }
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
    } catch (error) {input.checked=!input.checked;notice(error.message,true);}
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
        $('sweep-state').textContent = state.running ? t('Läuft') : (state.phase === 'Idle' ? t('Bereit') : state.phase || t('Bereit'));
        $('sweep-cancel').hidden = !state.running;
        $('sweep-progress-wrap').hidden = !state.running;
        $('run-sweep').disabled = state.running || actionBusy;
        if (state.running) {
            $('sweep-phase').textContent = t('{done}/{total} Geräte abgeschlossen · {phase}',
                {done: state.phaseIndex, total: state.phaseCount, phase: state.phase});
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
    for (const run of runs) {
        const card = make('article', 'worker-card');
        const info = make('div', '');
        const wave = run.mode === 'FULL' && Number.isInteger(run.referenceBatch)
            ? ` · ${t('Welle {number}', {number: run.referenceBatch + 1})}` : '';
        info.append(make('strong', '', `${run.model} · ${run.algorithm}`),
            make('p', 'muted', `${run.coin} · ${t(run.mode === 'FULL' ? 'Referenzkurve' : 'Parallele Gerätevalidierung')}${wave} · ${run.deviceId}`));
        const tag = make('span', 'tag', t(statusLabel[run.status] || run.status));
        if (run.detail) info.append(make('p', '', run.detail));
        if (run.status === 'RUNNING') {
            info.append(make('p', 'muted', t('{watts} W · {samples}/{required} Messpunkte',
                {watts: run.limitWatts ?? '—', samples: run.samples, required: run.samplesRequired})));
        }
        const completed = new Map((run.steps || []).map(step => [step.limitWatts, step]));
        const plan = (run.plannedLimits || []).map(limit => {
            const step = completed.get(limit);
            if (!step) return `${limit} W ○`;
            const measurement = step.medianHashrateHs ? ` · ${fmtRate(step.medianHashrateHs)} · ${Math.round(step.avgPowerWatts)} W` : '';
            return `${limit} W ${step.stable ? '✓' : '✗'}${measurement}`;
        }).join('  ·  ');
        if (plan) info.append(make('p', 'muted', plan));
        card.append(info, tag);
        root.append(card);
    }
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
            root.append(make('p', 'empty', 'Noch kein Effizienz-Profil. Starte einen Test, um stabile Power-Limits zu ermitteln.'));
            return;
        }
        for (const profile of profiles) {
            const card = make('article', 'worker-card');
            const info = make('div', '');
            info.append(make('strong', '', `${profile.model} · ${profile.algorithm}`),
                make('p', 'muted', `${profile.coin} · getestet ${new Intl.DateTimeFormat(window.SolarMinerI18n.locale, {dateStyle: 'medium', timeStyle: 'short'}).format(new Date(profile.testedAt))}`));
            if (profile.bestStableWatts != null) {
                info.append(make('p', '', `Bester stabiler Wert: ${profile.bestStableWatts} W · ${fmtRate(profile.bestHashrateHs)} · ${Math.round(profile.bestPowerWatts)} W · ${window.SolarMinerMeasurements.efficiency(profile.bestHashrateHs, profile.bestPowerWatts)}`));
            } else info.append(make('p', '', 'Kein stabiler Power-Limit-Wert gefunden.'));
            const steps = (profile.steps || []).map(step =>
                `${step.limitWatts} W ${step.stable ? '✓' : '✗'}${step.medianHashrateHs ? ` (${fmtRate(step.medianHashrateHs)} · ${Math.round(step.avgPowerWatts)} W)` : ''}${step.note ? ` — ${step.note}` : ''}`).join(' · ');
            if (steps) info.append(make('p', 'muted', steps));
            card.append(info);
            root.append(card);
        }
    } catch (e) {
        // Profiles remain hidden until the local store is reachable again.
    }
}

async function startSweep() {
    if (actionBusy) return; actionBusy = true;
    $('run-sweep').disabled = true;
    try {
        const response = await fetch('/api/agent/local/efficiency', {method: 'POST'});
        if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.message || body?.detail || 'Der Effizienz-Sweep konnte nicht gestartet werden.'); }
        notice('Effizienz-Sweep gestartet. Leistungsgrenzen werden schrittweise gesenkt und nach dem Lauf wiederhergestellt.');
    } catch (error) { notice(error.message, true); }
    finally { actionBusy = false; await pollSweep(); }
}

$('run-sweep').addEventListener('click', startSweep);
$('sweep-cancel').addEventListener('click', async () => {
    if (actionBusy) return; actionBusy = true; $('sweep-cancel').disabled = true;
    try { const response = await fetch('/api/agent/local/efficiency/cancel', {method: 'POST'}); if (!response.ok) throw new Error('Der Sweep konnte nicht abgebrochen werden.'); notice('Abbruch angefordert. Leistungsgrenzen und Miner-Zustand werden wiederhergestellt.'); }
    catch (error) { notice(error.message, true); }
    finally { actionBusy = false; $('sweep-cancel').disabled = false; await pollSweep(); }
});

poll();
pollSweep();
timer = setInterval(() => {if (!document.hidden) { poll(); pollSweep(); }}, 1000);
