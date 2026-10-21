/* View primitives only; measurement policy, safety and scheduling belong to the agent. */
(() => {
    const {t, s, locale} = window.SolarMinerI18n;
    const node = (tag, cls, value) => { const el = document.createElement(tag); if (cls) el.className = cls; if (value != null) el.textContent = value; return el; };
    const number = (value, digits = 1) => new Intl.NumberFormat(locale, {maximumFractionDigits: digits}).format(value);
    const metric = (value, unit) => value != null && Number.isFinite(value) ? `${number(value)} ${unit}` : '—';
    const rate = value => {
        if (value == null || !Number.isFinite(value)) return '—';
        const units = ['H/s', 'kH/s', 'MH/s', 'GH/s', 'TH/s', 'PH/s'];
        let i = 0; while (value >= 1000 && i < units.length - 1) { value /= 1000; i++; }
        return `${number(value, 2)} ${units[i]}`;
    };
    async function request(path, options) {
        const response = await fetch(`/api/agent/local/${path}`, {cache: 'no-store', ...options});
        const body = await response.json().catch(() => null);
        if (!response.ok) throw new Error(body?.message || body?.detail || 'lab_request_failed');
        return body;
    }
    const write = (path, body, method = 'POST') => request(path, {method, headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
    const workerUrl = (device, results = false) => `/gpu-lab.html?device=${encodeURIComponent(device)}${results ? '&view=results' : ''}`;
    const terminal = row => ['COMPLETE', 'CANCELLED', 'SKIPPED', 'FAILED'].includes(row.status || row.state);
    function activity(device, sweep, sessions = []) {
        const completion = (sweep.devices || []).find(row => row.deviceId === device);
        const tuning = sessions.find(row => row.deviceId === device && row.running);
        const optimization = (sweep.optimization?.runs || []).find(row => row.deviceId === device && row.state === 'RUNNING');
        const run = (sweep.runs || []).find(row => row.deviceId === device && row.status === 'RUNNING');
        const rows = (sweep.runs || []).filter(row => row.deviceId === device);
        const pending = sweep.running && rows.some(row => !terminal(row));
        const active = !completion && (run || optimization || tuning);
        const key = completion ? 'lab_device_released' : tuning || optimization ? tuning?.sampleCount === 32 ? 'lab_confirming' : 'lab_tuning'
            : run ? 'lab_sweeping' : pending ? 'lab_waiting' : rows.length ? 'lab_finished' : 'lab_ready';
        return {key: completion && !completion.released ? 'lab_restore_failed' : key, active: Boolean(active), pending, run, tuning, optimization, completion, rows};
    }
    function observe(onChange) {
        const state = {workers: [], sweep: {}, sessions: [], stale: new Set()};
        const live = window.SolarMinerLive;
        for (const [key, name, endpoint, rest] of [
            ['workers', 'workers', 'workers'], ['sweep', 'efficiency', 'efficiency'],
            ['sessions', 'lab-tuning-sessions', 'gpu-tuning/sessions', true]
        ]) live.channel(name, {endpoint: `/api/agent/local/${endpoint}`, persist: false, maxAgeMs: 6000,
            ...(rest ? {transport: 'rest', pollMs: 4000} : {})}).subscribe((value, meta) => {
            if (meta?.error || meta?.stale) state.stale.add(key); else state.stale.delete(key);
            if (value) state[key] = value;
            onChange(state);
        });
        live.start();
        return state;
    }
    window.SolarMinerLab = {t, s, locale, node, number, metric, rate, request, write, workerUrl, terminal, activity, observe};
})();
