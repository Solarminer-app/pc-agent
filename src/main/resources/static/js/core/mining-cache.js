// A short-lived view snapshot for this origin and tab; never an authorization to run miners.
(() => {
  const key = 'solarminer.mining-view.v1', maxAge = 5 * 60 * 1000;
  const valid = value => value && Array.isArray(value.coins) &&
    value.coins.every(coin => coin && typeof coin.id === 'string' && typeof coin.binaryAvailable === 'boolean') &&
    Array.isArray(value.gpus) && value.gpus.every(gpu => gpu && typeof gpu === 'object') &&
    value.stats && Array.isArray(value.stats.workers) && value.stats.workers.every(worker => worker && typeof worker === 'object') &&
    value.proxy && typeof value.proxy === 'object';
  window.SolarMinerMiningCache = {
    read() {
      try {
        const snapshot = JSON.parse(sessionStorage.getItem(key));
        const age = Date.now() - snapshot?.savedAt;
        if (!(age >= 0 && age < maxAge) || !valid(snapshot?.overview)) { sessionStorage.removeItem(key); return null; }
        return snapshot;
      } catch (_) { try { sessionStorage.removeItem(key); } catch (_) {} return null; }
    },
    write(overview) {
      if (!valid(overview)) return;
      try { sessionStorage.setItem(key, JSON.stringify({savedAt: Date.now(), overview})); } catch (_) {}
    },
    clear() { try { sessionStorage.removeItem(key); } catch (_) {} }
  };
})();
