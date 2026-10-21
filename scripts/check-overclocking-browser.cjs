// Isolated HTTP fixtures. This never contacts an agent, miner, driver or pool.
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
    const browser = await chromium.launch({headless: true, executablePath: process.env.OPERATIONS_BROWSER || '/home/lukas/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome'});
    try {
        for (const language of ['en', 'de']) {
            const context = await browser.newContext({viewport: {width: 1440, height: 1100}});
            await context.addInitScript(language => { try { if (!localStorage.getItem('solarminer.pc-agent.language')) localStorage.setItem('solarminer.pc-agent.language', language); } catch (_) { } }, language);
            const errors = [], writes = [];
            console.log(language + ': starting');
            context.on('page', page => { page.on('pageerror', error => errors.push(error.message)); page.on('console', msg => { if (/missing frontend translation/.test(msg.text())) errors.push(msg.text()); }); });
            const workers = Array.from({length: 3}, (_, i) => ({deviceId: `gpu${i}`, hardwareModel: `NVIDIA RTX 3080 · ${i}`, hardwareType: 'GPU', vendor: 'NVIDIA', coin: 'pearl', status: 'MINING', telemetry: {terahashPerSecond: 1e-9, approximatedPowerUsageWatts: 105, temperatureCelsius: 62}}));
            const choices = [{coin: 'pearl', coinName: 'Pearl', id: 'srbminer-multi', name: 'SRBMiner-MULTI', installed: true, selectable: true},
                {coin: 'quantus', coinName: 'Quantus', id: 'wildrig', name: 'WildRig NG', installed: false, selectable: false}];
            const point = {limitWatts: 120, medianHashrateHs: 1000, avgPowerWatts: 105, stable: true, maxTemperatureC: 62};
            let sweep = {running: false, runs: [], devices: [], results: []}, policies = {}, failProfiles = false;
            const confirmed = [{deviceId: 'gpu0', coinId: 'pearl', softwareId: 'srbminer-multi', algorithm: 'PearlHash', hashrateHs: 1200, measuredWatts: 100, coreMhz: 1300, powerLimitWatts: 120}];
            const snapshot = {benchmarks: [], sweeps: [{deviceId: 'gpu0', coin: 'pearl', algorithm: 'PearlHash', minerSoftwareId: 'srbminer-multi', steps: [point]}]};
            await context.route('**/api/agent/**', async route => {
                const endpoint = new URL(route.request().url()).pathname.replace('/api/agent/local/', '');
                const json = body => route.fulfill({json: body});
                if (route.request().method() !== 'GET') {
                    const body = route.request().postDataJSON(); writes.push({endpoint, body});
                    if (endpoint.startsWith('benchmark-policies/')) { policies[endpoint.split('/').pop()] = body; return json(body); }
                    if (endpoint === 'efficiency') {
                        sweep = {running: true, phase: 'benchmark_optimization_tuning', startedAt: new Date().toISOString(), secondsRemaining: 500, preset: body.preset,
                            runs: body.workers.map((device, i) => ({id: device, deviceId: device, model: workers[i].hardwareModel, coin: 'pearl', softwareId: 'srbminer-multi', status: 'RUNNING', plannedLimits: [120, 100, 80], steps: [point], samples: 4, samplesRequired: 12, limitWatts: 100})),
                            optimization: {enabled: true, runs: []}, devices: []}; return json(sweep);
                    }
                    return json({});
                }
                if (endpoint === 'workers') return json(workers);
                if (endpoint === 'efficiency') return json(sweep);
                if (endpoint === 'benchmark-policies') return json(policies);
                if (endpoint.startsWith('benchmark-policies/options/')) return json(choices);
                if (endpoint === 'measurements/profiles') return failProfiles ? route.fulfill({status: 503, json: {}}) : json(snapshot);
                if (endpoint === 'gpu-tuning/profiles') return json(confirmed);
                if (endpoint === 'measurements/tuning-curve') return json([]);
                if (endpoint === 'gpu-tuning/sessions') return json([]);
                if (endpoint === 'power-control/identity') return json({name: 'Performance Lab'});
                if (endpoint === 'status/events') return route.fulfill({contentType: 'text/event-stream', body: ':fixture\n\n'});
                return json([]);
            });
            const origin = `http://127.0.0.1:${server.address().port}`, page = await context.newPage();
            await page.goto(origin + '/benchmarks.html'); console.log(language + ': setup loaded');
            await page.locator('#lab-worker-picker input').first().waitFor();
            await page.screenshot({path: `/tmp/oc-setup-desktop-${language}.png`, fullPage: true, animations: 'disabled'});
            assert.equal(await page.locator('#lab-worker-picker input').count(), 3);
            await page.locator('#lab-next').click();
            await page.waitForFunction(() => !document.getElementById('lab-notice').hidden);
            await page.locator('#lab-worker-picker input').nth(0).check(); await page.locator('#lab-worker-picker input').nth(1).check();
            await page.locator('#lab-next').click();
            await page.locator('[name="lab-preset"][value="QUICK"]').check();
            await page.locator('#lab-next').click();
            assert.equal(await page.locator('#lab-scope fieldset').count(), 2);
            assert.equal(await page.locator('#lab-scope input:disabled').count(), 2);
            await page.locator('#lab-parallel').fill('2'); await page.locator('#lab-autostart').check();
            await page.locator('#lab-next').click();
            assert.equal(await page.locator('#lab-review .lab-review-row').count(), 2);
            await page.locator('#lab-next').click();
            await page.waitForFunction(() => document.getElementById('lab-wizard').hidden);
            const start = writes.find(row => row.endpoint === 'efficiency'); assert.ok(start);
            assert.deepEqual(start.body.workers, ['gpu0', 'gpu1']); assert.equal(start.body.preset, 'QUICK');
            assert.equal(start.body.autoStartMining, true); assert.equal(start.body.autoTuneAfterSweep, true); assert.equal(start.body.maxConcurrentGpus, 2);
            assert.equal(writes.filter(row => row.endpoint.startsWith('benchmark-policies/')).length, 2);
            assert.equal(await page.locator('#sweep-runs, #optimization-runs, #sweep-live-chart').count(), 0);
            const links = page.locator('#lab-worker-links a'); assert.equal(await links.count(), 6);
            for (const link of await links.all()) { assert.equal(await link.getAttribute('target'), '_blank'); assert.equal(await link.getAttribute('rel'), 'noopener'); }
            console.log(language + ': launched'); const popupEvent = context.waitForEvent('page'); await links.first().click(); const gpuPage = await popupEvent;
            await gpuPage.locator('#gpu-model').filter({hasText: /RTX 3080/}).waitFor();
            assert.equal(await gpuPage.locator('header, nav, .app-sidebar').count(), 0);
            await gpuPage.locator('#gpu-chart circle').first().waitFor();
            assert.equal(await gpuPage.locator('#gpu-profiles article').count(), 1);
            await gpuPage.waitForFunction(() => document.body.classList.contains('is-measuring'));
            await gpuPage.locator('#lab-motion').check(); assert.equal(await gpuPage.locator('body.lab-still').count(), 1);
            console.log(language + ': GPU page rendered'); await gpuPage.screenshot({path: `/tmp/oc-gpu-live-${language}.png`, fullPage: true, animations: 'disabled'});
            console.log(language + ': live verified'); await gpuPage.locator('#lab-view-results').click(); assert.equal(await gpuPage.locator('#lab-live-view').isVisible(), false);
            await gpuPage.reload(); assert.equal(await gpuPage.locator('#lab-live-view').isVisible(), false);
            sweep = {...sweep, devices: [{deviceId: 'gpu0', released: true, miningState: 'STARTED', detail: 'lab_device_released'}], runs: sweep.runs.map(row => row.deviceId === 'gpu0' ? {...row, status: 'COMPLETE'} : row)};
            await gpuPage.evaluate(() => window.SolarMinerLive.refresh('efficiency'));
            await gpuPage.waitForFunction(() => document.getElementById('gpu-completion').textContent.includes(window.SolarMinerI18n.t('lab_mining_started')));
            await page.evaluate(() => window.SolarMinerLive.refresh('efficiency'));
            await page.waitForFunction(() => !document.querySelector('[data-device-id="gpu0"]').classList.contains('is-active'));
            assert.equal(await page.locator('[data-device-id="gpu1"].is-active').count(), 1);
            failProfiles = true;
            await gpuPage.evaluate(() => window.SolarMinerLive.refresh('efficiency'));
            // A changed signature triggers the result refresh; old results stay visible.
            sweep = {...sweep, running: false}; await gpuPage.evaluate(() => window.SolarMinerLive.refresh('efficiency'));
            await gpuPage.waitForFunction(() => document.getElementById('lab-freshness').textContent.includes(window.SolarMinerI18n.t('lab_stale')));
            assert.equal(await gpuPage.locator('#gpu-profiles article').count(), 1);
            // Existing language preference controls standalone pages as well.
            const opposite = language === 'de' ? 'en' : 'de'; await Promise.all([gpuPage.waitForNavigation({waitUntil: 'domcontentloaded'}), gpuPage.locator('.language-switcher').selectOption(opposite)]);
            await gpuPage.waitForFunction(language => window.SolarMinerI18n.language === language, opposite);
            assert.equal(await gpuPage.locator('html').getAttribute('lang'), opposite);
            await gpuPage.setViewportSize({width: 390, height: 844});
            console.log(language + ': GPU page rendered'); await gpuPage.screenshot({path: `/tmp/oc-gpu-mobile-${language}.png`, fullPage: true, animations: 'disabled'});
            assert.ok(await gpuPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'GPU page fits mobile');
            const fresh = await context.newPage(); await fresh.goto(origin + '/benchmarks.html'); await fresh.locator('#lab-worker-picker input').first().waitFor();
            await fresh.setViewportSize({width: 390, height: 844}); await fresh.screenshot({path: `/tmp/oc-setup-mobile-${language}.png`, fullPage: true, animations: 'disabled'});
            assert.ok(await fresh.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Wizard fits mobile');
            assert.deepEqual(errors, []); await context.close();
            console.log(`${language}: wizard, presets, start payload, independent GPU tabs/results, stale state, motion, locale switch and mobile passed`);
        }
    } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });
