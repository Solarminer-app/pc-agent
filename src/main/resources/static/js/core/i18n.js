// The translation schema of the PC-Agent frontend. It is HARD-WIRED and closed:
//
//   t(text, params) — for every string the frontend authors (German source text).
//     Exact lookup in window.SolarMinerI18nCatalog.frontend (js/core/i18n-catalog.js).
//     No regex, no guessing: an unknown key logs a console warning and the audit script
//     (.codex-qa/i18n-audit.cjs) fails. Interpolated values use {placeholder} tokens:
//     t('Aktualisiert {time}', {time: …}) — never t(`Aktualisiert ${…}`).
//
//   s(text) — ONLY for strings produced by the Java agent (English source: miner errors,
//     connectionDetail, downloadDetail, managedDetail, regulationError, unavailableReason,
//     benchmark phases). They cannot be rewritten in the browser, so they translate through
//     the closed agent catalog plus a few documented prefix rules. In English they pass
//     through untouched.
//
// Static HTML stays German in the source and is translated exactly once at
// DOMContentLoaded. There is deliberately NO MutationObserver: every dynamic string must
// already pass through t() or s() when it is built, which keeps the obligation enforceable.
// See pc-agent/FRONTEND-I18N.md for the full rule set.
(() => {
  const preferenceKey = 'solarminer.pc-agent.language';
  const supported = ['de', 'en'];
  const saved = (() => { try { return localStorage.getItem(preferenceKey); } catch (_) { return null; } })();
  const locale = supported.includes(saved) ? saved : 'en';

  const catalog = window.SolarMinerI18nCatalog || {frontend: {}, agent: {}};
  const missingKeys = new Set();

  const normalize = value => value.replace(/\s+/g, ' ').trim();

  function t(text, params) {
    if (typeof text !== 'string') return text;
    const key = normalize(text);
    let out = key;
    if (locale === 'en') {
      const hit = catalog.frontend[key];
      if (hit === undefined) {
        // Hard-wired schema: an untranslated frontend string is a defect, never a silent pass.
        if (!missingKeys.has(key)) {
          missingKeys.add(key);
          console.warn(`[i18n] missing frontend translation: ${key}`);
        }
      } else out = hit;
    }
    if (params) out = out.replace(/\{(\w+)\}/g, (_, name) => params[name] ?? '');
    return out;
  }

  /**
   * Agent-authored text. The agent authors English (LibreHardwareMonitor, miner errors) and,
   * for a few status messages, German (benchmark upload status). Both directions stay inside
   * the closed catalogs: English goes through the agent catalog, German through the frontend
   * catalog. Unknown text passes through untouched.
   */
  function s(text) {
    if (typeof text !== 'string') return text;
    const key = normalize(text);
    if (locale === 'de') {
      const hit = catalog.agent[key];
      if (hit) return hit;
      // Documented prefix rules for compound agent messages (benchmark phases, miner errors).
      if (key.startsWith('Benchmarking ')) return `Benchmark: ${key.slice(13)}`;
      if (key.startsWith('Error: ')) return `Fehler: ${s(key.slice(7))}`;
      if (key.startsWith('Could not fully restore miner state: '))
        return `Mining-Zustand konnte nicht vollständig wiederhergestellt werden: ${s(key.slice('Could not fully restore miner state: '.length))}`;
      if (key.startsWith('Worker did not start or stopped: '))
        return `Worker nicht gestartet oder angehalten: ${key.slice('Worker did not start or stopped: '.length)}`;
      if (key.includes(' · ')) return key.split(' · ').map(s).join(' · ');
      if (key.includes('; skipped: ')) { const [result, skipped] = key.split('; skipped: '); return `${s(result)}; übersprungen: ${skipped}`; }
      return text;
    }
    // English locale: German agent status messages translate through the frontend catalog.
    return catalog.frontend[key] ?? text;
  }

  // ------------------------------------------------------------------ preferences

  const currencyKey = 'solarminer.agent.currency';
  const fiatCacheKey = 'solarminer.agent.fiat-rates';
  const supportedCurrencies = ['EUR', 'USD', 'CHF'];
  let currency = (() => { try { const value = localStorage.getItem(currencyKey); return supportedCurrencies.includes(value) ? value : 'USD'; } catch (_) { return 'USD'; } })();
  let fiatRates = {USD: 1};
  let fiatRateDate = null;
  let fiatRatesStale = true;
  try {
    const cached = JSON.parse(localStorage.getItem(fiatCacheKey) || 'null');
    if (cached?.rates?.USD === 1 && Date.now() - Number(cached.savedAt || 0) < 7 * 86400000) {
      fiatRates = cached.rates; fiatRateDate = cached.dataUtcDate || null; fiatRatesStale = true;
    }
  } catch (_) { }

  const localeTag = () => locale === 'de' ? 'de-DE' : 'en-US';
  const convertMoney = (value, sourceCurrency = 'USD', targetCurrency = currency) => {
    const source = String(sourceCurrency || 'USD').toUpperCase(), target = String(targetCurrency || currency).toUpperCase();
    const amount = Number(value);
    if (!Number.isFinite(amount)) return null;
    if (source === target) return amount;
    const sourceRate = fiatRates[source], targetRate = fiatRates[target];
    return sourceRate > 0 && targetRate > 0 ? amount / sourceRate * targetRate : null;
  };
  const money = (value, sourceCurrency = 'USD', options = {}) => {
    const converted = convertMoney(value, sourceCurrency);
    const displayCurrency = converted == null ? String(sourceCurrency || 'USD').toUpperCase() : currency;
    return new Intl.NumberFormat(localeTag(), {style: 'currency', currency: displayCurrency, maximumFractionDigits: 2, ...options})
      .format(converted == null ? Number(value || 0) : converted);
  };
  const notifyPreferences = () => document.dispatchEvent(new CustomEvent('solarminer:preferences-changed', {detail: {language: locale, currency}}));
  const preferences = {
    get language() { return locale; }, get locale() { return localeTag(); }, get currency() { return currency; },
    get currencies() { return [...supportedCurrencies]; }, get rates() { return {...fiatRates}; },
    get rateDate() { return fiatRateDate; }, get ratesStale() { return fiatRatesStale; },
    convert: convertMoney, money, moneyFromUsd: value => money(value, 'USD'),
    setCurrency(value) {
      if (!supportedCurrencies.includes(value) || value === currency) return;
      currency = value; try { localStorage.setItem(currencyKey, currency); } catch (_) { }
      notifyPreferences();
    },
    async refreshRates() {
      try {
        let snapshot;
        try {
          const response = await fetch('/api/agent/local/fiat-rates', {cache: 'no-store'});
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          snapshot = await response.json();
        } catch (_) {
          // Compatibility fallback for a UI updated before the local agent process has restarted.
          const values = await Promise.all(['EUR', 'CHF'].map(async code => {
            const response = await fetch(`https://api.frankfurter.dev/v2/rate/usd/${code.toLowerCase()}`);
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            return [code, Number((await response.json()).rate)];
          }));
          snapshot = {rates: Object.fromEntries([['USD', 1], ...values]), dataUtcDate: null, stale: false};
        }
        const next = {USD: 1};
        for (const code of supportedCurrencies) if (Number(snapshot.rates?.[code]) > 0) next[code] = Number(snapshot.rates[code]);
        fiatRates = next; fiatRateDate = snapshot.dataUtcDate || null; fiatRatesStale = Boolean(snapshot.stale);
        try { localStorage.setItem(fiatCacheKey, JSON.stringify({rates: fiatRates, dataUtcDate: fiatRateDate, savedAt: Date.now()})); } catch (_) { }
        notifyPreferences();
      } catch (_) { /* Values remain truthful in their source currency until a rate is available. */ }
    }
  };
  window.SolarMinerPreferences = preferences;

  // ------------------------------------------------------------------ public surface

  window.SolarMinerI18n = {
    locale: localeTag(), language: locale, t, s,
    /** Audit hook: keys that reached t() without a catalog entry during this page life. */
    missingKeys: () => [...missingKeys]
  };
  document.documentElement.lang = locale;

  // ------------------------------------------------------------------ static markup, once

  function translateTextNode(node) {
    if (node.parentElement?.closest('pre,script,style,[data-no-i18n]')) return;
    const original = node.nodeValue;
    const trimmed = normalize(original);
    if (!trimmed) return;
    const translated = t(trimmed);
    if (translated !== trimmed)
      node.nodeValue = `${original.match(/^\s*/)[0]}${translated}${original.match(/\s*$/)[0]}`;
  }
  function translateStaticMarkup() {
    document.title = t(document.title);
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    const nodes = []; while (walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach(translateTextNode);
    for (const element of document.querySelectorAll('[title], [placeholder], [aria-label]'))
      for (const attribute of ['title', 'placeholder', 'aria-label'])
        if (element.hasAttribute(attribute)) element.setAttribute(attribute, t(element.getAttribute(attribute)));
  }
  document.addEventListener('DOMContentLoaded', () => {
    translateStaticMarkup();
    const switcher = document.createElement('select');
    switcher.className = 'button subtle language-switcher'; switcher.setAttribute('aria-label', t('Sprache wählen'));
    switcher.innerHTML = '<option value="de">Deutsch</option><option value="en">English</option>'; switcher.value = locale;
    switcher.addEventListener('change', () => { try { localStorage.setItem(preferenceKey, switcher.value); } catch (_) {} location.reload(); });
    document.querySelector('.top-actions')?.prepend(switcher);
  });
  preferences.refreshRates();
})();
