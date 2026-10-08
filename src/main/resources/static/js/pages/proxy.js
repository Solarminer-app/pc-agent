const $ = id => document.getElementById(id);
const t = window.SolarMinerI18n.t;
let current, busy = false, refreshing = false, hostDirty = false;
const text = (id, value) => { $(id).textContent = t(value); };
function notice(message, error = false, kind = 'action') { const box = $('notice'); box.hidden = false; box.dataset.kind = kind; box.className = 'notice' + (error ? ' error' : ''); box.textContent = t(message); }
function controls() { $('proxy-local').disabled = busy || !current || current.mode === 'standalone' && current.managedStatus === 'running'; $('proxy-external').disabled = $('proxy-discover').disabled = busy || !current; $('external-host').disabled = busy; const local = current?.mode === 'standalone'; $('roll-random').disabled = $('roll-stateful').disabled = busy || !local; $('roll-random').classList.toggle('primary', current?.feeRollMode !== 'stateful'); $('roll-stateful').classList.toggle('primary', current?.feeRollMode === 'stateful'); text('roll-mode-state', current?.feeRollMode === 'stateful' ? 'AUSGEWOGEN' : 'ZUFÄLLIG'); }
async function command(url) {
  const response = await fetch(url, {method:'POST'});
  if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.message || body?.detail || 'HTTP ' + response.status); }
  if (await response.json() !== true) throw new Error('Der Agent hat die Verbindungsänderung abgelehnt. Prüfe Sensorfreigabe, Proxy und Agent-Logs.');
}
async function activate(mode) {
  if (busy) return;
  if (mode === 'external' && !$('external-form').reportValidity()) return;
  busy = true; controls();
  try {
    if (mode === 'external') await command('/api/agent/local/proxy?host=' + encodeURIComponent($('external-host').value.trim()));
    await command('/api/agent/local/proxy/mode?mode=' + mode); hostDirty = false;
    notice('Verbindung geändert. Alle Miner wurden pausiert. Starte sie auf der Miner-Seite erneut.');
  } catch (error) { notice(error.message, true); }
  finally { busy = false; await refresh(); controls(); }
}
function render(data) {
  current = data.proxy; const local = current?.mode === 'standalone';
  text('proxy-heading', local ? 'Lokaler Proxy ausgewählt' : 'Externer Proxy ausgewählt'); text('proxy-mode', local ? 'LOKAL' : 'EXTERN');
  text('proxy-state', current?.reachable ? 'Proxy erreichbar' : 'Proxy nicht erreichbar');
  text('proxy-description', local ? current?.managedStatus === 'running' ? 'Der enthaltene Proxy läuft auf diesem PC.' : current?.managedDetail || 'Der lokale Proxy ist noch nicht bereit.' : current?.host || 'Kein externer Host gespeichert.');
  const dashboard = $('proxy-dashboard-link');
  const dashboardAvailable = local
    ? current?.managedStatus === 'running'
    : Boolean(current?.host);
  dashboard.href = local
    ? '/proxy-dashboard/'
    : `http://${current.host}:8090/`;
  dashboard.hidden = !dashboardAvailable;
  $('local-mode').classList.toggle('active', local); $('external-mode').classList.toggle('active', !local);
  text('local-state', local ? 'AUSGEWÄHLT' : 'AUF DIESEM PC'); text('external-state', local ? 'IM NETZWERK' : 'AUSGEWÄHLT');
  if (!hostDirty && document.activeElement !== $('external-host')) $('external-host').value = current?.host || '';
  const routes = $('proxy-routes'); routes.replaceChildren();
  for (const [key, name, experimental] of [['monero','Monero',false],['pearl','Pearl',false],['ravencoin','Ravencoin',true],['ethereumclassic','Ethereum Classic',true]]) {
    const card = document.createElement('article'); card.className = 'route-coin'; const title = document.createElement('h3'); title.textContent = name;
    const state = document.createElement('span'); state.className = 'tag ' + (current?.[key + 'FeeReady'] && !experimental ? 'ready' : ''); state.textContent = t(experimental ? 'VORBEREITET' : current?.[key + 'FeeReady'] ? 'FEE-ZIEL GELADEN' : 'FEE-ZIEL FEHLT');
    const route = document.createElement('p'); route.textContent = current?.[key + 'Url'] || t('Keine Route verfügbar'); card.append(title,state,route); routes.append(card);
  }
  controls();
}
async function refresh() {
  if (refreshing) return; refreshing = true;
  try { const response = await fetch('/api/agent/local/overview', {cache:'no-store'}); if (!response.ok) throw new Error('HTTP ' + response.status); render(await response.json()); if ($('notice').dataset.kind === 'connection') $('notice').hidden = true; $('connection').className = 'badge online'; text('connection','Agent verbunden'); text('updated','Aktualisiert ' + new Date().toLocaleTimeString(window.SolarMinerI18n.locale)); }
  catch (error) { current = null; controls(); $('connection').className = 'badge offline'; text('connection','Agent nicht erreichbar'); notice('Proxy-Daten konnten nicht geladen werden: ' + error.message,true,'connection'); }
  finally { refreshing = false; }
}
$('external-host').addEventListener('input',()=>{hostDirty=true;});
async function setRollMode(mode) {
  if (busy) return;
  busy = true; controls();
  try {
    await command('/api/agent/local/proxy/roll-mode?mode=' + mode);
    notice('Fee-Verteilung geändert. Der lokale Proxy wurde neu gestartet und alle Miner wurden pausiert. Starte sie auf der Miner-Seite erneut.');
  } catch (error) { notice(error.message, true); }
  finally { busy = false; await refresh(); controls(); }
}
$('roll-random').addEventListener('click',()=>setRollMode('random'));
$('roll-stateful').addEventListener('click',()=>setRollMode('stateful'));
$('external-form').addEventListener('submit',event=>{event.preventDefault(); activate('external');});
$('proxy-local').addEventListener('click',()=>activate('local'));
$('proxy-discover').addEventListener('click',async()=>{
  if (busy) return; busy=true; controls(); text('discovery-status','Suche im lokalen Netzwerk …'); $('proxy-candidates').replaceChildren();
  try {
    const response=await fetch('/api/agent/local/proxy/discover',{method:'POST'}); if (!response.ok) throw new Error('HTTP ' + response.status); const candidates=await response.json();
    text('discovery-status', candidates.length ? 'Wähle einen gefundenen Proxy. Die Verbindung wird erst beim Aktivieren geändert.' : 'Kein Proxy gefunden. Du kannst einen bekannten Host manuell eintragen.');
    for (const candidate of candidates) {
      const button=document.createElement('button'); button.type='button'; button.className='button subtle'; button.textContent=candidate.host + ' ' + t('auswählen');
      button.addEventListener('click',()=>{ $('external-host').value=candidate.host;hostDirty=true;$('external-host').focus(); }); $('proxy-candidates').append(button);
    }
  } catch (error) { text('discovery-status',t('Suche fehlgeschlagen:') + ' ' + error.message); }
  finally {busy=false;controls();}
});
$('refresh').addEventListener('click',refresh); refresh(); setInterval(()=>{if(!document.hidden && !busy)refresh();},12000);
