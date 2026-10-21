// NODE_PATH=/tmp/solarminer-benchmark-browser/node_modules node scripts/check-benchmark-policy-browser.cjs
// Real browser, local static assets, mocked APIs: no mining or external requests.
const {chromium} = require('playwright');
const http = require('node:http'), fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../src/main/resources/static');
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/fixture') {
    res.setHeader('Content-Type', 'text/html');
    return res.end('<html><body><div id="editor"></div><script src="/js/core/i18n-catalog.js"></script><script src="/js/core/i18n.js"></script><script src="/js/core/benchmark-policy-editor.js"></script></body></html>');
  }
  const file = path.resolve(root, '.' + url.pathname);
  if (!file.startsWith(root + path.sep) || !fs.existsSync(file)) return res.writeHead(404).end();
  res.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : 'text/html');
  fs.createReadStream(file).pipe(res);
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({headless:true, executablePath: process.env.OPERATIONS_BROWSER || '/home/lukas/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome'});
  const origin = `http://127.0.0.1:${server.address().port}`;
  try {
    for (const locale of ['de', 'en']) {
      const context = await browser.newContext();
      const page = await context.newPage();
      const errors = [], saved = {};
      page.on('pageerror', error => errors.push(error.message));
      await page.route('**/api/agent/local/**', async route => {
        const url = new URL(route.request().url());
        if (route.request().method() === 'PUT') {
          const body = route.request().postDataJSON(); saved[decodeURIComponent(url.pathname.split('/').pop())] = body;
          return route.fulfill({json:body});
        }
        if (url.pathname.endsWith('/benchmark-policies')) return route.fulfill({json:saved});
        return route.fulfill({json:[
          {coin:'pearl',coinName:'Pearl',id:'srbminer-multi',name:'SRBMiner-MULTI',device:'GPU',installed:true,selectable:true,compatibleGpuVendors:['NVIDIA']},
          {coin:'pearl',coinName:'Pearl',id:'peakminer',name:'PeakMiner',device:'GPU',installed:true,selectable:true,compatibleGpuVendors:['NVIDIA']},
          {coin:'quantus',coinName:'Quantus',id:'wildrig-ng',name:'WildRig NG',device:'GPU',installed:false,selectable:true,compatibleGpuVendors:['AMD']}
        ]});
      });
      await page.goto(origin + '/fixture');
      // Exercise the app's real persisted language selection, then reload.
      await page.evaluate(locale => localStorage.setItem('solarminer.pc-agent.language', locale), locale);
      await page.reload();
      const render = () => page.evaluate(async () => window.SolarMinerBenchmarkPolicyEditor(document.getElementById('editor'),
        {deviceId:'GPU:test',hardwareType:'GPU',vendor:'NVIDIA',powerMode:'MANUAL'}));
      await render();
      assert.equal(await page.locator('fieldset input').count(), 2, 'Do not offer AMD miners for NVIDIA');
      assert.equal(await page.locator('fieldset input:checked').count(), 2);
      await page.locator('fieldset input').nth(1).uncheck();
      await page.locator('#editor > label input[type=checkbox]').nth(0).check();
      await page.locator('input[type=number]').fill('72');
      await page.getByRole('button', {name:locale === 'de' ? 'Regeln speichern' : 'Save preferences'}).click();
      await page.waitForFunction(() => document.querySelector('[role=status]').textContent.length > 0);
      assert.deepEqual(saved['GPU:test'], {combinations:[{coin:'pearl',minerSoftwareId:'srbminer-multi'}],allowCoinSwitch:true,allowMinerSwitch:false,maxTemperatureC:72});
      await render();
      assert.equal(await page.locator('input[type=number]').inputValue(), '72');
      assert.equal(await page.locator('fieldset input:checked').count(), 1);
      // An empty selection is an explicit exclusion, never converted to all miners.
      await page.locator('fieldset input').nth(0).uncheck();
      await page.evaluate(() => document.getElementById('editor').savePolicy());
      assert.deepEqual(saved['GPU:test'].combinations, []);
      assert.deepEqual(errors, []);
      await context.close();
    }
    console.log('PASS: DE/EN editor, vendor filtering, independent switches, temperature persistence, empty exclusion and reload.');
  } finally { await browser.close(); server.close(); }
})().catch(error => {console.error(error); server.close(); process.exitCode=1;});
