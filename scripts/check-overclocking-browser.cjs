// UI-only fixtures: no real miner or driver commands.
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
        for (const language of ['en','de']) {
            const context = await browser.newContext({viewport:{width:1440,height:1100}});
            await context.addInitScript(language => { if (!localStorage.getItem('solarminer.pc-agent.language')) localStorage.setItem('solarminer.pc-agent.language', language); }, language);
            const page = await context.newPage(); page.setDefaultTimeout(10000);
            const errors = [], writes = [];
            page.on('pageerror', error => errors.push(error.message));
            page.on('console', msg => { if (/missing frontend translation/.test(msg.text())) errors.push(msg.text()); });
            const workers = Array.from({length:28}, (_, i) => ({deviceId:`gpu${i}`, hardwareModel:`NVIDIA RTX 3080 · ${i}`,hardwareType:'GPU',vendor:'NVIDIA',coin:'pearl',status:i===0?'MINING':'STOPPED',telemetry:i===0?{terahashPerSecond:1e-9,approximatedPowerUsageWatts:105,temperatureCelsius:62}:null}));
            const options = [{coin:'pearl',coinName:'Pearl',id:'srbminer-multi',name:'SRBMiner-MULTI',device:'GPU',installed:true,selectable:true,compatibleGpuVendors:['NVIDIA']}];
            let failProfiles = false;
            const step = {limitWatts:120,medianHashrateHs:1000,avgPowerWatts:105,stable:true,maxTemperatureC:62};
            let sweep = {running:true,phase:'Preparing',phaseIndex:1,phaseCount:2,secondsRemaining:600,runs:[{id:'r0',deviceId:'gpu0',model:'RTX 3080',coin:'pearl',algorithm:'PearlHash',softwareId:'srbminer-multi',status:'RUNNING',mode:'FULL',plannedLimits:[120,100],steps:[step],samples:4,samplesRequired:12,limitWatts:100}],optimization:{enabled:true,runs:[]}};
            const profiles = [{deviceId:'gpu0',coinId:'pearl',softwareId:'srbminer-multi',algorithm:'PearlHash',hashrateHs:1200,measuredWatts:100,efficiencyHashesPerWatt:12,coreMhz:1300,powerLimitWatts:120}];
            const snapshot = {benchmarks:[],sweeps:[{deviceId:'gpu0',coin:'pearl',algorithm:'PearlHash',minerSoftwareId:'srbminer-multi',steps:[step]}]};
            await page.route('**/api/agent/**', async route => {
                const endpoint = new URL(route.request().url()).pathname.replace('/api/agent/local/', '');
                const json = body => route.fulfill({json:body});
                if (route.request().method() !== 'GET') { writes.push(endpoint); return json({}); }
                if (endpoint === 'workers') return json(workers);
                if (endpoint === 'efficiency') return json(sweep);
                if (endpoint === 'benchmarks') return json({running:false,phase:'Idle',results:[],selectedWorkers:[],liveSeries:[]});
                if (endpoint === 'measurements/profiles') return json(snapshot);
                if (endpoint === 'gpu-tuning/profiles') return failProfiles ? route.fulfill({status:503,json:{}}) : json(profiles);
                if (endpoint === 'gpu-tuning/sessions') return json([]);
                if (endpoint === 'gpu-tuning') return json([{deviceId:'gpu0',model:'RTX 3080',vendor:'NVIDIA',coinId:'pearl',workerStatus:'MINING',tuning:{coreClockControl:true,memoryClockControl:false},selection:{core:true,power:true},powerLimitWatts:120}]);
                if (endpoint === 'gpu-tuning/concurrency') return json({maxConcurrent:1});
                if (endpoint === 'miner-options' || endpoint.startsWith('benchmark-policies/options/')) return json(options);
                if (endpoint === 'benchmark-policies') return json({});
                if (endpoint === 'miner-catalog') return json([{id:'pearl',name:'Pearl',device:'GPU',binaryAvailable:true,miners:options}]);
                if (endpoint === 'benchmarks/sharing') return json(false);
                if (endpoint === 'benchmarks/sharing/upload-status') return json({manual:{status:'IDLE'},periodic:{status:'IDLE'}});
                if (endpoint === 'power-control/identity') return json({name:'Performance Lab'});
                if (endpoint === 'coins' || endpoint === 'status/coin-catalog') return json([{id:'pearl',name:'Pearl',ticker:'PRL',algorithm:'PearlHash'}]);
                if (/events|stream/.test(endpoint)) return route.fulfill({contentType:'text/event-stream',body:':fixture\n\n'});
                return json([]);
            });
            const origin = `http://127.0.0.1:${server.address().port}`;
            await page.goto(origin+'/benchmarks.html');
            await page.waitForFunction(() => document.getElementById('oc-coverage').textContent==='1 / 28');
            assert.equal(await page.locator('#oc-workers .oc-worker').count(), 12);
            assert.equal(await page.locator('#oc-active').textContent(), '1');
            assert.equal(await page.locator('#oc-points').textContent(), '1', 'Saved/live duplicate point counted once');
            assert.match(await page.locator('#oc-workers .oc-worker').first().textContent(), /12 H\/s\/W/);
            assert.equal(await page.locator('#oc-workers .oc-worker').first().locator('progress').getAttribute('value'), '0');
            assert.equal(await page.locator('#oc-configuration').getAttribute('open'), null);
            assert.equal(await page.locator('script[src*="pages/autotuning.js"]').count(), 0, 'No expert controller before request');
            await page.locator('#oc-next').click();
            assert.equal(await page.locator('#oc-workers .oc-worker').first().getAttribute('data-device-id'),'gpu12');
            await page.locator('#oc-search').fill('gpu27');
            assert.equal(await page.locator('#oc-workers .oc-worker').count(),1);
            await page.locator('#oc-search').fill('');
            await page.locator('#oc-filter').selectOption('active');
            assert.equal(await page.locator('#oc-workers .oc-worker').count(),1);
            await page.locator('#oc-workers button').first().click();
            assert.equal(await page.locator('#oc-curves').getAttribute('open'),'');
            await page.locator('#oc-curves > summary').click();
            await page.locator('#oc-workers button').nth(1).click();
            await page.locator('#devices .tuning-card[open]').waitFor();
            assert.equal(await page.locator('#devices .tuning-card').count(),1);
            assert.match(await page.locator('#devices').textContent(),/RTX 3080/);
            await page.locator('#oc-expert > summary').click();
            // New confirmed sweep result survives completion; active count returns to idle.
            sweep = {...sweep,running:false,phase:'Complete',phaseIndex:2,runs:sweep.runs.map(row=>({...row,status:'COMPLETE'}))};
            await page.evaluate(() => window.SolarMinerLive.refresh('efficiency'));
            await page.waitForFunction(() => document.getElementById('oc-active').textContent==='0');
            await page.locator('#oc-filter').selectOption('results');
            assert.equal(await page.locator('#oc-workers .oc-worker').count(),1);
            failProfiles = true;
            await page.evaluate(() => window.SolarMinerLive.refresh('oc-profiles'));
            await page.waitForFunction(() => document.getElementById('oc-freshness').textContent.includes(window.SolarMinerI18n.t('oc_partial')));
            assert.equal(await page.locator('#oc-profiles').textContent(),'1','Retain confirmed profiles on refresh failure');
            await page.evaluate(() => scrollTo(0,0));
            await page.screenshot({path:`/tmp/solarminer-overclocking-${language}-desktop.png`,fullPage:true});
            await page.setViewportSize({width:390,height:844});
            await page.waitForFunction(() => getComputedStyle(document.getElementById('app-navigation')).visibility === 'hidden');
            await page.evaluate(() => scrollTo(0,0));
            assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),true);
            await page.screenshot({path:`/tmp/solarminer-overclocking-${language}-mobile.png`,fullPage:true});
            assert.deepEqual(writes,[],'Read-only browsing must not change mining state');
            failProfiles = false;
            await page.locator('.language-switcher').selectOption(language === 'en' ? 'de' : 'en');
            await page.waitForFunction(lang => window.SolarMinerI18n.language === lang, language === 'en' ? 'de' : 'en');
            await page.goto(origin+'/autotuning.html');
            await page.waitForURL('**/benchmarks.html#oc-expert');
            await page.locator('#devices .tuning-card').waitFor();
            assert.equal(await page.locator('#oc-expert').getAttribute('open'),'');
            assert.deepEqual(errors,[]);
            await context.close();
        }
        console.log('PASS: DE/EN, 28-worker pagination/search/filter, live telemetry/progress, confirmed bests, deduplication, partial failure retention, lazy expert controls, legacy route and mobile layout. No mining writes.');
    } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); server.close(); process.exitCode=1; });
