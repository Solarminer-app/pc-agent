# PC-Agent work log

## 2026-10-08 — Post-benchmark one-time share prompt

- After a manual benchmark finishes while periodic benchmark sharing is OFF, `/benchmarks.html`
  now opens a modal asking whether to upload the just-measured results once, anonymously. The
  prompt appears only when the sharing consent is known to be disabled and at most once per
  page session; "Nicht teilen" (or Esc) dismisses it without any network call.
- `BenchmarkSharingService.reportManualResults` now retains the sampled workers even when
  sharing is off (`NOT_SHARED` status carries the real `sampleCount`), so the retained batch
  can be offered for upload. New `uploadManualResultsOnce()` + `POST
  /api/agent/local/benchmarks/sharing/upload-manual` send exactly that retained batch with
  `telemetryOptIn=true` while deliberately NOT flipping the persisted periodic consent — the
  admin ingest (`StandaloneBenchmarkIngestService`) treats the batch as one participant report;
  the next periodic interval still sends nothing because `state.sharingEnabled()` stays false.
  On success the retained samples are cleared; on failure the existing "Upload erneut
  versuchen" retry path stays available (it requires sharing to be on, matching its semantics).
- i18n: all new German strings (dialog title/body/buttons, notices) got frontend-catalog
  entries in alphabetical position; the dialog reuses the existing `.worker-editor`/
  `.dialog-actions` styles.
- Verification: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test` passed (full
  suite, exit 0) and `git diff --check` passed. Catalog coverage was verified by grepping every
  new `t()` literal and static-HTML string back into `i18n-catalog.js`. Live HTTP probe against
  a headless `bootRun` agent: `POST /benchmarks {"mode":"LIVE"}` returned 200 and completed;
  `upload-status` then showed `NOT_SHARED` with the real `sampleCount` (0 here, no active
  workers); `POST /sharing/upload-manual` returned `NO_DATA` as designed. The dialog itself was
  not clicked in a browser (no Chromium on this host); `node --check` on the page script passed.

## 2026-10-08 — Endpoint-confirmed orphan proxy cleanup

- On startup, the PC-Agent now queries the loopback proxy API before it cleans up a prior managed child. A current proxy must identify itself through `GET /api/health` as `solarminer-stratum-proxy`; only then are matching processes from the managed release directory terminated. This avoids killing a process solely because its command line resembles a proxy launch.
- Older published proxy releases fall back to their existing `GET /api/network/ip` endpoint, while the release-directory command-line marker still confines termination to a PC-Agent-managed proxy.
- Verification: focused `ManagedProxyHealthTest` covers the service identity distinction.

## 2026-10-08 — Managed proxy popup readiness

- The boot gate now reads the managed proxy's `/api/health` and checks a fresh per-launch instance ID plus service name. It rechecks directly when the gate API is requested, while the visible browser overlay refreshes that API every two seconds in addition to SSE. This closes the stale-popup path and prevents another process on the API port from confirming a new child. Older published proxy JARs still use `/api/network/ip` until the health endpoint ships.
- Verification: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test --offline --no-daemon` passed after four existing test fixtures were updated for the parallel fee-tier constructor change and the proxy-log fixture's literal `\\n` was corrected to a newline. `node --check` and `git diff --check` passed. No live Windows popup/proxy session was available; the sibling proxy's focused health-controller test passed.

## 2026-10-08 — Managed-proxy startup log in the boot gate

- The blocking startup gate now shows the managed Stratum proxy child's combined
  stdout/stderr as it starts. The browser reads only the fixed release-directory
  `proxy.log` through `GET /api/agent/local/proxy-gate/log?offset=…`; it cannot
  select a filesystem path. Reads are incremental and capped at 64 KiB per
  response, while the UI refreshes every 750 ms and stops when the gate closes.
- `ManagedProxyService` remains the owner of the child process and release log;
  no proxy implementation or cross-repository protocol changed. The agent's
  existing miner consoles remain separate.
- Verification: `compileJava` completed successfully as part of the targeted
  Gradle test invocation; `node --check src/main/resources/static/js/chrome/proxy-gate.js`
  and `git diff --check` passed. Test compilation is currently blocked by
  pre-existing uncommitted fee-tier changes whose three test fixtures still use
  the old `FeeTransparencyService` and `AgentWriteAccessFilter` constructors.

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

## 2026-10-08 — Fee tier enforcement + header status badge

- `FeeTierService` decides the effective dev-fee tier locally and pushes it to
  the proxy (`POST /api/v1/fees/tier`) on every consent-hook flip (listener on
  `AgentControlSettingsService.update`), on every external Node call
  (`AgentWriteAccessFilter` records presence; 15 min window) and via a 60 s
  self-heal. Rule fails toward the higher fee: consent hook on OR recent Node
  presence => `node`; only a genuinely Node-free agent runs `proxy`. The
  managed proxy child also starts with `--solarminer.fee.tier=` so a fresh
  child never resolves the wrong tier before the first push.
- Transparency: `FeeTransparencyService`, `PayoutDefaultsService` and the new
  `devFeeSummary()` read targets with the effective `tier`, so display and
  enforcement never diverge. Nothing hardcodes 2.5/1.0 — a referral code can
  shift or lower the total and the UI shows it live.
- Header badge (`js/chrome/fee-tier-badge.js`, all 8 pages): green lamp = Node
  attached, amber = proxy-only; label shows mode + live total dev fee; hover/
  focus tooltip lists SolarMiner/referrer shares, total and referral code.
  New SSE channel `fee-tier` (5 s) + `GET /api/agent/local/fee-tier`. i18n
  frontend catalog extended for every new string.
- Evidence: `sh gradlew test` green (JDK 21) incl. new `FeeTierServiceTest`
  (consent hook, presence window, proxy default). Browser rendering not
  exercised; badge verified by code review only.
- Open gates: end-to-end tier flip against a running proxy+fee-backend not yet
  observed on real hardware.
