# PC-Agent work log

## 2026-10-08 — Repository split from Solar-Miner-Node

- Created this repository from `Solar-Miner-Node/pc-agent` (tracked files via
  `git archive`, 145 files; no `build/` or runtime `solarminer-agent/` state).
- Layout flattened to repository root: `src/`, `standalone/`, `Dockerfile`,
  `build.gradle.kts`, compose files, `MINER-INTEGRATION-GUIDE.md`.
- Added standalone Gradle root: `settings.gradle.kts` (`rootProject.name =
  "pc-agent"`), `gradle.properties` (`pcAgentVersion=1.1.7`,
  `pcAgentImage=verdox/solar-miner-pc-agent`), Gradle wrapper 8.14.5 copied
  from the Node repository, and `printPcAgentVersion`/`printPcAgentImage`
  tasks required by the release workflow.
- `.github/workflows/release.yml` adapted from the Node repo's
  `docker-deploy-pc-agent.yml`: same `pc-agent-v*` tag trigger, paths without
  the `pc-agent/` prefix, unused embedded-proxy checkout removed (the agent
  downloads the proxy JAR at runtime; the JAR-content guard stays).
- Windows launchers (`standalone/start-agent*.ps1|bat`) now resolve releases
  from `Solarminer-app/pc-agent` instead of `Solar-Miner-Node`. NOTE: until the
  first release is cut from this repository, the launchers find no release —
  keep the Node-repo release flow authoritative until then (removal from the
  Node repo is a separate, later step).
- Wiki pages copied from the Node repo's `docs/agent-wiki/` (pc-agent*.md,
  rvn-etc-integration.md); internal links adjusted, historical `pc-agent/...`
  paths documented as mapping to this repository root.
- LICENSE (AGPLv3) and TRADEMARK.md copied from the Node repository.
- NOT yet done (owner decision / follow-up): nothing was removed from
  `Solar-Miner-Node` — the module, its CI workflow and the Node wiki pages are
  still in place; this repository is currently a full copy plus standalone
  build wiring. Git history was not rewritten (plain copy commit pending).
