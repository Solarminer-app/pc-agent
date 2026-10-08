# PC-Agent Operations UI and local energy journal

Stand: 7. Oktober 2026. Owner: `pc-agent`. This is the current task boundary for Dashboard, Miner-Software, Wallets and Worker. Source and tests remain authoritative.

## Operator model

| Page | Owns | Must not own |
| --- | --- | --- |
| Dashboard | Live operating state, active workers, current forecast, pool health, session energy | Installation or device assignment forms |
| Miner-Software | One card per actual software package; install/remove/readiness/provenance | Coin/device assignment, pool setup, start/pause |
| Wallets | Coin-level wallet, pool target and worker-name configuration | Hardware ownership or worker assignment |
| Worker | Complete CPU/GPU inventory, device-to-coin and miner assignment, local/Node mode, start/pause, live and energy data | Binary installation internals |

The global balance strip is shown directly below the header on all four operating pages. It derives coin chips from saved Wallets configurations and refreshes immediately after a successful save. Pool and on-chain balances remain separate positions; the fiat value is explicitly the known subtotal. The current service can read Kryptex XMR/PRL pool credits and the configured PRL on-chain address. It cannot derive a Monero on-chain wallet balance from a public address, and RVN/ETC/DCR/QTC balance providers are not implemented; their saved wallet chips say so instead of showing a fabricated zero. Independent public coin-price quotes can still be shown for those coins, and the BTC quote is displayed separately because this Agent does not configure a BTC miner/wallet.

Language (`solarminer.pc-agent.language`) and display currency (`solarminer.agent.currency`) are browser-local global preferences shared by every PC-Agent page. `GET /api/agent/local/fiat-rates` caches the current USD-based EUR/USD/CHF rates from the SolarMiner Currency-Service for one hour. Wallet values, gross earnings, energy costs and tariff labels use the same converter and locale-aware formatter. A last-good browser rate cache is retained for seven days; if no conversion is available, the UI keeps the truthful source currency instead of relabeling the amount.

The Dashboard deliberately has one compact operating canvas: readiness notices, live worker cards and the energy summary. Historical page-session charts, duplicate KPI rows, pool lists, worker tables and a second earnings section were removed from it; configuration belongs to Wallets and operational detail belongs to Worker. Per-worker cards contain hashrate, measured watts, temperature, gross daily earnings, gross earnings per kWh, pool target and session energy. Both earnings figures use the selected display currency. The per-kWh value divides the worker's daily forecast by 24 hours at its current measured power; it is unavailable when the forecast or power reading is missing. It is a gross forecast, not net profit after electricity costs.

The energy summary fetch has a five-second timeout. A failed `/api/agent/local/energy` response now shows its error instead of leaving the loading placeholder indefinitely; HTTP 404 explicitly points to restarting or updating the Agent. The tariff control is disabled while energy settings are unavailable. The dashboard keeps refreshing, so a recovered endpoint fills the summary without a page reload.

## Local worker contract

`GET /api/agent/local/workers` returns one entry per physical CPU/GPU, including idle devices. `POST /api/agent/local/workers/{deviceId}/assignment` accepts `coin`, `minerSoftwareId` and `externalControlEnabled`. It stops an old assignment before persisting the new one, selects only an installed/selectable compatible miner, and synchronizes the assigned GPU list into an existing coin configuration. Start and pause are available at `/{deviceId}/start` and `/{deviceId}/pause`.

The Worker page tracks pending start, pause and assignment requests by `deviceId`. While a request is in flight, only that worker's Start/Pause and Change actions are disabled. An assignment dialog closes after its values are captured and submitted so another worker remains operable; a failed request reports an error and leaves the worker available for a retry. The global Node-control switch stays disabled during worker requests because its settings payload includes the shared worker assignment profile.

Wallet, pool target and worker name are configured together on the Wallets page because the miner/proxy processes use one route per coin. There is no separate pool-management page or second credentials store. Assigning the final GPU away from a configured coin leaves those credentials stored for later use; the persistent `workerCoins` profile is authoritative for whether a physical device is assigned. A change can stop affected processes and never implicitly starts the replacement.

## Energy journal

`EnergyJournalService` samples server-side every five seconds, independent of an open browser. For every `MINING` device it trapezoid-integrates consecutive positive power readings into Wh. A gap over 30 seconds, a missing/non-positive reading, stopped process or changed coin/software is never extrapolated.

Files below `solarminer-agent/energy/`:

- `active-sessions.json`: atomic crash/restart checkpoint;
- `sessions-YYYY-MM.jsonl`: append-only completed sessions;
- `settings.json`: electricity price and display currency.

`GET /api/agent/local/energy` returns active/recent sessions and today/7-day/30-day aggregates. `POST /api/agent/local/energy/settings` stores the local tariff. Sessions retain device, hardware, coin, miner software, algorithm, timestamps, runtime, measured seconds, Wh, average/maximum power, average hashrate, share deltas, source and end reason.

Measurement scope is explicit: CPU package and GPU board readings are component energy, not guaranteed wall energy. Motherboard, PSU conversion loss, fans, unsupported AMD cards and other unmeasured consumers may be absent. `measurementCoverage` exposes measured time; unknown readings never become zero. A future smart-plug source should be stored separately as wall energy instead of silently replacing component measurements.

## Verification and open gates

- `:pc-agent:test --offline`: 72 tests successful under GraalVM JDK 21, including fiat-rate parsing, measured integration, missing-sensor behavior and worker-assignment compatibility.
- Historical `.codex-qa/pc-agent-operations.cjs` evidence covers the prior Pools page; a browser run for the Wallets replacement remains outstanding.
- JavaScript syntax checks pass for `overview.js`, `workers.js` and `software.js`.
- Not verified: long-running real-device Wh comparison against a calibrated wall meter, Windows sleep/crash recovery, AMD power readings, or real pool balances beyond the existing provider contracts. USD earnings remain gross forecasts, not accounting entries.

## Embedded proxy dashboard route (2026-10-06)

The embedded proxy binds to loopback on HTTP port 8090. When local proxy mode is running, the Connection page links to the proxy dashboard mounted through the PC-Agent at `/proxy-dashboard/` (Agent web port 8084). The reverse route permits only GET requests for dashboard HTML, CSS, JavaScript and `/api/dashboard[/**]`; it does not expose the proxy's fee/network APIs or any general URL forwarding. This routing change is source-implemented but has not yet been browser-verified.
