#!/usr/bin/env node
// NODE_PATH=<playwright>/node_modules OPERATIONS_BROWSER=<chromium> node scripts/check-operations-tools-browser.cjs
// No hardware or production network activity: every API request is intercepted.
const assert=require('node:assert/strict'), fs=require('node:fs'), path=require('node:path'), http=require('node:http');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../src/main/resources/static');
const server=http.createServer((request,response)=>{
  const file=path.resolve(root,'.'+new URL(request.url,'http://localhost').pathname);
  if(!file.startsWith(root+path.sep)||!fs.existsSync(file)||fs.statSync(file).isDirectory()){response.writeHead(404).end();return;}
  response.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html'); fs.createReadStream(file).pipe(response);
});
(async()=>{
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const browser=await chromium.launch({headless:true,...(process.env.OPERATIONS_BROWSER?{executablePath:process.env.OPERATIONS_BROWSER}:{})});
  try{
    const page=await browser.newPage({viewport:{width:1440,height:1000}}), errors=[], writes=[], jobs=new Map();
    page.on('pageerror',error=>errors.push(error.message));
    // Exercise the request-ID fallback used on insecure LAN HTTP origins.
    await page.addInitScript(()=>Object.defineProperty(Crypto.prototype,'randomUUID',{value:undefined,configurable:true}));
    let offline=false, tuningRunning=false, updateStarted=false, updateReads=0;
    const wallets=[{coin:'monero',name:'Monero',ticker:'XMR',usesDefault:false,route:{poolUrl:'stratum+ssl://pool.example:443',wallet:'4'+'A'.repeat(94),worker:'studio'},defaultTarget:{available:true,poolUrl:'stratum+ssl://house.example:443',maskedWallet:'4ABC…1234'}},
      {coin:'pearl',name:'Pearl',ticker:'PRL',usesDefault:false,route:{poolUrl:'stratum+ssl://prl.example:443',wallet:'prl1'+'q'.repeat(40),worker:'gpu'},defaultTarget:{available:false}}];
    const workers=Array.from({length:1000},(_,i)=>({deviceId:`GPU-${String(i).padStart(4,'0')}`,agentId:'own',own:true,model:'NVIDIA RTX 4090',vendor:'NVIDIA',index:i,hardwareType:'GPU',status:'STOPPED',coin:'pearl',powerMode:'MANUAL',externalControlEnabled:false,startOnBoot:false,collectedAt:new Date().toISOString(),startBlocked:null,pauseBlocked:null}));
    const logBytes=Buffer.from('ü <img src=x onerror="window.xss=true">\nMiner output\n','utf8');
    await page.route('**/api/**',async route=>{
      const request=route.request(), url=new URL(request.url()); assert(url.pathname.startsWith('/api/agent/local/operations/'),'Legacy endpoint called');
      const send=value=>route.fulfill({json:value});
      if(url.pathname.endsWith('/workspace')){
        const q=url.searchParams.get('q')||'', filtered=workers.filter(row=>row.deviceId.includes(q)); const size=Math.min(50,Number(url.searchParams.get('size'))||50), pages=Math.max(1,Math.ceil(filtered.length/size)), current=Math.min(pages-1,Number(url.searchParams.get('page'))||0);
        await send({agents:[{agentId:'own',name:'Studio',own:true,collectedAt:new Date().toISOString()}],workers:filtered.slice(current*size,(current+1)*size),page:current,pages,total:filtered.length}); return;
      }
      if(url.pathname.endsWith('/configuration/software')){await send([{id:'peakminer',coin:'pearl',coinName:'Pearl',hardware:'GPU',installed:true}]);return;}
      if(url.pathname.endsWith('/tools/tasks')&&request.method()==='POST'){
        const body=request.postDataJSON(); writes.push(body); assert(body.id.length>=16);
        const job={id:body.id,action:body.action,state:'queued',createdAt:new Date().toISOString(),error:null}; jobs.set(job.id,job);
        if(body.action==='tuning_start')tuningRunning=true; if(body.action==='tuning_cancel')tuningRunning=false;
        if(body.action==='update_start'){updateStarted=true;updateReads=0;}
        if(body.action==='clock_apply'){await route.abort();return;}
        await route.fulfill({status:202,json:job}); return;
      }
      if(url.pathname.includes('/tools/tasks/')){const job=jobs.get(url.pathname.split('/').at(-1)); if(!job){await route.fulfill({status:404});return;} job.state='succeeded';await send(job);return;}
      if(url.pathname.endsWith('/tools/logs')){
        const offset=Number(url.searchParams.get('offset')); const end=offset===0?1:logBytes.length;
        await send({nextOffset:end,runId:'fixture-run',data:logBytes.subarray(offset,end).toString('base64'),hasMore:end<logBytes.length});return;
      }
      if(url.pathname.includes('/tools/state/')){
        if(offline){await route.abort();return;}
        const section=url.pathname.split('/').at(-1), device=url.searchParams.get('device'), worker=workers.find(row=>row.deviceId===device);
        const gpu=worker?{...worker,driverMinWatts:100,driverMaxWatts:350,minWatts:100,maxWatts:300,currentPowerLimitWatts:240,driverDefaultWatts:300,supportsDynamicPowerScaling:true}:null;
        let payload;
        if(section==='wallets')payload={routes:wallets,balances:[{coin:'pearl',poolBalance:12.5,paidTotal:20,onChainBalance:null}]};
        if(section==='measurements')payload={benchmark:{running:false,phaseIndex:0,phaseCount:1,results:[]},sweep:{running:false,phaseIndex:0,phaseCount:1,runs:[]},benchmarks:[],efficiency:[],consent:false,uploads:{manual:{status:'IDLE'},periodic:{status:'NOT_SHARED'}}};
        if(section==='hardware')payload={workers:worker?[worker]:[],gpus:gpu?[gpu]:[],energy:{settings:{pricePerKwh:0.25,currency:'EUR'},today:{kilowattHours:3.4,cost:0.85}}};
        if(section==='tuning')payload={gpus:gpu?[gpu]:[],capabilities:gpu?[{deviceId:device,coreClockControl:true,memoryClockControl:true,coreMinMhz:800,coreMaxMhz:2000,memoryMinMhz:500,memoryMaxMhz:6000}]:[],selection:{core:true,memory:true,power:true,goal:'EFFICIENCY'},concurrency:4,
          sessions:worker?[{deviceId:device,running:tuningRunning,phase:tuningRunning?'auto_tuning_phase_sampling':'auto_tuning_complete',candidateIndex:3,candidateCount:18,sampleIndex:4,sampleCount:16,currentCoreMhz:1200,currentMemoryMhz:5000,currentPowerWatts:220,observations:[{score:10},{score:15},{score:13}]}]:[],
          profiles:[],measurements:[],measurementsTotal:0,clocks:{coreMhz:1200,memoryMhz:5000},offsetRange:{minMhz:-2000,maxMhz:1000,pstate:-1}};
        if(section==='updates'){
          updateReads++;
          if(updateStarted&&updateReads===1){await route.abort();return;}
          payload={deployment:'native',automatic:true,channel:'beta',currentTag:updateStarted?'pc-agent-beta-222222222222':'pc-agent-beta-111111111111',availableTag:'pc-agent-beta-222222222222',updateAvailable:!updateStarted,phase:'ready',percent:100,error:''};
        }
        if(section==='logs')payload={sources:[{id:'pearl-NVIDIA-0',name:'GPU 0 · Pearl'}]};
        assert(payload,'Unrecognized section'); await send({collectedAt:new Date().toISOString(),payload,error:null});return;
      }
      await route.fulfill({status:404});
    });
    const base=`http://127.0.0.1:${server.address().port}/console/tools.html`;
    const ready=()=>page.waitForFunction(()=>document.querySelector('#tool-connection').classList.contains('live') && document.querySelector('#tool-panel').dataset.section === location.hash.slice(1));
    const done=async action=>{
      await page.waitForFunction(action=>[...document.querySelectorAll('#tool-tasks .tool-task')].some(node=>node.querySelector('.succeeded')),action);
      await page.waitForFunction(()=>[...document.querySelectorAll('#tool-tasks .tool-task .state')].every(node=>!node.classList.contains('queued')&&!node.classList.contains('running')));
    };
    await page.goto(base+'#tuning');await ready();
    assert.equal(await page.getByRole('spinbutton',{name:'Concurrent GPUs (1–64)',exact:true}).inputValue(),'4');
    await page.locator('a[href="#wallets"]').click();await ready();
    await page.getByLabel('Pool worker name',{exact:true}).fill('new-worker');
    await page.getByRole('button',{name:'Save wallet/pool route',exact:true}).click();await done('route_save');
    assert.equal(writes.at(-1).arguments.worker,'new-worker'); assert.equal(writes.at(-1).arguments.coin,'monero');
    await page.locator('a[href="#measurements"]').click();await ready();await page.waitForFunction(()=>document.querySelectorAll('.picker-row').length===50);
    assert.equal(await page.locator('.picker-row').count(),50);
    await page.getByRole('button',{name:'Select this page',exact:true}).click();
    assert.equal(await page.locator('.picker-row input:checked').count(),50);
    await page.locator('#picker-clear').click();
    await page.locator('.picker-row input').nth(0).check();await page.locator('.picker-row input').nth(1).check();
    await page.getByLabel('Pearl',{exact:true}).check();await page.getByRole('spinbutton',{name:'Concurrent GPUs (1–64)',exact:true}).fill('2');
    await page.getByRole('button',{name:'Start benchmark',exact:true}).click();await done('benchmark_start');
    assert.deepEqual(writes.at(-1).arguments.workers,['GPU-0000','GPU-0001']);assert.deepEqual(writes.at(-1).arguments.coins,['pearl']);assert.equal(writes.at(-1).arguments.concurrency,2);
    await page.locator('a[href="#hardware"]').click();await ready();await page.locator('.picker-row input').first().check();
    await page.waitForFunction(()=>document.querySelector('input[type="number"][max="350"]'));
    await page.getByLabel('Manual power limit (W)',{exact:true}).fill('250');await page.getByRole('button',{name:'Save power limit',exact:true}).click();await done('power_limit');assert.equal(writes.at(-1).arguments.watts,250);
    await page.getByLabel('Power mode',{exact:true}).selectOption('EFFICIENCY');await page.getByRole('button',{name:'Save operating mode',exact:true}).click();await done('power_mode');assert.equal(writes.at(-1).arguments.mode,'EFFICIENCY');
    await page.getByLabel('Start this worker when the agent starts',{exact:true}).check();await page.getByRole('button',{name:'Save worker permissions',exact:true}).click();await done('worker_policy');assert.equal(writes.at(-1).arguments.startOnBoot,true);
    await page.locator('a[href="#tuning"]').click();await ready();await page.waitForFunction(()=>document.querySelectorAll('.tool-field select').length>0);
    await page.getByLabel('Operating goal',{exact:true}).selectOption('PROFIT_PER_DAY');await page.getByRole('button',{name:'Save calibration settings',exact:true}).click();await done('tuning_selection');assert.equal(writes.at(-1).arguments.selection.goal,'PROFIT_PER_DAY');
    await page.getByRole('button',{name:'Calibrate device now',exact:true}).click();await done('tuning_start');
    await page.waitForFunction(()=>!document.querySelector('.tool-chart').hasAttribute('hidden'));
    await page.getByRole('button',{name:'Cancel calibration for this device',exact:true}).click();await done('tuning_cancel');
    await page.waitForFunction(()=>document.querySelector('.tool-chart').hasAttribute('hidden'));
    await page.getByLabel('Core minimum (MHz)',{exact:true}).fill('1200');await page.getByLabel('Core maximum (MHz)',{exact:true}).fill('1300');
    await page.getByRole('button',{name:'Apply clocks',exact:true}).click();await done('clock_apply');assert.equal(writes.at(-1).arguments.coreMin,1200);assert.equal(writes.at(-1).arguments.memoryOffset,null);
    await page.evaluate(()=>window.scrollTo(0,0));
    await page.screenshot({path:'/tmp/solarminer-tools-tuning.png',fullPage:true});
    offline=true;await page.locator('#tool-refresh').click();await page.waitForFunction(()=>document.querySelector('#tool-connection').classList.contains('offline'));
    assert.equal(await page.getByRole('button',{name:'Apply clocks',exact:true}).isDisabled(),true);
    offline=false;await page.locator('#tool-refresh').click();await ready();
    await page.locator('a[href="#updates"]').click();await ready();
    page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'Install update and restart agent',exact:true}).click();await done('update_start');
    await page.waitForFunction(()=>document.querySelector('#tool-content').textContent.includes('The agent is running the expected new version.'));
    assert.equal(writes.filter(row=>row.action==='update_start').length,1);
    await page.locator('a[href="#logs"]').click();await ready();
    await page.waitForFunction(()=>document.querySelector('.tool-log').textContent.includes('ü <img'));
    assert.equal(await page.evaluate(()=>window.xss),undefined);assert.equal(await page.locator('.tool-log img').count(),0);
    await page.locator('#language').selectOption('de');await page.waitForFunction(()=>document.documentElement.lang==='de');await ready();
    await page.locator('a[href="#hardware"]').click();await ready();assert.equal(await page.getByRole('button',{name:'Leistungsgrenze speichern',exact:true}).count(),1);
    await page.setViewportSize({width:390,height:844});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    await page.screenshot({path:'/tmp/solarminer-tools-mobile.png',fullPage:true});
    assert.deepEqual(await page.evaluate(()=>[...OperationsI18n.missing]),[]);assert.deepEqual(errors,[]);
    assert(writes.every(row=>row.id.length>=16));assert(new Set(writes.map(row=>row.id)).size===writes.length);
    console.log('PASS: independent tools, 1,000-worker picker, wallet/pool, benchmark selection, power/modes/policy, tuning goal/start/cancel/clocks, update restart verification, UTF-8-safe inert logs, DE/EN and mobile; no repeated writes.');
  }finally{await browser.close();server.close();}
})().catch(error=>{console.error(error);server.close();process.exitCode=1;});
