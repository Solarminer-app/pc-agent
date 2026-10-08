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
    min.setAttribute('aria-label', gpu.model + ': ' + i18n.t('minimale SolarMiner-Leistung'));
    max.setAttribute('aria-label', gpu.model + ': ' + i18n.t('maximale SolarMiner-Leistung'));
    const values = node('div', 'dual-range-values'), actions = node('div', 'hardware-actions');
    const save = node('button', 'button primary', 'Grenzen anwenden');
    save.type = 'button';
    save.disabled = true;
    const reset = node('button', 'button subtle', 'Verwerfen');
    reset.type = 'button';
    reset.disabled = true;
    const state = node('small', '', 'Gespeicherte Grenzen. Verschieben ändert noch nichts.');
    const draw = () => {
        if (+min.value > +max.value) {
            if (document.activeElement === min) max.value = min.value; else min.value = max.value;
        }
        const span = +min.max - +min.min;
        selected.style.left = (span ? 100 * (+min.value - +min.min) / span : 0) + '%';
        selected.style.right = (span ? 100 - 100 * (+max.value - +max.min) / span : 0) + '%';
        values.replaceChildren(node('span', '', 'Min. ' + fmt(min.value) + ' W'), node('strong', '', fmt(min.value) + '–' + fmt(max.value) + ' W'), node('span', '', 'Max. ' + fmt(max.value) + ' W'));
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
        state.textContent = i18n.t(dirty ? 'Noch nicht angewendet.' : 'Gespeicherte Grenzen. Verschieben ändert noch nichts.');
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
            if (!response.ok) throw new Error('Die Treiber haben diese Grenzen abgelehnt. Der Entwurf bleibt zum Prüfen erhalten.');
            drafts.delete(gpu.deviceId);
            gpu.userMinPowerLimitWatts = value.minimumWatts;
            gpu.userMaxPowerLimitWatts = value.maximumWatts;
            state.classList.remove('hardware-draft');
            state.textContent = i18n.t('Leistungsgrenzen gespeichert.');
            notice(gpu.model + ': ' + i18n.t('Leistungsgrenzen gespeichert.'));
            save.blur();
        } catch (error) {
            notice(error.message, true);
        } finally {
            saving = false;
            min.disabled = max.disabled = !enabled;
            save.disabled = reset.disabled = !drafts.has(gpu.deviceId);
            await refresh();
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
    copy.append(node('strong', '', 'Dynamische Leistungsregelung'), node('small', '', 'Der Node darf Wattziele innerhalb deiner Grenzen verteilen. Ohne Regelung bleiben Start und Pause möglich.'));
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
            if (!response.ok) throw new Error('Die lokale Einstellung konnte nicht gespeichert werden.');
            notice('Leistungsregelung aktualisiert.');
        } catch (error) {
            input.checked = !input.checked;
            notice(error.message, true);
        } finally {
            saving = false;
            input.disabled = false;
            input.blur();
            await refresh();
        }
    });
    const link = node('a', 'control-setting', 'Welche Geräte der Node verwendet, legst du im Mining-Profil fest →');
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
            card.append(node('p', 'muted', 'Für dieses Gerät ist nur Start/Stopp verfügbar. Leistungsgrenzen können nicht zuverlässig angewendet werden.'));
            if (gpu.regulationError) card.append(node('p', 'muted', gpu.regulationError));
            list.append(card);
            continue;
        }
        const stats = node('div', 'hardware-gpu-stats');
        stats.append(node('span', '', 'Treiber ' + fmt(gpu.driverMinPowerLimitWatts) + '–' + fmt(gpu.driverMaxPowerLimitWatts) + ' W'), node('span', '', 'Limit ' + (gpu.currentPowerLimitWatts ?? '—') + ' W'), node('span', '', i18n.t('Aufnahme') + ' ' + (gpu.currentUsageWatts == null ? '—' : fmt(gpu.currentUsageWatts) + ' W')));
        card.append(stats, rangeControl(gpu, data.dynamicPowerScalingEnabled));
        if (!data.dynamicPowerScalingEnabled) card.append(node('p', 'muted', 'Dynamische Leistungsregelung ist ausgeschaltet. Aktiviere sie oben, um Grenzen zu bearbeiten.'));
        const details = node('details', 'hardware-identity');
        details.append(node('summary', '', 'Gerätekennung'), node('p', 'hardware-id', gpu.deviceId));
        card.append(details);
        list.append(card);
    }
    if (!list.children.length) {
        const empty = node('div', 'empty-state');
        empty.append(node('h2', '', 'Keine regelbare GPU erkannt'), node('p', 'muted', 'Prüfe GPU-Treiber und Gerätezugriff. CPU-Mining kannst du unabhängig davon in Miner einrichten.'));
        const link = node('a', 'button', 'Miner öffnen →');
        link.href = '/mining.html';
        empty.append(link);
        list.append(empty);
    }
}

async function refresh() {
    if (refreshing) return;
    refreshing = true;
    try {
        const response = await fetch('/api/agent/local/power-control', {cache: 'no-store'});
        if (!response.ok) throw new Error('HTTP ' + response.status);
        render(await response.json());
        if ($('notice').dataset.kind === 'connection') $('notice').hidden = true;
        $('connection').className = 'badge online';
        $('connection').textContent = i18n.t('Agent verbunden');
        $('updated').textContent = i18n.t('Aktualisiert ' + new Date().toLocaleTimeString(i18n.locale));
    } catch (error) {
        $('connection').className = 'badge offline';
        $('connection').textContent = i18n.t('Agent nicht erreichbar');
        notice('Hardwaredaten konnten nicht geladen werden: ' + error.message, true, 'connection');
    } finally {
        refreshing = false;
    }
}

window.addEventListener('beforeunload', event => {
    if (drafts.size) {
        event.preventDefault();
        event.returnValue = '';
    }
});
$('refresh').addEventListener('click', refresh);
refresh();
setInterval(() => {
    if (!document.hidden) refresh();
}, 5000);
