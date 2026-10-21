/* A single physical GPU, full canvas. No navigation or legacy benchmark renderers. */
(() => {
    const {t, s, locale, node, number, metric, rate, request, activity, observe} = window.SolarMinerLab;
    const $ = id => document.getElementById(id), params = new URLSearchParams(location.search), device = params.get('device');
    let resultsView = params.get('view') === 'results', state = {workers: [], sweep: {}, sessions: [], stale: new Set()};
    let saved = {sweeps: [], benchmarks: []}, profiles = [], tuningPoints = [], pending = null, measurementStale = false, signature = '';
    const stateLabel = value => t(({COMPLETE: 'complete', FAILED: 'failed', CANCELLED: 'cancelled', SKIPPED: 'skipped', RUNNING: 'running', WAITING: 'lab_waiting', QUEUED: 'lab_waiting'})[value] || 'ready');
    const svgNS = 'http://www.w3.org/2000/svg';
    const svgNode = (tag, attrs) => { const n = document.createElementNS(svgNS, tag); for (const [key, value] of Object.entries(attrs)) n.setAttribute(key, String(value)); return n; };
    function setView(results) {
        resultsView = results; $('lab-live-view').hidden = results;
        $('lab-view-live').setAttribute('aria-pressed', String(!results)); $('lab-view-results').setAttribute('aria-pressed', String(results));
        const url = new URL(location.href); results ? url.searchParams.set('view', 'results') : url.searchParams.delete('view'); history.replaceState(null, '', url);
    }
    $('lab-view-live').addEventListener('click', () => setView(false)); $('lab-view-results').addEventListener('click', () => setView(true));
    let motion = false; try { motion = localStorage.getItem('solarminer.lab.reduce-motion') === 'true'; } catch (_) { }
    $('lab-motion').checked = motion; document.body.classList.toggle('lab-still', motion);
    $('lab-motion').addEventListener('change', () => { document.body.classList.toggle('lab-still', $('lab-motion').checked); try { localStorage.setItem('solarminer.lab.reduce-motion', String($('lab-motion').checked)); } catch (_) { } });
    function refreshMeasurements() {
        if (!device || pending) return pending;
        pending = Promise.all([request('measurements/profiles'), request('gpu-tuning/profiles'), request(`measurements/tuning-curve?deviceId=${encodeURIComponent(device)}`)])
            .then(([measurements, confirmed, points]) => { saved = measurements; profiles = confirmed.filter(row => row.deviceId === device); tuningPoints = points; measurementStale = false; })
            .catch(() => { measurementStale = true; }).finally(() => { pending = null; draw(); });
        return pending;
    }
    const pairKey = row => JSON.stringify([row.coin || row.coinId, row.softwareId || row.minerSoftwareId]);
    function points() {
        const rows = new Map();
        const add = row => rows.set(JSON.stringify([row.coin, row.softwareId, row.kind, row.limitWatts, row.hash, row.watts, row.core, row.memory, row.targetCore, row.targetMemory, row.offset]), row);
        for (const run of [...(saved.sweeps || []), ...(state.sweep.results || []), ...(state.sweep.runs || [])]) if (run.deviceId === device)
            for (const point of run.steps || []) add({coin: run.coin, softwareId: run.softwareId || run.minerSoftwareId, algorithm: run.algorithm,
                kind: 'lab_power_point', limitWatts: point.limitWatts, hash: point.medianHashrateHs, watts: point.avgPowerWatts, temp: point.maxTemperatureC,
                stable: point.stable, core: point.observedCoreMhz, memory: point.observedMemoryMhz, detail: point.note});
        for (const row of saved.benchmarks || []) if ((row.deviceId || row.workerId) === device) add({
            coin: row.coin, softwareId: row.minerSoftwareId, algorithm: row.algorithm, kind: 'oc_benchmark_point',
            hash: row.hashrateHs, watts: row.powerWatts, stable: true
        });
        for (const point of tuningPoints) add({coin: point.coin, softwareId: point.softwareId,
            kind: point.stage === 'RESULT' ? 'lab_confirmed_point' : point.stage === 'SAMPLE' ? 'lab_live_point' : 'lab_search_point', limitWatts: point.limitWatts,
            hash: point.hashrateHs, watts: point.measuredWatts, temp: point.temperatureC, stable: point.stable,
            core: point.coreMhz, memory: point.memoryMhz, targetCore: point.targetCoreMhz, targetMemory: point.targetMemoryMhz, offset: point.memoryOffsetMhz});
        return [...rows.values()];
    }
    const kinds = ['lab_power_point', 'lab_search_point', 'lab_confirmed_point', 'lab_live_point', 'oc_benchmark_point'];
    function chart(rows) {
        const root = $('gpu-chart'); root.replaceChildren();
        const valid = rows.filter(row => row.hash > 0 && row.watts > 0);
        if (!valid.length) { root.append(node('p', 'lab-empty', t('lab_no_points'))); return; }
        const width = Math.max(360, Math.min(1000, root.clientWidth - 16));
        const height = width < 600 ? 240 : 320, left = width < 600 ? 70 : 82, right = width < 600 ? 20 : 38, top = 28, bottom = 52;
        const lowW = Math.min(...valid.map(row => row.watts)) * .9, highW = Math.max(...valid.map(row => row.watts)) * 1.06;
        const highH = Math.max(...valid.map(row => row.hash)) * 1.12;
        const x = value => left + (value - lowW) / Math.max(1, highW - lowW) * (width - left - right);
        const y = value => height - bottom - value / highH * (height - top - bottom);
        const svg = svgNode('svg', {viewBox: `0 0 ${width} ${height}`, role: 'img', 'aria-label': t('lab_accessible_chart')});
        for (let i = 0; i <= 4; i++) {
            const yy = top + i / 4 * (height - top - bottom), xx = left + i / 4 * (width - left - right);
            svg.append(svgNode('line', {x1: left, x2: width - right, y1: yy, y2: yy, class: 'lab-grid-line'}));
            const hl = svgNode('text', {x: left - 12, y: yy + 4, 'text-anchor': 'end'}); hl.textContent = rate((4 - i) / 4 * highH); svg.append(hl);
            const wl = svgNode('text', {x: xx, y: height - 18, 'text-anchor': 'middle'}); wl.textContent = metric(lowW + i / 4 * (highW - lowW), 'W'); svg.append(wl);
        }
        const baseline = valid.filter(row => row.kind === 'lab_power_point' && row.stable).sort((a, b) => a.watts - b.watts);
        if (baseline.length > 1) svg.append(svgNode('polyline', {points: baseline.map(row => `${x(row.watts)},${y(row.hash)}`).join(' '), class: 'lab-curve-line'}));
        for (const row of valid) {
            const circle = svgNode('circle', {cx: x(row.watts), cy: y(row.hash), r: row.kind === 'lab_confirmed_point' ? 7 : 5,
                class: `lab-dot lab-kind-${kinds.indexOf(row.kind)}${row.stable === false ? ' unstable' : ''}`});
            const title = svgNode('title', {}); title.textContent = `${t(row.kind)} · ${rate(row.hash)} · ${metric(row.watts, 'W')} · ${metric(row.temp, '°C')}`; circle.append(title); svg.append(circle);
        }
        root.append(svg);
    }
    function records(rows) {
        const root = $('gpu-records'); root.replaceChildren();
        $('lab-record-count').textContent = number(rows.length, 0);
        if (!rows.length) { root.append(node('p', 'lab-empty', t('lab_no_points'))); return; }
        const table = node('table', 'lab-table'), head = node('thead'), headers = node('tr');
        for (const key of ['lab_stage', 'lab_limit', 'hashrate', 'lab_measured', 'lab_efficiency', 'lab_temperature', 'lab_core', 'lab_memory', 'lab_core_target', 'lab_memory_target', 'lab_clock_offset', 'lab_note']) headers.append(node('th', '', t(key)));
        head.append(headers); table.append(head); const body = node('tbody');
        for (const row of rows) {
            const tr = node('tr');
            const values = [t(row.kind) + (row.stable === false ? ` · ${t('lab_unstable')}` : ''), metric(row.limitWatts, 'W'), rate(row.hash), metric(row.watts, 'W'),
                row.hash > 0 && row.watts > 0 ? `${rate(row.hash / row.watts)}/W` : '—', metric(row.temp, '°C'), metric(row.core, 'MHz'), metric(row.memory, 'MHz'), metric(row.targetCore, 'MHz'), metric(row.targetMemory, 'MHz'), metric(row.offset, 'MHz'), row.detail ? s(row.detail) : '—'];
            values.forEach(value => tr.append(node('td', '', value))); if (row.detail) { tr.title = s(row.detail); }
            body.append(tr);
        }
        table.append(body); root.append(table);
    }
    function drawProfiles(pair) {
        const root = $('gpu-profiles'); root.replaceChildren();
        const rows = profiles.filter(row => pairKey(row) === pair);
        if (!rows.length) { root.append(node('p', 'lab-empty', t('lab_no_profiles'))); return; }
        for (const row of rows) {
            const card = node('article', 'lab-confirmed-card');
            card.append(node('small', 'lab-eyebrow', t('lab_confirmed_point')), node('h3', '', `${row.coinId} · ${row.softwareId}`),
                node('strong', 'lab-profile-value', rate(row.hashrateHs)), node('p', '', `${metric(row.measuredWatts, 'W')} · ${rate(row.hashrateHs / row.measuredWatts)}/W`),
                node('p', 'lab-fine', `${t('lab_limit')}: ${metric(row.powerLimitWatts, 'W')} · ${t('lab_core_target')}: ${metric(row.coreMhz, 'MHz')} · ${t('lab_memory_target')}: ${metric(row.memoryMhz, 'MHz')}`));
            root.append(card);
        }
    }
    function draw() {
        const worker = state.workers.find(row => row.deviceId === device), status = activity(device, state.sweep, state.sessions);
        const stale = state.stale.size > 0 || measurementStale;
        const missing = !device || state.workers.length && !worker && !status.rows.length && !profiles.length;
        $('lab-notice').hidden = !missing; $('lab-notice').textContent = missing ? t('lab_no_worker') : '';
        $('gpu-model').textContent = worker?.hardwareModel || status.rows[0]?.model || profiles[0]?.model || t('lab_workspace');
        $('gpu-id').textContent = device || '—';
        $('lab-freshness').textContent = t(stale ? 'lab_stale' : 'lab_connection_ok'); $('lab-freshness').classList.toggle('stale', stale);
        document.body.classList.toggle('is-measuring', status.active && !stale);
        $('gpu-phase').textContent = t(status.key);
        const current = status.tuning || status.optimization || status.run;
        $('gpu-task').textContent = current ? `${current.coinId || current.coin} · ${current.softwareId || '—'}${status.run?.limitWatts ? ` · ${metric(status.run.limitWatts, 'W')}` : ''}` : t('lab_not_running');
        const count = current?.sampleIndex ?? current?.samples ?? 0, total = current?.sampleCount || current?.samplesRequired || 12;
        $('gpu-samples').replaceChildren(...Array.from({length: Math.min(total, 32)}, (_, i) => node('i', i < count ? 'filled' : '')));
        $('gpu-sample-label').textContent = current ? t('lab_points', {count, total}) : '';
        if (status.tuning) $('gpu-sample-label').textContent += ` · ${t('lab_attempts', {current: status.tuning.candidateIndex, total: status.tuning.candidateCount})}`;
        const completion = status.completion;
        $('gpu-completion').replaceChildren();
        if (completion) {
            $('gpu-completion').append(node('strong', '', t(completion.released ? 'lab_device_released' : 'lab_restore_failed')),
                node('p', '', t(completion.miningState === 'STARTED' ? 'lab_mining_started' : completion.miningState === 'RESTORED' ? 'lab_restored' : 'lab_mining_failed')));
            if (completion.miningState === 'FAILED' || !completion.released) $('gpu-completion').append(node('small', '', s(completion.detail)));
        }
        const time = [];
        if (state.sweep.running && state.sweep.startedAt && status.rows.length) {
            time.push(t('lab_elapsed', {minutes: number((Date.now() - Date.parse(state.sweep.startedAt)) / 60000, 0)}));
            if (state.sweep.secondsRemaining > 0) time.push(t('lab_minute', {minutes: number(Math.ceil(state.sweep.secondsRemaining / 60), 0)}));
        }
        $('gpu-time').textContent = time.join(' · ');
        const telemetry = stale ? null : worker?.telemetry;
        const metrics = [['hashrate', rate(telemetry?.terahashPerSecond == null ? null : telemetry.terahashPerSecond * 1e12)],
            ['lab_measured', metric(telemetry?.approximatedPowerUsageWatts, 'W')], ['lab_temperature', metric(telemetry?.temperatureCelsius, '°C')],
            ['lab_limit', metric(current?.currentPowerWatts ?? status.run?.limitWatts, 'W')],
            ['lab_core_target', metric(status.tuning?.currentCoreMhz, 'MHz')], ['lab_memory_target', metric(status.tuning?.currentMemoryMhz, 'MHz')]];
        $('gpu-metrics').replaceChildren(...metrics.map(([key, value]) => { const item = node('article'); item.append(node('span', '', t(key)), node('strong', '', value)); return item; }));
        const runList = $('gpu-run-list'); runList.replaceChildren();
        if (status.rows.length) {
            runList.append(node('h3', '', t('lab_test_queue')));
            for (const row of status.rows) {
                const optimization = (state.sweep.optimization?.runs || []).find(item => item.deviceId === device && item.coin === row.coin && item.softwareId === row.softwareId);
                const item = node('div', 'lab-run-row');
                item.append(node('strong', '', `${row.coin} · ${row.softwareId || '—'}`), node('span', '', stateLabel(row.status)),
                    node('small', '', s(row.detail)));
                if (optimization) item.append(node('small', '', `${t('lab_refine')}: ${stateLabel(optimization.state)} · ${s(optimization.detail)}`));
                runList.append(item);
            }
        }
        const all = points(), combinations = new Map();
        for (const row of [...all, ...profiles]) combinations.set(pairKey(row), `${row.coin || row.coinId} · ${row.softwareId || row.minerSoftwareId || '—'}`);
        const select = $('lab-combination'), old = select.value;
        select.replaceChildren(...[...combinations].map(([key, text]) => { const option = node('option', '', text); option.value = key; return option; }));
        if (combinations.has(old)) select.value = old;
        select.disabled = combinations.size < 2;
        const rows = all.filter(row => pairKey(row) === select.value);
        chart(rows); records(rows); drawProfiles(select.value);
        $('lab-legend').replaceChildren(...kinds.map((kind, i) => node('span', `lab-legend-item lab-kind-${i}`, t(kind))));
    }
    $('lab-combination').addEventListener('change', draw);
    window.addEventListener('resize', draw);
    setView(resultsView);
    if (!device) { draw(); return; }
    observe(value => {
        state = value;
        const next = JSON.stringify([value.sweep.startedAt, value.sweep.running, (value.sweep.runs || []).filter(row => row.deviceId === device).map(row => row.steps.length), value.sessions.filter(row => row.deviceId === device).map(row => [row.running, row.candidateIndex, row.sampleIndex])]);
        if (signature !== next) { signature = next; refreshMeasurements(); }
        draw();
    });
    refreshMeasurements();
})();
