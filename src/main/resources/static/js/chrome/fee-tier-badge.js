// Header status: green lamp for "SolarMiner Node attached / steering" vs "running without a
// Node", plus the effective dev-fee tier. Percentages are NEVER hardcoded here — they come
// from the cached fee-tier REST snapshot (agent -> proxy -> fee-backend), so a referral code
// that changes or lowers the total, or a tier that scales the split, shows up as-is.
// Hover/focus reveals the exact split (SolarMiner share, referrer share, total).
(() => {
  const t = window.SolarMinerI18n.t;

  const badge = document.createElement('span');
  badge.id = 'fee-tier-badge';
  badge.className = 'badge fee-tier-badge';
  badge.tabIndex = 0;
  badge.setAttribute('role', 'status');
  badge.setAttribute('aria-live', 'polite');
  const dot = document.createElement('i');
  dot.className = 'fee-tier-dot';
  const label = document.createElement('span');
  label.className = 'fee-tier-label';
  badge.append(dot, label);

  const tooltip = document.createElement('span');
  tooltip.className = 'fee-tier-tooltip';
  tooltip.setAttribute('role', 'tooltip');
  tooltip.hidden = true;
  badge.append(tooltip);

  const actions = document.querySelector('.top-actions');
  if (!actions) return;
  actions.insertBefore(badge, actions.firstChild);

  const fmtPct = value => {
    const n = Number(value);
    if (!Number.isFinite(n)) return '–';
    return (Number.isInteger(n) ? String(n) : String(Math.round(n * 1000) / 1000)) + ' %';
  };

  function render(summary) {
    if (!summary) return;
    const nodeMode = summary.tier === 'node';
    badge.classList.toggle('node', nodeMode);
    badge.classList.toggle('proxy', !nodeMode);
    const devParts = (summary.parts || []).filter(p => p.kind === 'SOLARMINER' || p.kind === 'REFERRER');
    const total = devParts.reduce((sum, p) => sum + (Number(p.percentage) || 0), 0);
    const modeLabel = nodeMode ? t('node_connected') : t('without_a_node');
    label.textContent = summary.resolved
      ? `${modeLabel} · ${t('dev_fee')} ${fmtPct(total)}`
      : modeLabel;

    const lines = [];
    lines.push(nodeMode
      ? t('a_solarminer_node_steers_or_reads_this_agent')
      : t('this_agent_runs_without_a_solarminer_node_re'));
    if (summary.resolved) {
      lines.push(t('dev_fee_split'));
      for (const part of devParts) {
        lines.push(part.kind === 'SOLARMINER'
          ? t('solarminer_pct', { pct: fmtPct(part.percentage) })
          : t('referrer_code_pct', { code: summary.referral || '', pct: fmtPct(part.percentage) }));
      }
      lines.push(t('total_pct', { pct: fmtPct(total) }));
      lines.push(t('values_come_live_from_the_solarminer_fee_bac'));
    } else {
      lines.push(t('dev_fee_split_currently_unavailable_check_th'));
    }
    tooltip.textContent = lines.join('\n');
    badge.setAttribute('aria-label', lines.join(' '));
  }

  const show = () => { tooltip.hidden = false; };
  const hide = () => { tooltip.hidden = true; };
  badge.addEventListener('mouseenter', show);
  badge.addEventListener('mouseleave', hide);
  badge.addEventListener('focus', show);
  badge.addEventListener('blur', hide);

  window.SolarMinerLive.channel('fee-tier', {endpoint: '/api/agent/local/fee-tier', maxAgeMs: 60_000,
    transport: 'rest', pollMs: 60_000}).subscribe(render);
  window.SolarMinerLive.start();
})();
