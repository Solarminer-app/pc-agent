# PC-Agent repository wiki

Split out of `Solar-Miner-Node/pc-agent` on 2026-10-08. This repository owns the
PC-Agent: local CPU/GPU mining, miner installation and supervision, hardware
sensors, the Stratum-proxy child-process management, and the local operator UI.

Start with [pc-agent.md](pc-agent.md) — the ownership map for the source tree.

## Cross-repository boundaries

- **Node ↔ Agent:** LAN HTTP only (`:8084`; discovery via
  `GET /api/agent/external/identity` and UDP `SOLARMINER_PROXY_DISCOVER_V1`).
  The Node sends targets/decisions, never per-GPU driver commands.
- **Agent ↔ Stratum proxy:** the proxy JAR is downloaded at runtime from
  `Solarminer-app/solarminer-stratum-proxy` GitHub releases (SHA-256 verified);
  no compile-time dependency. Fee-roll mode and proxy mode persist as txt files
  under `./solarminer-agent/` and are passed to the child as `--proxy.fee.*` flags.
- **Agent ↔ Currency Service:** HTTP only, `https://currency.solarminer.app/api/v1/public/**`,
  owned by the separate `currency-service` repository.
- Workspace-wide contracts: `../../../AGENTS.md` and
  `../../../admin-portal/docs/encyclopedia/08-contracts.md` (C1, C2, C4, C9).

## Wiki pages

| Page | Use |
| --- | --- |
| [pc-agent.md](pc-agent.md) | Source ownership map (paths moved: `pc-agent/src/...` → `src/...`) |
| [refactoring-adapter-layers.md](refactoring-adapter-layers.md) | Adapter-layer refactoring program: phases 0–5 checklist (authoritative progress), hard wire/seam rules |
| [rest-sse-loading-plan.md](rest-sse-loading-plan.md) | Loading-strategy plan (P1–P6): static data via REST once, config via REST+POST response, live via event-driven SSE with `invalidate(section)` dirty flags |
| [docker-lan-discovery.md](docker-lan-discovery.md) | Host-network ports and UDP broadcast limits for LAN discovery |
| [frontend-console-tools.md](frontend-console-tools.md) | Independent wallet/pool, benchmark, power, Auto Tuning, update and log tools / Eigenständige Spezialwerkzeuge |
| [frontend-console-audit.md](frontend-console-audit.md) | Independent operations frontend: audit, DE/EN UI, new controllers, scalability/transport contract and verification / Eigenständige Betriebskonsole und Analyse |
| [frontend-i18n.md](frontend-i18n.md) | German/English catalog contract and verification |
| [../KNOWN_BUGS.md](../KNOWN_BUGS.md) | Prioritized PC-Agent defect backlog; agents check it after scoping their assigned work when capacity permits |
| [Economic dispatch (C10)](economic-dispatch.md) | Node capability/short-lived-plan boundary and cold-start policy |
| [Revenue candidate comparison](coin-hopping.md) | Historical coin-hopping contract, retirement, and the benchmark USD/kWh comparison |
| [Worker operating modes](worker-power-modes.md) | Per-worker manual, efficiency and revenue modes; local cap ownership and Node on/off contract |
| [GPU Auto Tuning](auto-tuning.md) | Driver-probed core/memory clock control, Bayesian calibration sessions, profile cache and local UI |
| [GPU Toolkit adapters](gpu-toolkit.md) | Shared GPUInterface API and NVIDIA NVML / nvidia-smi implementations |
| [Daily-profit modes](worker-daily-profit-modes-draft.md) | Beta fixed-coin and coin-selecting GPU modes that maximize estimated net profit per day |
| [Power Consumption history](power-consumption.md) | Historical measured mining energy, period aggregates and the consent-gated LAN history contract |
| [MINER-INTEGRATION-GUIDE.md](../../MINER-INTEGRATION-GUIDE.md) | Adding a miner to an existing coin |
| [pc-agent-miner-candidates.md](pc-agent-miner-candidates.md) | Miner candidate backlog |
| [quantus-integration.md](quantus-integration.md) | QuanPool miner contract, implementation and live certification gates |
| [peak-wildrig-beta.md](peak-wildrig-beta.md) | PeakMiner Pearl and WildRig NG Quantus Beta adapter contracts, verification and open gates |
| [miner-onboarding-architecture.md](miner-onboarding-architecture.md) | HiveOS-style signed manifest design for faster miner onboarding |
| [pc-agent-node-coin-compatibility-2026-10-07.md](pc-agent-node-coin-compatibility-2026-10-07.md) | Coin compatibility matrix vs Node |
| [pc-agent-ux-profile.md](pc-agent-ux-profile.md), [pc-agent-ui-redesign.md](pc-agent-ui-redesign.md), [pc-agent-design-concept.md](pc-agent-design-concept.md), [pc-agent-operations-ui.md](pc-agent-operations-ui.md), [pc-agent-frontend-refactor-handoff.md](pc-agent-frontend-refactor-handoff.md) | UI/UX records; static dashboard under `src/main/resources/static/` |
| [pc-agent-pool-api-research-2026-10-04.md](pc-agent-pool-api-research-2026-10-04.md), [rvn-etc-integration.md](rvn-etc-integration.md) | Pool research and RVN/ETC status |
| [windows-first-start.md](windows-first-start.md) | Native Windows bootstrap, Defender and power-cap permission analysis |
| [agent-updates.md](agent-updates.md) | Native launcher updates, Docker instructions, channel and rollback contract |

Historical pages still contain some `pc-agent/...` paths from the Node-repo
layout; they map to the repository root here.

## Build & test on this host

`./gradlew` may not be executable: run `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test`.

The Gradle `bootRun` development launch uses port `8085` so it can run beside a
normally installed Agent on `8084`. Spring's configured `server.port` also
feeds the Agent's LAN discovery announcement, so discovered development peers
advertise the reachable port.

## Work log

See [work-log.md](work-log.md).
