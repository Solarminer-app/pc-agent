// Header status: green lamp for "SolarMiner Node attached / steering" vs "running without a
// Node", plus the effective dev-fee tier. Percentages are NEVER hardcoded here — they come
// live from the "fee-tier" SSE channel (agent -> proxy -> fee-backend), so a referral code
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
    const modeLabel = nodeMode ? t('Node verbunden') : t('Ohne Node');
    label.textContent = summary.resolved
      ? `${modeLabel} · ${t('Dev-Fee')} ${fmtPct(total)}`
      : modeLabel;

    const lines = [];
    lines.push(nodeMode
      ? t('Ein SolarMiner Node steuert oder fragt diesen Agenten ab – volle Automatisierungsgebühr.')
      : t('Dieser Agent läuft ohne SolarMiner Node – reduzierte Proxy-Gebühr.'));
    if (summary.resolved) {
      lines.push(t('Aufteilung der Dev-Fee:'));
      for (const part of devParts) {
        lines.push(part.kind === 'SOLARMINER'
          ? t('SolarMiner: {pct}', { pct: fmtPct(part.percentage) })
          : t('Referrer {code}: {pct}', { code: summary.referral || '', pct: fmtPct(part.percentage) }));
      }
      lines.push(t('Gesamt: {pct}', { pct: fmtPct(total) }));
      lines.push(t('Die Werte kommen live vom SolarMiner Fee-Backend und ändern sich mit Modus und Referral-Code.'));
    } else {
      lines.push(t('Dev-Fee-Aufteilung gerade nicht abrufbar – Proxy-Verbindung prüfen.'));
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

  window.SolarMinerLive.channel('fee-tier', {endpoint: '/api/agent/local/fee-tier', maxAgeMs: 15_000}).subscribe(render);
  window.SolarMinerLive.start();
})();
