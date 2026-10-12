// The translation schema of the PC-Agent frontend. It is HARD-WIRED and closed:
//
//   t(key, params) — for every string the frontend authors. Keys reference
//     window.SolarMinerI18nCatalog.frontend (js/core/i18n-catalog.js), where each entry
//     carries BOTH locales: {de: "German source", en: "English"}. No regex, no guessing:
//     an unknown key logs a console warning and the audit script (scripts/check-i18n.cjs)
//     fails. Interpolated values use {placeholder} tokens:
//     t('updated_time', {time: …}) — never t(`…${…}`).
//
//   s(text) — for strings produced by the Java agent (English source: miner errors,
//     connectionDetail, downloadDetail, managedDetail, regulationError, unavailableReason,
//     benchmark phases; German source: a few status messages). They arrive as runtime text
//     and cannot carry keys, so they resolve through the closed
//     agentEnglishToGerman/agentGermanToEnglish
//     dictionaries plus a few documented prefix rules. Keys thrown inside Error(t(…))
//     messages resolve through the frontend dictionary.
//
// Static HTML keeps German text as the no-JS fallback and references keys through
// data-i18n (text content) and data-i18n-title / data-i18n-placeholder /
// data-i18n-aria-label (attributes), applied once at DOMContentLoaded. There is deliberately
// NO raw-text fallback or MutationObserver: every dynamic string must already pass through
// t() or s() when it is built, which keeps the obligation enforceable.
// See docs/agent-wiki/frontend-i18n.md for the full rule set.
(() => {
  const preferenceKey = 'solarminer.pc-agent.language';
  const supported = ['de', 'en'];
  const saved = (() => { try { return localStorage.getItem(preferenceKey); } catch (_) { return null; } })();
  const locale = supported.includes(saved) ? saved : 'en';

  const catalog = window.SolarMinerI18nCatalog || {
    frontend: {}, agentEnglishToGerman: {}, agentGermanToEnglish: {}
  };
  const missingKeys = new Set();

  const normalize = value => value.replace(/\s+/g, ' ').trim();
  function t(key, params) {
    if (typeof key !== 'string') return key;
    const k = normalize(key);
    const entry = catalog.frontend[k];
    let out = entry ? (entry[locale] ?? entry.de) : k;
    if (!entry && !missingKeys.has(k)) {
      missingKeys.add(k);
      console.warn(`[i18n] missing frontend translation key: ${k}`);
    }
    if (params) out = out.replace(/\{(\w+)\}/g, (_, name) => params[name] ?? '');
    return out;
  }

  /**
   * Agent-authored text (runtime, text-keyed) and keys thrown inside Error(t(…)) messages.
   * English agent text resolves through agentEnglishToGerman for German; German
   * agent text through agentGermanToEnglish for English. Unknown text passes through.
   */
  function s(text) {
    if (typeof text !== 'string') return text;
    const key = normalize(text);
    const entry = catalog.frontend[key];
    if (entry) return entry[locale] ?? entry.de;
    if (locale === 'de') {
      const hit = catalog.agentEnglishToGerman[key];
      if (hit) return hit;
      const sourceStatus = key.match(/^(available|starting|stopped|failed|api-disabled|api-unavailable|not-started): (.+)$/);
      if (sourceStatus) return `${s(sourceStatus[1])}: ${s(sourceStatus[2])}`;
      // Documented prefix rules for compound agent messages (benchmark phases, miner errors).
      if (key.startsWith('Benchmarking ')) return `Benchmark: ${key.slice(13)}`;
      if (key.startsWith('Error: ')) return `Fehler: ${s(key.slice(7))}`;
      if (key.startsWith('Could not fully restore miner state: '))
        return `Mining-Zustand konnte nicht vollständig wiederhergestellt werden: ${s(key.slice('Could not fully restore miner state: '.length))}`;
      if (key.startsWith('Worker did not start or stopped: '))
        return `Worker nicht gestartet oder angehalten: ${key.slice('Worker did not start or stopped: '.length)}`;
      if (key.startsWith('SolarMiner default payout for '))
        return `SolarMiner-Standardziel für ${key.slice('SolarMiner default payout for '.length).replace(' is unavailable. Check the proxy and fee target.', '')} nicht verfügbar. Prüfe Proxy und Fee-Ziel.`;
      if (key.startsWith('SolarMiner proxy route for '))
        return `SolarMiner-Proxy-Route für ${key.slice('SolarMiner proxy route for '.length).replace(' is unavailable', '')} nicht verfügbar.`;
      if (key.startsWith('SolarMiner default payout could not be configured: '))
        return `SolarMiner-Standardziel konnte nicht eingerichtet werden: ${s(key.slice('SolarMiner default payout could not be configured: '.length))}`;
      if (key.startsWith('Benchmark cannot start: '))
        return `Benchmark kann nicht gestartet werden: ${s(key.slice('Benchmark cannot start: '.length))}`;
      if (key.startsWith('Benchmark setup skipped: '))
        return `Benchmark-Vorbereitung übersprungen: ${s(key.slice('Benchmark setup skipped: '.length))}`;
      if (key.startsWith('Pool connected, last job '))
        return `Pool verbunden, letzter Job vor ${key.slice('Pool connected, last job '.length).replace(' s ago', ' s')}`;
      if (key.startsWith('Miner API returned HTTP '))
        return `Miner-API meldet HTTP ${key.slice('Miner API returned HTTP '.length)}`;
      if (key.startsWith('SRBMiner on ') && key.includes(' exited (code '))
        return key.replace('SRBMiner on ', 'SRBMiner auf ').replace(' exited (code ', ' beendet (Code ');
      if (key.startsWith('SRBMiner on ') && key.includes(' could not be started: '))
        return key.replace('SRBMiner on ', 'SRBMiner auf ').replace(' could not be started: ', ' konnte nicht gestartet werden: ');
      if (key.startsWith('No XMRig archive for ')) return `Kein XMRig-Archiv für ${key.slice('No XMRig archive for '.length)}`;
      if (key.startsWith('No SRBMiner asset for ')) return `Keine SRBMiner-Release-Datei für ${key.slice('No SRBMiner asset for '.length)}`;
      if (key.startsWith('GitHub release API returned HTTP ')) return `GitHub-Release-API meldet HTTP ${key.slice('GitHub release API returned HTTP '.length)}`;
      if (key.startsWith('SRBMiner download returned HTTP ')) return `SRBMiner-Download meldet HTTP ${key.slice('SRBMiner download returned HTTP '.length)}`;
      if (key.startsWith('Unsafe SRBMiner archive entry: ')) return `Unsicherer Eintrag im SRBMiner-Archiv: ${key.slice('Unsafe SRBMiner archive entry: '.length)}`;
      if (key.startsWith('Unsafe XMRig archive entry: ')) return `Unsicherer Eintrag im XMRig-Archiv: ${key.slice('Unsafe XMRig archive entry: '.length)}`;
      const skippedCoin = key.match(/^([a-z]+) \((.+)\)$/);
      if (skippedCoin) return `${skippedCoin[1]} (${s(skippedCoin[2])})`;
      if (key.includes(' · ')) return key.split(' · ').map(s).join(' · ');
      if (key.includes('; skipped: ')) { const [result, skipped] = key.split('; skipped: '); return `${s(result)}; übersprungen: ${s(skipped)}`; }
      return text;
    }
    // English locale: German agent status messages translate through agentGermanToEnglish.
    let translated = catalog.agentGermanToEnglish[key];
    if (translated === undefined) {
      if (key.startsWith('Fehler: ')) translated = `Error: ${s(key.slice(8))}`;
      else if (key.startsWith('SRBMiner konnte nicht vollständig entfernt werden: '))
        translated = `Could not fully remove SRBMiner: ${s(key.slice('SRBMiner konnte nicht vollständig entfernt werden: '.length))}`;
      else if (key.startsWith('Der lokale Proxy wurde beendet (Exit-Code '))
        translated = key.replace('Der lokale Proxy wurde beendet (Exit-Code ', 'The local proxy exited (code ').replace('). Protokoll: ', '). Log: ');
      else if (key.startsWith('Der lokale Proxy konnte nicht gestartet werden: '))
        translated = `Could not start the local proxy: ${s(key.slice('Der lokale Proxy konnte nicht gestartet werden: '.length))}`;
      else if (key.startsWith('Proxy wurde gewählt, aber Miner-Routen konnten nicht aktualisiert werden: '))
        translated = `Proxy selected, but miner routes could not be updated: ${s(key.slice('Proxy wurde gewählt, aber Miner-Routen konnten nicht aktualisiert werden: '.length))}`;
      else if (key.startsWith('Der lokale Proxy hat sich nicht gemeldet. Protokoll: '))
        translated = `The local proxy did not respond. Log: ${key.slice('Der lokale Proxy hat sich nicht gemeldet. Protokoll: '.length)}`;
      else if (key.startsWith('Power-Cap nicht schreibbar (Administrator/root und Treiber prüfen): '))
        translated = `Power cap is not writable (check administrator/root access and driver): ${s(key.slice('Power-Cap nicht schreibbar (Administrator/root und Treiber prüfen): '.length))}`;
      else if (key.startsWith('Nicht unterstützter GPU-Hersteller: '))
        translated = `Unsupported GPU vendor: ${key.slice('Nicht unterstützter GPU-Hersteller: '.length)}`;
      else if (key.startsWith('Ungültige ') && key.endsWith('-Wallet'))
        translated = `Invalid ${key.slice('Ungültige '.length)}`;
      else if (key.startsWith('Kein SolarMiner-Standard-Auszahlungsziel für '))
        translated = `No SolarMiner default payout available for ${key.slice('Kein SolarMiner-Standard-Auszahlungsziel für '.length).replace(' erreichbar', '')}`;
      else if (key.startsWith('GPU-Tool fehlgeschlagen: '))
        translated = `GPU tool failed: ${s(key.slice('GPU-Tool fehlgeschlagen: '.length))}`;
      else if (key.startsWith('XMRig konnte nicht entfernt werden: '))
        translated = `Could not remove XMRig: ${s(key.slice('XMRig konnte nicht entfernt werden: '.length))}`;
      else if (key.startsWith('GPU hasht, letzter Pool-Job vor '))
        translated = `GPU hashing, last pool job ${key.slice('GPU hasht, letzter Pool-Job vor '.length)} ago`;
      else if (/^GPU wurde [\d.,]+ °C heiß; Grenze über dem Schwellwert von [\d.,]+ °C$/.test(key))
        translated = key.replace('GPU wurde ', 'GPU reached ').replace(' heiß; Grenze über dem Schwellwert von ', '; above the threshold of ');
      else if (/^\d+ von \d+ Shares verworfen \(Power-Cap nicht stabil\)$/.test(key))
        translated = key.replace(' von ', ' of ').replace(' Shares verworfen (Power-Cap nicht stabil)', ' shares rejected (power cap unstable)');
      else if (key.startsWith('Andere Miner auf ') && key.endsWith(' konnten nicht angehalten werden'))
        translated = key.replace('Andere Miner auf ', 'Other miners on ').replace(' konnten nicht angehalten werden', ' could not be stopped');
      else if (key.startsWith('SolarMiner-Proxy, Fee-Ziel oder SRBMiner für ') && key.endsWith(' nicht bereit'))
        translated = key.replace('SolarMiner-Proxy, Fee-Ziel oder SRBMiner für ', 'SolarMiner proxy, fee target or SRBMiner for ').replace(' nicht bereit', ' is not ready');
      else if (key.startsWith('SRBMiner-Start fehlgeschlagen: '))
        translated = `Could not start SRBMiner: ${s(key.slice('SRBMiner-Start fehlgeschlagen: '.length))}`;
      else if (key.startsWith('SRBMiner beendet (Code ')) translated = key.replace('SRBMiner beendet (Code ', 'SRBMiner exited (code ');
      else if (key.startsWith('Bester stabiler Wert: ')) translated = key.replace('Bester stabiler Wert: ', 'Best stable value: ');
      else if (key.startsWith('Abgebrochen; bisher bester Messwert: ')) translated = key.replace('Abgebrochen; bisher bester Messwert: ', 'Cancelled; best measurement so far: ');
      else if (key.endsWith(' W stabil')) translated = key.replace(' W stabil', ' W stable');
      else if (key.includes(' W instabil: ')) translated = key.replace(' W instabil: ', ' W unstable: ');
      else if (key.includes(' · ')) translated = key.split(' · ').map(s).join(' · ');
      else translated = text;
    }
    return translated;
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
          const response = await fetch('/api/agent/local/economics/rates', {cache: 'no-store'});
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
    /** Audit hook: keys that reached t() without a dictionary entry during this page life. */
    missingKeys: () => [...missingKeys]
  };
  document.documentElement.lang = locale;

  // ------------------------------------------------------------------ static markup, once

  const translateAttribute = element => {
    for (const attribute of ['title', 'placeholder', 'aria-label']) {
      const key = element.getAttribute(`data-i18n-${attribute}`);
      if (key) element.setAttribute(attribute, t(key));
    }
  };
  function translateStaticMarkup() {
    const title = document.querySelector('title[data-i18n]');
    if (title) document.title = t(title.getAttribute('data-i18n'));
    for (const element of document.querySelectorAll('[data-i18n]')) {
      if (element.matches('title')) continue;
      element.textContent = t(element.getAttribute('data-i18n'));
    }
    for (const element of document.querySelectorAll('[data-i18n-title], [data-i18n-placeholder], [data-i18n-aria-label]'))
      translateAttribute(element);
  }
  document.addEventListener('DOMContentLoaded', () => {
    translateStaticMarkup();
    const switcher = document.createElement('select');
    switcher.className = 'button subtle language-switcher'; switcher.setAttribute('aria-label', t('choose_language'));
    switcher.innerHTML = '<option value="de">Deutsch</option><option value="en">English</option>'; switcher.value = locale;
    switcher.addEventListener('change', () => { try { localStorage.setItem(preferenceKey, switcher.value); } catch (_) {} location.reload(); });
    document.querySelector('.top-actions')?.prepend(switcher);
  });
  preferences.refreshRates();
})();
