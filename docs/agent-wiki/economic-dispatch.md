# Economic dispatch contract (C10)

The Node may inspect a PC-Agent's economic capabilities and submit a short-lived
economic plan over the existing gated LAN boundary. It never receives pool
credentials, wallets, driver controls or unrestricted per-GPU commands.

## Agent API

- `GET /api/agent/external/capabilities` returns protocol version 1, workers,
  locally allowed coins, route state and optional local performance profiles.
- `POST /api/agent/external/economic-plan` accepts `{planId, validForSeconds,
  targetWatts, assignments}`. Plans have a 30-second to 30-minute validity
  range. The response states whether the plan was accepted and, when accepted,
  its expiry.

The Node's economic cluster additively sends `activeWorkerIds` and
`targetWatts: null`. Each active worker in manual power mode needs one coin
assignment. An Agent-local automatic power mode may keep its current coin
without a Node assignment. All selected workers must be locally enabled with
`coinPolicy=AUTO`, a measured profile and fresh market data. The Agent
starts the selected workers and pauses the other enabled `AUTO` workers
individually. `FIXED` and disabled workers stay outside the plan. The Node
renews a 90-second plan every 30 seconds. At expiry, this worker-selection
form pauses its managed workers so an absent Node decision cannot leave them
mining. Legacy plans without `activeWorkerIds` retain the old target behavior.

`CoinCapability` additively carries nullable `usdPerDay` and
`marketUpdatedAt` for a measured local profile. The values are gross USD/day
forecasts from cached network and coin-price snapshots. Unavailable or stale
market data yields null. The Node also requires a profile no older than 30
days and a market observation no older than two hours.

Every external route remains behind `externalControlEnabled`; `/identity` is
still the sole discovery exception. A worker must additionally be locally
enabled and opt into `coinPolicy=AUTO`. The default is `FIXED` for every new
and migrated worker.

## Cold start and safety

The agent publishes a performance profile after a complete local manual benchmark
(at least twelve valid samples), after it observes a mining worker with positive
hash rate and measured mining watts, or when a stable local GPU efficiency-sweep
profile exists. An absent profile is an explicit
`unavailableReason`, not zero performance. V1 rejects economic plans that
select an unmeasured coin. This intentionally favours verified hardware over a
new GPU whose profitability is still unknown. A legacy plan is reverted at TTL
expiry unless the operator has changed the assignment or target in the meantime.

Pool configuration and a ready fee/proxy route are prerequisites as well.
Operator-fixed assignments and disabled workers cannot be changed by a Node
plan. The Node remains responsible for comparing economic candidates; the Agent
remains responsible for local process lifecycle and power safety.

## Current V1 limitation

Manual benchmark results persist locally per `(worker, coin, algorithm)` and
are preferred when they are at least as fresh as a GPU sweep. Stable GPU sweeps
remain a valid fallback. The next compatible extension is power-bucketed
profiles and profile expiry, so old measurements can be retired automatically.
