/* Fleet-first workspace. Measurement and driver commands remain owned by their existing controllers. */
(() => {
    const byId = id => document.getElementById(id);
    const {t, s, locale} = window.SolarMinerI18n;
    const ui = window.SolarMinerUI;
    const el = (tag, cls, text) => { const n = document.createElement(tag); if (cls) n.className = cls; if (text != null) n.textContent = text; return n; };
    const number = value => new Intl.NumberFormat(locale, {maximumFractionDigits: 2}).format(value);
    const metric = (value, unit) => value != null && Number.isFinite(Number(value)) ? `${number(value)} ${unit}` : '—';
    const rate = value => value != null && Number.isFinite(Number(value)) ? ui.hashrate(value) : '—';
    let workers = [], profiles = [], snapshot = {sweeps: [], benchmarks: []}, sessions = [];
    let sweep = {}, benchmark = {}, page = 0, profileRequest = null;
    const failures = new Set(), expanded = new Set();
    const pageSize = 12;
    const failure = (key, meta) => { if (meta?.error || meta?.stale) failures.add(key); else failures.delete(key); };
    async function measurements() {
        if (profileRequest) return profileRequest;
        profileRequest = readMeasurements().then(value => {
            snapshot = value; failures.delete('measurements');
        }).catch(() => failures.add('measurements')).finally(() => { profileRequest = null; draw(); });
        return profileRequest;
    }
    function openSection(id) {
        const section = byId(id); section.open = true;
        section.scrollIntoView({behavior: 'smooth', block: 'start'});
    }
    function openCurves(deviceId) {
        const picker = byId('curve-gpu');
        if ([...picker.options].some(option => option.value === deviceId)) {
            picker.value = deviceId; picker.dispatchEvent(new Event('change'));
        }
        openSection('oc-curves');
        // The short-benchmark telemetry panel has a separate selected worker.
        const tab = [...document.querySelectorAll('#benchmark-worker-tabs button')].find(button => button.dataset.workerId === deviceId);
        if (tab) tab.click();
    }
    let expertLoaded = false;
    function expert(deviceId) {
        window.SolarMinerOCDevice = deviceId;
        openSection('oc-expert');
        for (const card of byId('devices').children) card.open = card.dataset.deviceId === deviceId;
    }
    byId('oc-expert').addEventListener('toggle', () => {
        if (!byId('oc-expert').open || expertLoaded) return;
        expertLoaded = true;
        const script = document.createElement('script'); script.src = '/js/pages/autotuning.js?v=20261021-oc';
        script.onerror = () => { expertLoaded = false; byId('tuning-notice').hidden = false; byId('tuning-notice').textContent = t('oc_partial'); };
        document.head.append(script);
    });
    function routeHash() {
        const hash = location.hash;
        if (hash === '#worker-selection') openSection('oc-configuration');
        if (hash === '#oc-expert') openSection('oc-expert');
        if (hash === '#oc-curves') openSection('oc-curves');
    }
    window.addEventListener('hashchange', routeHash); routeHash();
    document.querySelector('a[href="#worker-selection"]').addEventListener('click', () => openSection('oc-configuration'));
    function pointsFor(worker) {
        const points = [];
        const add = (coin, software, algorithm, hash, watts, kind) => {
            if (!(hash > 0) || !(watts > 0)) return;
            points.push({coin, software, algorithm, hash, watts, kind, score: hash / watts});
        };
        for (const row of snapshot.sweeps || []) if (row.deviceId === worker.deviceId) {
            for (const step of row.steps || []) if (step.stable) add(row.coin, row.minerSoftwareId, row.algorithm, step.medianHashrateHs, step.avgPowerWatts, 'oc_sweep_point');
        }
        for (const row of snapshot.benchmarks || []) if ((row.deviceId || row.workerId) === worker.deviceId) {
            add(row.coin, row.minerSoftwareId, row.algorithm, row.hashrateHs, row.powerWatts, 'oc_benchmark_point');
        }
        for (const row of profiles) if (row.deviceId === worker.deviceId) add(row.coinId, row.softwareId, row.algorithm, row.hashrateHs, row.measuredWatts, 'oc_confirmed');
        for (const row of sweep.runs || []) if (row.deviceId === worker.deviceId) {
            for (const step of row.steps || []) if (step.stable) add(row.coin, row.softwareId || row.minerSoftwareId, row.algorithm, step.medianHashrateHs, step.avgPowerWatts, 'oc_sweep_point');
        }
        const best = new Map();
        for (const point of points) {
            const key = JSON.stringify([point.coin, point.software, point.algorithm]);
            if (!best.has(key) || point.score > best.get(key).score || point.score === best.get(key).score && point.kind === 'oc_confirmed') best.set(key, point);
        }
        for (const point of best.values()) {
            const baseline = (snapshot.sweeps || []).find(row => row.deviceId === worker.deviceId
                && row.coin === point.coin && row.algorithm === point.algorithm && row.minerSoftwareId === point.software);
            const first = (baseline?.steps || []).filter(step => step.stable && step.avgPowerWatts > 0 && step.medianHashrateHs > 0)
                .sort((a, b) => b.limitWatts - a.limitWatts)[0];
            if (first) {
                const gain = (point.score / (first.medianHashrateHs / first.avgPowerWatts) - 1) * 100;
                if (gain >= 0.1) point.gain = t('oc_gain', {percent: number(gain), watts: number(first.limitWatts)});
            }
        }
        // Deterministic order, never a ranking across unrelated algorithms.
        return [...best.values()].sort((a, b) => `${a.coin}|${a.software}|${a.algorithm}`.localeCompare(`${b.coin}|${b.software}|${b.algorithm}`));
    }
    function draw() {
        const activeIds = new Set([
            ...(sweep.running ? (sweep.runs || []).filter(row => row.status === 'RUNNING').map(row => row.deviceId) : []),
            ...(sweep.running ? (sweep.optimization?.runs || []).filter(row => row.state === 'RUNNING').map(row => row.deviceId) : []),
            ...sessions.filter(row => row.running).map(row => row.deviceId),
            ...(benchmark.running ? benchmark.selectedWorkers || [] : [])
        ]);
        const stable = new Set();
        for (const run of [...(snapshot.sweeps || []), ...(sweep.runs || [])]) for (const step of run.steps || []) {
            if (step.stable) stable.add(JSON.stringify([run.deviceId, run.coin, run.algorithm, run.minerSoftwareId || run.softwareId, step.limitWatts, step.medianHashrateHs, step.avgPowerWatts]));
        }
        const resultIds = new Set([...(snapshot.sweeps || []).filter(row => row.steps?.some(step => step.stable)).map(row => row.deviceId),
            ...(snapshot.benchmarks || []).map(row => row.deviceId || row.workerId), ...profiles.map(row => row.deviceId)]);
        byId('oc-active').textContent = activeIds.size;
        byId('oc-activity').textContent = t(activeIds.size ? 'oc_testing' : 'oc_idle', {count: activeIds.size});
        byId('oc-points').textContent = stable.size;
        byId('oc-profiles').textContent = profiles.length;
        byId('oc-coverage').textContent = `${workers.filter(row => resultIds.has(row.deviceId)).length} / ${workers.length}`;
        byId('oc-freshness').textContent = failures.size ? t('oc_partial') : t('oc_updated', {time: new Date().toLocaleTimeString(locale)});
        const search = byId('oc-search').value.toLocaleLowerCase(locale), filter = byId('oc-filter').value;
        const rows = workers.filter(worker => `${worker.hardwareModel} ${worker.deviceId} ${worker.coin || ''}`.toLocaleLowerCase(locale).includes(search)
            && (filter !== 'active' || activeIds.has(worker.deviceId)) && (filter !== 'results' || resultIds.has(worker.deviceId)));
        page = Math.max(0, Math.min(page, Math.ceil(rows.length / pageSize) - 1));
        const root = byId('oc-workers');
        // Preserve keyboard focus when live values arrive.
        const focused = root.contains(document.activeElement) ? document.activeElement.dataset.focus : null;
        root.replaceChildren();
        if (!rows.length) root.append(el('p', 'empty', t('oc_empty')));
        for (const worker of rows.slice(page * pageSize, (page + 1) * pageSize)) {
            const card = el('article', 'oc-worker'); card.dataset.deviceId = worker.deviceId;
            const heading = el('div', 'oc-worker-head'), identity = el('div');
            identity.append(el('h3', '', worker.hardwareModel || worker.deviceId), el('small', 'muted', worker.deviceId));
            heading.append(identity, el('span', activeIds.has(worker.deviceId) ? 'tag ready' : 'tag', activeIds.has(worker.deviceId) ? t('running') : s(worker.status || 'STOPPED')));
            card.append(heading);
            const telemetry = worker.telemetry;
            const live = el('div', 'oc-live');
            const reading = (key, value) => { const n = el('div'); n.append(el('small', '', t(key)), el('strong', '', value)); live.append(n); };
            reading('hashrate', telemetry ? rate(telemetry.terahashPerSecond == null ? null : telemetry.terahashPerSecond * 1e12) : '—');
            reading('auto_tuning_graph_power', telemetry ? metric(telemetry.approximatedPowerUsageWatts, 'W') : '—');
            reading('auto_tuning_graph_temperature', telemetry ? metric(telemetry.temperatureCelsius, '°C') : '—');
            live.setAttribute('aria-label', t('oc_live')); card.append(live);
            const tests = [...(sweep.runs || []).filter(row => row.deviceId === worker.deviceId), ...(sweep.optimization?.runs || []).filter(row => row.deviceId === worker.deviceId)];
            if (tests.length) {
                const done = tests.filter(row => ['COMPLETE', 'FAILED', 'SKIPPED', 'CANCELLED'].includes(row.status || row.state)).length;
                const label = t('oc_progress', {done, total: tests.length});
                const progress = el('progress'); progress.max = tests.length; progress.value = done; progress.setAttribute('aria-label', label);
                card.append(el('p', 'oc-run-progress', label), progress);
            }
            const measuring = tests.find(row => row.status === 'RUNNING' && row.samplesRequired > 0);
            if (measuring) card.append(el('p', 'muted', t('benchmark_live_points', {count: measuring.samples || 0, required: measuring.samplesRequired})));
            const session = sessions.find(row => row.deviceId === worker.deviceId && row.running);
            if (session) card.append(el('p', 'muted', `${s(session.phase)} · ${t('oc_attempt', {current: session.candidateIndex || 0, total: session.candidateCount || 0})}`));
            const points = pointsFor(worker);
            const bestHost = el('div', 'oc-best');
            bestHost.append(el('p', 'kicker', t('oc_best'))); bestHost.title = t('oc_best_hint');
            const addPoint = point => {
                const line = el('div', 'oc-best-point');
                line.append(el('strong', '', `${point.coin ? point.coin.toUpperCase() : point.algorithm || '—'} · ${point.software || '—'}`),
                    el('span', '', `${rate(point.hash)} · ${metric(point.watts, 'W')}`),
                    el('b', '', `${rate(point.score)}/W`), el('small', '', t(point.kind)));
                if (point.gain) line.append(el('span', 'oc-gain', point.gain));
                bestHost.append(line);
            };
            if (points.length) {
                addPoint(points[0]);
                if (points.length > 1) {
                    const disclosure = el('details', 'oc-combinations'); disclosure.open = expanded.has(worker.deviceId);
                    disclosure.append(el('summary', '', t('oc_more_results', {count: points.length})));
                    for (const point of points.slice(1)) { const host = el('div'); addPoint(point); host.append(bestHost.lastElementChild); disclosure.append(host); }
                    disclosure.addEventListener('toggle', () => { if (disclosure.open) expanded.add(worker.deviceId); else expanded.delete(worker.deviceId); });
                    bestHost.append(disclosure);
                }
            } else bestHost.append(el('p', 'muted', t('oc_no_results')));
            card.append(bestHost);
            const actions = el('div', 'row-actions');
            const action = (key, fn) => { const button = el('button', 'button subtle', t(key)); button.type = 'button'; button.dataset.focus = `${worker.deviceId}:${key}`; button.addEventListener('click', fn); actions.append(button); };
            action('oc_details', () => openCurves(worker.deviceId));
            if (worker.hardwareType === 'GPU') action('oc_device_tuning', () => expert(worker.deviceId));
            card.append(actions); root.append(card);
        }
        if (focused) [...root.querySelectorAll('button')].find(button => button.dataset.focus === focused)?.focus({preventScroll: true});
        byId('oc-page').textContent = t('oc_page', {from: rows.length ? page * pageSize + 1 : 0, to: Math.min(rows.length, (page + 1) * pageSize), total: rows.length});
        byId('oc-prev').disabled = page === 0; byId('oc-next').disabled = (page + 1) * pageSize >= rows.length;
    }
    for (const id of ['oc-search', 'oc-filter']) byId(id).addEventListener('input', () => { page = 0; draw(); });
    byId('oc-prev').addEventListener('click', () => { page--; draw(); });
    byId('oc-next').addEventListener('click', () => { page++; draw(); });
    const live = window.SolarMinerLive;
    live.channel('workers', {endpoint: '/api/agent/local/workers', maxAgeMs: 5000, persist: false}).subscribe((value, meta) => { failure('workers', meta); if (Array.isArray(value)) workers = value; draw(); });
    live.channel('oc-profiles', {endpoint: '/api/agent/local/gpu-tuning/profiles', maxAgeMs: 15000, persist: false, transport: 'rest', pollMs: 15000}).subscribe((value, meta) => { failure('profiles', meta); if (Array.isArray(value)) profiles = value; draw(); });
    live.channel('oc-sessions', {endpoint: '/api/agent/local/gpu-tuning/sessions', maxAgeMs: 4000, persist: false, transport: 'rest', pollMs: 4000}).subscribe((value, meta) => {
        failure('sessions', meta);
        if (Array.isArray(value)) { const finished = sessions.some(row => row.running) && !value.some(row => row.running); sessions = value; window.SolarMinerOCRunning = value.some(row => row.running); updateMeasurementControls(); if (finished) { invalidateMeasurements(); measurements(); live.refresh('oc-profiles'); } }
        draw();
    });
    live.channel('efficiency').subscribe((value, meta) => { failure('sweep', meta); if (value) { const changed = sweep.running !== value.running || JSON.stringify((sweep.runs || []).map(row => row.steps)) !== JSON.stringify((value.runs || []).map(row => row.steps)); sweep = value; if (changed) { invalidateMeasurements(); measurements(); } } draw(); });
    live.channel('benchmarks').subscribe((value, meta) => { failure('benchmark', meta); if (value) { const finished = benchmark.running && !value.running; benchmark = value; if (finished) measurements(); } draw(); });
    byId('refresh').addEventListener('click', () => { measurements(); for (const key of ['workers', 'oc-profiles', 'oc-sessions']) live.refresh(key); });
    measurements();
})();
