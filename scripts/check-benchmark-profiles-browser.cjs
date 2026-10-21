// Real static pages and SVGs against synthetic local APIs. No miners or external calls.
const {chromium}=require('playwright');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const root=path.resolve(__dirname,'../src/main/resources/static');
const server=http.createServer((req,res)=>{const file=path.resolve(root,'.'+new URL(req.url,'http://localhost').pathname);
  if(!file.startsWith(root+path.sep)||!fs.existsSync(file)||fs.statSync(file).isDirectory())return res.writeHead(404).end();
  res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html');fs.createReadStream(file).pipe(res);});
(async()=>{
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const browser=await chromium.launch({headless:true,executablePath:process.env.OPERATIONS_BROWSER||'/home/lukas/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome'});
  try{
    for(const language of ['en','de']){
      const context=await browser.newContext({viewport:{width:1440,height:1000}});
      await context.addInitScript(language=>{localStorage.setItem('solarminer.pc-agent.language',language);
        localStorage.setItem('solarminer.pc-agent.benchmark-workers',JSON.stringify(Array.from({length:12},(_,i)=>`gpu${i}`)));},language);
      const page=await context.newPage(),errors=[];let activeLoads=0,maxLoads=0,liveRate=1250;
      page.on('pageerror',e=>errors.push(e.message));page.on('console',msg=>{if(/missing frontend translation/.test(msg.text()))errors.push(msg.text());});
      const now=new Date().toISOString();
      const candidate=(miner,score,profit,core)=>({coin:'pearl',limitWatts:100,hashrateHps:core,measuredWatts:core===1000?90:95,
        usdPerKwh:score,usdPerDay:score*2.4,coinsPerDay:1,profitUsdPerDay:profit,measuredAt:now,marketAt:now,source:'AUTO_TUNING',minerSoftwareId:miner,
        tunedProfile:{softwareVersion:'test-version',coreMhz:core,memoryMhz:core===1000?5000:6000,memoryOffsetMhz:null,memoryOffsetPstate:null}});
      const candidates=[candidate('srbminer-multi',3,.2,1000),candidate('peakminer',2,3,1400),candidate('configured-miner',1,.1,900)];
      const workers=Array.from({length:12},(_,i)=>({deviceId:`gpu${i}`,hardwareModel:'NVIDIA RTX 3080',hardwareType:'GPU',vendor:'NVIDIA',coin:'pearl',minerSoftwareId:'configured-miner',status:'STOPPED',powerMode:'COIN_REVENUE_PER_KWH'}));
      const options=[{coin:'pearl',coinName:'Pearl',id:'srbminer-multi',name:'SRBMiner-MULTI',device:'GPU',installed:true,selectable:true,compatibleGpuVendors:['NVIDIA']},
        {coin:'pearl',coinName:'Pearl',id:'peakminer',name:'PeakMiner',device:'GPU',installed:true,selectable:true,compatibleGpuVendors:['NVIDIA']}];
      const catalog=[{id:'pearl',name:'Pearl',device:'GPU',algorithm:'PearlHash',binaryAvailable:true,miners:options}];
      const policies=Object.fromEntries(workers.map(w=>[w.deviceId,{combinations:options.map(o=>({coin:o.coin,minerSoftwareId:o.id})),allowCoinSwitch:false,allowMinerSwitch:true,maxTemperatureC:75}]));
      const steps=[{limitWatts:100,medianHashrateHs:1000,avgPowerWatts:90,maxTemperatureC:60,stable:true,observedCoreMhz:980,observedMemoryMhz:5000},
        {limitWatts:150,medianHashrateHs:1400,avgPowerWatts:140,maxTemperatureC:64,stable:true,observedCoreMhz:1380,observedMemoryMhz:6000}];
      const sweeps=workers.map(w=>({deviceId:w.deviceId,model:w.hardwareModel,coin:'pearl',algorithm:'PearlHash',bestStableWatts:100,testedAt:now,steps,minerSoftwareId:'srbminer-multi'}));
      const point=(miner,cap,core,memory,stage,stable,rate=1000)=>({coin:'pearl',softwareId:miner,softwareVersion:'test-version',runId:'run',at:now,
        limitWatts:cap,hashrateHs:rate,measuredWatts:cap-10,coreMhz:core,memoryMhz:memory,targetCoreMhz:core+20,targetMemoryMhz:memory,
        memoryOffsetMhz:null,memoryOffsetPstate:null,stage,stable,samples:stage==='RESULT'?32:stage==='SAMPLE'?4:16});
      const curves=()=>[point('srbminer-multi',100,980,5000,'RESULT',true),point('srbminer-multi',150,1380,6000,'SEARCH_RESULT',true,1400),
        {...point('peakminer',100,1380,6000,'RESULT',true,1400),targetCoreMhz:1400},point('peakminer',120,1280,5800,'SAMPLE',null,liveRate)];
      const fleetWorkers=[{deviceId:'gpu0',model:'RTX 3080',configuredMiner:'configured-miner',manualTargetWatts:150,candidates},
        {deviceId:'gpu1',model:'RTX 3080',configuredMiner:'configured-miner',manualTargetWatts:150,candidates:candidates.map(p=>({...p,profitUsdPerDay:p.minerSoftwareId==='peakminer'?4:.4}))},
        {deviceId:'gpu2',model:'No tariff GPU',configuredMiner:'configured-miner',manualTargetWatts:150,candidates:candidates.map(p=>({...p,profitUsdPerDay:null}))}];
      await page.route('**/api/agent/**',async route=>{
        const url=new URL(route.request().url()),endpoint=url.pathname.replace('/api/agent/local/','');
        const json=body=>route.fulfill({json:body});
        if(route.request().method()!=='GET')throw new Error('Comparison test must remain read-only');
        if(endpoint==='workers')return json(workers);
        if(endpoint==='benchmark-policies')return json(policies);
        if(endpoint.startsWith('benchmark-policies/options/')){activeLoads++;maxLoads=Math.max(maxLoads,activeLoads);await new Promise(r=>setTimeout(r,50));activeLoads--;return json(options);}
        if(endpoint==='miner-catalog')return json(catalog);
        if(endpoint==='measurements/profiles')return json({benchmarks:[],sweeps,benchmarkStorage:{},sweepStorage:{}});
        if(endpoint==='measurements/tuning-curve')return json(curves());
        if(endpoint==='gpu-tuning/profiles')return json([]);
        if(endpoint==='gpu-tuning/sessions')return json([{deviceId:'gpu0',running:false,sampleIndex:liveRate}]);
        if(endpoint==='benchmarks/comparison'||endpoint.startsWith('coin-hopping/candidates'))return json(candidates);
        if(endpoint==='economics')return json({settings:{pricePerKwh:.3,currency:'USD'},activeSessions:[]});
        if(endpoint==='power-control/gpus')return json({gpus:[]});
        if(endpoint==='network/fleet-comparison')return json([{agentId:'local',name:'Test PC',available:true,own:true,points:{tariff:{pricePerKwh:.3,currency:'USD'},workers:fleetWorkers,profitModel:'REVENUE_MINUS_ELECTRICITY'}},
          {agentId:'offline',name:'Offline PC',available:false,reason:'unreachable',points:null}]);
        if(endpoint==='benchmarks')return json({running:false,phase:'Idle',results:[],liveSeries:[],selectedWorkers:[],autoStartResults:[]});
        if(endpoint==='efficiency')return json({running:false,phase:'Idle',runs:sweeps.map((p,i)=>({...p,id:`gpu${i}|pearl|srbminer-multi`,softwareId:'srbminer-multi',status:'COMPLETE',mode:'FULL'})),results:[],phaseIndex:0,phaseCount:0});
        if(endpoint==='benchmarks/sharing')return json(false);
        if(endpoint==='benchmarks/sharing/upload-status')return json({manual:{status:'IDLE'},periodic:{status:'IDLE'}});
        if(endpoint==='wallets'||endpoint==='earnings')return json([]);
        if(endpoint==='status/coin-catalog')return json([{id:'pearl',name:'Pearl',ticker:'PRL',algorithm:'PearlHash'}]);
        if(endpoint==='power-control/identity')return json({name:'Test PC'});
        if(/events|stream/.test(endpoint))return route.fulfill({contentType:'text/event-stream',body:':fixture\n\n'});
        return json({});
      });
      const origin=`http://127.0.0.1:${server.address().port}`;
      await page.goto(origin+'/gpu-lab.html?device=gpu0&view=results');
      await page.waitForSelector('#gpu-chart circle');
      assert.equal(await page.locator('header,nav,.app-sidebar').count(),0);
      assert.equal(await page.locator('#lab-live-view').isVisible(),false);
      assert.equal(await page.locator('#lab-combination option').count(),2);
      await page.locator('#lab-combination').selectOption(JSON.stringify(['pearl','peakminer']));
      const resultPoint=page.locator('#gpu-chart .lab-kind-3');
      await resultPoint.waitFor();
      assert(Math.abs(Number(await resultPoint.getAttribute('cx'))-(82+(110-81)/(116.6-81)*880))<1);
      assert(Math.abs(Number(await resultPoint.getAttribute('cy'))-(268-1250/(1400*1.12)*240))<1);
      assert.match(await page.locator('#gpu-records').textContent(),/1280|1[.,]280/);
      assert.match(await page.locator('#gpu-records').textContent(),/1300|1[.,]300/,'Requested clock stays separate from measured clock');
      liveRate=1600;await page.waitForFunction(()=>[...document.querySelectorAll('#gpu-chart .lab-kind-3 title')].some(p=>p.textContent.includes('1.6')||p.textContent.includes('1,6')),{},{timeout:12000});
      await page.screenshot({path:`/tmp/benchmark-multi-gpu-${language}.png`,fullPage:true});
      await page.setViewportSize({width:390,height:844});
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'GPU results fit mobile viewport');
      await page.screenshot({path:`/tmp/benchmark-telemetry-mobile-${language}.png`,fullPage:true});
      await page.setViewportSize({width:1440,height:1000});
      await page.goto(origin+'/benchmark-results.html');
      await page.waitForSelector('.profile-result-profit[data-miner="peakminer"]');
      assert.equal(await page.locator('.profile-result').count(),2);
      assert.equal(await page.locator('.profile-result-efficiency').getAttribute('data-miner'),'srbminer-multi');
      assert.equal(await page.locator('#mode-comparison-miner').count(),0);
      assert.equal(await page.locator('#mode-comparison-same').isVisible(),false,'Same watt limit with different software is not the same point');
      await page.locator('.profile-result-profit button').click();await page.waitForSelector('.curve-selected');
      assert.match(await page.locator('.curve-point-detail').textContent(),/peakminer/);
      await page.evaluate(()=>{document.activeElement?.blur();scrollTo(0,0);});
      await page.screenshot({path:`/tmp/benchmark-profile-results-${language}.png`,fullPage:true});
      await page.setViewportSize({width:390,height:844});
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'Result page must fit mobile viewport');
      await page.evaluate(()=>{document.activeElement?.blur();scrollTo(0,0);});
      await page.screenshot({path:`/tmp/benchmark-profile-results-mobile-${language}.png`,fullPage:true});
      await page.goto(origin+'/fleet.html');
      await page.waitForSelector('.fleet-mode[data-mode="profit"]');
      assert.equal(await page.locator('.fleet-mode').count(),2);
      assert.equal(await page.locator('#fleet-miner').count(),0);
      assert.equal(await page.locator('.fleet-mode[data-mode="profit"]').getAttribute('data-profit-usd'),'7');
      assert.equal(await page.locator('.fleet-mode[data-mode="efficiency"]').getAttribute('data-workers'),'2');
      assert.equal(await page.locator('.fleet-worker').first().locator('.profile-result-profit').getAttribute('data-miner'),'peakminer');
      assert.equal(await page.locator('.fleet-worker').first().locator('.profile-result-efficiency').getAttribute('data-miner'),'srbminer-multi');
      assert.match(await page.locator('#fleet-coverage').textContent(),/1/,'Unavailable peer must remain explicit');
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'Fleet page must fit mobile viewport');
      await page.evaluate(()=>{document.activeElement?.blur();scrollTo(0,0);});
      await page.screenshot({path:`/tmp/benchmark-fleet-mobile-${language}.png`,fullPage:true});
      assert.deepEqual(errors,[]);
      await context.close();
    }
    console.log('PASS: standalone GPU results, measured-watts/hashrate geometry, measured/target clocks, live sample updates, archive profiles, fair fleet coverage and DE/EN mobile layout.');
  }finally{await browser.close();server.close();}
})().catch(error=>{console.error(error);server.close();process.exitCode=1;});
