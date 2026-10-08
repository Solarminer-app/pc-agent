# PC-Agent miner integration guide

This is the required implementation guide for an AI agent adding an additional mining program to an already supported PC-Agent coin. It complements the workspace [new-coin guide](../../NEW-MINING-COIN-GUIDE.md); it does not relax any coin, proxy, fee, accounting or rollout gate.

## Before changing code

1. Read the repository `AGENTS.md`, `docs/agent-wiki/pc-agent.md`, the coin's integration record and this guide. For RVN/ETC, the record is `docs/agent-wiki/rvn-etc-integration.md`; both coins remain experimental until real share/accounting evidence exists.
2. Inspect Git status and preserve unrelated work. Record the miner, exact version, official source/release channel, license, device/OS matrix, algorithm, Stratum dialect, API protocol, dev fee, pool compatibility and known limitations in `docs/agent-wiki/pc-agent-miner-candidates.md` before presenting it to users.
3. Do not treat a common algorithm as compatibility evidence. Capture a real direct-pool startup and a SolarMiner-proxy startup before enabling the option.

## Required implementation seams

1. **Catalog:** add a `MinerCatalogService.MinerOption` with a stable software ID, accurate device support, algorithm, fee, user-facing pros/cons, official URL and experimental status. Until every following item is implemented and tested, set `selectable=false` with a reason; the frontend will show it but cannot select it. Installations are software-ID scoped: never overwrite or remove a different installed miner when installing this one.
2. **Installer:** add a dedicated official-source downloader selected by the software ID. Require HTTPS, expected filename/version, release metadata, finite size and SHA-256 verification before extraction. Track exactly installed files and remove only those files. Never reuse SRBMiner's installer for another vendor.
3. **Lifecycle adapter:** implement a dedicated package/class for command construction, configuration persistence, start, safe stop, process-exit monitoring, duplicate process detection and restart failure states. A catalog entry alone must never launch or pretend to control a process.
4. **Telemetry and UI:** map the miner's documented local API/log data to per-GPU status, hashrate units, accepted/rejected shares, pool health, console output and process ownership. Preserve stable GPU matching; do not use a vendor-local numeric index when a PCI/UUID mapping is available.
5. **Power and exclusivity:** integrate with `MiningService`, `LocalGpuPowerService`, `MinerProcessRegistry` and the Node worker assignment so one physical GPU is never started by two miners. Unsupported power control must remain explicit start/stop-only.
6. **Proxy and fees:** the final start path must validate the selected coin's SolarMiner proxy route, reachability and loaded fee target. Direct pool fallback is forbidden. Capture subscribe/login, jobs, difficulty, submit, reconnect and fee switch for this miner's actual protocol.
7. **Frontend:** keep the generic catalog selector. Expose the miner only as selectable after its adapter is usable. Display developer fee, device restrictions, experimental state and unavailable reason from API data; do not hard-code them in JavaScript.

## Acceptance gates

- Unit tests cover catalog selection/rejection, command construction, installer integrity failure, lifecycle failure/stop, parser/API fixtures and GPU exclusivity.
- A supported device/OS proves start, hash rate, stop, and no stale process. A direct path is only diagnostic; the managed path must use the SolarMiner proxy.
- Before any release claim: prove accepted user, SolarMiner and referral shares plus pool credit/payout as required by the workspace guide. Until then keep the option experimental and document the gate.
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
