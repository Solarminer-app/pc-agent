# Local coin hopping

The PC-Agent owns the local automatic coin choice. `CoinHoppingService` reads
`GpuEfficiencyStore`, `MiningPerformanceProfileStore` and the `MarketDataService`
cache; it never calls the
Currency Service from a browser request or scheduler decision. The only upstream
remains `https://currency.solarminer.app/api/v1/public/mining-networks` in
`MarketDataService`.

## Decision contract

- Operator consent, configured cooldown/threshold and the last switch time are stored in
  `solarminer-agent/coin-hopping.json`; missing or unreadable settings disable
  automation. The setting names one physical GPU and
  one exact driver power limit. Cooldown is 1–1440 minutes; required gain is
  0.1–100%. Missing fields in the first settings version migrate to 30 minutes
  and 5%. The local API is `GET/POST
  /api/agent/local/coin-hopping`; GET accepts optional `deviceId` and
  `targetWatts` for a preview without changing settings.
- Candidates may come from a stable sweep step, a completed manual benchmark,
  or twelve consistent five-second samples during ordinary mining. All three
  use the same eligibility rules: the same GPU and exact power target,
  positive measured hashrate and watts, and a point no older than 30 days.
  The newest valid point per coin wins regardless of source. Ordinary mining
  samples are stored locally only and never trigger benchmark sharing.
  The coin/algorithm must match the closed `Coin` catalogue. The miner and
  fee/proxy route must be ready in C10 capabilities.
- Network data must be available, non-stale, and no older than two hours.
  Gross USD/kWh is `forecast.usdPerDay / (measuredWatts * 24 / 1000)`.
  This is gross revenue before pool/miner fees and electricity cost. Each coin
  keeps its newest measured step at the selected target.
- Evaluation runs every ten minutes. It changes only an actively mining GPU
  when its driver cap matches the selected target, both its current coin and
  another coin have valid candidates, the alternative exceeds the configured
  gain threshold, and the configured cooldown has elapsed since the last
  switch. It defers during benchmarks, efficiency sweeps, and active Node plans.
  The shared `LocalRunLock` serializes the actual switch with measurements and
  Node economic-plan writes. A failed switch attempts to restore the prior
  assignment, cap, and miner start. A stopped worker is never restarted merely
  because coin hopping is enabled.

## Action and revenue history

- `coin-hopping-ledger.json` stores the current GPU/target campaign, latest 200
  attempted/successful switch actions and cumulative modelled gross revenue.
  Changing the GPU or target starts a new campaign; disabling without changing
  them preserves its history. The file contains no wallet or pool credentials.
- At each evaluation the agent checks the energy journal's active mining session.
  Only the interval since the prior evaluation, capped at 20 minutes and cut to
  the actual session start, is counted. Startup and settings changes also break
  continuity. Missing worker, target, or market data breaks continuity rather
  than filling a gap. Quotes at the evaluation time estimate gross coins and
  USD for the actual coin and every comparable fixed coin for the same estimated
  kWh consumed by the active coin. This matters when measured watts differ
  despite an equal driver power target.
- The counterfactual is the **best single fixed coin** with valid coverage over
  every accounted interval. `actual USD - best fixed USD` can be negative.
  Estimated actual coins are shown separately by coin; coins with different
  denominations are never summed. Fees, power costs, rejected shares and
  actual pool credits are outside this model. The UI calls out this boundary.

## Measurement and UI fixes

- Benchmark profile persistence and sharing errors can no longer bypass
  `MeasurementRunGuard.end()`, so a failed disk write does not leave the local
  measurement lock held. GPU benchmark batches stop collecting after the
  required twelve samples per worker.
- `GpuEfficiencyStore.find` resolves canonical coin algorithms against
  persisted display aliases such as Quantus `QPoW (Poseidon2)`, without losing failed-run
  history. The benchmark page loads durable profiles once at startup, after a
  run completes, or on manual refresh. Single-flight requests prevent stale
  responses from replacing a newer render. The page shows a run/profile
  overview and measured curves in both supported languages. Coin hopping has
  its own `/coin-hopping.html` page with settings, USD/kWh chart, accounting
  summary and action history; the benchmark page links to it.

Hardware acceptance is still needed for changed GPU caps, miner restart and
restoration with real drivers/pools. The local unit tests cover candidate
ranking across sweep/live sources, exact-target filtering, operating-point
collection, a successful automatic switch, alias lookup and ledger continuity;
they do not prove live profitability or pool payouts.
