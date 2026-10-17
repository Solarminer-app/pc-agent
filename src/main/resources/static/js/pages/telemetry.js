const $ = id => document.getElementById(id);
const i18n = window.SolarMinerI18n;
const fmt = (value, digits = 2) => new Intl.NumberFormat(i18n.locale, { maximumFractionDigits: digits }).format(value);
let currentTelemetry;
let refreshing = false;
const text = (id, value) => { $(id).textContent = value; };
function el(tag, className, value) { const n = document.createElement(tag); if (className) n.className = className; if (value !== undefined) n.textContent = value; return n; }
function meter(value, maximum, className = '') { const bar = el('div', `telemetry-meter ${className}${value == null ? ' unavailable' : ''}`); const fill = el('i'); fill.style.width = `${Math.max(0, Math.min(100, value == null ? 0 : value / maximum * 100))}%`; bar.append(fill); return bar; }
function formatMetric(value, unit) {
  if (unit !== 'B' && unit !== 'B/s') return `${fmt(value)} ${unit || ''}`.trim();
  const suffix = unit === 'B/s' ? '/s' : '';
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB'];
  let scaled = value, index = 0;
  while (Math.abs(scaled) >= 1024 && index < units.length - 1) {
    scaled /= 1024;
    index++;
  }
  return `${fmt(scaled, index === 0 ? 0 : 2)} ${units[index]}${suffix}`;
}
function render(data) {
  currentTelemetry = data;
  text('platform', `${data.platform || '—'} · ${data.architecture || '—'}`);
  text('cpu-name', data.cpuName || i18n.t('unknown_processor'));
  const cpuPower = data.metrics?.['cpu.package_power'];
  const cpuTemp = data.metrics?.['cpu.temperature'];
  text('cpu-temperature', cpuTemp?.available ? `${fmt(cpuTemp.value, 0)} °C` : '—');
  const known = new Map((data.gpus || []).map(gpu => [`${gpu.vendor}:${gpu.index}`, gpu]));
  const names = data.gpuNames || [...known.values()].map(g => g.model);
  text('gpu-count', String(names.length));
  text('gpu-summary', names.length ? names.length === 1 ? names[0] : i18n.t('count_devices', {count: names.length}) : i18n.t('not_detected'));
  const devices = $('device-list'); devices.replaceChildren();
  const cpu = el('article', 'telemetry-device cpu-device');
  const cpuHead = el('div', 'telemetry-device-head'); cpuHead.append(el('span', 'device-kind', 'CPU'), el('strong', '', data.cpuName || i18n.t('unknown_processor')));
  const cpuValues = el('div', 'telemetry-device-values'); cpuValues.append(el('span', '', cpuTemp?.available ? `${fmt(cpuTemp.value, 0)} °C` : '—'), el('span', '', cpuPower?.available ? `${fmt(cpuPower.value, 0)} W` : '—'));
  cpu.append(cpuHead, meter(cpuTemp?.available ? cpuTemp.value : null, 100, 'temperature'), cpuValues);
  devices.append(cpu);
  names.forEach((name, index) => {
    const cardData = [...known.values()].find(g => g.model === name) || [...known.values()][index];
    const card = el('article', 'telemetry-device gpu-device');
    const head = el('div', 'telemetry-device-head'); head.append(el('span', 'device-kind', cardData?.vendor || 'GPU'), el('strong', '', name));
    const max = cardData?.maxWatts || 1, draw = cardData?.currentWatts;
    const metrics = data.metrics || {};
    const gpuTemperature = cardData?.vendor === 'NVIDIA'
      ? metrics[`gpu.nvidia.${cardData.index}.temperature`]
      : cardData?.vendor === 'AMD'
        ? Object.entries(metrics).find(([key, value]) => key.startsWith(`gpu.amd.card${cardData.index}.`)
          && key.endsWith('.temperature') && value.available)?.[1]
          || (Object.entries(metrics).filter(([key, value]) => key.startsWith('gpu.amd.')
            && key.endsWith('.temperature') && value.available).length === 1
            ? Object.entries(metrics).find(([key, value]) => key.startsWith('gpu.amd.')
              && key.endsWith('.temperature') && value.available)?.[1] : null)
        : cardData?.vendor === 'Intel' ? metrics[`gpu.intel.${cardData.index}.temperature`] : null;
    const values = el('div', 'telemetry-device-values'); values.append(
      el('span', '', draw != null ? `${fmt(draw, 0)} W` : '—'),
      el('span', '', gpuTemperature?.available ? `${fmt(gpuTemperature.value, 0)} °C` : '—'),
      el('span', '', cardData?.currentPowerLimitWatts ? i18n.t('limit_watts_w', {watts: fmt(cardData.currentPowerLimitWatts, 0)}) : '—'));
    card.append(head, meter(draw, max, 'power'), values);
    devices.append(card);
  });
  const otherPower = data.otherPowerReadings || [];
  const systemCard = el('article', 'telemetry-device system-device');
  const systemHead = el('div', 'telemetry-device-head');
  systemHead.append(el('span', 'device-kind', i18n.t('pc_components')), el('strong', '', i18n.t('other_power_sensors')));
  systemCard.append(systemHead);
  if (otherPower.length) {
    for (const reading of otherPower) {
      const row = el('div', 'telemetry-device-values');
      row.append(el('span', '', reading.sensorKey), el('strong', '', `${fmt(reading.watts)} W`));
      systemCard.append(row);
    }
  } else systemCard.append(el('p', 'empty', i18n.t('no_separate_system_power_sensor')));
  devices.append(systemCard);
  if (!names.length) devices.append(el('p', 'empty', i18n.t('no_gpus_were_detected')));
  const total = data.metrics?.['system.cpu_gpu_power'];
  text('total-power', total?.available ? `${fmt(total.value)} W` : '—');
  text('power-quality', total?.available ? i18n.s(total.source) : i18n.t('power_measurement_unavailable'));
  const metrics = Object.entries(data.metrics || {}).filter(([key]) => key !== 'system.total_power');
  const available = metrics.filter(([, value]) => value.available).length;
  text('sensor-count', String(available));
  text('sensor-summary', i18n.t('available_of_total_readings_available', {available, total: metrics.length}));
  text('detail-count', `(${available}/${metrics.length})`);
  text('collected-at', data.collectedAt ? i18n.t('as_of_time', {time: new Date(data.collectedAt).toLocaleTimeString(i18n.locale)}) : '—');
  const sources = $('sensor-sources'); sources.replaceChildren();
  for (const [name, state] of Object.entries(data.sources || {})) sources.append(el('span', 'source', `${name}: ${i18n.s(state)}`));
  renderMetricList(data);
  const status = data.sensorServiceStatus || 'not-required';
  $('sensor-status').className = `tag ${status === 'available' || status === 'not-required' ? 'ready' : 'blocked'}`;
  const sensorLabels = {available: 'sensor_access_ready', 'not-required': 'sensor_access_ready',
    starting: 'waiting_for_windows_approval', stopped: 'hardware_monitor_stopped',
    failed: 'hardware_monitor_failed', 'api-disabled': 'local_sensor_api_disabled',
    'api-unavailable': 'local_sensor_api_unavailable', 'not-started': 'hardware_monitor_not_started'};
  text('sensor-status', i18n.t(sensorLabels[status] || 'sensor_access_status', {status}));
}
function applyTelemetry(data) {
  try {
    render(data);
  } catch (error) {
    $('connection').className = 'badge online'; text('connection', i18n.t('agent_connected'));
    $('sensor-status').className = 'tag blocked';
    text('sensor-status', i18n.t('could_not_render_sensor_data'));
    text('updated', i18n.t('could_not_display_sensor_data_error', {error: i18n.s(error.message)}));
    return;
  }
  $('connection').className = 'badge online'; text('connection', i18n.t('agent_connected'));
  text('updated', i18n.t('updated_time', {time: new Date().toLocaleTimeString(i18n.locale)}));
}
$('refresh').addEventListener('click', () => window.SolarMinerLive.refresh('telemetry'));
// Phase 5c: sensor state arrives over the shared SSE stream; the watchdog only re-reads
// over REST when the push went silent.
window.SolarMinerLive.channel('telemetry', {endpoint: '/api/agent/local/telemetry', maxAgeMs: 8000})
    .subscribe((data, meta) => {
        if (data) applyTelemetry(data);
        else if (meta.error) {
            $('connection').className = 'badge offline'; text('connection', i18n.t('agent_unavailable'));
            $('sensor-status').className = 'tag blocked';
            text('sensor-status', i18n.t('sensor_api_unavailable'));
            text('updated', i18n.t('could_not_load_telemetry_error', {error: i18n.s(meta.error.message || '')}));
        }
    });
