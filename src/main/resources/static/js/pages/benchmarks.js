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
    $('sharing').checked = value; $('sharing').disabled = false; sharingSummary();
}).catch(() => {
    $('sharing-summary').textContent = t('Nicht verfügbar');
    $('sharing-note').textContent = t('Die Freigabe konnte nicht gelesen werden. Aktualisiere die Seite, bevor du sie änderst.');
});

poll();
timer = setInterval(() => {if (!document.hidden) poll();}, 1000);
