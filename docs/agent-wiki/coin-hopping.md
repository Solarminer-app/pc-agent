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
  automation. The setting names one physical GPU; the agent chooses its power
  target automatically from measured operating points. Cooldown is 1–1440 minutes; required gain is
  0.1–100%. Missing fields in the first settings version migrate to 30 minutes
  and 5%. Older fixed-target settings migrate to automatic target selection
  (`targetWatts = 0`). The local API is `GET/POST
  /api/agent/local/coin-hopping`; GET accepts an optional `deviceId` for a
  preview without changing settings.
- Candidates may come from a stable sweep step, a completed manual benchmark,
  or twelve consistent five-second samples during ordinary mining. All three
  use the same eligibility rules: the same GPU, a measured power target,
  positive measured hashrate and watts, and a point no older than 30 days.
  The newest valid point per coin and target wins regardless of source. Ordinary mining
  samples are stored locally only and never trigger benchmark sharing.
  The coin/algorithm must match the closed `Coin` catalogue. The miner and
  fee/proxy route must be ready in C10 capabilities.
- Network data must be available, non-stale, and no older than two hours.
  Gross USD/kWh is `forecast.usdPerDay / (measuredWatts * 24 / 1000)`.
  This is gross revenue before pool/miner fees and electricity cost. Every
  stable target in the GPU's verified range can compete, including two targets
  for the same coin.
- Evaluation runs every ten minutes. It changes only an actively mining GPU
  when its current coin and driver cap match an eligible measured point and
  another measured coin/target point exceeds the configured
  gain threshold, and the configured cooldown has elapsed since the last
  switch. It defers during benchmarks, efficiency sweeps, and active Node plans.
  The shared `LocalRunLock` serializes the actual switch with measurements and
  Node economic-plan writes. A failed switch attempts to restore the prior
  assignment, cap, and miner start. A same-coin target adjustment applies the
  verified GPU cap without restarting the miner. A stopped worker is never restarted merely
  because coin hopping is enabled.

## Action and revenue history

- `coin-hopping-ledger.json` stores the current GPU campaign, latest 200
  attempted/successful switch actions and cumulative modelled gross revenue.
  Changing the GPU starts a new campaign; disabling without changing
  them preserves its history. The file contains no wallet or pool credentials.
- At each evaluation the agent checks the energy journal's active mining session.
  Only the interval since the prior evaluation, capped at 20 minutes and cut to
  the actual session start, is counted. Startup and settings changes also break
  continuity. Missing worker, target, or market data breaks continuity rather
  than filling a gap. Quotes at the evaluation time estimate gross coins and
  USD for the actual coin and the best measured target for every comparable fixed
  coin for the same estimated kWh consumed by the active coin. This matters
  when measured watts differ between targets.
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
  history. The benchmark page reads both durable profile stores with one
  `GET /api/agent/local/measurements/profiles` request at startup, after a run
  completes, or on manual refresh. It continues to receive live run state over
  SSE. The page shows stored data before the controls and a live Hashrate/W
  sweep curve built from completed stable steps in both supported languages.
  Coin hopping has
  its own `/coin-hopping.html` page with settings, USD/kWh chart, accounting
  summary and action history; the benchmark page links to it.

Hardware acceptance is still needed for changed GPU caps, miner restart and
restoration with real drivers/pools. The local unit tests cover candidate
ranking across sweep/live sources, exact-target filtering, operating-point
collection, a successful automatic switch, alias lookup and ledger continuity;
they do not prove live profitability or pool payouts.

## 2026-10-17 — Replaced by worker operating modes

The former local coin-hopping controls and scheduled automatic switcher are retired.
`COIN_REVENUE_PER_KWH` in the per-worker operating modes owns automatic coin
and power-point decisions. The `CoinHoppingService` class remains as the shared
measured candidate calculation used by that mode; its legacy `evaluate()` has
no scheduler and the settings POST route is removed. A previously saved
`coin-hopping.json` no longer activates automatic switching on restart.

`/benchmarks.html#comparison` now owns the read-only USD/kWh comparison. Its
`GET /api/agent/local/benchmarks/comparison?deviceId=...` endpoint returns
candidate points from the shared eligibility logic. It does not apply a worker
assignment or GPU limit. The old `/coin-hopping.html` bookmark redirects to the
new section; the old settings/history UI and route are retired. Historic
ledger files remain on disk for operator retention; the new page does not
represent legacy counterfactual history as current operating-mode accounting.
