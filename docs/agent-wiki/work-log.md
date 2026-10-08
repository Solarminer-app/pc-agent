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

## 2026-10-08 — Beta pipeline established; module removed from Solar-Miner-Node

- Added `.github/workflows/beta.yml` (push to `beta`): publishes
  `verdox/solar-miner-pc-agent` commit-tagged/`latest-amd64-beta`/`latest-beta`,
  builds the standalone JAR with the no-proxy-classes guard, and creates the
  `pc-agent-beta-<shortsha>` prerelease with beta launchers. Mirrors the former
  Node-repo `docker-beta.yml` pc-agent jobs exactly, with root-relative paths.
- Created local branch `beta` from `main` (push pending owner approval).
- Solar-Miner-Node side completed the same day: `pc-agent/` deleted, Gradle
  wiring (`include`, versions, print tasks), `docker-deploy-pc-agent.yml` and
  all pc-agent jobs in `docker-beta.yml` removed; Node compiles clean
  (`gradlew projects` shows only cgminerapi/core/proto/pv-api; compileJava
  EXIT 0). Node wiki pages for the agent moved to this repository
  (`docs/agent-wiki/`), `PC-AGENT-PV-POWER-CONTROL.md` moved to `docs/`, and
  the agent API contract section of the Node `docs/API.md` moved to
  `docs/API.md` here with a pointer left in the Node doc.
