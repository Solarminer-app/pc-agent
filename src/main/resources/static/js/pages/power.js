(() => {
  const $ = id => document.getElementById(id), t = window.SolarMinerI18n.t;
  const day = date => { const d = new Date(date); return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10); };
  const number = (value, digits = 2) => new Intl.NumberFormat(window.SolarMinerI18n.locale, {maximumFractionDigits: digits}).format(value || 0);
  const rate = value => window.SolarMinerUI?.hashrate ? window.SolarMinerUI.hashrate(value) : `${number(value)} H/s`;
  const from = $('date-from'), to = $('date-to');
  to.value = day(new Date()); from.value = day(Date.now() - 6 * 86400000);
  function svg(points) {
    if (!points.length) { const empty=document.createElement('p'); empty.className='muted'; empty.textContent=t('no_power_history'); return empty; }
    const W = 900, H = 250, pad = 36, maxE = Math.max(0.001, ...points.map(p => p.kilowattHours)), maxH = Math.max(1, ...points.map(p => p.averageHashrateHs));
    const ns='http://www.w3.org/2000/svg', el=(tag, attrs={})=>{const n=document.createElementNS(ns,tag);for(const [k,v] of Object.entries(attrs))n.setAttribute(k,v);return n;};
    const chart=el('svg',{viewBox:`0 0 ${W} ${H}`,preserveAspectRatio:'none','aria-hidden':'true'});
    const grid=el('path',{class:'grid',d:`M${pad} ${pad}H${W-pad} M${pad} ${H/2}H${W-pad} M${pad} ${H-pad}H${W-pad}`}); chart.append(grid);
    const line=(key,max,cls)=>{const p=el('polyline',{class:cls,points:points.map((v,i)=>`${pad+i*(W-2*pad)/Math.max(1,points.length-1)},${H-pad-(v[key]/max)*(H-2*pad)}`).join(' ')});chart.append(p);};
    line('averageHashrateHs',maxH,'hash-line');line('kilowattHours',maxE,'energy-line');
    points.forEach((p,i)=>{if(i%Math.max(1,Math.ceil(points.length/7))!==0&&i!==points.length-1)return;const label=el('text',{x:pad+i*(W-2*pad)/Math.max(1,points.length-1),y:H-7,'text-anchor':'middle'});label.textContent=new Date(p.at).toLocaleDateString(window.SolarMinerI18n.locale,{month:'short',day:'numeric'});chart.append(label);});
    const wrap=document.createElement('div'),scales=document.createElement('div');scales.className='chart-scales';const a=document.createElement('span'),b=document.createElement('span');a.textContent=`${t('hashrate_scale')}: ${rate(maxH)}`;b.textContent=`${t('energy_scale')}: ${number(maxE,3)} kWh`;scales.append(a,b);wrap.append(chart,scales);return wrap;
  }
  function render(data) {
    const rows = (data.daily || []).filter(p => p.runtimeSeconds > 0 || p.kilowattHours > 0), total = rows.reduce((a, p) => ({kwh:a.kwh+p.kilowattHours, runtime:a.runtime+p.runtimeSeconds, measured:a.measured+p.measuredSeconds, hash:a.hash+p.averageHashrateHs*p.runtimeSeconds}), {kwh:0,runtime:0,measured:0,hash:0});
    $('metrics').replaceChildren(...[
      [t('measured_energy'), `${number(total.kwh, 3)} kWh`], [t('average_hashrate'), rate(total.runtime ? total.hash / total.runtime : 0)],
      [t('mining_time'), `${number(total.runtime / 3600, 1)} h`], [t('measurement_coverage'), `${total.runtime ? number(total.measured / total.runtime * 100, 0) : 0} %`]
    ].map(([label,value]) => { const el=document.createElement('article'); el.className='stat'; const s=document.createElement('span');s.textContent=label;const b=document.createElement('strong');b.textContent=value;el.append(s,b);return el; }));
    const fromTime = new Date(`${from.value}T00:00:00`), toTime = new Date(`${to.value}T00:00:00`);
    const chartPoints = toTime - fromTime < 2 * 86400000 ? (data.hourly || []) : rows;
    $('chart').replaceChildren(svg(chartPoints));
    const body = $('daily-rows'); body.replaceChildren(); $('empty').hidden = rows.length > 0;
    for (const p of [...rows].reverse()) { const tr=document.createElement('tr'), values=[new Date(p.at+'T00:00:00').toLocaleDateString(window.SolarMinerI18n.locale),rate(p.runtimeSeconds ? p.averageHashrateHs : 0),`${number(p.runtimeSeconds/3600,1)} h`,`${number(p.kilowattHours,3)} kWh`,`${p.runtimeSeconds ? number(p.measuredSeconds/p.runtimeSeconds*100,0) : 0} %`]; for (const v of values) { const td=document.createElement('td');td.textContent=v;tr.append(td); } body.append(tr); }
  }
  async function load() {
    window.SolarMinerUI?.setConnection('connecting');
    $('range-status').textContent = t('loading_power_history');
    try {
      const start = new Date(`${from.value}T00:00:00`), end = new Date(`${to.value}T00:00:00`); end.setDate(end.getDate()+1);
      if (!from.value || !to.value || to.value < from.value || (end-start) > 366*86400000) throw new Error('invalid_range');
      const query = `from=${encodeURIComponent(start.toISOString())}&to=${encodeURIComponent(end.toISOString())}`;
      const response = await fetch(`/api/agent/local/economics/history?${query}`, {cache:'no-store'});
      if (!response.ok) throw new Error('history_unavailable');
      render(await response.json()); $('range-status').textContent = '';
      window.SolarMinerUI?.setConnection('online');
    } catch (error) { $('range-status').textContent = t(error.message === 'invalid_range' ? 'invalid_date_range' : 'power_history_unavailable'); window.SolarMinerUI?.setConnection(error.message === 'invalid_range' ? 'online' : 'offline'); }
  }
  $('apply').addEventListener('click', load); $('refresh').addEventListener('click', load); load();
})();
