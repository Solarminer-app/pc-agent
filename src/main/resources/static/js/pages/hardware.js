const $ = id => document.getElementById(id);
const i18n = window.SolarMinerI18n;
const fmt = value => new Intl.NumberFormat(i18n.locale, {maximumFractionDigits: 0}).format(value);
const node = (tag, cls, value) => {
    const el = document.createElement(tag);
    el.className = cls || '';
    if (value !== undefined) el.textContent = i18n.t(value);
    return el;
};
const drafts = new Map();
let refreshing = false, saving = false;

function notice(message, error = false, kind = 'action') {
    const box = $('notice');
    box.hidden = false;
    box.dataset.kind = kind;
    box.className = 'notice' + (error ? ' error' : '');
    box.textContent = i18n.t(message);
}

function rangeControl(gpu) {
    const wrap = node('div'), control = node('div', 'dual-range');
    const track = node('div', 'dual-range-track'), selected = node('div', 'dual-range-selected');
    track.append(selected);
    const limit = document.createElement('input');
    limit.type = 'range';
    limit.min = String(gpu.driverMinPowerLimitWatts);
    limit.max = String(gpu.driverMaxPowerLimitWatts);
    limit.step = '1';
    limit.className = 'dual-range-input';
    limit.disabled = false;
    limit.value = String(gpu.userMaxPowerLimitWatts);
    limit.setAttribute('aria-label', gpu.model + ': ' + i18n.t('gpu_power_limit'));
    const values = node('div', 'dual-range-values'), actions = node('div', 'hardware-actions');
    const save = node('button', 'button primary', 'apply_limits');
    save.type = 'button';
    save.disabled = true;
    const reset = node('button', 'button subtle', 'discard');
    reset.type = 'button';
    reset.disabled = true;
    const state = node('small', '', 'power_limit_saved');
    const draw = () => {
        const minimum = Number(limit.min), maximum = Number(limit.max);
        const value = Math.min(maximum, Math.max(minimum, Math.round(Number(limit.value) || minimum)));
        limit.value = String(value);
        const span = maximum - minimum;
        const position = span ? 100 * (value - minimum) / span : 100;
        selected.style.left = '0%';
        selected.style.right = (100 - position) + '%';
        const rangeLabel = node('span');
        rangeLabel.textContent = i18n.t('driver_power_range', {min: fmt(minimum), max: fmt(maximum)});
        const valueLabel = node('strong');
        valueLabel.textContent = i18n.t('power_limit_watts', {watts: fmt(value)});
        values.replaceChildren(rangeLabel, valueLabel);
    };
    const edit = () => {
        draw();
        const dirty = Number(limit.value) !== gpu.userMaxPowerLimitWatts;
        if (dirty) drafts.set(gpu.deviceId, Number(limit.value)); else drafts.delete(gpu.deviceId);
        save.disabled = reset.disabled = !dirty;
        state.classList.toggle('hardware-draft', dirty);
        state.textContent = i18n.t(dirty ? 'not_applied_yet' : 'power_limit_saved');
    };
    limit.addEventListener('input', edit);
    reset.addEventListener('click', () => {
        drafts.delete(gpu.deviceId);
        limit.value = gpu.userMaxPowerLimitWatts;
        edit();
    });
    save.addEventListener('click', async () => {
        const value = drafts.get(gpu.deviceId);
        if (value == null || saving) return;
        saving = true;
        save.disabled = reset.disabled = limit.disabled = true;
        try {
            const response = await fetch('/api/agent/local/power-control/gpus/' + encodeURIComponent(gpu.deviceId) + '/limits', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({watts: value})
            });
            if (!response.ok) {
                const failure = await response.json().catch(() => null);
                if (failure?.message?.startsWith('gpu_power_') || failure?.message === 'gpu_not_found') {
                    const detail = failure.detail ? ` ${i18n.s(failure.detail)}` : '';
                    throw new Error(`${i18n.t(failure.message)}${detail}`);
                }
                throw new Error('the_drivers_rejected_these_limits_your_draft');
            }
            // Loading plan P4: the POST answer is the new GpuStatus — apply it directly
            // instead of waiting for the next push, then re-read the page view once.
            const saved = await response.json();
            drafts.delete(gpu.deviceId);
            Object.assign(gpu, saved);
            limit.value = String(gpu.userMaxPowerLimitWatts);
            state.classList.remove('hardware-draft');
            state.textContent = i18n.t('power_limit_saved');
            notice(i18n.t('gpu_power_limit_saved', {gpu: gpu.model, watts: fmt(gpu.userMaxPowerLimitWatts)}));
            save.blur();
        } catch (error) {
            notice(i18n.s(error.message), true);
        } finally {
            saving = false;
            limit.disabled = false;
            save.disabled = reset.disabled = !drafts.has(gpu.deviceId);
            window.SolarMinerLive.refresh('power-control');
        }
    });
    draw();
    control.append(track, limit, values);
    actions.append(save, reset, state);
    wrap.append(control, actions);
    return wrap;
}

