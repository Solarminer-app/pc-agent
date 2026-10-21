// Real measurement page and assets, mocked local APIs; never starts miners.
// NODE_PATH=<playwright node_modules> node scripts/check-benchmark-workflow-browser.cjs
const {chromium} = require('playwright');
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), http = require('node:http');
const root = path.resolve(__dirname, '../src/main/resources/static');
const server = http.createServer((req, res) => {
  const file = path.resolve(root, '.' + new URL(req.url, 'http://localhost').pathname);
  if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) return res.writeHead(404).end();
  res.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html');
  fs.createReadStream(file).pipe(res);
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({headless:true, executablePath:process.env.OPERATIONS_BROWSER || '/home/lukas/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome'});
  try {
    const context = await browser.newContext({viewport:{width:1440,height:1000}});
    await context.addInitScript(() => {
      if (!localStorage.getItem('workflow-test-initialized')) {
        localStorage.setItem('solarminer.pc-agent.language', 'en');
        localStorage.setItem('solarminer.pc-agent.benchmark-workers', JSON.stringify(['gpuA']));
        localStorage.setItem('solarminer.pc-agent.benchmark-coins', JSON.stringify(['monero']));
        localStorage.setItem('solarminer.pc-agent.benchmark-auto-start', 'true');
        localStorage.setItem('workflow-test-initialized', 'true');
      }
    });
    const page = await context.newPage(), errors = [], writes = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', msg => { if (/missing.*i18n|unknown.*translation|missing frontend/i.test(msg.text())) errors.push(msg.text()); });
    const idleBenchmark = () => ({running:false, mode:'LIVE', phase:'Idle', selectedWorkers:[], results:[], liveSeries:[], autoStartResults:[], phaseIndex:0, phaseCount:0});
    const idleSweep = () => ({running:false, phase:'Idle', results:[], runs:[], phaseIndex:0, phaseCount:0});
    let benchmark = idleBenchmark(), sweep = idleSweep(), rejectSave = false;
    const policies = {gpuA:{combinations:[{coin:'pearl',minerSoftwareId:'srbminer-multi'}],allowCoinSwitch:false,allowMinerSwitch:true,maxTemperatureC:72}};
    const options = [
      {coin:'pearl',coinName:'Pearl',id:'srbminer-multi',name:'SRBMiner-MULTI',device:'GPU',installed:true,selectable:true,compatibleGpuVendors:['NVIDIA']},
      {coin:'pearl',coinName:'Pearl',id:'peakminer',name:'PeakMiner',device:'GPU',installed:false,selectable:true,compatibleGpuVendors:['NVIDIA']},
      {coin:'quantus',coinName:'Quantus',id:'srbminer-multi',name:'SRBMiner-MULTI',device:'GPU',installed:true,selectable:true,compatibleGpuVendors:['NVIDIA']},
      {coin:'monero',coinName:'Monero',id:'xmrig',name:'XMRig',device:'CPU',installed:true,selectable:true,compatibleGpuVendors:[]}
    ];
    const workers = [
      {deviceId:'gpuA',hardwareModel:'NVIDIA RTX 3080',hardwareType:'GPU',vendor:'NVIDIA',coin:'pearl',powerMode:'COIN_REVENUE_PER_KWH',status:'STOPPED'},
      {deviceId:'cpu',hardwareModel:'AMD Ryzen 9',hardwareType:'CPU',vendor:'AMD',coin:'monero',powerMode:'MANUAL',status:'STOPPED'}
    ];
    await page.route('**/api/agent/**', async route => {
      const url = new URL(route.request().url()), method = route.request().method(), endpoint = url.pathname.replace('/api/agent/local/', '');
      const json = body => route.fulfill({json:body});
      if (method === 'PUT' && endpoint.startsWith('benchmark-policies/')) {
        const body = route.request().postDataJSON(); writes.push({endpoint,body});
        if (rejectSave) return route.fulfill({status:409,json:{message:'benchmark_policy_save_failed'}});
        policies[decodeURIComponent(endpoint.split('/').pop())] = body; return json(body);
      }
      if (method === 'POST') {
        const body = route.request().postData() ? route.request().postDataJSON() : null; writes.push({endpoint,body});
        if (endpoint === 'efficiency') {sweep = {...idleSweep(),optimization:{enabled:body.autoTuneAfterSweep,runs:[{id:'gpuA|pearl|srb',deviceId:'gpuA',coin:'pearl',softwareId:'srbminer-multi',state:'RUNNING',detail:'auto_tuning_phase_power_refining',candidateIndex:19,candidateCount:24,sampleIndex:5,sampleCount:32}]},running:true,phase:'Preparing',phaseCount:2,runs:[{id:'gpuA|pearl|srb',deviceId:'gpuA',model:'NVIDIA RTX 3080',coin:'pearl',algorithm:'PearlHash',softwareId:'srbminer-multi',status:'RUNNING',mode:'FULL',plannedLimits:[200,180],steps:[{limitWatts:200,avgPowerWatts:190,medianHashrateHs:1000,maxTemperatureC:60,stable:true}],samplesRequired:12,samples:4,limitWatts:180}]}; return json(sweep);}
        if (endpoint === 'efficiency/cancel') {sweep = idleSweep(); return json(sweep);}
        if (endpoint === 'benchmarks') {benchmark = {...idleBenchmark(),running:true,phase:'Preparing',selectedWorkers:body.workers}; return json(benchmark);}
        if (endpoint === 'benchmarks/cancel') {benchmark = idleBenchmark(); return json(benchmark);}
        return json({});
      }
      if (endpoint === 'measurements/profiles') return json({benchmarks:[],sweeps:[],benchmarkStorage:{},sweepStorage:{}});
      if (endpoint === 'benchmarks/comparison' || endpoint.startsWith('coin-hopping/candidates')) return json([]);
      if (endpoint === 'workers') return json(workers);
      if (endpoint === 'miner-options') return json(options);
      if (endpoint === 'economics') return json({settings:{pricePerKwh:0.3,currency:'EUR'},activeSessions:[]});
      if (endpoint === 'earnings') return json([]);
      if (endpoint === 'benchmark-policies') return json(policies);
      if (endpoint.startsWith('benchmark-policies/options/')) return json(options.filter(row => endpoint.endsWith('/cpu') ? row.device === 'CPU' : row.device === 'GPU'));
      if (endpoint === 'miner-catalog') return json([{id:'pearl',name:'Pearl',device:'GPU',binaryAvailable:true},{id:'monero',name:'Monero',device:'CPU',binaryAvailable:true}]);
      if (endpoint === 'benchmarks') return json(benchmark);
      if (endpoint === 'efficiency') return json(sweep);
      if (endpoint === 'benchmarks/sharing') return json(false);
      if (endpoint === 'benchmarks/sharing/upload-status') return json({manual:{status:'IDLE'},periodic:{status:'IDLE'}});
      if (endpoint === 'power-control/identity') return json({name:'Benchmark Test'});
      if (endpoint === 'wallets') return json([]);
      if (endpoint === 'coins' || endpoint === 'status/coin-catalog') return json([{id:'pearl',name:'Pearl',ticker:'PRL',algorithm:'PearlHash'},{id:'quantus',name:'Quantus',ticker:'QUAN',algorithm:'QPoW (Poseidon2)'}]);
      if (/events|stream/.test(endpoint)) return route.fulfill({contentType:'text/event-stream',body:':fixture\n\n'});
      return json({});
    });
    const origin = `http://127.0.0.1:${server.address().port}`;
    await page.goto(origin + '/benchmarks.html');
    const gpuCard = page.locator('.benchmark-worker-card[data-worker="gpuA"]');
    await gpuCard.locator('fieldset').first().waitFor();
    await page.waitForFunction(() => !document.getElementById('run-sweep').disabled);
    assert.equal(await page.locator('#coin-selection').count(), 0, 'Global coin filter must not narrow worker scope');
    assert.equal(await page.locator('#overview-selected').textContent(), '1');
    assert.equal(await page.locator('#overview-combinations').textContent(), '1');
    await gpuCard.locator('fieldset').nth(1).locator('input').check();
    await gpuCard.locator('input[type=number]').fill('69');
    assert.equal(await page.locator('#overview-combinations').textContent(), '2');
    await page.screenshot({path:'/tmp/solarminer-benchmark-workflow-desktop.png',fullPage:true});
    // Operating permissions changed in Workers while this measurement editor was open.
    policies.gpuA.allowCoinSwitch = true; policies.gpuA.allowMinerSwitch = false;
    await page.locator('#run-sweep').click();
    await page.waitForFunction(() => document.getElementById('sweep-cancel').hidden === false);
    assert.deepEqual(writes.slice(0,2).map(row => row.endpoint), ['benchmark-policies/gpuA','efficiency']);
    assert.deepEqual(writes[1].body, {coins:['pearl','quantus'],workers:['gpuA'],maxConcurrentGpus:1,autoTuneAfterSweep:true});
    assert.equal(await page.locator('#auto-tune-after-sweep').isChecked(), true);
    assert.equal(await page.locator('#auto-tune-after-sweep').isDisabled(), true);
    assert.match(await page.locator('#optimization-runs').textContent(), /19\/24/);
    assert.match(await page.locator('#optimization-runs').textContent(), /Rechecking power limits around tuned settings/);
    assert.equal(await page.locator('#sweep-live-chart').isVisible(), true);
    assert.equal(await page.locator('#sweep-live-chart .sweep-curve-point').count(), 1);
    assert.equal(writes[0].body.maxTemperatureC, 69);
    assert.equal(writes[0].body.allowCoinSwitch, true);
    assert.equal(writes[0].body.allowMinerSwitch, false);
    assert.equal(await gpuCard.locator('input[type=number]').isDisabled(), true);
    assert.equal(await page.locator('#run-installed').isDisabled(), true);
    assert.equal(await page.locator('#run-sweep').isDisabled(), true);
    await page.locator('#sweep-cancel').click();
    // The real live-data module re-reads the server after cancellation.
    await page.waitForFunction(() => !document.getElementById('run-sweep').disabled);
    rejectSave = true;
    await gpuCard.locator('input[type=number]').fill('68');
    const before = writes.filter(row => row.endpoint === 'efficiency').length;
    await page.locator('#run-sweep').click();
    await page.waitForFunction(() => document.getElementById('notice').classList.contains('error'));
    assert.equal(writes.filter(row => row.endpoint === 'efficiency').length, before, 'Failed save must not start measurement');
    rejectSave = false;
    await page.locator('#clear-workers').click();
    await page.locator('.benchmark-worker-card[data-worker="cpu"] input[data-hardware-type]').check();
    await page.locator('.benchmark-worker-card[data-worker="cpu"] fieldset').waitFor();
    assert.equal(await page.locator('#run-sweep').isDisabled(), true, 'CPU-only selection cannot run a GPU sweep');
    await page.locator('.benchmark-alternatives > summary').click();
    await page.locator('#run-live').click();
    await page.waitForFunction(() => !document.getElementById('cancel').hidden);
    const live = writes.filter(row => row.endpoint === 'benchmarks').at(-1).body;
    assert.equal(live.startMiningAfterBenchmark, false, 'Live observation must never apply the installed-run auto-start checkbox');
    assert.deepEqual(live.workers,['cpu']);
    assert.deepEqual(live.coins,['monero']);
    await page.locator('#cancel').click();
    await page.waitForFunction(() => !document.getElementById('run-live').disabled);
    await page.evaluate(() => localStorage.setItem('solarminer.pc-agent.language','de'));
    await page.reload();
    await page.getByRole('heading',{name:'Worker & Testauswahl',exact:true}).waitFor();
    assert.equal(await page.evaluate(() => window.SolarMinerI18n.s('auto_tuning_phase_power_refining')), 'Power-Limits um getunte Einstellungen nachprüfen');
    await page.setViewportSize({width:390,height:844});
    await page.evaluate(() => scrollTo(0,0));
    await page.waitForFunction(() => getComputedStyle(document.getElementById('app-navigation')).visibility === 'hidden');
    await page.locator('.app-menu').click();
    await page.locator('.app-menu-close').click();
    await page.waitForFunction(() => getComputedStyle(document.getElementById('app-navigation')).visibility === 'hidden');
    await page.screenshot({path:'/tmp/solarminer-benchmark-workflow-mobile.png',fullPage:true});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true, 'Mobile page must not overflow horizontally');
    await page.goto(origin + '/benchmark-results.html');
    await page.locator('#comparison-gpu option').first().waitFor({state:'attached'});
    await page.waitForFunction(() => document.getElementById('saved-benchmarks').textContent.length > 0);
    await page.locator('.results-raw-details > summary').click();
    assert.equal(await page.locator('#measurement-library').isVisible(), true);
    // Worker table must show the temporary test pair and retain the saved assignment.
    Object.assign(workers[0], {coin:'quantus',coinName:'Quantus',minerSoftwareId:'srbminer-multi',minerSoftwareName:'SRBMiner-MULTI',status:'MINING',
      measurement:{coin:'pearl',minerSoftwareId:'peakminer',phase:'POWER_SWEEP'},
      telemetry:{currentAlgorithm:'PearlHash',terahashPerSecond:0.000001,temperatureCelsius:64,approximatedPowerUsageWatts:145}});
    await page.setViewportSize({width:1440,height:1000});
    await page.goto(origin + '/workers.html');
    await page.waitForFunction(() => document.getElementById('worker-table').textContent.includes('PeakMiner'));
    assert.match(await page.locator('#worker-table').textContent(), /Power Sweep läuft/);
    assert.match(await page.locator('#worker-table').textContent(), /Gespeichert: Quantus · SRBMiner-MULTI/);
    assert.match(await page.locator('#worker-table').textContent(), /145/);
    assert.match(await page.locator('#worker-table').textContent(), /64/);
    workers[0].status = 'STOPPED'; workers[0].telemetry = null;
    await page.locator('#refresh').click();
    await page.waitForFunction(() => document.getElementById('worker-table').textContent.includes('Wartet auf Miner-Telemetrie'));
    assert.match(await page.locator('#worker-table').textContent(), /Power Sweep läuft/);
    workers[0].measurement.phase = 'AUTO_TUNING';
    await page.locator('#refresh').click();
    await page.waitForFunction(() => document.getElementById('worker-table').textContent.includes('Automatische Nachoptimierung läuft'));
    await page.screenshot({path:'/tmp/solarminer-worker-measurement.png',fullPage:true});
    await page.evaluate(() => localStorage.setItem('solarminer.pc-agent.language','en'));
    await page.reload();
    await page.waitForFunction(() => document.getElementById('worker-table').textContent.includes('Automatic refinement in progress'));
    assert.match(await page.locator('#worker-table').textContent(), /Saved: Quantus · SRBMiner-MULTI/);
    assert.deepEqual(errors,[]);
    console.log('PASS: full DE/EN measurement page, scoped auto-save before start, no global filter, CPU-only guard, lock state, failed save, LIVE no auto-start, mobile layout, shared results page.');
    await context.close();
  } finally {await browser.close(); server.close();}
})().catch(error => {console.error(error); server.close(); process.exitCode=1;});
