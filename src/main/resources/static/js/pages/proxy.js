const $ = id => document.getElementById(id);
const t = window.SolarMinerI18n.t;
let current, busy = false, refreshing = false, hostDirty = false;
const text = (id, value) => { $(id).textContent = t(value); };
function notice(message, error = false, kind = 'action') { const box = $('notice'); box.hidden = false; box.dataset.kind = kind; box.className = 'notice' + (error ? ' error' : ''); box.textContent = t(message); }
function controls() { $('proxy-local').disabled = busy || !current || current.mode === 'standalone' && current.managedStatus === 'running'; $('proxy-external').disabled = $('proxy-discover').disabled = busy || !current; $('external-host').disabled = busy; const local = current?.mode === 'standalone'; $('roll-random').disabled = $('roll-stateful').disabled = busy || !local; $('roll-random').classList.toggle('primary', current?.feeRollMode !== 'stateful'); $('roll-stateful').classList.toggle('primary', current?.feeRollMode === 'stateful'); text('roll-mode-state', current?.feeRollMode === 'stateful' ? 'balanced' : 'random'); }
async function command(url) {
  const response = await fetch(url, {method:'POST'});
  if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.message || body?.detail || 'HTTP ' + response.status); }
  if (await response.json() !== true) throw new Error('the_agent_rejected_the_connection_change_che');
}
async function activate(mode) {
  if (busy) return;
  if (mode === 'external' && !$('external-form').reportValidity()) return;
  busy = true; controls();
  try {
    if (mode === 'external') await command('/api/agent/local/proxy?host=' + encodeURIComponent($('external-host').value.trim()));
    await command('/api/agent/local/proxy/mode?mode=' + mode); hostDirty = false;
    notice('connection_changed_all_miners_were_paused_re');
  } catch (error) { notice(window.SolarMinerI18n.s(error.message), true); }
  finally { busy = false; await refresh(); controls(); }
}
function render(data) {
  current = data.proxy; const local = current?.mode === 'standalone';
  text('proxy-heading', local ? 'local_proxy_selected' : 'external_proxy_selected'); text('proxy-mode', local ? 'LOKAL' : 'EXTERN');
  text('proxy-state', current?.reachable ? 'proxy_reachable' : 'proxy_unreachable');
  text('proxy-description', local ? current?.managedStatus === 'running' ? 'the_included_proxy_is_running_on_this_pc' : window.SolarMinerI18n.s(current?.managedDetail) || 'the_local_proxy_is_not_ready_yet' : current?.host || 'no_external_host_saved');
  const dashboard = $('proxy-dashboard-link');
  const dashboardAvailable = local
    ? current?.managedStatus === 'running'
    : Boolean(current?.host);
  dashboard.href = local
    ? '/proxy-dashboard/'
    : `http://${current.host}:8090/`;
  dashboard.hidden = !dashboardAvailable;
  $('local-mode').classList.toggle('active', local); $('external-mode').classList.toggle('active', !local);
  text('local-state', local ? 'selected' : 'on_this_pc'); text('external-state', local ? 'on_your_network' : 'selected');
  if (!hostDirty && document.activeElement !== $('external-host')) $('external-host').value = current?.host || '';
  const routes = $('proxy-routes'); routes.replaceChildren();
  for (const coin of current?.coinRoutes || []) {
    const card = document.createElement('article'); card.className = 'route-coin'; const title = document.createElement('h3'); title.textContent = coin.name;
    const state = document.createElement('span'); state.className = 'tag ' + (coin.feeReady && !coin.experimental ? 'ready' : ''); state.textContent = t(coin.experimental ? 'prepared' : coin.feeReady ? 'fee_target_loaded' : 'fee_target_missing');
    const route = document.createElement('p'); route.textContent = coin.url || t('no_route_available'); card.append(title,state,route); routes.append(card);
  }
  controls();
}
async function refresh() {
  if (refreshing) return; refreshing = true;
  try { const response = await fetch('/api/agent/local/overview', {cache:'no-store'}); if (!response.ok) throw new Error('HTTP ' + response.status); render(await response.json()); if ($('notice').dataset.kind === 'connection') $('notice').hidden = true; $('connection').className = 'badge online'; text('connection','agent_connected'); text('updated',t('updated_time', {time: new Date().toLocaleTimeString(window.SolarMinerI18n.locale)})); }
  catch (error) { current = null; controls(); $('connection').className = 'badge offline'; text('connection','agent_unavailable'); notice(t('could_not_load_proxy_data_error', {error: window.SolarMinerI18n.s(error.message)}),true,'connection'); }
  finally { refreshing = false; }
}
// Phase 5d: the 12 s overview poll is gone — the page rides the shared "overview" SSE
// REST snapshot (same payload shape) and the live-data watchdog re-reads only when
// the push went silent.
window.SolarMinerLive.channel('overview', {endpoint: '/api/agent/local/overview', maxAgeMs: 15000, transport: 'rest', pollMs: 15000})
  .subscribe((data, meta) => {
    if (data) { render(data); if ($('notice').dataset.kind === 'connection') $('notice').hidden = true; $('connection').className = 'badge online'; text('connection','agent_connected'); text('updated',t('updated_time', {time: new Date().toLocaleTimeString(window.SolarMinerI18n.locale)})); }
    else if (meta.error) { current = null; controls(); $('connection').className = 'badge offline'; text('connection','agent_unavailable'); notice(t('could_not_load_proxy_data_error', {error: window.SolarMinerI18n.s(meta.error.message || '')}),true,'connection'); }
  });
window.SolarMinerLive.start();
$('external-host').addEventListener('input',()=>{hostDirty=true;});
async function setRollMode(mode) {
  if (busy) return;
  busy = true; controls();
  try {
    await command('/api/agent/local/proxy/roll-mode?mode=' + mode);
    notice('fee_distribution_changed_the_local_proxy_res');
  } catch (error) { notice(window.SolarMinerI18n.s(error.message), true); }
  finally { busy = false; await refresh(); controls(); }
}
$('roll-random').addEventListener('click',()=>setRollMode('random'));
$('roll-stateful').addEventListener('click',()=>setRollMode('stateful'));
$('external-form').addEventListener('submit',event=>{event.preventDefault(); activate('external');});
$('proxy-local').addEventListener('click',()=>activate('local'));
$('proxy-discover').addEventListener('click',async()=>{
  if (busy) return; busy=true; controls(); text('discovery-status','searching_the_local_network'); $('proxy-candidates').replaceChildren();
  try {
    const response=await fetch('/api/agent/local/proxy/discover',{method:'POST'}); if (!response.ok) throw new Error('HTTP ' + response.status); const candidates=await response.json();
    text('discovery-status', candidates.length ? 'choose_a_discovered_proxy_the_connection_cha' : 'no_proxy_found_you_can_enter_a_known_host_ma');
    for (const candidate of candidates) {
      const button=document.createElement('button'); button.type='button'; button.className='button subtle'; button.textContent=candidate.host + ' ' + t('select');
      button.addEventListener('click',()=>{ $('external-host').value=candidate.host;hostDirty=true;$('external-host').focus(); }); $('proxy-candidates').append(button);
    }
  } catch (error) { text('discovery-status',t('discovery_failed_error', {error: window.SolarMinerI18n.s(error.message)})); }
  finally {busy=false;controls();}
});
$('refresh').addEventListener('click',refresh); refresh();
