# PC-Agent work log

## 2026-10-09 — External proxy bypasses the local-proxy boot gate

- Selecting an external SolarMiner proxy now immediately marks the managed-local-proxy gate ready and stops any local child. The UI therefore remains operable while the remote proxy is selected; it no longer waits for a GitHub release download or a local proxy start.
- Mining readiness now requires the managed-proxy gate and child process only in local mode. External mode still verifies the configured remote proxy's API/listener and per-coin fee route before a miner can start.
- `ManagedProxyHealthTest` covers both a persisted external selection and a live switch away from local mode. Verification: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test standaloneJar --no-daemon` passed.

## 2026-10-09 — Proxy heartbeat discovery and external-host mode fix

- Root cause for known addresses: `ProxyConfigurationService.configure` rejected every host while local proxy mode was active, although the UI must save the external host before switching modes. Saving is now allowed without changing the active loopback route; `setMode("external")` activates the saved host afterward.
- A second Windows-only blocker coupled proxy discovery/configuration to optional LibreHardwareMonitor readiness. Proxy search, host/mode selection and fee-roll selection no longer require the sensor helper; mining and power operations retain their own safety gates.
- Discovery now listens for the proxy's versioned heartbeat on UDP 8092 and sends the existing `SOLARMINER_PROXY_DISCOVER_V1` query to UDP 8091 from the same socket as a backward-compatible fallback. Candidate service/version/ports and the HTTP API are still verified before display.
- The standard container publishes `8092/udp`; its old `8091/udp` mapping was ineffective and conflicted with the proxy-owned query port. TCP 8084 remains the Node control/identity port. Native launchers now bind it explicitly to `0.0.0.0`; Windows operators still have to permit the Java/8084 listener on the Private firewall profile.
- Verification: full `gradle test --offline` passed under JDK 21 after adding the sensor-decoupling regression (10 seconds on the final incremental run). No two-host LAN or Windows Firewall test was available.

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

## 2026-10-09 — Linux powercap cycle fixed

- Diagnosed the running standalone Agent with same-origin API probes and a JVM
  thread dump. `/api/agent/local/overview` produced no response within 20 s and
  `/api/agent/local/workers` produced none within 30 s, while
  `/api/agent/local/miner-options` returned HTTP 200 in 4 ms. The owning HTTP
  thread had spent more than five minutes recursively scanning
  `/sys/class/powercap` in `LinuxSensorReader.energyFiles`; sysfs `device`,
  `subsystem` and `power` links formed ancestor cycles. Other Overview and Worker
  requests accumulated behind the synchronized sensor and worker monitors.
- Replaced the recursive, link-following directory test with a bounded
  `Files.find` traversal. The selected RAPL class entry is resolved once, links
  below that root are never followed, traversal depth is capped at eight, and
  counter selection remains deterministic.
- Added `LinuxSensorReaderTest`, which builds a temporary powercap tree with
  `device`, `subsystem` and `power` cycles and verifies within one second that
  the real `energy_uj` counter is found exactly once.
- Wallet and Worker initial reads now use the shared JSON client with its
  eight-second timeout. A later backend stall therefore produces the existing
  visible page error instead of keeping both configuration views blank forever.
- Verification: focused sensor and worker tests passed; full
  `sh gradlew test standaloneJar` passed on JDK 21; `node --check` passed for
  `wallets.js` and `workers.js`; `git diff --check` passed. The already-running
  JVM still contains the old loaded class and requires one restart before the
  runtime fix takes effect.

## 2026-10-09 — GPU power-limit efficiency sweep with stability gate

- New feature next to the benchmarks: `EfficiencySweepService` walks the driver
  power limit downward from the currently active cap (never upward; default 15 W
  steps, `solarminer.agent.sweep.step-watts`)
  for every configured GPU miner target (coin × GPU: pearl, ravencoin,
  ethereumclassic, decred, quantus) and keeps only steps that prove stable under
  real mining load.
- Hardware-protection gates (the hard constraint of every step): only
  driver-verified limits are written (`LocalGpuPowerService.setTotalPowerTarget`
  read-back check; driver and persisted user limits are never undercut); NVIDIA
  uses `nvidia-smi`, AMD uses AMD-SMI or Linux `amdgpu` hwmon; every 5 s sample reads the GPU
  temperature via the new `LocalGpuPowerService.readTemperatureC` and aborts the
  step above `solarminer.agent.sweep.max-temperature-c` (default 85 °C) or when
  the temperature is unreadable; a step is stable only with a live miner
  process, valid hashrate, pool connection and rejected-share ratio below
  `solarminer.agent.sweep.max-rejected-ratio` (default 0.10); the previous power
  limits and the previous miner running/paused state are restored and verified
  in the finally block, including cancel and failure paths. Incomplete restoration
  is reported as an error. External SRBMiner processes refuse the sweep entirely.
- Best stable value per device+algorithm (max H/J) is persisted atomically to
  `./solarminer-agent/gpu-efficiency-profiles.json` via `GpuEfficiencyStore`,
  together with the full step history. No wallets, pools or identities stored.
- `LocalRunLock` serializes the sweep against benchmark sessions; Node controls
  stay locked while either runs (`withExternalControl` now checks the shared
  lock). The benchmark's early external-miner return path releases the lock.
- API: `GET/POST /api/agent/local/efficiency`, `POST .../cancel`,
  `GET .../profiles` (`EfficiencySweepController`). UI: new "Effizienz-Sweep"
  panel on benchmarks.html with progress, cancel and per-device profile cards
  including the step history.
- Power writes are capability-probed with a same-value write/readback. Multi-GPU
  failures roll every card back to its live snapshot. Original limits are
  atomically persisted before the first change and recovered on graceful shutdown
  or the next process start after a crash.
- Verification: focused `LocalGpuPowerServiceTest` and `EfficiencySweepServiceTest`
  pass on JDK 21, covering NVIDIA permission probing, NVIDIA set/readback,
  multi-GPU rollback, AMD-SMI BDF control, Linux AMD sysfs fallback, crash
  recovery and a descending ladder that never exceeds the initial cap. Full
  `sh gradlew test standaloneJar` also passes on JDK 21.
- Open gates: sweep has not yet been run end-to-end against real GPUs on this
  host; temperature ceiling and step size should be validated on the target rig
  before recommending the stored best values as permanent limits. Native Windows
  AMD remains fail-closed: ADLX exposes the tuning limit as a percentage, not an
  absolute watt cap, so it cannot safely satisfy the existing watt-target contract
  without a separately shipped and hardware-validated native adapter.

## 2026-10-09 — Efficiency sweep: dev-fee fallback for unconfigured coins

- `EfficiencySweepService.targets()` now also measures coins the operator never
  configured: an ephemeral in-memory configuration is built from the fee-backend
  house target (`PayoutDefaultsService.resolve`) exactly like the empty-wallet
  path in the operator UI, so the sweep runs coin-by-coin over every supported
  GPU coin. `PearlMinerService` and `GpuCoinMinerService` gained a
  `sweepOverride`/`effectiveConfig()` layer: operator config always wins, the
  override is never written to disk, is applied per target and cleared after
  each target plus a safety-net clear for all targets in the finally block.
- Fallback configs pass the same `validate()` as operator configs; an
  unreachable fee target or invalid wallet simply drops that coin from the run.
- Verification: `sh gradlew test standaloneJar` passed on JDK 21.
- Open gates: unchanged — real-hardware end-to-end run still pending.

## 2026-10-09 — Worker UI dynamic strings use the localization catalog

- Shared table rendering now passes primitive cell values through `t()`, matching
  the existing element, status and table-header builders. This closes a shared
  gap that left dynamic worker status and control cells in German for English UI.
- Worker count/filter/help text now uses catalog lookups and `{count}` parameters;
  backend-provided miner availability reasons use the agent-text translator `s()`.
- Added the corresponding closed-catalog entries. Verification: `git diff --check`
  passed; no build or tests run (not needed for this frontend-only change).

## 2026-10-09 — Measurement-run lock now covers local UI controls too

- The benchmark/sweep lock previously only gated Node (external) controls. Local
  UI routes in `MiningController` (miner resume/pause, per-coin and global power
  targets, coin switch, GPU resume/pause, global pause/resume, Pearl and GPU-coin
  configuration) could start a second miner on a GPU while a benchmark or
  efficiency sweep owned it — corrupting the measurement and leaving a worker
  running against the operator's restore snapshot.
- All those routes now check the shared `LocalRunLock` (`localRunsFree()`);
  configuration changes answer HTTP 400 with a cancel-the-run hint, control
  toggles answer `false`. `AgentPowerController` GPU user-limit writes now go
  through `withExternalControl` as well (409 while a run holds the lock).
- Tests: `MiningControllerValidationTest` gained lock-held and lock-free cases
  (controls refused with no service interactions while held; allowed after
  release). `sh gradlew test standaloneJar` passed on JDK 21.

## 2026-10-09 — Efficiency-sweep start diagnosis on the Linux GPU host

- The live local API showed four NVIDIA TITAN RTX cards, SRBMiner installed and
  the managed proxy running, but every card reported
  `supportsDynamicPowerScaling=false`: the harmless same-value
  `nvidia-smi -pl` capability probe failed with `Insufficient Permissions`.
  This is the concrete reason the sweep had no eligible target; the previous
  generic install/configuration error pointed operators in the wrong direction.
- Sweep admission now distinguishes no GPU, no writable Power Cap, missing
  SRBMiner and missing ready route. For a driver-permission failure the HTTP 409
  detail tells the operator to run the Agent with the required
  administrator/root driver permission and includes a one-line per-model driver
  status (multiline tool output is collapsed). The controller now returns that
  detail explicitly as `{"message":"..."}`; Spring's default 409 response on
  this installation omitted `ResponseStatusException` messages, which forced
  the dashboard to show only its generic fallback.
- Verification: `EfficiencySweepServiceTest` covers the permission-specific,
  no-GPU and missing-miner diagnostics; `EfficiencySweepControllerTest` covers
  the HTTP 409 message consumed by the dashboard. Full
  `sh gradlew test standaloneJar` passed on JDK 21. The hardware sweep remains
  blocked until the Agent process can successfully write and read back an NVIDIA
  Power Cap.

## 2026-10-09 — Docker startup diagnosis and proxy-download backoff

- A Linux `latest-beta` container log showed that Tomcat was already serving on
  port 8084, while every proxy-release lookup failed with
  `UnresolvedAddressException`. The failing dependency was Docker DNS resolution
  for `api.github.com`, not Spring Boot or the NVIDIA capability configuration.
- The image healthcheck still called the removed shared
  `/api/agent/telemetry` route. It now calls the deliberately always-open,
  read-only `/api/agent/external/identity` endpoint, so a healthy Agent is no
  longer marked unhealthy by its own namespace access filter.
- Managed proxy release retries now back off for 60 seconds after an attempted
  lookup instead of logging a complete connection stack every five seconds.
  The dashboard's explicit retry remains immediate, and a cached verified proxy
  release remains the offline fallback.
- Docker documentation now records the `api.github.com` DNS probe and persistent
  cache behavior. Verification: `ManagedProxyHealthTest` covers the retry window;
  full `sh gradlew test standaloneJar` passed on JDK 21.

## 2026-10-09 — Model-cohort efficiency sweeps and live run plan

- Power-efficiency targets are now grouped only when coin, algorithm, vendor,
  model, driver range and effective user range all match. The first card runs the
  complete descending curve. Its siblings then validate the selected limit in
  parallel; an unstable sibling advances upward by the configured watt step up
  to its original cap. Every sibling still has to prove its own miner process,
  hashrate, temperature, pool health and rejected-share ratio, and profiles stay
  keyed by device ID plus algorithm.
- The session contract now exposes one `RunStatus` per GPU/coin/algorithm with
  full-vs-validation mode, queue/running/final state, planned limits, completed
  step results and live sample count. `secondsRemaining` estimates sequential
  reference work and counts sibling validations as parallel cohorts.
- The Benchmarks page renders that plan while the sweep runs: operators see
  completed and upcoming watt steps, measured hashrate/power for finished steps,
  current sample progress, per-device outcome and approximate remaining time.
  Persisted profiles continue to appear as soon as each device completes.
- Tests cover strict cohort separation, upward validation candidates, parallel
  ETA accounting and JSON exposure of queue/sample/ETA state. JavaScript syntax,
  focused tests and full `sh gradlew test standaloneJar` passed on JDK 21.

## 2026-10-09 — Breadth-first efficiency results across GPU coins

- Reference curves for separate coin/algorithm cohorts are now packed into
  conflict-free batches. Each concurrent reference owns a distinct physical GPU;
  when all available cards are occupied, remaining coins move to the next batch.
  This produces initial profiles across the supported GPU coins earlier while
  retaining the complete descending reference curve and per-device validation.
- The chosen reference is moved to the front of its cohort before execution, so
  later sibling validation uses exactly the matching reference profile. The run
  contract exposes its `referenceBatch`, and the ETA takes the longest run per
  parallel reference batch instead of summing those runs as sequential work.
- Focused scheduling tests verify that no batch assigns one device twice, all
  cohorts remain scheduled, and same-batch reference ETAs are counted in
  parallel. `node --check` for both changed JavaScript files, `git diff --check`
  and full `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test
  standaloneJar` passed. Live multi-coin SRBMiner and driver validation remains
  a hardware rollout gate.

## 2026-10-09 — Dependency-aware coin pipeline without batch barriers

- Replaced the two-stage "all references, then all validations" execution with
  a device-exclusive completion queue. Waiting sibling GPUs can run references
  for other coins; each completed reference immediately unlocks its own sibling
  validations, and unlocked validations have priority whenever their physical
  device becomes free. Later reference start groups no longer wait for every
  worker in an earlier group.
- Ephemeral fee-backend overrides now live for the complete sweep session. This
  avoids a concurrent task clearing a coin route while another card still needs
  it for a restart between validation limits; the existing final safety path
  still clears every override before miner and power-cap restoration completes.
- Pure scheduler tests cover validation priority, fallback to another coin while
  the validation device is occupied, and refusal to double-book the busy GPU.
  `node --check` for both changed JavaScript files, `git diff --check`, and full
  `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew clean test
  standaloneJar --no-daemon` passed. Live multi-coin scheduling remains a
  hardware rollout gate.

## 2026-10-09 — Resume or restart an interrupted efficiency sweep

- The local efficiency start endpoint now accepts `mode=resume` or
  `mode=restart` (restart is the default for existing callers). Resume carries
  forward only fully completed run/profile pairs from the interrupted in-memory
  session and reconstructs the dependency queue around them. Reference and
  validation roles must still match the newly discovered plan; incompatible,
  cancelled, failed and queued work is measured again.
- The Benchmarks page exposes both choices after an incomplete session:
  "Unterbrochenen Sweep fortsetzen" and "Komplett neu starten". Reused runs are
  visibly marked as checkpoint results while the remaining live queue and ETA
  continue normally.
- Cancelling after one or more stable steps retains those partial measurements
  in session status but marks the run `CANCELLED`; it no longer overwrites a
  previously complete persisted efficiency profile. Focused service/controller
  tests cover checkpoint filtering and the resume API. `node --check` for both
  changed JavaScript files, `git diff --check`, and full
  `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew clean test
  standaloneJar --no-daemon` passed. The checkpoint is lost on Agent restart by
  design; persistent resume would require an explicit versioned sweep-state
  contract rather than guessing from best-result profiles.

## 2026-10-09 — Consent-gated upload of complete efficiency curves

- `EfficiencySweepService` now hands every produced profile, including partial
  and no-stable-result step histories, to `BenchmarkSharingService` after miner
  and power-cap restoration. A cancelled partial profile is uploadable evidence
  but remains `CANCELLED`, is not persisted as the local best and is not reused
  by resume.
- The standalone payload adds `efficiencySweeps` with participant-scoped device
  pseudonym, GPU model, coin, algorithm, requested limit, measured H/s and watts,
  maximum temperature, stability and bounded note. Upload remains behind the
  existing default-off benchmark consent and one-time prompt. Payloads above 512
  points are chunked; backend upserts make a complete retry safe.
- `BenchmarkSharingEfficiencyTest` covers retention of both stable and unstable
  points while consent is disabled. The cross-repository receiver, schema and
  validation live in `admin-portal` migration V16 and its ingest service.

## 2026-10-09 — Sweep hashrate grace and compact run-list design

- The power-limit sweep restarts the miner at every step, but the per-step
  startup grace was only 95 s while SRBMiner legitimately needs longer: DAG
  build plus its one-minute average window keep the reported hashrate at zero
  for minutes on a healthy card. The sweep therefore declared working cards
  unstable ("Miner liefert trotz Pool-Jobs keine Hashrate"). The grace is now a
  configurable `solarminer.agent.sweep.startup-grace-seconds` (default 240,
  floor 60) and the ETA startup estimate rose from 20 s to 45 s per step.
- `GpuCoinMinerService`'s own first-hashrate watchdog was raised from 180 s to
  the named constant `HASHRATE_STARTUP_WATCHDOG_SECONDS = 300` so the sweep's
  grace (240 s) always decides stability before the miner monitor kills the
  process mid-measurement.
- Benchmarks page run list redesign: only RUNNING runs keep a full highlighted
  card; QUEUED runs collapse into a "Wartende Läufe (n)" `<details>` group and
  terminal runs into an expanded "Abgeschlossene Läufe (n)" group of one-line
  disclosure rows (best stable watts in the summary). Operator-opened
  disclosures survive the 1 s re-render via `openSweepGroups`/`openSweepRows`.
  New i18n keys added in alphabetical position; the sweep explainer paragraph
  was updated to state the four-minute hashrate grace and the collapsed layout.
- ETA unit tests updated for the 45 s startup estimate (160→210, 320→420).
  `node --check` for both changed JavaScript files, `git diff --check`, and
  full `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test
  standaloneJar --no-daemon` passed. Live multi-coin timing behaviour remains
  a hardware rollout gate.
