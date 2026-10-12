// Global balance strip. Reads the batched "wallets" SSE channel (targets + balances + prices in
// one snapshot) instead of pulling the full overview plus two extra endpoints on a 60 s timer.
// Every string goes through t(); stale data clears the numbers instead of keeping old ones.
(() => {
  const strip = document.getElementById('wallet-strip');
  if (!strip) return;
  const preferences = window.SolarMinerPreferences;
  const t = window.SolarMinerI18n.t;

  const number = value => new Intl.NumberFormat(preferences.locale, {
    maximumFractionDigits: 8
  }).format(value);

  function chip(symbol, title, amount, ticker, status, description) {
    const item = document.createElement('div');
    item.className = `wallet-chip${status === 'AVAILABLE' ? '' : ' wallet-chip--loading'}`;
    const icon = document.createElement('span'); icon.setAttribute('aria-hidden', 'true'); icon.textContent = symbol;
    const label = document.createElement('span'); label.className = 'wallet-chip__content';
    const name = document.createElement('small'); name.textContent = t(title);
    const value = document.createElement('strong');
    value.textContent = status === 'AVAILABLE' && amount !== null
      ? `${number(amount)} ${ticker}` : t('wallet_configured');
    const hint = document.createElement('small');
    const valueInUsd = status === 'AVAILABLE' && amount !== null && prices[ticker] > 0 ? amount * prices[ticker] : null;
    hint.textContent = status === 'NOT_CONFIGURED' ? t('not_configured_yet')
      : status === 'UNSUPPORTED_POOL' ? t('unavailable_for_this_pool')
        : status === 'UNAVAILABLE' ? t('currently_unavailable')
            : valueInUsd === null ? t(description) : t('value_description', {value: preferences.moneyFromUsd(valueInUsd), description: t(description)});
    label.append(name, value, hint);
    item.append(icon, label);
    return item;
  }

  let balances = [];
  let prices = {};
  let wallets = [];
  let snapshotRetries = 0, snapshotRetryTimer = null;

  function render() {
    const byCoin = Object.fromEntries(balances.map(entry => [entry.coin, entry]));
    strip.replaceChildren();
    const items = document.createElement('div'); items.className = 'wallet-strip__items';
    for (const wallet of wallets) {
      const balance = byCoin[wallet.coin] || {};
      const status = balance.poolStatus || 'UNSUPPORTED_POOL';
      const amount = balance.poolBalance ?? null;
      items.append(chip(wallet.symbol, t('coin_in_pool', {coin: wallet.name}), amount, wallet.ticker,
        status, 'pool_balance_before_payout'));
      if (wallet.coin === 'pearl') items.append(chip(wallet.symbol, 'pearl_at_address', balance.onChainBalance,
        'PRL', balance.onChainStatus || 'UNAVAILABLE', 'already_paid_balance'));
    }
    if (!wallets.length) {
      const link = document.createElement('a'); link.href = '/wallets.html';
      link.className = 'wallet-chip'; link.textContent = t('no_wallet_configured_open_wallets');
      items.append(link);
    }
    const settings = document.createElement('div'); settings.className = 'wallet-strip__settings';
    const amounts = wallets.flatMap(wallet => {
      const balance = byCoin[wallet.coin] || {};
      return wallet.coin === 'pearl'
        ? [[balance.poolBalance, balance.poolStatus, prices.PRL], [balance.onChainBalance, balance.onChainStatus, prices.PRL]]
        : [[balance.poolBalance, balance.poolStatus, prices[wallet.ticker]]];
    });
    const known = amounts.filter(([amount, status, price]) => status === 'AVAILABLE' && amount !== null && price > 0);
    const totalUsd = known.reduce((sum, [amount, , price]) => sum + amount * price, 0);
    const summary = document.createElement('strong'); summary.className = 'wallet-strip__total';
    summary.textContent = t('known_value_value', {value: known.length ? preferences.moneyFromUsd(totalUsd) : '—'});
    const btc = document.createElement('span'); btc.className = 'wallet-strip__total';
    btc.textContent = prices.BTC > 0 ? `BTC ${preferences.moneyFromUsd(prices.BTC)}` : t('btc_price');
    const selector = document.createElement('select'); selector.setAttribute('aria-label', t('display_currency'));
    for (const code of preferences.currencies) {
      const option = document.createElement('option'); option.value = code; option.textContent = code;
      selector.append(option);
    }
    selector.value = preferences.currency;
    selector.addEventListener('change', () => {
      preferences.setCurrency(selector.value);
    });
    settings.append(btc, summary, selector);
    strip.append(items, settings);
  }

  // A transport failure drops the derived state instead of re-rendering stale balances; a
  // stable snapshot that simply did not change stays visible (the watchdog re-reads it).
  window.SolarMinerLive.channel('wallets', {endpoint: '/api/agent/local/wallet-snapshot', maxAgeMs: 90_000,
    transport: 'rest', pollMs: 90_000})
    .subscribe((snapshot, meta) => {
      if (!snapshot || meta.error) { balances = []; prices = {}; wallets = []; }
      else {
        wallets = (snapshot.targets || []).map(target => ({
          coin: target.coin, name: target.name, ticker: target.ticker,
          address: target.wallet, symbol: target.ticker.slice(0, 1)
        }));
        balances = Array.isArray(snapshot.balances) ? snapshot.balances : [];
        prices = snapshot.prices && typeof snapshot.prices === 'object' ? snapshot.prices : {};
      }
      render();
      // The first read after an agent restart may precede the background pool/chain refresh.
      // Re-read the local cache a few times; the endpoint itself never performs upstream I/O.
      if (!wallets.length && !meta.error && snapshotRetries < 5 && !snapshotRetryTimer) {
        snapshotRetries++;
        snapshotRetryTimer = window.setTimeout(() => {
          snapshotRetryTimer = null;
          window.SolarMinerLive.refresh('wallets');
        }, 1000);
      } else if (wallets.length) {
        snapshotRetries = 0;
        if (snapshotRetryTimer) { window.clearTimeout(snapshotRetryTimer); snapshotRetryTimer = null; }
      }
    });

  render();
  document.addEventListener('solarminer:preferences-changed', render);
  window.addEventListener('solarminer:wallets-changed', () => window.SolarMinerLive.refresh('wallets'));
})();
