// Result curve and independent telemetry plots. Targets stay in the point details.
(() => {
  const selections=new WeakMap(), highlights=new WeakMap();
  function render(root, {series, highlight, telemetry = false}) {
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
    const number = value => Number(value).toLocaleString(locale,{maximumFractionDigits:1});
    const mhz = value => Number.isFinite(value) && value > 0 ? `${number(value)} MHz` : '—';
    const detail = make('div','curve-point-detail'); detail.setAttribute('role','status'); detail.setAttribute('aria-live','polite');
    const stage = p => t(`curve_stage_${(p.stage || 'SWEEP').toLowerCase()}`);
    const label = p => `${p.line.label} · ${stage(p)} · ${p.power} W · ${ui.hashrate(p.hashrate)} · ${ui.power(p.watts)} · ${ui.hashrate(p.hashrate/p.watts)}/W · ${t('curve_core')}: ${mhz(p.core)} · ${t('curve_memory')}: ${mhz(p.memory)}`;
    const svgNS='http://www.w3.org/2000/svg', svg=document.createElementNS(svgNS,'svg');
    svg.setAttribute('viewBox','0 0 1000 410'); svg.setAttribute('role','group'); svg.setAttribute('aria-label',t('curve_accessible'));
    const add=(tag,attrs,text) => { const e=document.createElementNS(svgNS,tag); for(const[k,v]of Object.entries(attrs))e.setAttribute(k,v); if(text!=null)e.textContent=text; svg.append(e); return e; };
    const left=90,right=890,top=32,bottom=330;
    const min=Math.min(...points.map(p=>p.watts)),max=Math.max(...points.map(p=>p.watts));
    const rateMax=Math.max(...points.map(p=>p.hashrate))*1.15;
    const x=v=>left+(max===min ? .5 : (v-min)/(max-min))*(right-left);
    const y=v=>bottom-v/rateMax*(bottom-top);
    for(let i=0;i<=4;i++){
      const py=bottom-i*(bottom-top)/4;
      add('line',{x1:left,x2:right,y1:py,y2:py,class:'sweep-grid-line'});
      add('text',{x:left-12,y:py+4,'text-anchor':'end',class:'sweep-axis-label'},ui.hashrate(rateMax*i/4));
    }
    add('text',{x:left,y:18,class:'sweep-axis-label'},t('hashrate'));
    add('text',{x:(left+right)/2,y:390,'text-anchor':'middle',class:'sweep-axis-label'},t('curve_measured_watts'));
    const caps=[...new Set(points.map(p=>p.watts))].sort((a,b)=>a-b);
    for(const [index,cap]of caps.entries()) if(index%Math.max(1,Math.ceil(caps.length/10))===0||index===caps.length-1){
      add('text',{x:x(cap),y:bottom+23,'text-anchor':'middle',class:'sweep-axis-label'},number(cap));
    }
    const palette=['#91e8b4','#79baff','#f4c67d','#bc9bff','#e69ba6'];
    // Connect baseline curves and confirmed tuned envelopes only; searches remain independent scatter points.
    for(const [index,line]of series.entries()){
      const byCap=new Map();
      for(const p of points.filter(p=>p.line===line&&p.stable!==false&&(p.stage==='SWEEP'||p.stage==='RESULT'))){
        const prior=byCap.get(p.power); if(!prior||p.hashrate/p.watts>prior.hashrate/prior.watts)byCap.set(p.power,p);
      }
      const path=[...byCap.values()].sort((a,b)=>a.watts-b.watts);
      if(path.length>1)add('polyline',{points:path.map(p=>`${x(p.watts)},${y(p.hashrate)}`).join(' '),fill:'none',stroke:palette[index%palette.length],'stroke-width':line.source==='SWEEP'?3:2,'stroke-dasharray':line.source==='SWEEP'?'none':'7 4',class:'sweep-curve-line'});
    }
    const focusLine=add('line',{x1:left,x2:left,y1:top,y2:bottom,class:'curve-focus-line',visibility:'hidden'});
    function show(p,node){
      selections.set(root,pointKey(p));
      svg.querySelectorAll('.curve-selected').forEach(e=>e.classList.remove('curve-selected')); node.classList.add('curve-selected');
      focusLine.setAttribute('x1',x(p.watts));focusLine.setAttribute('x2',x(p.watts));focusLine.setAttribute('visibility','visible');
      detail.replaceChildren(make('strong','',label(p)));
      const target=t('curve_targets',{core:mhz(p.targetCore),memory:mhz(p.targetMemory),offset:p.offset==null?'—':`${p.offset>=0?'+':''}${p.offset} MHz (P${p.pstate??'—'})`});
      detail.append(make('p','muted',target),make('small','muted',t('curve_point_evidence',{samples:p.samples??'—',state:p.stable===false?t('unstable'):p.stage==='RESULT'&&p.stable?t('curve_confirmed'):t('curve_unconfirmed')})));
    }
    let initial=null;
    for(const p of points){
      const node=add('circle',{cx:x(p.watts),cy:y(p.hashrate),r:p.stage==='RESULT'?7:p.stage==='SAMPLE'?6:4.5,
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
    if (telemetry) renderTelemetry();
    root.append(make('h3','',t('curve_results_title')),svg,legend,make('p','muted curve-help',t('curve_explanation')),detail);
    function renderTelemetry() {
      const section=make('section','benchmark-telemetry');
      section.append(make('h3','',t('auto_tuning_graph_title')),make('p','muted curve-help',t('curve_telemetry_hint')));
      const grid=make('div','benchmark-telemetry-grid');
      const metrics=[
        ['core','auto_tuning_graph_core','MHz'],
        ['memory','auto_tuning_graph_memory','MHz'],
        ['temperature','auto_tuning_graph_temperature','°C'],
        ['watts','auto_tuning_graph_power','W'],
        ['hashrate','auto_tuning_graph_hashrate','H/s']
      ];
      for (const [field,key,unit] of metrics) {
        const card=make('article','benchmark-telemetry-card');card.dataset.metric=field;
        card.append(make('h4','',t(key)));
        const chart=document.createElementNS(svgNS,'svg');
        chart.setAttribute('viewBox','0 0 520 184');chart.setAttribute('role','img');chart.setAttribute('aria-label',t(key));
        const draw=(tag,attrs,text)=>{const node=document.createElementNS(svgNS,tag);for(const [k,v]of Object.entries(attrs))node.setAttribute(k,v);if(text!=null)node.textContent=text;chart.append(node);return node;};
        const valid=p=>Number.isFinite(p[field])&&p[field]>0;
        // Keep missing readbacks absent; never substitute targets, offsets or zero.
        const values=series.flatMap(line=>line.points).filter(valid).map(p=>p[field]);
        if (!values.length) {card.append(make('p','muted',t('auto_tuning_graph_unavailable')));grid.append(card);continue;}
        const min=Math.min(...values),max=Math.max(...values),span=max-min||Math.max(max*.1,1);
        const low=Math.max(0,min-span*.12),high=max+span*.12;
        const y=value=>145-(value-low)/(high-low)*112;
        const format=value=>field==='hashrate'?ui.hashrate(value):`${number(value)} ${unit}`;
        for(let i=0;i<3;i++) {const value=high-(high-low)*i/2,pos=y(value);
          draw('line',{x1:85,x2:500,y1:pos,y2:pos,class:'sweep-grid-line'});
          draw('text',{x:78,y:pos+4,'text-anchor':'end',class:'sweep-axis-label'},format(value));
        }
        series.forEach((line,index)=>{
          const x=i=>85+(line.points.length===1 ? .5 : i/(line.points.length-1))*415;
          let segment=[];
          const flush=()=>{if(segment.length>1)draw('polyline',{points:segment.join(' '),fill:'none',stroke:palette[index%palette.length],'stroke-width':2,'stroke-dasharray':index%2?'5 4':'none'});segment=[];};
          line.points.forEach((p,i)=>{
            if(!valid(p)){flush();return;}
            segment.push(`${x(i)},${y(p[field])}`);
            const dot=draw('circle',{cx:x(i),cy:y(p[field]),r:3,fill:palette[index%palette.length]});
            const title=document.createElementNS(svgNS,'title');title.textContent=`${line.label} · ${i+1} · ${format(p[field])}`;dot.append(title);
          });flush();
        });
        draw('text',{x:85,y:174,class:'sweep-axis-label'},t('auto_tuning_graph_first'));
        draw('text',{x:500,y:174,'text-anchor':'end',class:'sweep-axis-label'},t('auto_tuning_graph_latest'));
        card.append(chart);grid.append(card);
      }
      section.append(grid,legend.cloneNode(true));root.append(section);
    }
    if(initial){show(initial.p,initial.node);if(restoreFocus)initial.node.focus({preventScroll:true});}else detail.append(make('p','muted',t('curve_select_point')));
  }
  window.SolarMinerBenchmarkCurve={render};
})();
