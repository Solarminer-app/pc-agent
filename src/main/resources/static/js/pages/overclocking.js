/* Guided setup and links only. GPU telemetry/results live in their own browser tabs. */
(() => {
    const {t, s, node, request, write, workerUrl, activity, observe} = window.SolarMinerLab;
    const $ = id => document.getElementById(id);
    const steps = ['lab_step_workers', 'lab_step_preset', 'lab_step_scope', 'lab_step_review'];
    const presets = [['QUICK', 'lab_quick', 'lab_quick_time', 'lab_quick_help'], ['MEDIUM', 'lab_medium', 'lab_medium_time', 'lab_medium_help'], ['SLOW', 'lab_slow', 'lab_slow_time', 'lab_slow_help']];
    let step = 0, busy = false, policies = {}, workers = [], state = {workers: [], sweep: {}, sessions: [], stale: new Set()}, loaded = false;
    const selected = new Set(), editors = new Map(), options = new Map();
    function notice(value) { $('lab-notice').hidden = !value; $('lab-notice').textContent = value ? s(value) : ''; }
    const presetName = () => presets.find(row => row[0] === document.querySelector('[name="lab-preset"]:checked')?.value) || presets[1];
    function renderStep(focus = false) {
        document.querySelectorAll('[data-step]').forEach(el => { el.hidden = Number(el.dataset.step) !== step; });
        $('lab-steps').replaceChildren(...steps.map((key, i) => {
            const el = node('li', i === step ? 'current' : i < step ? 'done' : '', t(key));
            if (i === step) el.setAttribute('aria-current', 'step');
            el.prepend(node('span', '', i < step ? '✓' : String(i + 1))); return el;
        }));
        $('lab-back').hidden = step === 0; $('lab-back').disabled = busy;
        $('lab-next').textContent = t(busy ? 'lab_launch_busy' : step === 3 ? 'lab_launch' : 'oc_next');
        $('lab-next').disabled = !loaded || busy || state.sweep.running || state.sessions.some(row => row.running);
        $('lab-step-count').textContent = t('lab_step', {current: step + 1, total: steps.length});
        if (focus) { const heading = document.querySelector(`[data-step="${step}"] h2`); heading.tabIndex = -1; heading.focus({preventScroll: true}); }
    }
    function renderPicker() {
        const root = $('lab-worker-picker'); root.replaceChildren();
        if (!workers.length) root.append(node('p', 'lab-empty', t('lab_empty')));
        for (const worker of workers) {
            const label = node('label', 'lab-gpu-choice'), input = node('input'); input.type = 'checkbox'; input.value = worker.deviceId;
            input.checked = selected.has(worker.deviceId);
            const text = node('span'); text.append(node('strong', '', worker.hardwareModel || worker.deviceId), node('small', 'lab-mono', worker.deviceId));
            label.append(input, node('b', 'lab-chip-icon', '▦'), text);
            input.addEventListener('change', () => { input.checked ? selected.add(worker.deviceId) : selected.delete(worker.deviceId); });
            root.append(label);
        }
    }
    async function load() {
        $('lab-retry').hidden = true; loaded = false; renderStep();
        try {
            const [available, saved] = await Promise.all([request('workers'), request('benchmark-policies')]);
            workers = available.filter(row => row.hardwareType === 'GPU'); policies = saved;
            selected.forEach(id => { if (!workers.some(row => row.deviceId === id)) selected.delete(id); });
            renderPicker(); loaded = true; notice(null);
        } catch (error) { notice(error.message); $('lab-retry').hidden = false; }
        renderStep();
    }
    async function scope() {
        const root = $('lab-scope'); root.replaceChildren();
        // Fetch only the selected GPUs and keep edited values when stepping backwards.
        for (const worker of workers.filter(row => selected.has(row.deviceId))) {
            let editor = editors.get(worker.deviceId);
            if (!editor) {
                let choices = options.get(worker.deviceId);
                if (!choices) { choices = await request(`benchmark-policies/options/${encodeURIComponent(worker.deviceId)}`); options.set(worker.deviceId, choices); }
                const saved = policies[worker.deviceId];
                const card = node('fieldset', 'lab-scope-card'); card.append(node('legend', '', worker.hardwareModel));
                const list = node('div', 'lab-test-choices'), checks = [];
                for (const miner of choices) {
                    const label = node('label'), input = node('input'); input.type = 'checkbox';
                    input.disabled = !miner.installed || !miner.selectable;
                    input.checked = !input.disabled && (saved ? saved.combinations.some(pair => pair.coin === miner.coin && pair.minerSoftwareId === miner.id) : true);
                    const text = node('span'); text.append(node('strong', '', `${miner.coinName} · ${miner.name}`));
                    if (input.disabled) text.append(node('small', '', !miner.installed ? t('not_installed') : s(miner.unavailableReason || 'currently_unavailable_for_assignment')));
                    label.append(input, text); list.append(label); checks.push({input, miner});
                }
                const label = node('label', 'lab-temperature', t('benchmark_gpu_temperature')), temp = node('input');
                temp.type = 'number'; temp.min = '40'; temp.max = '90'; temp.step = '1'; temp.required = true; temp.value = saved?.maxTemperatureC ?? 80;
                temp.setAttribute('aria-label', t('benchmark_gpu_temperature')); label.append(temp); card.append(list, label);
                editor = {card, checks, temp, userEdited: false}; editors.set(worker.deviceId, editor);
                card.addEventListener('input', () => { editor.userEdited = true; });
            }
            root.append(editor.card);
        }
    }
    function readPolicy(id) {
        const editor = editors.get(id), saved = policies[id];
        const combinations = editor.checks.filter(row => row.input.checked && !row.input.disabled).map(({miner}) => ({coin: miner.coin, minerSoftwareId: miner.id}));
        if (!combinations.length) throw new Error('lab_no_combinations');
        if (!editor.temp.reportValidity()) throw new Error('benchmark_temperature_invalid');
        return {combinations, maxTemperatureC: Number(editor.temp.value), allowCoinSwitch: saved?.allowCoinSwitch ?? false, allowMinerSwitch: saved?.allowMinerSwitch ?? false};
    }
    function review() {
        const root = $('lab-review'); root.replaceChildren(node('h3', '', t('lab_review_summary', {count: selected.size, preset: t(presetName()[1]), parallel: $('lab-parallel').value})));
        for (const worker of workers.filter(row => selected.has(row.deviceId))) {
            const policy = readPolicy(worker.deviceId), item = node('div', 'lab-review-row');
            item.append(node('strong', '', worker.hardwareModel), node('span', '', t('lab_review_tests', {count: policy.combinations.length, temperature: policy.maxTemperatureC})),
                node('small', 'lab-mono', policy.combinations.map(row => `${row.coin} · ${row.minerSoftwareId}`).join(' / '))); root.append(item);
        }
        for (const id of ['lab-refine', 'lab-autostart']) root.append(node('p', '', `${$(id).checked ? '✓' : '—'} ${t(id === 'lab-refine' ? 'lab_refine' : 'lab_auto_start')}`));
    }
    async function launch() {
        busy = true; renderStep(); notice(null);
        const form = $('lab-form');
        try {
            const scopePolicies = [...selected].map(id => [id, readPolicy(id)]);
            for (const input of form.querySelectorAll('input')) input.disabled = true;
            for (const [id, policy] of scopePolicies) {
                // Refresh independent automatic permissions instead of overwriting another tab's changes.
                const latest = (await request('benchmark-policies'))[id];
                if (latest) { policy.allowCoinSwitch = latest.allowCoinSwitch; policy.allowMinerSwitch = latest.allowMinerSwitch; }
                await write(`benchmark-policies/${encodeURIComponent(id)}`, policy, 'PUT'); policies[id] = policy;
            }
            const coins = [...new Set(scopePolicies.flatMap(([, policy]) => policy.combinations.map(row => row.coin)))];
            state.sweep = await write('efficiency', {workers: [...selected], coins, maxConcurrentGpus: Number($('lab-parallel').value),
                autoTuneAfterSweep: $('lab-refine').checked, preset: presetName()[0], autoStartMining: $('lab-autostart').checked});
            $('lab-wizard').hidden = true; $('lab-worker-links').tabIndex = -1; $('lab-worker-links').focus(); renderLive();
        } catch (error) { notice(error.message); }
        finally { busy = false; for (const input of form.querySelectorAll('input')) input.disabled = false;
            // Restore unavailable tests' disabled state.
            for (const editor of editors.values()) for (const row of editor.checks) row.input.disabled = !row.miner.installed || !row.miner.selectable;
            renderStep(); window.SolarMinerLive.refresh('efficiency'); }
    }
    $('lab-form').addEventListener('submit', async event => {
        event.preventDefault(); if (busy) return; notice(null);
        try {
            if (!selected.size) throw new Error('benchmark_select_gpu_for_sweep');
            if (step === 0) {
                busy = true; renderStep();
                const pickerInputs = [...$('lab-worker-picker').querySelectorAll('input')];
                pickerInputs.forEach(input => { input.disabled = true; });
                try { await scope(); }
                finally { pickerInputs.forEach(input => { input.disabled = false; }); busy = false; }
            }
            if (step === 1) {
                for (const worker of workers.filter(row => selected.has(row.deviceId))) {
                    const editor = editors.get(worker.deviceId);
                    if (editor.userEdited) continue;
                    if (presetName()[0] === 'QUICK') {
                        const ready = editor.checks.filter(row => !row.input.disabled);
                        const preferred = ready.find(row => row.miner.coin === worker.coin && row.miner.id === worker.minerSoftwareId)
                            || ready.find(row => row.miner.coin === worker.coin) || ready[0];
                        for (const row of editor.checks) row.input.checked = row === preferred;
                    } else for (const row of editor.checks) row.input.checked = !row.input.disabled && (policies[worker.deviceId]
                        ? policies[worker.deviceId].combinations.some(pair => pair.coin === row.miner.coin && pair.minerSoftwareId === row.miner.id) : true);
                }
            }
            if (step === 2) { for (const id of selected) readPolicy(id); if (!$('lab-parallel').reportValidity()) return; review(); }
            if (step === 3) { await launch(); return; }
            step++; renderStep(true);
        } catch (error) { busy = false; notice(error.message); renderStep(); }
    });
    $('lab-back').addEventListener('click', () => { if (!busy) { step = Math.max(0, step - 1); notice(null); renderStep(true); } });
    $('lab-new').addEventListener('click', () => { $('lab-wizard').hidden = false; step = 0; renderStep(true); });
    $('lab-retry').addEventListener('click', load);
    $('refresh').addEventListener('click', () => { load(); for (const name of ['workers', 'efficiency', 'lab-tuning-sessions']) window.SolarMinerLive.refresh(name); });
    $('lab-cancel').addEventListener('click', async () => {
        $('lab-cancel').disabled = true;
        try { await write('efficiency/cancel', {}); notice('lab_cancel_requested'); }
        catch (error) { notice(error.message); }
        finally { $('lab-cancel').disabled = false; window.SolarMinerLive.refresh('efficiency'); }
    });
    for (const [value, name, time, description] of presets) {
        const label = node('label', 'lab-preset'), input = node('input'); input.type = 'radio'; input.name = 'lab-preset'; input.value = value; input.checked = value === 'MEDIUM';
        label.append(input, node('small', 'lab-eyebrow', t(time)), node('strong', '', t(name)), node('span', '', t(description))); $('lab-presets').append(label);
    }
    try {
        const preferred = localStorage.getItem('solarminer.lab.preset');
        const radio = [...document.querySelectorAll('[name="lab-preset"]')].find(input => input.value === preferred);
        if (radio) radio.checked = true;
        $('lab-autostart').checked = localStorage.getItem('solarminer.lab.auto-start') === 'true';
    } catch (_) { }
    $('lab-presets').addEventListener('change', () => { try { localStorage.setItem('solarminer.lab.preset', presetName()[0]); } catch (_) { } });
    $('lab-autostart').addEventListener('change', () => { try { localStorage.setItem('solarminer.lab.auto-start', String($('lab-autostart').checked)); } catch (_) { } });
    function renderLive() {
        const sweep = state.sweep, root = $('lab-worker-links');
        const focus = root.contains(document.activeElement) ? document.activeElement.href : null;
        root.replaceChildren();
        const ids = new Set([...(state.workers || []).filter(row => row.hardwareType === 'GPU').map(row => row.deviceId), ...(sweep.runs || []).map(row => row.deviceId)]);
        for (const id of ids) {
            const worker = state.workers.find(row => row.deviceId === id), status = activity(id, sweep, state.sessions);
            const card = node('article', 'lab-worker-link'); card.dataset.deviceId = id;
            card.classList.toggle('is-active', status.active); card.append(node('div', 'lab-link-indicator'));
            const identity = node('div'); identity.append(node('h3', '', worker?.hardwareModel || status.rows[0]?.model || id), node('small', 'lab-mono', id), node('p', 'lab-state', t(status.key))); card.append(identity);
            const links = node('div', 'lab-link-actions');
            for (const results of [false, true]) { const link = node('a', 'lab-button', t(results ? 'lab_open_results' : 'lab_open_worker')); link.href = workerUrl(id, results); link.target = '_blank'; link.rel = 'noopener'; links.append(link); }
            card.append(links); root.append(card);
        }
        if (focus) [...root.querySelectorAll('a')].find(link => link.href === focus)?.focus({preventScroll: true});
        $('lab-session-state').textContent = sweep.startedAt ? s(sweep.phase) : t('lab_ready');
        $('lab-run-actions').hidden = !sweep.running; $('lab-new').hidden = sweep.running || !$('lab-wizard').hidden;
        $('connection').textContent = t(state.stale.size ? 'lab_stale' : 'lab_connection_ok'); $('connection').classList.toggle('offline', state.stale.size > 0);
        renderStep();
    }
    observe(value => { state = value; if (value.sweep.running && !busy) $('lab-wizard').hidden = true; renderLive(); });
    renderStep(); load();
})();
