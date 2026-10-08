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
  text('cpu-name', data.cpuName || 'Prozessor unbekannt');
  const cpuPower = data.metrics?.['cpu.package_power'];
  const cpuTemp = data.metrics?.['cpu.temperature'];
  text('cpu-temperature', cpuTemp?.available ? `${fmt(cpuTemp.value, 0)} °C` : '—');
  const known = new Map((data.gpus || []).map(gpu => [`${gpu.vendor}:${gpu.index}`, gpu]));
  const names = data.gpuNames || [...known.values()].map(g => g.model);
  text('gpu-count', String(names.length));
  text('gpu-summary', names.length ? names.length === 1 ? names[0] : `${names.length} Geräte` : 'Nicht erkannt');
  const devices = $('device-list'); devices.replaceChildren();
  const cpu = el('article', 'telemetry-device cpu-device');
  const cpuHead = el('div', 'telemetry-device-head'); cpuHead.append(el('span', 'device-kind', 'CPU'), el('strong', '', data.cpuName || 'Prozessor unbekannt'));
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
        : null;
    const values = el('div', 'telemetry-device-values'); values.append(
      el('span', '', draw != null ? `${fmt(draw, 0)} W` : '—'),
      el('span', '', gpuTemperature?.available ? `${fmt(gpuTemperature.value, 0)} °C` : '—'),
      el('span', '', cardData?.currentPowerLimitWatts ? `Limit ${fmt(cardData.currentPowerLimitWatts, 0)} W` : '—'));
    card.append(head, meter(draw, max, 'power'), values);
    devices.append(card);
  });
  if (!names.length) devices.append(el('p', 'empty', 'Es wurden keine GPUs erkannt.'));
  const total = data.metrics?.['system.total_power'];
  text('total-power', total?.available ? `${fmt(total.value)} W` : '—');
  text('power-quality', total?.available ? total.source : 'Leistungsmessung nicht verfügbar');
  const metrics = Object.entries(data.metrics || {});
  const available = metrics.filter(([, value]) => value.available).length;
  text('sensor-count', String(available));
  text('sensor-summary', `${available} von ${metrics.length} Messwerten verfügbar`);
  text('detail-count', `(${available}/${metrics.length})`);
  text('collected-at', data.collectedAt ? i18n.t(`Stand ${new Date(data.collectedAt).toLocaleTimeString(i18n.locale)}`) : '—');
  const sources = $('sensor-sources'); sources.replaceChildren();
  for (const [name, state] of Object.entries(data.sources || {})) sources.append(el('span', 'source', `${name}: ${state}`));
  renderMetricList(data);
  const status = data.sensorServiceStatus || 'not-required';
  $('sensor-status').className = `tag ${status === 'available' || status === 'not-required' ? 'ready' : 'blocked'}`;
  const sensorLabels = {available: 'Sensorzugriff bereit', 'not-required': 'Sensorzugriff bereit',
    starting: 'Warte auf Windows-Freigabe …', stopped: 'Hardware-Monitor angehalten',
    failed: 'Hardware-Monitor fehlgeschlagen', 'api-disabled': 'Lokale Sensor-API deaktiviert',
    'api-unavailable': 'Lokale Sensor-API nicht erreichbar', 'not-started': 'Hardware-Monitor noch nicht gestartet'};
  text('sensor-status', sensorLabels[status] || `Sensorzugriff: ${status}`);
}
async function refresh() {
  if (refreshing) return;
  refreshing = true;
  try {
    const response = await fetch('/api/agent/local/telemetry', { cache: 'no-store', signal: AbortSignal.timeout(8000) });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const data = await response.json();
    try {
      render(data);
    } catch (error) {
      $('connection').className = 'badge online'; text('connection', 'Agent verbunden');
      $('sensor-status').className = 'tag blocked';
      text('sensor-status', i18n.t('Sensoranzeige fehlgeschlagen'));
      text('updated', `Sensordaten konnten nicht dargestellt werden: ${error.message}`);
      return;
    }
    $('connection').className = 'badge online'; text('connection', 'Agent verbunden');
    text('updated', i18n.t(`Aktualisiert ${new Date().toLocaleTimeString(i18n.locale)}`));
  } catch (error) {
    $('connection').className = 'badge offline'; text('connection', 'Agent nicht erreichbar');
    $('sensor-status').className = 'tag blocked';
    text('sensor-status', i18n.t(error.name === 'TimeoutError' ? 'Zeitüberschreitung beim Sensorabruf' : 'Sensor-API nicht erreichbar'));
    text('updated', `Telemetrie konnte nicht geladen werden: ${error.message}`);
  } finally {
    refreshing = false;
  }
}
$('refresh').addEventListener('click', refresh);
refresh(); setInterval(() => {if (!document.hidden) refresh();}, 3000);

function renderMetricList(data) {
  const query = $('sensor-search').value.trim().toLowerCase(), onlyAvailable = $('sensor-available').checked;
  const list = $('telemetry-list'); list.replaceChildren();
  for (const [key, metric] of Object.entries(data.metrics || {}).filter(([key, metric]) => (!onlyAvailable || metric.available) && [key, metric.unit, metric.source].join(' ').toLowerCase().includes(query))) {
    const row = el('div', `metric${metric.available ? '' : ' unavailable'}`);
    const value = metric.available ? formatMetric(metric.value, metric.unit) : 'Nicht verfügbar';
    const exactBytes = metric.available && (metric.unit === 'B' || metric.unit === 'B/s')
      ? `${fmt(metric.value, 0)} ${metric.unit}` : '';
    row.append(el('span', '', key), el('strong', '', value),
      el('small', '', `${metric.source || 'Quelle unbekannt'} · ${metric.directMeasurement ? 'direkte Messung' : 'abgeleitet / geschätzt'}`));
    if (exactBytes) row.title = `Rohwert: ${exactBytes}`;
    list.append(row);
  }

  if (!list.children.length) list.append(el('p', 'empty', window.SolarMinerI18n.t('Keine Sensoren passen zu diesem Filter.')));
}
$('sensor-search').addEventListener('input', () => {if(currentTelemetry)renderMetricList(currentTelemetry);});
$('sensor-available').addEventListener('change', () => {if(currentTelemetry)renderMetricList(currentTelemetry);});
const sensorAlert = $('lhm-gate'); document.querySelector('.telemetry-details').before(sensorAlert);
