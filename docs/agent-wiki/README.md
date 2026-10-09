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
| [Economic dispatch (C10)](economic-dispatch.md) | Node capability/short-lived-plan boundary and cold-start policy |
| [MINER-INTEGRATION-GUIDE.md](../../MINER-INTEGRATION-GUIDE.md) | Adding a miner to an existing coin |
| [pc-agent-miner-candidates.md](pc-agent-miner-candidates.md) | Miner candidate backlog |
| [pc-agent-node-coin-compatibility-2026-10-07.md](pc-agent-node-coin-compatibility-2026-10-07.md) | Coin compatibility matrix vs Node |
| [pc-agent-ux-profile.md](pc-agent-ux-profile.md), [pc-agent-ui-redesign.md](pc-agent-ui-redesign.md), [pc-agent-design-concept.md](pc-agent-design-concept.md), [pc-agent-operations-ui.md](pc-agent-operations-ui.md), [pc-agent-frontend-refactor-handoff.md](pc-agent-frontend-refactor-handoff.md) | UI/UX records; static dashboard under `src/main/resources/static/` |
| [pc-agent-pool-api-research-2026-10-04.md](pc-agent-pool-api-research-2026-10-04.md), [rvn-etc-integration.md](rvn-etc-integration.md) | Pool research and RVN/ETC status |

Historical pages still contain some `pc-agent/...` paths from the Node-repo
layout; they map to the repository root here.

## Build & test on this host

`./gradlew` may not be executable: run `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test`.

## Work log

See [work-log.md](work-log.md).
