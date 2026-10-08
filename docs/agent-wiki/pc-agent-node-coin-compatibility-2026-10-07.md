# PC-Agent coin compatibility with Solar-Miner-Node (2026-10-07)

## Scope and verdict

Source audit of the PC-Agent coin catalogue against Solar-Miner-Node backend, React frontend, watched-wallet balances, today's mining result, and finance performance. No runtime tests or edits to product code were made. The PC-Agent has six gross-forecast entries: XMR, PRL, RVN, ETC, DCR and QTC. BTC and CFX are not PC-Agent mining forecasts in `EarningsForecastService`.

**Verdict: not fully compatible.** Node's live earnings forecast only admits BTC, XMR and PRL in `MiningCoin`; its Agent earnings adapter silently skips the other coins. The wallet/watch UI and backend likewise support BTC/XMR/PRL only. Today's actual-revenue and historical-finance paths account for BTC plus Kryptex XMR/PRL data, not RVN/ETC/DCR/QTC. A shared-coin view is therefore partial: the Agent can estimate the six GPU/CPU coin paths, but Node cannot present or retain all six end to end.

## Coin matrix

| Coin | PC-Agent forecast | Node live earnings forecast | Watched wallet balance | Today's actual mining result / historical finance |
|---|---|---|---|---|
| BTC | No entry | Bitcoin pool path, but not PC-Agent forecast | Yes, public address; Lightning is separate BTC | Yes, pool statistics in sats |
| XMR | Yes | Yes, if Node receives Agent's matching worker algorithm and forecast | Accepted, but public-address balance explicitly `NOT_SUPPORTED` | Yes, Kryptex reward chart and price history |
| PRL | Yes | Yes, if Node receives Agent's matching worker algorithm and forecast | Yes, Pearl explorer balance | Yes, Kryptex reward chart and price history |
| RVN | Yes | No: absent from `MiningCoin`, so Agent estimate is ignored | No | No |
| ETC | Yes | No: absent from `MiningCoin`, so Agent estimate is ignored | No | No |
| DCR | Yes | No: absent from `MiningCoin`, so Agent estimate is ignored | No | No |
| QTC | Yes | No: absent from `MiningCoin`, so Agent estimate is ignored | No | No |
| CFX | No PC-Agent forecast / mining path | No | No | No |

## Behavior and evidence

- `pc-agent/.../EarningsForecastService.java` defines XMR, PRL, RVN, ETC, DCR and QTC. These are probability-based gross network estimates, not actual pool credits.
- `AgentEarningsSource` reads `/api/agent/external/earnings`, maps each reported coin through `MiningCoin.from`, and intentionally skips unregistered coins. `MiningEarningsService` publishes the remaining estimates through `/api/pv-site/{siteId}/dashboard/earnings`. This explains why an Agent forecast can exist locally while the Node's site earnings page omits it.
- The React earnings page renders the backend snapshot generically, so it does not impose its own six-coin cap. Its content is limited by Node registration and mapping.
- `MiningCoin` registers only bitcoin, monero and pearl; its address validation supports just those three. `WatchedWalletController` fetches BTC through mempool.space, PRL through pearlchain.live, and marks XMR as unsupported for public-address balance. The React wallet selector and explanatory text also reflect that limited set.
- Dashboard `Mining result today` combines Bitcoin pool reward statistics (satoshis) with coin-keyed Kryptex XMR/PRL reward snapshots and price conversions. BTC-oriented legacy fields remain in the DTO; the by-coin breakdown handles XMR/PRL. No evidence in this path records RVN/ETC/DCR/QTC actual credits.
- `PVFinanceService` adds BTC pool history and Kryptex XMR/PRL daily rewards. Its detailed coin lines label all non-Monero Kryptex rows PRL, and BTC-specific cost-per-mined-BTC, break-even BTC price, and sales ledger remain Bitcoin metrics. Broader revenue KPIs include the recognized other mining revenue but do not establish accounting for the four unsupported Agent coins.
- The PC-Agent wallet backend has its own pool adapters (current local work), including Kryptex RVN/ETC/QTC and DCR still marked unsupported for its configured pool. That local Agent balance surface is separate from Node's watched-address endpoint and does not provide Node site finance accounting.

## Contract gaps / follow-up

To claim full Node compatibility, register each supported mining coin in the Node enum with canonical algorithm, ticker, address rules and precision; wire Agent route selection and forecast mapping; add coin-specific wallet or pool-balance providers and explicit unavailable states; ingest actual daily credits with source, payout address, coin units and historical prices; then update daily dashboard and finance DTO/UI labels so BTC-only metrics are clearly separated. Do not present a gross forecast as today's earned reward or as realized finance revenue. CFX should remain explicitly unsupported until the PC-Agent has a mining path.

## Verification limits

This is a source-level compatibility audit, not an API integration test. Existing uncommitted user work was preserved. No tests were run. The working tree already contained unrelated PC-Agent and Node changes; inspect `git status` before any follow-up implementation.

## Follow-up implementation, 2026-10-07

- Node coin metadata now recognizes all six PC-Agent forecast coins and maps their worker algorithm IDs. The Node target-control UI/API remains limited to BTC/XMR/PRL because the external Agent contract currently exposes configuration writes only for XMR/PRL; the extra four coins are not presented as Node-controlled routes.
- Watched-wallet validation and UI include RVN/ETC/DCR/QTC. Kryptex pool balances use its public balance endpoint for XMR/PRL/RVN/ETC/QTC; DCR clearly reports that no pool balance adapter is integrated.
- Actual daily/historical reward ingestion now accepts Kryptex routes for XMR/PRL/RVN/ETC/QTC and can discover Kryptex accounts from watched addresses. It uses Kryptex's reward chart and price chart, not a gross hashrate forecast. Missing price/reward data remains unvalued and makes the day's mining revenue/net unavailable. DCR is included as a history-unavailable route because its current PC-Agent pool (Suprnova) has no integrated API adapter.
- Finance displays coin-agnostic pool rewards while keeping BTC sale, unsold BTC and cost-per-BTC metrics BTC-specific. The dashboard's current-day result and mining net now become unavailable when a tracked coin lacks actual reward valuation.
- `tsc --noEmit --pretty false` passed after the frontend changes. Node Java compilation could not start: the wrapper first failed to create a lock file under read-only `/home/lukas/.gradle/wrapper/dists`; a direct Gradle run with copied caches under `/tmp` then failed before build startup with `Could not determine a usable wildcard IP for this machine`. Tests were not run. Existing unrelated working-tree changes were preserved.
