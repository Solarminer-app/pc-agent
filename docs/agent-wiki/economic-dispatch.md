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
new GPU whose profitability is still unknown. A valid plan is reverted at TTL
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
