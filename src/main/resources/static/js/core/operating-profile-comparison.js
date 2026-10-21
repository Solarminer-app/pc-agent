// One read-only selection rule for local GPU results and shared fleet scenarios.
(() => {
  const valid = point => point && point.coin && point.minerSoftwareId
    && ['hashrateHps', 'measuredWatts', 'usdPerKwh', 'usdPerDay', 'limitWatts'].every(key =>
      Number.isFinite(point[key]) && point[key] > 0);
  const key = point => point ? JSON.stringify([point.coin, point.minerSoftwareId, point.limitWatts,
    point.tunedProfile?.softwareVersion, point.tunedProfile?.coreMhz, point.tunedProfile?.memoryMhz,
    point.tunedProfile?.memoryOffsetMhz, point.tunedProfile?.memoryOffsetPstate]) : '';
  const best = (points, score) => [...points].sort((a, b) => score(b) - score(a)
    || a.measuredWatts - b.measuredWatts || key(a).localeCompare(key(b)))[0] || null;
  function select(candidates, coin) {
    const points = (candidates || []).filter(point => valid(point) && point.coin === coin);
    return {efficiency: best(points, point => point.usdPerKwh),
      profit: best(points.filter(point => Number.isFinite(point.profitUsdPerDay)), point => point.profitUsdPerDay),
      count: points.length, miners: new Set(points.map(point => point.minerSoftwareId)).size};
  }
  const make = (tag, cls, text) => {
    const node = document.createElement(tag); if (cls) node.className = cls;
    if (text != null) node.textContent = text; return node;
  };
  function settings(point) {
    const p = point?.tunedProfile, t = window.SolarMinerI18n.t;
    if (!p) return t('profiles_baseline_settings');
    const mhz = value => value == null ? '—' : `${value} MHz`;
    return t('bm_tuned_settings', {version:p.softwareVersion || '—', core:mhz(p.coreMhz),
      memory:p.memoryOffsetMhz == null ? mhz(p.memoryMhz)
        : `${p.memoryOffsetMhz >= 0 ? '+' : ''}${p.memoryOffsetMhz} MHz (P${p.memoryOffsetPstate ?? '—'})`});
  }
  function cards(selection, nameOf = (_, id) => id, onSelect) {
    const t = window.SolarMinerI18n.t, ui = window.SolarMinerUI, prefs = window.SolarMinerPreferences;
    const root = make('div', 'profile-comparison-grid');
    for (const [mode, title] of [['efficiency','benchmark_goal_efficiency'], ['profit','benchmark_goal_profit']]) {
      const point = selection[mode], card = make('article', `profile-result profile-result-${mode}`);
      card.dataset.mode = mode;
      card.append(make('h3', '', t(title)));
      if (!point) {
        card.append(make('p', 'empty', t(mode === 'profit' && selection.count ? 'worker_mode_comparison_no_profit' : 'profiles_no_usable_point')));
        root.append(card); continue;
      }
      card.dataset.miner = point.minerSoftwareId;
      card.append(make('p', 'profile-result-miner', nameOf(point.coin, point.minerSoftwareId)),
        make('p', 'profile-result-value', mode === 'efficiency' ? ui.moneyPerKwh(point.usdPerKwh) : `≈ ${prefs.moneyFromUsd(point.profitUsdPerDay)}`),
        make('p', 'profile-result-settings', settings(point)));
      const metrics = make('dl', 'profile-result-metrics');
      for (const [label, value] of [
        ['hashrate', ui.hashrate(point.hashrateHps)], ['worker_mode_comparison_limit', `${point.limitWatts} W`],
        ['worker_mode_comparison_measured_power', ui.power(point.measuredWatts)],
        ['worker_mode_comparison_gross_day', `≈ ${prefs.moneyFromUsd(point.usdPerDay)}`],
        ['worker_mode_comparison_cost_day', Number.isFinite(point.profitUsdPerDay) ? `≈ ${prefs.moneyFromUsd(point.usdPerDay-point.profitUsdPerDay)}` : '—'],
        ['worker_mode_comparison_net_day', Number.isFinite(point.profitUsdPerDay) ? `≈ ${prefs.moneyFromUsd(point.profitUsdPerDay)}` : '—'],
        ['worker_mode_comparison_energy_day', `${(point.measuredWatts * 24 / 1000).toLocaleString(prefs.locale,{maximumFractionDigits:3})} kWh`]
      ]) { const item = make('div'); item.append(make('dt','',t(label)),make('dd','',value)); metrics.append(item); }
      card.append(metrics);
      const source = t(`coin_hopping_source_${point.source?.toLowerCase() || 'live'}`);
      const date = new Date(point.measuredAt);
      card.append(make('small', 'muted', t('worker_mode_comparison_source', {source,
        time:Number.isFinite(date.getTime()) ? new Intl.DateTimeFormat(prefs.locale,{dateStyle:'medium',timeStyle:'short'}).format(date) : '—'})));
      if (onSelect) {
        const button = make('button','button subtle',t('profiles_show_in_curve')); button.type='button';
        button.addEventListener('click', () => onSelect(point)); card.append(button);
      }
      root.append(card);
    }
    return root;
  }
  window.SolarMinerProfileComparison = {valid, key, select, settings, cards};
})();
