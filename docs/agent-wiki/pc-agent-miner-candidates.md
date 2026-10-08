# PC-Agent miner candidates

Research status: **candidate assessment only**, checked 2026-10-05. A listed binary is not enabled, downloaded or represented as a supported SolarMiner mining route. Any adoption must follow the workspace new-coin guide, the relevant coin integration record and the PC-Agent extension contract.

## Selection infrastructure

`mining/MinerCatalogService` is the local source of selectable miner metadata. Every entry contains a stable miner ID, coin, device/algorithm, disclosed developer fee, pros/cons, provenance URL, experimental state and live installer state. The selection is persisted per coin without starting a process or mutating pool configuration. `GET /api/agent/local/miner-options` returns all entries; the additive `miners` and `selectedMiner` fields in a coin overview let the UI present any future alternatives without a coin-specific hard-coded list. `POST /api/agent/local/{coin}/miner?minerId=...` changes only this persisted choice. Download endpoints dispatch through that selected ID, so a future installer is registered once alongside its metadata rather than added as another controller coin branch.

Adding an actual implementation still requires its own process/API adapter and official downloader with pinned release metadata, size and SHA-256 verification. Before it is selectable in a working route, connect the adapter to the lifecycle/power/proxy/fee gates, process registry, console, per-worker stats and integration tests. Do not add an advertised option merely because its executable can be downloaded.

Entries marked `selectable=false` are intentionally visible as a researched alternative but cannot be selected, installed or started. This is the required state before an adapter has passed the integration guide; it avoids a download button that looks like mining support.

The API also supports an explicit software installation at `POST /api/agent/local/{coin}/miners/{minerId}/download`. Installations are therefore independent by miner ID; selecting a coin changes only that coin's active installed miner and never deletes another miner binary. Shared packages such as SRBMiner remain one installed software package used by its compatible coins.

## Current candidates

| Coin | Candidate | Devices / algorithms | disclosed developer fee | Decision / key trade-off |
| --- | --- | --- | --- | --- |
| XMR | XMRig (existing) | CPU / RandomX | 1% default/minimum in upstream source | Keep as default: existing PC-Agent lifecycle and RandomX setup already target it. |
| XMR | SRBMiner-MULTI | CPU / RandomX | 0.85% listed upstream | Candidate only. It would require a separate CPU adapter and a clean decision about Huge Pages, telemetry and proxy login semantics; no benefit has been measured here. |
| PRL | SRBMiner-MULTI (existing) | AMD, NVIDIA, Intel GPU / PearlHash | 2% listed upstream | Keep as current baseline; current Pearl path is SRBMiner-specific. |
| PRL | PrimeAI Open Pearl Miner | NVIDIA Pascal/Ampere/Ada / Pearl | 2% | Research candidate, not trusted/qualified. NVIDIA-only and license/dev-fee behavior require legal, release-provenance, API and real-pool validation. The existing Pearl record remains authoritative for what may be enabled. |
| RVN | SRBMiner-MULTI (existing experimental path) | AMD, NVIDIA, Intel GPU / KAWPOW | 0.85% | Keep only as prepared/experimental route. Existing proxy, accepted-share and accounting gates remain open. |
| RVN | TeamRedMiner | AMD GPU / KAWPOW | 2% | Best focused alternative to evaluate for AMD. It has KAWPOW support and a documented API, but needs a new adapter, vendor capability guard, and full proxy/fee-share test. |
| ETC | SRBMiner-MULTI (existing experimental path) | AMD, NVIDIA, Intel GPU / ETCHash | 0.65% | Keep only as prepared/experimental route; does not satisfy end-to-end release gates. |
| ETC | TeamRedMiner | AMD GPU / ETCHash | 0.75% Polaris, 1% other GPU (Ethash table) | Candidate for AMD only. Its ETCHash mode/stratum behavior and fee switching must be tested through the SolarMiner proxy. |
| ETC | lolMiner | AMD and NVIDIA / Ethash/Etchash | upstream release material reports 0.7–1%; confirm exact version at adoption | Candidate worth a separate verification spike because it covers AMD and NVIDIA, but its proprietary release and API/process contract need review. |
| QTC | SRBMiner-MULTI (prepared, blocked) | GPU / QPoW (Poseidon2) | 2.5% per Kryptex guide | Existing shared package and generic GPU adapter path are wired, but selection stays disabled until SolarMiner supplies a house wallet and accepted user/house/referral shares are proven. Kryptex's live fee display and guide differ; pool fee estimate is intentionally unknown. |

Primary sources: [XMRig donation source](https://github.com/xmrig/xmrig/blob/master/src/donate.h), [SRBMiner algorithms and fees](https://github.com/doktor83/SRBMiner-Multi/blob/master/README.md), [TeamRedMiner algorithms/API](https://github.com/todxx/teamredminer/blob/master/USAGE.txt), [TeamRedMiner fee table](https://github.com/todxx/teamredminer), [lolMiner official releases](https://github.com/Lolliedieb/lolMiner-releases), [PrimeAI Pearl Miner](https://github.com/PrimeAI-Foundation/Pearl-Miner).

QTC sources and current integration gates are recorded in [the Quantus integration record](quantus-integration.md). Kryptex documents SRBMiner `--algorithm quantus` and port 7049; QTCScan provides public estimated network snapshots. The official Quantus external miner protocol is QUIC, and the Quantus native pool uses WebSocket, so neither is interchangeable with the selected Stratum route.

## Recommended implementation order

1. Keep XMRig and SRBMiner as the only registered executable adapters until the catalog/lifecycle seam has a green Java-21 test run.
2. Evaluate TeamRedMiner for AMD RVN/ETC first: it is narrowly scoped, documents KAWPOW/ETCHash and exposes a read-only API. Do not expose it to users until an adapter proves stats, graceful stop, proxy compatibility and accepted user/house/referrer shares.
3. Evaluate lolMiner separately for mixed AMD/NVIDIA ETC only after validating its current signed release, fee value and API. Do not infer RVN support from an Ethash/Etchash result.
4. Treat alternative Pearl miners as a dedicated Pearl-record update, not a generic download addition.
