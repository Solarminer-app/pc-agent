(() => {
  const t = (key, params) => window.SolarMinerI18n.t(key, params);
  const node = (tag, text) => { const el = document.createElement(tag); if (text) el.textContent = text; return el; };
  async function request(url, options) {
    const response = await fetch(url, options);
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || body.detail || `HTTP ${response.status}`);
    return body;
  }
  async function editor(root, worker, settings = {}) {
    if (Array.isArray(settings)) settings = {};
    const generation = Symbol(); root.policyGeneration = generation;
    root.savePolicy = null; root.readPolicy = null; root.readyCombinations = null;
    root.classList.add('benchmark-policy-editor');
    root.replaceChildren(node('p', t('benchmark_policy_loading')));
    const changed = () => root.dispatchEvent(new Event('benchmark-policy-change', {bubbles: true}));
    try {
      const [policies, catalog] = await Promise.all([
        request('/api/agent/local/benchmark-policies'), request(`/api/agent/local/benchmark-policies/options/${encodeURIComponent(worker.deviceId)}`)
      ]);
      if (root.policyGeneration !== generation) return false;
      let saved = policies[worker.deviceId];
      root.replaceChildren();
      if (!settings.measurement) root.append(node('p', t('benchmark_workflow_help')));
      const inputs = [], groups = new Map();
      for (const miner of catalog.filter(miner => worker.hardwareType === 'CPU' ? miner.device === 'CPU'
        : miner.device !== 'CPU' && (miner.compatibleGpuVendors || []).includes(String(worker.vendor).toUpperCase()))) {
        if (!groups.has(miner.coin)) {
          const fieldset = node('fieldset'); fieldset.className = 'policy-coin';
          fieldset.append(node('legend', miner.coinName)); groups.set(miner.coin, fieldset); root.append(fieldset);
        }
        const label = node('label'); label.className = 'policy-miner';
        const input = node('input'); input.type = 'checkbox';
        input.checked = saved ? saved.combinations.some(pair => pair.coin === miner.coin && pair.minerSoftwareId === miner.id) : miner.installed && miner.selectable;
        inputs.push({input, miner});
        const text = node('span', miner.name);
        if (!miner.installed || !miner.selectable) {
          const detail = node('small', !miner.installed ? t('not_installed') : window.SolarMinerI18n.s(miner.unavailableReason || 'currently_unavailable_for_assignment'));
          text.append(detail);
        }
        label.append(input, text); groups.get(miner.coin).append(label);
      }
      const checkbox = (key, checked) => {
        const label = node('label'); label.className = 'policy-permission'; label.hidden = Boolean(settings.measurement);
        const input = node('input'); input.type = 'checkbox'; input.checked = checked;
        label.append(input, node('span', t(key))); root.append(label); return input;
      };
      const coin = checkbox('benchmark_allow_coin_switch', saved?.allowCoinSwitch ?? ['COIN_REVENUE_PER_KWH', 'COIN_PROFIT_PER_DAY'].includes(worker.powerMode));
      const software = checkbox('benchmark_allow_miner_switch', saved?.allowMinerSwitch ?? ['COIN_REVENUE_PER_KWH', 'COIN_PROFIT_PER_DAY'].includes(worker.powerMode));
      const tempLabel = node('label', t('benchmark_gpu_temperature')); tempLabel.className = 'policy-temperature';
      const temp = node('input'); temp.type = 'number'; temp.min = '40'; temp.max = '90'; temp.step = '1'; temp.required = true;
      temp.value = saved?.maxTemperatureC ?? 80;
      let savedTemperature = Number(temp.value); temp.setAttribute('aria-label', t('benchmark_gpu_temperature'));
      tempLabel.append(temp); tempLabel.hidden = worker.hardwareType !== 'GPU'; root.append(tempLabel);
      const save = node('button', t('benchmark_policy_save')); save.type = 'button'; save.className = 'button subtle'; save.hidden = Boolean(settings.measurement);
      const status = node('p', settings.measurement ? t('bm_policy_autosave') : ''); status.className = 'policy-status muted'; status.setAttribute('role', 'status');
      root.readPolicy = () => ({
        combinations: inputs.filter(row => row.input.checked).map(({miner}) => ({coin: miner.coin, minerSoftwareId: miner.id})),
        allowCoinSwitch: coin.checked, allowMinerSwitch: software.checked, maxTemperatureC: Number(temp.value)
      });
      root.readyCombinations = () => inputs.filter(row => row.input.checked && row.miner.installed && row.miner.selectable)
        .map(({miner}) => ({coin: miner.coin, minerSoftwareId: miner.id}));
      root.savePolicy = async () => {
        const policy = root.readPolicy();
        if (!Number.isFinite(policy.maxTemperatureC) || policy.maxTemperatureC < 40 || policy.maxTemperatureC > 90 || !temp.value.trim())
          throw new Error('benchmark_temperature_invalid');
        if (saved && JSON.stringify(saved) === JSON.stringify(policy)) return saved;
        if (settings.measurement) {
          const latest = (await request('/api/agent/local/benchmark-policies'))[worker.deviceId];
          if (latest) {
            policy.allowCoinSwitch = latest.allowCoinSwitch;
            policy.allowMinerSwitch = latest.allowMinerSwitch;
            if (policy.maxTemperatureC === savedTemperature) policy.maxTemperatureC = latest.maxTemperatureC;
          }
        }
        const result = await request(`/api/agent/local/benchmark-policies/${encodeURIComponent(worker.deviceId)}`, {
          method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(policy)
        });
        saved = result; savedTemperature = result.maxTemperatureC; coin.checked = result.allowCoinSwitch; software.checked = result.allowMinerSwitch; temp.value = result.maxTemperatureC;
        status.textContent = t('benchmark_policy_saved');
        return result;
      };
      save.addEventListener('click', async () => {
        save.disabled = true;
        try { await root.savePolicy(); status.textContent = t('benchmark_policy_saved'); }
        catch (error) { status.textContent = window.SolarMinerI18n.s(error.message); }
        finally { save.disabled = false; }
      });
      root.oninput = () => { if (settings.measurement) status.textContent = t('bm_policy_autosave'); changed(); };
      root.append(save, status); changed(); return true;
    } catch (error) {
      if (root.policyGeneration === generation) {
        root.replaceChildren(node('p', window.SolarMinerI18n.s(error.message)));
        if (settings.retry) {
          const retry = node('button', t('bm_retry_options')); retry.type = 'button'; retry.className = 'button subtle';
          retry.addEventListener('click', settings.retry); root.append(retry);
        }
        changed();
      }
      return false;
    }
  }
  window.SolarMinerBenchmarkPolicyEditor = editor;
})();
