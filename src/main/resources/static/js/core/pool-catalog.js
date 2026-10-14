// Single source for pool candidates. Previously these were <option> values inside mining.html,
// so the pool list could not be shown, compared or reused anywhere else.
(() => {
  const kryptex = (coin, port, ssl) => [
    {value: `stratum+${ssl ? 'ssl' : 'tcp'}://${coin}.kryptex.network:${port}`, name: 'Kryptex', region: 'Global', feePercent: 1},
    {value: `stratum+${ssl ? 'ssl' : 'tcp'}://${coin}-eu.kryptex.network:${port}`, name: 'Kryptex', region: 'europe', feePercent: 1},
    {value: `stratum+${ssl ? 'ssl' : 'tcp'}://${coin}-us.kryptex.network:${port}`, name: 'Kryptex', region: 'north_america', feePercent: 1}
  ];
  const catalog = {
    monero: kryptex('xmr', 7029, false),
    pearl: kryptex('prl', 8048, true),
    ravencoin: kryptex('rvn', 7031, false),
    ethereumclassic: kryptex('etc', 7033, false),
    quantus: [
      {value: 'stratum+tcp://global.quanpool.com:9854', name: 'QuanPool', region: 'europe', feePercent: 1},
      {value: 'stratum+tcp://us.quanpool.com:9854', name: 'QuanPool', region: 'north_america', feePercent: 1},
      {value: 'stratum+tcp://asia.quanpool.com:9854', name: 'QuanPool', region: 'asia', feePercent: 1},
      {value: 'stratum+tcp://qtc.kryptex.network:7049', name: 'Kryptex', region: 'Global', feePercent: 3},
      {value: 'stratum+tcp://qtc-eu.kryptex.network:7049', name: 'Kryptex', region: 'europe', feePercent: 3},
      {value: 'stratum+tcp://qtc-us.kryptex.network:7049', name: 'Kryptex', region: 'north_america', feePercent: 3},
      {value: 'stratum+tcp://qtc-br.kryptex.network:7049', name: 'Kryptex', region: 'south_america', feePercent: 3},
      {value: 'stratum+tcp://qtc-sg.kryptex.network:7049', name: 'Kryptex', region: 'singapore', feePercent: 3},
      {value: 'stratum+tcp://qtc-hk.kryptex.network:7049', name: 'Kryptex', region: 'hong_kong', feePercent: 3},
      {value: 'stratum+tcp://qtc-ru.kryptex.network:7049', name: 'Kryptex', region: 'russia', feePercent: 3},
      {value: 'stratum+tcp://qtc-ae.kryptex.network:7049', name: 'Kryptex', region: 'middle_east', feePercent: 3}
    ],
    decred: [
      {value: 'stratum+tcp://dcr.suprnova.cc:9333', name: 'Suprnova', region: 'europe', feePercent: null},
      {value: 'stratum+tcp://stratum-us.suprnova.cc:9333', name: 'Suprnova', region: 'north_america', feePercent: null},
      {value: 'stratum+tcp://stratum-apac.suprnova.cc:9333', name: 'Suprnova', region: 'asia', feePercent: null}
    ]
  };
  const standard = {value: 'solarminer', name: 'SolarMiner', region: 'default_destination', feePercent: null};
  const custom = {value: 'custom', name: 'custom_pool', region: 'custom_address', feePercent: null};

  window.SolarMinerPoolCatalog = {
    /** SolarMiner first is wrong here: a new user should start on their own payout route. */
    for(coinId) { return [standard, ...(catalog[coinId] || []), custom]; },
    candidates(coinId) { return catalog[coinId] || []; },
    find(coinId, value) {
      if (!value) return null;
      return this.for(coinId).find(entry => entry.value === value)
        || {value, name: 'custom_pool', region: 'custom_not_yet_catalogued_pool', feePercent: null};
    },
    feeLabel(entry) {
      const t = window.SolarMinerI18n?.t || (value => value);
      if (entry.value === 'solarminer') return t('solarminer_default_destination_no_personal_p');
      if (entry.value === 'custom') return t('custom_pool_address');
      return `${entry.name} · ${t(entry.region)}${entry.feePercent != null ? ` · ${entry.feePercent} % ${t('pool_fee')}` : ''}`;
    },
    validUrl(value) { return /^stratum\+(tcp|ssl):\/\/[A-Za-z0-9._-]+:[0-9]{1,5}$/.test(String(value || '').trim()); }
  };
})();