window.SolarMinerLive.start();

function renderMetricList(data) {
  const query = $('sensor-search').value.trim().toLowerCase(), onlyAvailable = $('sensor-available').checked;
  const list = $('telemetry-list'); list.replaceChildren();
  for (const [key, metric] of Object.entries(data.metrics || {}).filter(([key, metric]) => key !== 'system.total_power' && (!onlyAvailable || metric.available) && [key, metric.unit, metric.source].join(' ').toLowerCase().includes(query))) {
    const row = el('div', `metric${metric.available ? '' : ' unavailable'}`);
    const value = metric.available ? formatMetric(metric.value, metric.unit) : i18n.t('unavailable_2a5d2c');
    const exactBytes = metric.available && (metric.unit === 'B' || metric.unit === 'B/s')
      ? `${fmt(metric.value, 0)} ${metric.unit}` : '';
    row.append(el('span', '', key), el('strong', '', value),
      el('small', '', `${i18n.s(metric.source) || i18n.t('unknown_source')} · ${i18n.t(metric.directMeasurement ? 'direct_measurement' : 'derived_estimated')}`));
    if (exactBytes) row.title = i18n.t('raw_value_value', {value: exactBytes});
    list.append(row);
  }

  if (!list.children.length) list.append(el('p', 'empty', window.SolarMinerI18n.t('no_sensors_match_this_filter')));
}
$('sensor-search').addEventListener('input', () => {if(currentTelemetry)renderMetricList(currentTelemetry);});
$('sensor-available').addEventListener('change', () => {if(currentTelemetry)renderMetricList(currentTelemetry);});
const sensorAlert = $('lhm-gate'); document.querySelector('.telemetry-details').before(sensorAlert);
