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

function rangeControl(gpu, enabled) {
    const wrap = node('div'), control = node('div', 'dual-range');
    const track = node('div', 'dual-range-track'), selected = node('div', 'dual-range-selected');
    track.append(selected);
    const min = document.createElement('input'), max = document.createElement('input');
    for (const input of [min, max]) {
        input.type = 'range';
        input.min = String(gpu.driverMinPowerLimitWatts);
        input.max = String(gpu.driverMaxPowerLimitWatts);
        input.step = '1';
        input.className = 'dual-range-input';
        input.disabled = !enabled;
    }
    min.value = String(gpu.userMinPowerLimitWatts);
    max.value = String(gpu.userMaxPowerLimitWatts);
    min.setAttribute('aria-label', gpu.model + ': ' + i18n.t('minimum_solarminer_power'));
    max.setAttribute('aria-label', gpu.model + ': ' + i18n.t('maximum_solarminer_power'));
    const values = node('div', 'dual-range-values'), actions = node('div', 'hardware-actions');
    const save = node('button', 'button primary', 'apply_limits');
    save.type = 'button';
    save.disabled = true;
    const reset = node('button', 'button subtle', 'discard');
    reset.type = 'button';
    reset.disabled = true;
    const state = node('small', '', 'saved_limits_moving_the_sliders_does_not_app');
    const draw = () => {
        if (+min.value > +max.value) {
            if (document.activeElement === min) max.value = min.value; else min.value = max.value;
        }
        const span = +min.max - +min.min;
        selected.style.left = (span ? 100 * (+min.value - +min.min) / span : 0) + '%';
        selected.style.right = (span ? 100 - 100 * (+max.value - +max.min) / span : 0) + '%';
        values.replaceChildren(node('span', '', i18n.t('min_watts_w', {watts: fmt(min.value)})), node('strong', '', fmt(min.value) + '–' + fmt(max.value) + ' W'), node('span', '', i18n.t('max_watts_w', {watts: fmt(max.value)})));
    };
    const edit = () => {
        draw();
        const dirty = +min.value !== gpu.userMinPowerLimitWatts || +max.value !== gpu.userMaxPowerLimitWatts;
        if (dirty) drafts.set(gpu.deviceId, {
            minimumWatts: +min.value,
            maximumWatts: +max.value
        }); else drafts.delete(gpu.deviceId);
        save.disabled = reset.disabled = !dirty;
        state.classList.toggle('hardware-draft', dirty);
        state.textContent = i18n.t(dirty ? 'not_applied_yet' : 'saved_limits_moving_the_sliders_does_not_app');
    };
    for (const input of [min, max]) input.addEventListener('input', edit);
    reset.addEventListener('click', () => {
        drafts.delete(gpu.deviceId);
        min.value = gpu.userMinPowerLimitWatts;
        max.value = gpu.userMaxPowerLimitWatts;
        edit();
    });
    save.addEventListener('click', async () => {
        const value = drafts.get(gpu.deviceId);
        if (!value || saving) return;
        saving = true;
        save.disabled = reset.disabled = min.disabled = max.disabled = true;
        try {
            const response = await fetch('/api/agent/local/power-control/gpus/' + encodeURIComponent(gpu.deviceId) + '/limits', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(value)
            });
            if (!response.ok) throw new Error('the_drivers_rejected_these_limits_your_draft');
            // Loading plan P4: the POST answer is the new GpuStatus — apply it directly
            // instead of waiting for the next push, then re-read the page view once.
            const saved = await response.json();
            drafts.delete(gpu.deviceId);
            Object.assign(gpu, saved);
            state.classList.remove('hardware-draft');
            state.textContent = i18n.t('power_limits_saved');
            notice(i18n.t('gpu_power_limits_saved', {gpu: gpu.model}));
            save.blur();
        } catch (error) {
            notice(i18n.s(error.message), true);
        } finally {
            saving = false;
            min.disabled = max.disabled = !enabled;
            save.disabled = reset.disabled = !drafts.has(gpu.deviceId);
            window.SolarMinerLive.refresh('power-control');
        }
    });
    draw();
    control.append(track, min, max, values);
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
        card.append(stats, rangeControl(gpu, data.dynamicPowerScalingEnabled));
        if (!data.dynamicPowerScalingEnabled) card.append(node('p', 'muted', 'dynamic_power_control_is_disabled_enable_it'));
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
