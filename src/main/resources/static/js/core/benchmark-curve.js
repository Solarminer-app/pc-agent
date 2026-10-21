// Shared power/efficiency/clock plot. Absolute clock readings and target/offset settings stay separate.
(() => {
  const selections=new WeakMap(), highlights=new WeakMap(), clockModes=new WeakMap();
  function render(root, {series, highlight}) {
    const t = window.SolarMinerI18n.t, ui = window.SolarMinerUI, locale = window.SolarMinerI18n.locale;
    const make = (tag, cls, text) => { const e=document.createElement(tag); if(cls)e.className=cls; if(text!=null)e.textContent=text; return e; };
    const restoreFocus=root.contains(document.activeElement);
    const previous=selections.get(root), highlightKey=JSON.stringify(highlight || null);
    const explicitHighlight=highlights.get(root)!==highlightKey;highlights.set(root,highlightKey);
    const pointKey=p=>JSON.stringify([p.line.id,p.power,p.targetCore,p.targetMemory,p.offset,p.pstate,p.stage]);
    root.replaceChildren();
    const points = series.flatMap((line,index) => line.points.map(point => ({...point,line,index})))
      .filter(p => Number.isFinite(p.power) && p.power > 0 && Number.isFinite(p.hashrate) && p.hashrate > 0 && Number.isFinite(p.watts) && p.watts > 0);
    if (!points.length) { root.append(make('p','empty',t('curve_waiting'))); return; }
    const anyObserved=points.some(p=>p.core>0||p.memory>0), anyTargets=points.some(p=>p.targetCore>0||p.targetMemory>0);
    const clockMode=clockModes.get(root) || (!anyObserved&&anyTargets?'targets':'observed');
    const clocks=p=>clockMode==='targets'?{core:p.targetCore,memory:p.targetMemory}:{core:p.core,memory:p.memory};
    const controls=make('label','curve-clock-control',t('curve_clock_view'));
    const choice=make('select'); choice.setAttribute('aria-label',t('curve_clock_view'));
    choice.add(new Option(t('curve_clock_observed'),'observed'));choice.add(new Option(t('curve_clock_targets'),'targets'));choice.value=clockMode;
    choice.addEventListener('change',()=>{clockModes.set(root,choice.value);render(root,{series,highlight});});controls.append(choice);
    const number = value => Number(value).toLocaleString(locale,{maximumFractionDigits:1});
    const mhz = value => Number.isFinite(value) && value > 0 ? `${number(value)} MHz` : '—';
    const detail = make('div','curve-point-detail'); detail.setAttribute('role','status'); detail.setAttribute('aria-live','polite');
    const stage = p => t(`curve_stage_${(p.stage || 'SWEEP').toLowerCase()}`);
    const label = p => `${p.line.label} · ${stage(p)} · ${p.power} W · ${ui.hashrate(p.hashrate)} · ${ui.power(p.watts)} · ${t('curve_core')}: ${mhz(p.core)} · ${t('curve_memory')}: ${mhz(p.memory)}`;
    const svgNS='http://www.w3.org/2000/svg', svg=document.createElementNS(svgNS,'svg');
    svg.setAttribute('viewBox','0 0 1000 410'); svg.setAttribute('role','group'); svg.setAttribute('aria-label',t('curve_accessible'));
    const add=(tag,attrs,text) => { const e=document.createElementNS(svgNS,tag); for(const[k,v]of Object.entries(attrs))e.setAttribute(k,v); if(text!=null)e.textContent=text; svg.append(e); return e; };
    const left=90,right=890,top=32,bottom=330;
    const min=Math.min(...points.map(p=>p.power)),max=Math.max(...points.map(p=>p.power));
    const efficiencyMax=Math.max(...points.map(p=>p.hashrate/p.watts))*1.15;
    const clocksAvailable=points.some(p=>clocks(p).core>0||clocks(p).memory>0);
    const clockMax=Math.max(1,...points.flatMap(p=>[clocks(p).core,clocks(p).memory]).filter(v=>Number.isFinite(v)&&v>0))*1.1;
    const x=v=>left+(v-min)/Math.max(1,max-min)*(right-left);
    const y=v=>bottom-v/efficiencyMax*(bottom-top), cy=v=>bottom-v/clockMax*(bottom-top);
    for(let i=0;i<=4;i++){
      const py=bottom-i*(bottom-top)/4;
      add('line',{x1:left,x2:right,y1:py,y2:py,class:'sweep-grid-line'});
      add('text',{x:left-12,y:py+4,'text-anchor':'end',class:'sweep-axis-label'},ui.hashrate(efficiencyMax*i/4));
      add('text',{x:right+12,y:py+4,class:'sweep-axis-label'},clocksAvailable?number(clockMax*i/4):'—');
    }
    add('text',{x:left,y:18,class:'sweep-axis-label'},t('benchmark_hashes_per_watt'));
    add('text',{x:right,y:18,'text-anchor':'end',class:'sweep-axis-label'},t(!clocksAvailable?'curve_clocks_missing':clockMode==='targets'?'curve_target_axis':'curve_clock_axis'));
    add('text',{x:(left+right)/2,y:390,'text-anchor':'middle',class:'sweep-axis-label'},t('power_target_watts'));
    const caps=[...new Set(points.map(p=>p.power))].sort((a,b)=>a-b);
    for(const [index,cap]of caps.entries()) if(index%Math.max(1,Math.ceil(caps.length/10))===0||index===caps.length-1){
      add('text',{x:x(cap),y:bottom+23,'text-anchor':'middle',class:'sweep-axis-label'},String(cap));
    }
    const palette=['#91e8b4','#79baff','#f4c67d','#bc9bff','#e69ba6'];
    // Connect baseline curves and confirmed tuned envelopes only; searches remain independent scatter points.
    for(const [index,line]of series.entries()){
      const byCap=new Map();
      for(const p of points.filter(p=>p.line===line&&p.stable!==false&&(p.stage==='SWEEP'||p.stage==='RESULT'))){
        const prior=byCap.get(p.power); if(!prior||p.hashrate/p.watts>prior.hashrate/prior.watts)byCap.set(p.power,p);
      }
      const path=[...byCap.values()].sort((a,b)=>a.power-b.power);
      if(path.length>1)add('polyline',{points:path.map(p=>`${x(p.power)},${y(p.hashrate/p.watts)}`).join(' '),fill:'none',stroke:palette[index%palette.length],'stroke-width':line.source==='SWEEP'?3:2,'stroke-dasharray':line.source==='SWEEP'?'none':'7 4',class:'sweep-curve-line'});
    }
    const rank=p=>p.stage==='RESULT'?3:p.stage==='SEARCH_RESULT'?2:['SWEEP','OPERATING'].includes(p.stage)?1:0;
    for(const [field,color]of [['core','#69c8ff'],['memory','#e3a0ed']]){
      const envelope=new Map();
      for(const p of points.filter(p=>p.stable!==false&&Number.isFinite(clocks(p)[field])&&clocks(p)[field]>0)){
        const old=envelope.get(p.power);
        if(!old||rank(p)>rank(old)||rank(p)===rank(old)&&p.hashrate/p.watts>old.hashrate/old.watts)envelope.set(p.power,p);
      }
      const path=[...envelope.values()].sort((a,b)=>a.power-b.power);
      if(path.length>1)add('polyline',{points:path.map(p=>`${x(p.power)},${cy(clocks(p)[field])}`).join(' '),fill:'none',stroke:color,'stroke-width':2,'stroke-dasharray':field==='core'?'5 5':'2 5',class:`curve-${field}-line`});
      for(const p of path){
        const dot=add('circle',{cx:x(p.power),cy:cy(clocks(p)[field]),r:3,fill:color,class:`curve-${field}-point`});
        const title=document.createElementNS(svgNS,'title');title.textContent=`${p.line.label} · ${p.power} W · ${mhz(clocks(p)[field])}`;dot.append(title);
      }
    }
    const focusLine=add('line',{x1:left,x2:left,y1:top,y2:bottom,class:'curve-focus-line',visibility:'hidden'});
    function show(p,node){
      selections.set(root,pointKey(p));
      svg.querySelectorAll('.curve-selected').forEach(e=>e.classList.remove('curve-selected')); node.classList.add('curve-selected');
      focusLine.setAttribute('x1',x(p.power));focusLine.setAttribute('x2',x(p.power));focusLine.setAttribute('visibility','visible');
      detail.replaceChildren(make('strong','',label(p)));
      const target=t('curve_targets',{core:mhz(p.targetCore),memory:mhz(p.targetMemory),offset:p.offset==null?'—':`${p.offset>=0?'+':''}${p.offset} MHz (P${p.pstate??'—'})`});
      detail.append(make('p','muted',target),make('small','muted',t('curve_point_evidence',{samples:p.samples??'—',state:p.stable===false?t('unstable'):p.stage==='RESULT'&&p.stable?t('curve_confirmed'):t('curve_unconfirmed')})));
    }
    let initial=null;
    for(const p of points){
      const node=add('circle',{cx:x(p.power),cy:y(p.hashrate/p.watts),r:p.stage==='RESULT'?7:p.stage==='SAMPLE'?6:4.5,
        fill:p.stable===false?'#ed8793':palette[p.index%palette.length],stroke:p.stage==='RESULT'?'#f1f8ff':'#152432','stroke-width':p.stage==='RESULT'?2:1,
        opacity:p.stage==='SAMPLE'?0.7:p.stage==='SEARCH_RESULT'?0.65:1,tabindex:0,role:'button',class:`sweep-curve-point curve-stage-${p.stage}`,'aria-label':label(p)});
      const title=document.createElementNS(svgNS,'title');title.textContent=label(p);node.append(title);
      node.addEventListener('click',()=>show(p,node)); node.addEventListener('focus',()=>show(p,node));
      node.addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();show(p,node);}});
      const profile=highlight?.tunedProfile;
      if(!explicitHighlight&&previous===pointKey(p))initial={p,node};
      if(explicitHighlight&&highlight&&p.line.software===highlight.minerSoftwareId&&p.power===highlight.limitWatts
        &&(!profile&&['SWEEP','OPERATING'].includes(p.stage)||profile&&p.targetCore===profile.coreMhz&&p.targetMemory===profile.memoryMhz&&p.offset===profile.memoryOffsetMhz))initial={p,node};
      if(!initial&&p.stage==='SAMPLE')initial={p,node};
    }
    const legend=make('div','sweep-chart-legend');
    series.forEach((line,index)=>{const item=make('span','',line.label);item.style.setProperty('--series-color',palette[index%palette.length]);legend.append(item);});
    for(const [key,color]of [[clockMode==='targets'?'curve_target_core':'curve_core','#69c8ff'],[clockMode==='targets'?'curve_target_memory':'curve_memory','#e3a0ed']]){const item=make('span','',t(key));item.style.setProperty('--series-color',color);legend.append(item);}
    root.append(controls,svg,legend,make('p','muted curve-help',t('curve_explanation')),detail);
    if(initial){show(initial.p,initial.node);if(restoreFocus)initial.node.focus({preventScroll:true});}else detail.append(make('p','muted',t('curve_select_point')));
  }
  window.SolarMinerBenchmarkCurve={render};
})();