function renderSettings(data) {
    const list = $('control-settings');
    if (list.contains(document.activeElement) || saving) return;
    list.replaceChildren();
    const row = node('label', 'control-setting'), input = document.createElement('input');
    input.type = 'checkbox';
    input.checked = Boolean(data.dynamicPowerScalingEnabled);
    const copy = node('span');
    copy.append(node('strong', '', 'dynamic_power_control'), node('small', '', 'the_node_may_allocate_power_within_your_limi'));
    row.append(input, copy, node('span', 'toggle'));
    list.append(row);
    input.addEventListener('change', async () => {
        saving = true;
        input.disabled = true;
        try {
            const response = await fetch('/api/agent/local/power-control/settings', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({...data, dynamicPowerScalingEnabled: input.checked})
            });
            if (!response.ok) throw new Error('could_not_save_the_local_setting');
            // Loading plan P4: the POST answer is the saved settings object — trust it
            // immediately; the server also wakes the affected channels right away.
            const saved = await response.json().catch(() => null);
            if (saved) data.dynamicPowerScalingEnabled = Boolean(saved.dynamicPowerScalingEnabled);
            notice('power_control_updated');
        } catch (error) {
            input.checked = !input.checked;
            notice(i18n.s(error.message), true);
        } finally {
            saving = false;
            input.disabled = false;
            input.blur();
            window.SolarMinerLive.refresh('power-control');
        }
    });
    const link = node('a', 'control-setting', 'assign_devices_used_by_the_node_in_the_minin');
    link.href = '/mining.html';
    list.append(link);
}

function render(data) {
    renderSettings(data);
    const list = $('gpu-limits');
    if (saving || drafts.size || list.contains(document.activeElement) && document.activeElement.matches('input')) return;
    list.replaceChildren();
    for (const gpu of data.gpus || []) {
        const card = node('article', 'hardware-gpu-card'), head = node('div', 'hardware-gpu-head');
        head.append(node('span', 'device-kind', gpu.vendor), node('h2', '', gpu.model), node('span', 'hardware-index', 'GPU ' + gpu.index));
        card.append(head);
        if (!gpu.supportsDynamicPowerScaling) {
            card.append(node('p', 'muted', 'this_device_supports_start_stop_only_power_l'));
            if (gpu.regulationError) card.append(node('p', 'muted', i18n.s(gpu.regulationError)));
            list.append(card);
            continue;
        }
        const stats = node('div', 'hardware-gpu-stats');
        stats.append(node('span', '', i18n.t('driver_min_max_w', {min: fmt(gpu.driverMinPowerLimitWatts), max: fmt(gpu.driverMaxPowerLimitWatts)})), node('span', '', i18n.t('limit_watts_w', {watts: gpu.currentPowerLimitWatts ?? '—'})), node('span', '', i18n.t('draw_watts', {watts: gpu.currentUsageWatts == null ? '—' : fmt(gpu.currentUsageWatts) + ' W'})));
        card.append(stats, rangeControl(gpu));
        const details = node('details', 'hardware-identity');
        details.append(node('summary', '', 'device_identity'), node('p', 'hardware-id', gpu.deviceId));
        card.append(details);
        list.append(card);
    }
    if (!list.children.length) {
        const empty = node('div', 'empty-state');
        empty.append(node('h2', '', 'no_controllable_gpu_detected'), node('p', 'muted', 'check_gpu_drivers_and_device_access_you_can'));
        const link = node('a', 'button', 'open_miner_251fb2');
        link.href = '/mining.html';
        empty.append(link);
        list.append(empty);
    }
}

function applyPowerStatus(data) {
    render(data);
    if ($('notice').dataset.kind === 'connection') $('notice').hidden = true;
    $('connection').className = 'badge online';
    $('connection').textContent = i18n.t('agent_connected');
    $('updated').textContent = i18n.t('updated_time', {time: new Date().toLocaleTimeString(i18n.locale)});
}

window.addEventListener('beforeunload', event => {
    if (drafts.size) {
        event.preventDefault();
        event.returnValue = '';
    }
});
// Phase 5c: power-control state arrives over the shared SSE stream (server tick 5 s,
// same rate as the old poll); the watchdog re-reads only after a silent push.
$('refresh').addEventListener('click', () => window.SolarMinerLive.refresh('power-control'));
window.SolarMinerLive.channel('power-control', {endpoint: '/api/agent/local/power-control', maxAgeMs: 30000, transport: 'rest', pollMs: 30000})
    .subscribe((data, meta) => {
        if (data) applyPowerStatus(data);
        else if (meta.error) {
            $('connection').className = 'badge offline';
            $('connection').textContent = i18n.t('agent_unavailable');
            notice(i18n.t('could_not_load_hardware_data_error', {error: i18n.s(meta.error.message || '')}), true, 'connection');
        }
    });
window.SolarMinerLive.start();
