(() => {
  const t = (key, params) => window.SolarMinerI18n.t(key, params);
  const node = (tag, text) => { const el = document.createElement(tag); if (text) el.textContent = text; return el; };
  async function request(url, options) {
    const response = await fetch(url, options);
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || body.detail || `HTTP ${response.status}`);
    return body;
  }
  async function editor(root, worker) {
    const generation = Symbol(); root.policyGeneration = generation;
    root.savePolicy = null;
    root.replaceChildren(node('p', t('benchmark_policy_loading')));
    try {
      const [policies, catalog] = await Promise.all([
        request('/api/agent/local/benchmark-policies'), request(`/api/agent/local/benchmark-policies/options/${encodeURIComponent(worker.deviceId)}`)
      ]);
      if (root.policyGeneration !== generation) return;
      const policy = policies[worker.deviceId];
      root.replaceChildren();
      root.append(node('p', t('benchmark_workflow_help')));
      const fieldset = node('fieldset'); fieldset.append(node('legend', t('benchmark_worker_combinations')));
      const inputs = [];
      for (const miner of catalog.filter(miner => worker.hardwareType === 'CPU' ? miner.device === 'CPU'
        : miner.device !== 'CPU' && (miner.compatibleGpuVendors || []).includes(String(worker.vendor).toUpperCase()))) {
        const label = node('label'); label.style.display = 'block';
        const input = node('input'); input.type = 'checkbox';
        input.checked = policy ? policy.combinations.some(pair => pair.coin === miner.coin && pair.minerSoftwareId === miner.id) : miner.installed && miner.selectable;
        // Installed prerequisites are shown by the existing catalog; preferences can include software to install later.
        inputs.push({input, coin: miner.coin, minerSoftwareId: miner.id});
        label.append(input, node('span', `${miner.coinName} · ${miner.name}${miner.installed ? '' : ' · ' + t('not_installed')}`));
        fieldset.append(label);
      }
      root.append(fieldset);
      const checkbox = (key, checked) => {
        const label = node('label'); label.style.display = 'block';
        const input = node('input'); input.type = 'checkbox'; input.checked = checked;
        label.append(input, node('span', t(key))); root.append(label); return input;
      };
      const coin = checkbox('benchmark_allow_coin_switch', policy?.allowCoinSwitch ?? ['COIN_REVENUE_PER_KWH', 'COIN_PROFIT_PER_DAY'].includes(worker.powerMode));
      const software = checkbox('benchmark_allow_miner_switch', policy?.allowMinerSwitch ?? ['COIN_REVENUE_PER_KWH', 'COIN_PROFIT_PER_DAY'].includes(worker.powerMode));
      const tempLabel = node('label', t('benchmark_gpu_temperature'));
      const temp = node('input'); temp.type = 'number'; temp.min = '40'; temp.max = '90'; temp.step = '1'; temp.required = true;
      temp.value = policy?.maxTemperatureC ?? 80; temp.setAttribute('aria-label', t('benchmark_gpu_temperature'));
      tempLabel.append(temp); tempLabel.hidden = worker.hardwareType !== 'GPU'; root.append(tempLabel);
      const save = node('button', t('benchmark_policy_save')); save.type = 'button'; save.className = 'button subtle';
      const status = node('p'); status.setAttribute('role', 'status');
      root.savePolicy = async () => {
        if (!temp.reportValidity()) throw new Error('benchmark_temperature_invalid');
        return request(`/api/agent/local/benchmark-policies/${encodeURIComponent(worker.deviceId)}`, {
            method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({
              combinations: inputs.filter(row => row.input.checked).map(({coin, minerSoftwareId}) => ({coin, minerSoftwareId})),
              allowCoinSwitch: coin.checked, allowMinerSwitch: software.checked, maxTemperatureC: Number(temp.value)
            })
          });
      };
      save.addEventListener('click', async () => {
        save.disabled = true;
        try {
          await root.savePolicy();
          status.textContent = t('benchmark_policy_saved');
        } catch (error) { status.textContent = window.SolarMinerI18n.s(error.message); }
        finally { save.disabled = false; }
      });
      root.append(save, status);
    } catch (error) { if (root.policyGeneration === generation) root.replaceChildren(node('p', window.SolarMinerI18n.s(error.message))); }
  }
  window.SolarMinerBenchmarkPolicyEditor = editor;
})();
