# PC-Agent miner integration guide

This is the required implementation guide for an AI agent adding an additional mining program to an already supported PC-Agent coin. It complements the workspace [new-coin guide](../../NEW-MINING-COIN-GUIDE.md); it does not relax any coin, proxy, fee, accounting or rollout gate.

## Before changing code

1. Read the repository `AGENTS.md`, `docs/agent-wiki/pc-agent.md`, the coin's integration record and this guide. For RVN/ETC, the record is `docs/agent-wiki/rvn-etc-integration.md`; both coins remain experimental until real share/accounting evidence exists.
2. Inspect Git status and preserve unrelated work. Record the miner, exact version, official source/release channel, license, device/OS matrix, algorithm, Stratum dialect, API protocol, dev fee, pool compatibility and known limitations in `docs/agent-wiki/pc-agent-miner-candidates.md` before presenting it to users.
3. Do not treat a common algorithm as compatibility evidence. Capture a real direct-pool startup and a SolarMiner-proxy startup before enabling the option.

## Required implementation seams

1. **Catalog and assignment:** add a `MinerCatalogService.MinerOption` with a stable software ID, accurate device support by coin and GPU vendor/model, algorithm, fee, user-facing pros/cons, official URL and experimental status. The Software page must show coin/GPU combinations and filter miners by GPU compatibility. Worker assignment must offer only compatible miner/GPU combinations, and the backend must reject incompatible assignments and starts; a UI filter alone is not enforcement. Before any usable end-to-end path exists, it can be shown as unavailable with a precise implementation reason. As soon as a minimally testable end-to-end path exists, it must be installable/selectable on `beta` so users can test it; do not wait for real hardware, pool-share, payout, or release certification. Missing binary, unsupported OS/GPU, or missing proxy/fee route may block only the affected operation and must be reported as the actual runtime prerequisite, never as “not integrated.” Keep experimental status and pending evidence visible. Promotion to the release path requires the applicable acceptance gates below. Do not add a second hard-coded runtime channel switch: branch promotion is the rollout gate. Installations are software-ID scoped: never overwrite or remove a different installed miner when installing this one. Persist the chosen software ID per physical worker, not only per coin; changing one GPU must not silently migrate every GPU mining that coin.
2. **Installer:** add a dedicated official-source downloader selected by the software ID. Require HTTPS, expected filename/version, release metadata, finite size and SHA-256 verification before extraction. Track exactly installed files and remove only those files. Never reuse SRBMiner's installer for another vendor.
3. **Lifecycle adapter:** implement a dedicated package/class for command construction, configuration persistence, start, safe stop, process-exit monitoring, duplicate process detection and restart failure states. A catalog entry alone must never launch or pretend to control a process.
4. **Telemetry and UI:** map the miner's documented local API/log data to per-GPU status, hashrate units, accepted/rejected shares, pool health, console output and process ownership. Preserve stable GPU matching; do not use a vendor-local numeric index when a PCI/UUID mapping is available.
5. **Power and exclusivity:** integrate with `MiningService`, `LocalGpuPowerService`, `MinerProcessRegistry` and the Node worker assignment so one physical GPU is never started by two miners. Unsupported power control must remain explicit start/stop-only.
6. **Proxy and fees:** the final start path must validate the selected coin's SolarMiner proxy route, reachability and loaded fee target. Direct pool fallback is forbidden. Capture subscribe/login, jobs, difficulty, submit, reconnect and fee switch for this miner's actual protocol.
7. **Frontend:** keep the generic catalog selector. Expose the miner as selectable on beta as soon as its minimum testable path is usable. Display developer fee, device restrictions, experimental state and any concrete runtime unavailable reason from API data; do not hard-code them in JavaScript or use pending certification as a selection block.

## Acceptance gates

- Unit tests cover catalog selection/rejection, command construction, installer integrity failure, lifecycle failure/stop, parser/API fixtures and GPU exclusivity.
- A supported device/OS proves start, hash rate, stop, and no stale process. A direct path is only diagnostic; the managed path must use the SolarMiner proxy.
- Before release promotion or a production support claim: prove accepted user, SolarMiner and referral shares plus pool credit/payout as required by the workspace guide. Until then keep the beta option usable for testing, mark it experimental, and document the open gate.
- Add a dated work-log entry with source/test evidence, non-applicable guide steps, limitations, rollback and exact commands/results. Do not write “supported” based only on compilation or a running process.

## Minimal change map

| Concern | Primary location |
| --- | --- |
| catalog/selection/download dispatch | `mining/MinerCatalogService` |
| local API and overview | `controller/MiningController` |
| common GPU lifecycle examples | `pearl/GpuCoinMinerService`, `pearl/PearlMinerService` |
| installer integrity example | `pearl/SrbDownloadService` |
| power/process exclusion | `mining/MiningService`, `mining/MinerProcessRegistry` |
| generic catalog UI | `src/main/resources/static/agent.js` |
| coin-specific open gates | `docs/agent-wiki/rvn-etc-integration.md` |
