#!/usr/bin/env node
// Optional browser integration: NODE_PATH=<playwright install>/node_modules node scripts/check-operations-browser.cjs
// Deterministic mock API; never starts miners, discovers devices, or contacts real LAN peers.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const {chromium} = require('playwright');
const root = path.resolve(__dirname, '../src/main/resources/static');
const server = http.createServer((request, response) => {
  const pathname = new URL(request.url, 'http://localhost').pathname;
  const file = path.resolve(root, `.${pathname}`);
  if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {response.writeHead(404).end(); return;}
  response.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html');
  fs.createReadStream(file).pipe(response);
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({headless: true, ...(process.env.OPERATIONS_BROWSER ? {executablePath: process.env.OPERATIONS_BROWSER} : {})});
  try {
    const context = await browser.newContext({viewport: {width: 1600, height: 1100}});
    const page = await context.newPage();
    const errors = [], calls = [], writes = [];
    page.on('pageerror', error => errors.push(error.message));
    let offline = false, stalled = false, failFirst = false;
    const jobStore = new Map();
    let settings = {name: 'Studio', sharing: true, externalControl: false};
    let installed = false;
    const fixture = Array.from({length: 2000}, (_, i) => ({agentId: `agent-${Math.floor(i / 200)}`, deviceId: `GPU-${String(i).padStart(4, '0')}`,
      own: i < 200, model: ['NVIDIA RTX 4090', 'AMD Radeon RX 7900 XTX', 'NVIDIA TITAN RTX'][i % 3], hardwareType: 'GPU',
      coin: 'pearl', algorithm: i % 3 ? 'PearlHash' : 'KawPow', miner: 'SRBMiner-MULTI', status: 'STOPPED',
      hashrateHps: null, watts: null, temperature: null, acceptedShares: null, rejectedShares: null,
      powerMode: 'MANUAL', pool: 'stratum+tcp://pool.example:3333', benchmarking: false, sweeping: false,
      startBlocked: null, pauseBlocked: null, stale: false}));
    const agents = Array.from({length: 10}, (_, i) => ({agentId: `agent-${i}`, name: i ? `Mining room ${String(i).padStart(2, '0')}` : 'Studio · Local workstation',
      own: i === 0, reachable: true, issue: null, workerCount: 200}));
    await page.route('**/api/**', async route => {
      const url = new URL(route.request().url()); calls.push(url.pathname);
      assert(url.pathname.startsWith('/api/agent/local/operations/'), `Legacy API called: ${url.pathname}`);
      if (offline && url.pathname.endsWith('/workspace')) { await route.abort(); return; }
      const now = new Date().toISOString();
      const collectedAt = stalled ? new Date(Date.now() - 60000).toISOString() : now;
      const workers = fixture.map(row => ({...row, collectedAt, stale: stalled, startBlocked: stalled ? 'console_stale' : row.startBlocked,
        pauseBlocked: stalled ? 'console_stale' : row.pauseBlocked}));
      const json = value => route.fulfill({json: value});
      if (url.pathname.endsWith('/workspace')) {
        const q = (url.searchParams.get('q') || '').toLowerCase(), agent = url.searchParams.get('agent'), state = url.searchParams.get('state');
        const matching = workers.filter(row => (!q || [row.model, row.deviceId, row.algorithm].join(' ').toLowerCase().includes(q))
          && (!agent || row.agentId === agent) && (!state || row.status === state));
        const size = 50, pages = Math.max(1, Math.ceil(matching.length / size)), number = Math.max(0, Math.min(pages - 1, Number(url.searchParams.get('page')) || 0));
        await json({generatedAt: now, agents: agents.map(agent => ({...agent, collectedAt})), summary: {total: 2000, active: 0, attention: stalled ? 2000 : 0, reportedWatts: 0, reportingWorkers: 0},
          total: matching.length, page: number, pages, size, workers: matching.slice(number * size, (number + 1) * size)});
      } else if (url.pathname.endsWith('/configuration/software')) {
        if (route.request().method() === 'POST') {installed = true; await json({status: 'console_install_accepted'});}
        else await json([{id: 'peakminer', coin: 'pearl', coinName: 'Pearl', name: 'PeakMiner', hardware: 'GPU', installed,
          installable: true, selectable: installed, experimental: true, downloadStatus: installed ? 'installed' : 'missing'}]);
      } else if (url.pathname.endsWith('/configuration')) {
        if (route.request().method() === 'PUT') {settings = route.request().postDataJSON(); await json({status: 'console_settings_saved'});}
        else await json(settings);
      } else if (url.pathname.endsWith('/worker')) {
        const row = workers.find(row => row.agentId === url.searchParams.get('agent') && row.deviceId === url.searchParams.get('device'));
        assert(row, 'Detail requests must use the actual device ID, not a display-row key');
        await json(row);
      } else if (url.pathname.endsWith('/commands') && route.request().method() === 'POST') {
        const body = route.request().postDataJSON(); writes.push(body);
        const id = `job-${writes.length}`;
        const job = {id, action: body.action, createdAt: now, complete: false, polls: 0,
          results: body.targets.map(target => ({target, state: 'queued', error: null, detail: null}))};
        jobStore.set(id, job); await json(job);
      } else if (url.pathname.includes('/commands/')) {
        const job = jobStore.get(url.pathname.split('/').at(-1));
        if (!job) {await route.fulfill({status: 404}); return;}
        job.polls++;
        if (job.polls > 1) {
          job.complete = true;
          job.results.forEach((result, index) => {result.state = failFirst && index === 0 ? 'failed' : 'succeeded'; result.error = result.state === 'failed' ? 'peer_worker_control_disabled' : null;});
        }
        await json(job);
      } else {await route.fulfill({status: 404});}
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/console/index.html`);
    await page.waitForFunction(() => document.querySelectorAll('#worker-rows tr').length === 50);
    assert.equal(await page.locator('#total').textContent(), 'of 2,000 detected workers');
    assert.equal(await page.locator('#worker-rows tr').count(), 50);
    // A periodic refresh must retain the exact row node and focused control.
    await page.locator('.worker-name').first().focus();
    await page.evaluate(() => {window.testRow = document.querySelector('#worker-rows tr');});
    await page.waitForTimeout(2300);
    assert(await page.evaluate(() => window.testRow === document.querySelector('#worker-rows tr')));
    assert(await page.locator('.worker-name').first().evaluate(node => node === document.activeElement));
    await page.getByRole('button', {name: 'Next', exact: true}).click();
    await page.waitForFunction(() => document.querySelector('#page-info').textContent.startsWith('Page 2'));
    assert.equal(await page.locator('#worker-rows tr').count(), 50);
    await page.locator('#search').fill('GPU-1999');
    await page.waitForFunction(() => document.querySelectorAll('#worker-rows tr').length === 1);
    await page.locator('.worker-name').click();
    assert(await page.locator('#detail').isVisible());
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#detail').isVisible(), false);
    assert(await page.locator('.worker-name').evaluate(node => node === document.activeElement));
    await page.locator('#search').fill('');
    await page.waitForFunction(() => document.querySelectorAll('#worker-rows tr').length === 50);
    // Standalone configuration never depends on the old UI: explicit install and assignment.
    await page.locator('.worker-name').first().click();
    await page.waitForFunction(() => document.querySelector('#software-choice')?.options.length > 1);
    await page.locator('#software-choice').selectOption('pearl/peakminer');
    assert.equal(await page.getByRole('button', {name: 'Save assignment', exact: true}).isDisabled(), true);
    await page.getByRole('button', {name: 'Install selected software', exact: true}).click();
    await page.waitForFunction(() => !document.querySelector('.software-actions .primary').disabled);
    await page.getByRole('button', {name: 'Save assignment', exact: true}).click();
    await page.waitForFunction(() => document.querySelector('.job'));
    assert.equal(writes.at(-1).action, 'assign');
    assert.deepEqual(writes.at(-1).assignment, {coin: 'pearl', minerSoftwareId: 'peakminer'});
    await page.waitForFunction(() => document.querySelector('.job .succeeded'));
    await page.keyboard.press('Escape');
    await page.locator('#agent-name').fill('New studio');
    await page.locator('#external-control').check();
    await page.locator('#settings-save').click();
    await page.waitForFunction(() => document.querySelector('#settings-status').textContent === 'Agent settings saved.');
    assert.equal(settings.name, 'New studio'); assert.equal(settings.externalControl, true);
    // Reset fixture action history for the bounded-batch assertions below.
    writes.length = 0; jobStore.clear();
    await page.evaluate(() => {sessionStorage.removeItem('solarminer.operations.jobs');});
    await page.reload();
    await page.waitForFunction(() => document.querySelectorAll('#worker-rows tr').length === 50);
    // Shared and per-worker permission failures remain visible and disabled.
    fixture[0].startBlocked = 'console_remote_disabled';
    await page.locator('#refresh').click();
    await page.waitForFunction(() => document.querySelector('.row-action').disabled);
    fixture[0].startBlocked = null;
    await page.locator('#refresh').click();
    await page.waitForFunction(() => !document.querySelector('.row-action').disabled);
    // Select a bounded page, observe pending controls and partial outcomes; no auto-retry.
    failFirst = true;
    await page.locator('#select-page').check();
    await page.locator('#start-selected').click();
    await page.waitForFunction(() => document.querySelector('.job'));
    assert.equal(writes.length, 1); assert.equal(writes[0].targets.length, 50);
    await page.waitForFunction(() => document.querySelectorAll('.result .succeeded').length === 49);
    assert.equal(await page.locator('.result .failed').count(), 1);
    assert.equal(writes.length, 1);
    // Age and connectivity are independent; stale source disables otherwise successful reads.
    stalled = true; await page.locator('#refresh').click();
    await page.waitForFunction(() => document.querySelector('.row-action').disabled);
    assert.match(await page.locator('#connection').textContent(), /Connected/);
    assert.match(await page.locator('#worker-rows').textContent(), /Stale/);
    stalled = false; offline = true; await page.locator('#refresh').click();
    await page.waitForFunction(() => document.querySelector('#connection').textContent === 'Connection interrupted');
    assert.equal(await page.locator('#worker-rows tr').count(), 50);
    offline = false; await page.locator('#refresh').click();
    await page.waitForFunction(() => document.querySelector('#connection').textContent === 'Connected');
    await page.screenshot({path: '/tmp/solarminer-operations-desktop.png', fullPage: true});
    // Saved locale switch reloads standalone translations and restores the action IDs.
    await page.locator('#language').selectOption('de');
    await page.waitForFunction(() => document.documentElement.lang === 'de' && document.querySelectorAll('#worker-rows tr').length === 50);
    assert.equal(await page.locator('h1').textContent(), 'Alle Worker. Ein klarer Überblick.');
    assert.match(await page.locator('#selection').textContent(), /ausgewählt/);
    assert.deepEqual(await page.evaluate(() => [...window.OperationsI18n.missing]), []);
    const translationFailures = await page.evaluate(() => Object.entries(OperationsI18n.catalog).filter(([key, pair]) =>
      !Array.isArray(pair) || pair.length !== 2 || pair.some(text => typeof text !== 'string' || !text.trim())
      || JSON.stringify([...pair[0].matchAll(/\{(\w+)\}/g)].map(match => match[1]).sort()) !== JSON.stringify([...pair[1].matchAll(/\{(\w+)\}/g)].map(match => match[1]).sort())).map(([key]) => key));
    assert.deepEqual(translationFailures, []);
    await page.setViewportSize({width: 390, height: 844});
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({path: '/tmp/solarminer-operations-mobile.png', fullPage: true});
    assert.deepEqual(errors, []);
    assert(calls.every(call => call.startsWith('/api/agent/local/operations/')));
    console.log('PASS: 2,000 workers, 50-row bound, pagination/search, stable focus, dialog, permission/stale/offline states, partial batch, no write retry, DE/EN, mobile, install/assignment/settings, independent APIs.');
  } finally {await browser.close(); server.close();}
})().catch(error => {console.error(error); server.close(); process.exitCode = 1;});
