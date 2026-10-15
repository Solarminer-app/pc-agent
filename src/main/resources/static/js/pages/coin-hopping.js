const $ = id => document.getElementById(id);
const t = window.SolarMinerI18n.t;
const s = window.SolarMinerI18n.s;
const locale = () => window.SolarMinerI18n.locale;
const make = (tag, className, value) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (value != null) node.textContent = value;
    return node;
};
const money = value => new Intl.NumberFormat(locale(), {style: 'currency', currency: 'USD', maximumFractionDigits: 4}).format(value);
const number = (value, digits = 4) => new Intl.NumberFormat(locale(), {maximumFractionDigits: digits}).format(value);
const date = value => new Intl.DateTimeFormat(locale(), {dateStyle: 'medium', timeStyle: 'short'}).format(new Date(value));
const rate = value => window.SolarMinerMeasurements.hashrate(value);
let settings = null;
let targetMap = new Map();
let requestId = 0;

function notice(message, error = false) {
    const node = $('hopping-notice');
    node.hidden = false; node.className = `notice${error ? ' error' : ''}`;
    node.textContent = s(message);
}

async function loadTargets() {
    const [sweepResponse, benchmarkResponse] = await Promise.all([
        fetch('/api/agent/local/efficiency/profiles', {cache: 'no-store'}),
        fetch('/api/agent/local/benchmarks/profiles', {cache: 'no-store'})
    ]);
    if (!sweepResponse.ok || !benchmarkResponse.ok) throw new Error('coin_hopping_load_failed');
    const [sweeps, benchmarks] = await Promise.all([sweepResponse.json(), benchmarkResponse.json()]);
    const map = new Map();
    const add = (id, model, watts) => {
        if (!id || !(watts > 0)) return;
        if (!map.has(id)) map.set(id, {model, limits: new Set()});
        map.get(id).limits.add(watts);
    };
    for (const profile of sweeps) {
        if (profile.bestStableWatts == null) continue;
        for (const step of profile.steps || [])
            if (step.stable && step.medianHashrateHs > 0 && step.avgPowerWatts > 0)
                add(profile.deviceId, profile.model, step.limitWatts);
    }
    for (const profile of benchmarks)
        if (profile.observations >= 12 && profile.hashrateHps > 0 && profile.powerWatts > 0)
            add(profile.workerId, profile.hardwareModel, profile.powerTargetWatts);
    targetMap = map;
    updateOptions();
}

function updateOptions(changedGpu = false) {
    const gpu = $('hopping-gpu');
    const target = $('hopping-target');
    const desiredGpu = changedGpu ? gpu.value : (gpu.value || settings?.deviceId || '');
    const desiredTarget = changedGpu ? null : (Number(target.value) || settings?.targetWatts);
    gpu.replaceChildren();
    for (const [id, value] of targetMap) gpu.add(new Option(value.model || id, id));
    if (targetMap.has(desiredGpu)) gpu.value = desiredGpu;
    const limits = [...(targetMap.get(gpu.value)?.limits || [])].sort((a, b) => b - a);
    target.replaceChildren();
    for (const watts of limits) target.add(new Option(`${watts} W`, String(watts)));
    if (limits.includes(desiredTarget)) target.value = String(desiredTarget);
    $('hopping-save').disabled = $('hopping-enabled').checked && !target.value;
    refresh();
}

async function refresh() {
    const id = ++requestId;
    const deviceId = $('hopping-gpu').value;
    const targetWatts = Number($('hopping-target').value);
    const params = new URLSearchParams();
    if (deviceId && targetWatts) { params.set('deviceId', deviceId); params.set('targetWatts', String(targetWatts)); }
    try {
        const response = await fetch(`/api/agent/local/coin-hopping?${params}`, {cache: 'no-store'});
        if (!response.ok) throw new Error('coin_hopping_load_failed');
        const view = await response.json();
        if (id !== requestId) return;
        if (settings == null) {
            $('hopping-enabled').checked = view.settings.enabled;
            $('hopping-cooldown').value = view.settings.cooldownMinutes || 30;
            $('hopping-threshold').value = view.settings.thresholdPercent || 5;
        }
        settings = view.settings;
        $('hopping-state').textContent = t(settings.enabled ? 'coin_hopping_active' : 'coin_hopping_off');
        $('connection').className = 'badge online';
        $('connection').textContent = t('agent_connected');
        $('hopping-updated').textContent = t('benchmark_updated_at', {time: new Intl.DateTimeFormat(locale(), {hour: '2-digit', minute: '2-digit', second: '2-digit'}).format(new Date())});
        renderCandidates(view.candidates || [], view.lastOutcome);
        renderSummary(view.summary);
    } catch (error) {
        if (id !== requestId) return;
        $('connection').className = 'badge offline';
        $('connection').textContent = t('agent_unavailable');
        $('hopping-detail').textContent = s(error.message);
    }
}

function renderCandidates(candidates, outcome) {
    const root = $('hopping-chart'); root.replaceChildren();
    if (!candidates.length) root.append(make('p', 'empty', t('coin_hopping_no_candidates')));
    const max = Math.max(1, ...candidates.map(value => value.usdPerKwh));
    for (const candidate of candidates) {
        const row = make('div', 'hopping-row');
        const track = make('div', 'hopping-track');
        const fill = make('i', 'hopping-fill');
        fill.style.width = `${Math.max(2, candidate.usdPerKwh / max * 100)}%`;
        track.append(fill);
        row.append(make('strong', '', candidate.coin.toUpperCase()), track,
            make('span', '', `${money(candidate.usdPerKwh)} / kWh`),
            make('small', 'hopping-meta', t('coin_hopping_point_detail', {
                source: t(`coin_hopping_source_${candidate.source?.toLowerCase() || 'live'}`),
                rate: rate(candidate.hashrateHps), power: Math.round(candidate.measuredWatts),
                time: date(candidate.measuredAt)
            })));
        root.append(row);
    }
    $('hopping-detail').textContent = outcome ? t(outcome) : t('coin_hopping_gross_note');
}

function duration(seconds) {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    return t('coin_hopping_duration', {hours, minutes});
}

function renderSummary(summary) {
    if (!summary) return;
    $('hopping-actual').textContent = money(summary.actualUsd || 0);
    $('hopping-fixed').textContent = summary.bestFixedUsd == null ? '—' : money(summary.bestFixedUsd);
    $('hopping-fixed-coin').textContent = summary.bestFixedCoin?.toUpperCase() || '';
    $('hopping-gain').textContent = summary.gainUsd == null ? '—' : money(summary.gainUsd);
    $('hopping-gain').classList.toggle('negative', summary.gainUsd < 0);
    $('hopping-covered').textContent = duration(summary.accountedSeconds || 0);
    const chart = $('hopping-earnings-bars'); chart.replaceChildren();
    if (summary.bestFixedUsd != null) {
        const max = Math.max(summary.actualUsd || 0, summary.bestFixedUsd, 0.000001);
        for (const [label, value] of [[t('coin_hopping_actual_usd'), summary.actualUsd || 0],
            [t('coin_hopping_fixed_usd'), summary.bestFixedUsd]]) {
            const row = make('div', 'hopping-earnings-row');
            const track = make('div', 'hopping-earnings-track');
            const fill = make('i', 'hopping-earnings-fill'); fill.style.width = `${value / max * 100}%`;
            track.append(fill); row.append(make('span', '', label), track, make('strong', '', money(value)));
            chart.append(row);
        }
    } else chart.append(make('p', 'empty', t('coin_hopping_no_comparison_yet')));
    const coins = $('hopping-coins'); coins.replaceChildren();
    for (const [coin, amount] of Object.entries(summary.actualCoins || {}))
        coins.append(make('span', 'hopping-coin', `${coin.toUpperCase()} · ${number(amount, 8)}`));
    if (!coins.children.length) coins.append(make('p', 'empty', t('coin_hopping_no_coins_yet')));
    if (summary.bestFixedCoin && summary.bestFixedCoins != null)
        coins.append(make('span', 'hopping-coin', t('coin_hopping_fixed_coins', {
            coin: summary.bestFixedCoin.toUpperCase(), amount: number(summary.bestFixedCoins, 8)
        })));
    const history = $('hopping-history'); history.replaceChildren();
    for (const event of summary.actions || []) {
        const item = make('article', 'hopping-event');
        const time = make('time', '', date(event.at)); time.dateTime = event.at;
        const label = ['coin_hopping_switched', 'coin_hopping_switch_not_persisted'].includes(event.outcome)
            ? t('coin_hopping_action_switch', {from: event.fromCoin.toUpperCase(), to: event.toCoin.toUpperCase()})
            : t('coin_hopping_action_failed', {from: event.fromCoin.toUpperCase(), to: event.toCoin.toUpperCase()});
        item.append(time, make('strong', '', label), make('small', '', t('coin_hopping_action_gain', {
            gain: number(event.gainPercent, 1), watts: event.targetWatts
        })));
        history.append(item);
    }
    if (!history.children.length) history.append(make('p', 'empty', t('coin_hopping_no_actions_yet')));
}

$('hopping-gpu').addEventListener('change', () => updateOptions(true));
$('hopping-target').addEventListener('change', refresh);
$('hopping-enabled').addEventListener('change', () => {
    $('hopping-save').disabled = $('hopping-enabled').checked && !$('hopping-target').value;
});
$('refresh').addEventListener('click', async () => { await loadTargets(); refresh(); });
$('hopping-save').addEventListener('click', async () => {
    const button = $('hopping-save'); button.disabled = true;
    try {
        const response = await fetch('/api/agent/local/coin-hopping', {method: 'POST',
            headers: {'Content-Type': 'application/json'}, body: JSON.stringify({
                enabled: $('hopping-enabled').checked, deviceId: $('hopping-gpu').value,
                targetWatts: Number($('hopping-target').value),
                cooldownMinutes: Number($('hopping-cooldown').value),
                thresholdPercent: Number($('hopping-threshold').value)
            })});
        if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.detail || 'coin_hopping_save_failed'); }
        const view = await response.json(); settings = view.settings;
        notice('coin_hopping_saved');
        refresh();
    } catch (error) { notice(error.message, true); }
    finally { button.disabled = $('hopping-enabled').checked && !$('hopping-target').value; }
});

async function start() {
    await refresh();
    await loadTargets();
}
start().catch(error => { $('hopping-detail').textContent = s(error.message); });
setInterval(refresh, 60_000);
