# Agent entry point — SolarMiner PC-Agent

Read `docs/agent-wiki/README.md` and `docs/agent-wiki/pc-agent.md` (ownership
map) and inspect Git status before changing code. Preserve existing work. For
Java work, use focused IntelliJ IDEA MCP navigation when that server is
available; otherwise use repository and shell tools. Keep queries focused.

This repository owns the PC-Agent: local CPU/GPU mining (XMRig, SRBMiner-MULTI),
miner installation and supervision, hardware sensors, Stratum-proxy child
process management, and the local operator UI under
`src/main/resources/static/`. It was split out of `Solar-Miner-Node/pc-agent`
on 2026-10-08; the Node repository still contains the original module until it
is removed there (separate step).

Cross-repository coupling is protocol-only — keep it that way:

- Node ↔ Agent: LAN HTTP on `:8084` and UDP discovery
  (`SOLARMINER_PROXY_DISCOVER_V1`). The Node sends targets/decisions, never
  per-GPU driver commands. `GET /api/agent/external/identity` is the deliberate
  always-open exception to the external-control gate; every other external
  route stays behind `externalControlEnabled`.
- Agent ↔ Stratum proxy: the proxy JAR is downloaded at runtime from
  `Solarminer-app/solarminer-stratum-proxy` GitHub releases with SHA-256
  verification. Never bundle proxy classes into the agent JAR (the release
  workflow enforces this).
- Agent ↔ Currency Service: HTTP only via `https://currency.solarminer.app`;
  owned by the separate `currency-service` repository.

Adding a miner to an existing coin: follow
[`MINER-INTEGRATION-GUIDE.md`](MINER-INTEGRATION-GUIDE.md). New coins: the
workspace `NEW-MINING-COIN-GUIDE.md` and the Pearl record
`../PEARL-INTEGRATION.md` apply. Operator settings persist as plain txt files
under `./solarminer-agent/` and are handed to the proxy child as CLI flags;
changing them restarts the child, so pause miners first.

Build/test: JDK 21; `./gradlew test standaloneJar` (on hosts where the wrapper
is not executable: `sh gradlew ...`). Release: push a `pc-agent-v<version>` tag
matching `pcAgentVersion`; `release.yml` publishes the Docker image and the
GitHub release assets (standalone JAR, SHA-256, Windows launchers). Until the
first release is cut here, the authoritative releases remain in
`Solar-Miner-Node`.

Record durable findings in `docs/agent-wiki/` and completed work with
verification evidence in `docs/agent-wiki/work-log.md`. AGPLv3 + TRADEMARK.md
apply. Git to the outside (commit/push/tag) only with explicit owner approval.
