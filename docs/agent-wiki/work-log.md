# PC-Agent work log

## 2026-10-10 — Clean-code refactoring: closed coin/state enums and the miner adapter seam

- `coin/Coin` is now the single coin identity (id, ticker, algorithm plus aliases, device kind, experimental flag, `miningCoins()`, `gpuCoins()`, `sharedSrbCoins()`, `assignableTo(cpuWorker)`, `byAlgorithm()`, `byIdOrNull()`); a new coin is one enum entry plus an adapter. Closed state enums `DownloadState`, `SweepRunState` (+`terminal()`), `SweepMode`, `ProxyMode`, `FeeRollMode`, `BenchmarkMode`, `WorkerCoinPolicy`, `WorkerIds` and `mining/ProxyLifecycle` replaced magic strings across controllers and services without changing any wire name. Iron rule: every enum wire name is a REST contract; getters return `.wireName()`.
- `miner/` adapter seam: `CoinMiner` (lifecycle, `setPowerCap`, `updateProxyRoute`, `setupComplete()`, `appendBenchmarkEvent`), `CpuMiner` (+`readyForStart`, `savedRoute()` — XMRig login parsing stays in the XMR adapter) and `GpuCoinMiner` (per-GPU control, `configuration/applyConfig/validateConfig`, `reportedAlgorithm`). `MinerFactory` (`miner/cpuMiner/gpuMiner/srbGpuMiner/gpuMiners/supports`) is the only lookup used by `MiningService`, `EconomicPlanningService`, benchmark/sweep services, `WorkerAssignmentService` and `MiningController`; coin-specific knowledge lives only in `XmrMinerService implements CpuMiner`, `PearlMinerService implements GpuCoinMiner` and the per-coin `SrbGpuCoinView` delegate.
- Local REST consolidation: `/{coin}/download`, `/{coin}/remove` and `/{coin}/configuration` replace the monero/pearl special routes (GPU removal still stops all GPU runs because SRBMiner is one shared installation). `ProxyOverview` is coin-agnostic (`coinRoutes[]` plus `@JsonAnyGetter` re-emitting the legacy flat `<coinId>Url`/`<coinId>FeeReady` fields that Solar-Miner-Node `MinerAgentController.prepareProxy(urlField)` reads), pinned by `ProxyOverviewWireFormatTest`. `MinerCatalogService.MinerOption` gained `coinName` with a backward-compatible 15-arg constructor. External Node routes (`/api/agent/external/monero|pearl/configuration`) are unchanged by design; the frontend (`proxy.js`, `software.js`) now consumes `coinRoutes[]` and the generic `/remove`.
- Verification: `JAVA_HOME=/home/lukas/.jdks/temurin-21 sh gradlew test standaloneJar --no-daemon` → BUILD SUCCESSFUL, 127 tests in 33 suites, 0 failures/errors/skipped (results re-counted from `build/test-results/test` on 2026-10-10). `node --check` passed for `proxy.js` and `software.js`; `git diff --check` clean. Manual UI smoke (proxy page, software page, wallet editor) remains an open rollout gate. Changes are uncommitted on `beta` pending owner approval; see `REFACTORING-HANDOVER.md` for optional follow-ups.

## 2026-10-10 — Worker inventory loading regression

- The Worker page now renders the `/api/agent/local/workers` result immediately. Miner catalog loading only gates assignment controls; an unavailable catalog no longer hides the inventory or local start/pause actions.
- The five-second refresh is single-flight. A slow request can complete instead of being discarded by a newer polling revision on every cycle. The catalog failure notice is localized in German and English.
- Verification: `node --check` for the changed page and catalog and `git diff --check` passed. A jsdom smoke run held the miner-catalog request pending and confirmed that the CPU row still rendered while assignment stayed disabled. The local live Agent returned one CPU and four GPUs from `/api/agent/local/workers`; it was still serving an older `workers.html`/`workers.js` resource copy, so the running process must be updated before this frontend fix is visible there.

## 2026-10-10 — Central Node automation control

- Node remote-control consent and per-worker permissions now live on the localized `/automation.html` page. Worker setup and the Worker overview retain only coin/miner assignment and local start/pause operations, so changing a worker profile cannot accidentally change Node access.
- The global consent remains the server-side gate for all Node routes. Worker-level permissions are configurable only after that gate is enabled; disabling it blocks every Node command while preserving the individual worker choices for a later explicit re-enable.
- The fee-tier contract now follows that explicit consent only. Revoking global remote control immediately pushes the reduced `proxy` tier (the 1% Dev-Fee tier) to the managed/external proxy; prior Node activity cannot keep the higher Node tier alive.
- German and English frontend catalog entries cover the new page, navigation, notices and accessibility-facing labels. The controls capture the selected value before rendering the pending state, so an enable action cannot be turned into a disable request. Verification: `node --check` passed for the changed frontend files; JDK 21 focused `FeeTierServiceTest` and `WorkerAssignmentServiceTest` passed; `git diff --check` passed.

## 2026-10-10 — Benchmark default-payout fallback and actionable failure

- Sequential benchmarks now cover every locally integrated coin (Monero, Pearl, Ravencoin, Ethereum Classic, Decred and Quantus). Before the run, every installed GPU miner with a detected GPU receives its SolarMiner default route when neither a worker assignment nor an operator wallet/pool route exists. An unavailable default payout skips only that coin's phase rather than aborting another ready miner; the skipped phase is recorded in its console and the final session summary. GPU-coin phases select only their own algorithm's workers and restore GPU miners that were running before the session.
- When no phase can run, the start endpoint reports the precise default-payout/proxy/fee-target reason instead of only saying that a miner must be configured. The frontend renders these dynamic Agent diagnostics in German and English, including nested skipped-coin messages.
- The default payout remains proxy-resolved at start time; no wallet or pool credentials are invented or persisted when the SolarMiner target is unavailable.
- Verification: JDK 21 focused `BenchmarkSessionServiceTest`, `node --check src/main/resources/static/js/core/i18n.js`, and `git diff --check` passed.

## 2026-10-09 — Worker start diagnostics, wallet-free start, Windows bootstrap, AMD Docker sensors

- Local worker starts now return HTTP 400/409 with a concrete reason instead of a bare `false`; the Workers UI displays the response and refreshes the worker state. Sequential benchmark skips retain the miner's start error in the session phase summary and console. The benchmark start UI reads Spring's `detail` response field. These messages remain local to the Agent API; Node protocol payloads did not change.
- Starting an assigned GPU worker without a saved wallet now resolves the marked house payout from the proxy and configures the assigned devices and matching pool, wallet and worker before launch. Sequential Pearl benchmarks prepare the same route; Monero already used `XmrMinerService.ensureDefaultConfiguration`. A missing house target or proxy route stays a visible failure, since an unverified destination cannot be substituted. The default route directs the entire user payout to SolarMiner; the existing fee and wallet UI must keep this clear.
- RVN/ETC command inspection confirmed `--algorithm-gpu` and the coin-specific `--nicehash true`/`--esm 2` flags. The recorded Linux Titan RTX runs reached nonzero hashrates after the GPU parameter correction. The earlier Windows RTX 2080 Ti epoch failure, pool accepted shares, fee switching and payout verification remain open; no new support certification is claimed. See [RVN/ETC integration](rvn-etc-integration.md).
- The native Windows standalone launcher now runs a once-per-install bootstrap. It checks folder write access, Administrator token and NVIDIA tool presence, reads Defender's exact exclusion state, and offers an explicit UAC action to add the install folder. It verifies the state after the helper exits and can be rerun with `-Bootstrap`. Microsoft documents that `Add-MpPreference -ExclusionPath` suppresses scanning for that folder and warns that exclusions reduce protection: [cmdlet](https://learn.microsoft.com/powershell/module/defender/add-mppreference), [exclusion guidance](https://learn.microsoft.com/en-us/defender-endpoint/configure-contextual-file-folder-exclusions-microsoft-defender-antivirus). The launcher cannot certify a GPU power write; `LocalGpuPowerService` remains the readback gate. Native Windows/UAC and third-party antivirus behavior still need host testing.
- The AMD Docker overlay now exposes the host powercap class and symlink target read-only. AMD CPU package watts require a host `amd-rapl` `energy_uj` counter; this workspace host exposed no readable counter, so a real AMD CPU watt reading was not demonstrated. Compose config resolved cleanly.
- Workspace and Agent `AGENTS.md` now require German/English review for every user-facing change. New Worker and benchmark error presentation is translated through the existing catalogs; further pre-existing untranslated strings need separate audit.
- Verification: JDK 21 `sh gradlew test --offline` passed; `node --check` passed for changed JavaScript; `docker compose -f docker-compose.pc-agent.yml -f docker-compose.pc-agent.amd.yml config --quiet` and `git diff --check` passed. Windows launcher execution, real Defender policy, AMD host sensor, and RVN/ETC accepted-share tests were not available in this Linux workspace.

## 2026-10-09 — C10 economic dispatch capability and cold-start guard

- Added the externally gated, versioned `GET /api/agent/external/capabilities` and `POST /api/agent/external/economic-plan` contract. It exposes only worker identity, allowed coins, fee/route readiness and optional performance observations; no wallet, pool credential or driver command crosses the Node boundary.
- `AUTO` is a new explicit local worker coin policy. Existing and newly discovered workers remain `FIXED`; a Node cannot enable `AUTO` remotely. A plan must have a 30-second to 30-minute TTL, a locally enabled AUTO worker, a configured fee-ready route and a positive local profile.
- Cold start fails closed: a profile is a positive live hashrate plus measured mining watts, or a stable local GPU efficiency-sweep result. Unknown candidates are reported as needing a benchmark and cannot be selected by a plan. Accepted plans restore their prior assignment/target at TTL expiry unless an operator changed it meanwhile. This prevents theoretical GPU profitability from displacing a measured ASIC/PC workload.
- Node Core and the Node client now proxy the additive payload via `/agent/economic-capabilities` and `/agent/economic-plan`, preserving the Agent as local safety authority. The Node scheduler has not been switched to automatic economic dispatch yet; it must consume durable benchmark/sweep profiles before enabling a coin-changing planner.
- Verification: focused `ExternalAgentControllerTest` and `WorkerAssignmentServiceTest` passed with JDK 21; Node `:compileJava :core:compileJava` passed. `git diff --check` passed.

## 2026-10-09 — Manual benchmark profiles feed economic dispatch

- A complete local manual benchmark now persists median H/s and measured watts under `(worker, coin, algorithm)` in `solarminer-agent/performance-profiles.json`. Incomplete, zero-power and zero-hashrate observations are deliberately omitted.
- C10 capability output prefers a fresh persisted `BENCHMARK` profile over an efficiency sweep and uses a stable sweep as fallback. This lets a newly benchmarked CPU or GPU participate in economic planning without relying on a theoretical hardware estimate.
- Verification: `MiningPerformanceProfileStoreTest` covers atomic persistence and case-insensitive algorithm lookup; focused PC-Agent tests remain required after the full Spring wiring change.

## 2026-10-09 — External proxy bypasses the local-proxy boot gate

- Selecting an external SolarMiner proxy now immediately marks the managed-local-proxy gate ready and stops any local child. The UI therefore remains operable while the remote proxy is selected; it no longer waits for a GitHub release download or a local proxy start.
- Mining readiness now requires the managed-proxy gate and child process only in local mode. External mode still verifies the configured remote proxy's API/listener and per-coin fee route before a miner can start.
- `ManagedProxyHealthTest` covers both a persisted external selection and a live switch away from local mode. Verification: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test standaloneJar --no-daemon` passed.
- Switching the shared proxy now rewrites existing XMRig, Pearl and GPU-coin routes to the selected host's per-coin Stratum endpoint, so a remote selection cannot leave miners targeting the old loopback route. Remote Monero and Pearl now also require their own advertised listener and fee target; an API-only connection is insufficient.
- `MiningControllerValidationTest.proxySetupDoesNotDependOnOptionalWindowsSensors` verifies every migration call; `ProxyConfigurationServiceTest.remoteMoneroRequiresItsOwnListenerAndFeeTarget` covers the remote Monero gate. `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test standaloneJar --no-daemon` passed.

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

## 2026-10-10 — Make GPU relay close test deterministic

- `GpuStratumRelayTest.closeClosesListenerAndActiveMinerConnections` continues
  to verify that closing the relay terminates an already active miner socket.
  Removed the immediate follow-up connect assertion: it raced listener shutdown
  against the OS TCP connect/accept path and was not a reliable contract check.
- The focused test could not be run in this environment because the Gradle
  8.14.5 distribution was not cached and outbound network access is blocked.

## 2026-10-10 — Apply Windows bootstrap to the beta launcher

- Both standalone launchers run the non-privileged permission, driver and
  Defender-state preflight on every start. If the exact install-directory
  exclusion is missing, the explicit UAC option is offered again; neither
  launcher silently changes Defender. Stable and beta retain separate markers
  only as timestamps of their most recent completed checks. `-Bootstrap`
  remains accepted for a manual invocation. Startup-check output is English-only;
  localized Windows exception messages remain external operating-system text.
- The startup preflight uses a console header, three numbered sections
  (installation directory, GPU power-control readiness and Defender), indented
  outcomes, and a highlighted action-required line before an optional UAC
  prompt. The output makes the no-change/default-safe path explicit.
- Stable and beta batch launchers now refresh their cached PowerShell launcher
  asset on each run using a temporary file and atomic replace. A failed refresh
  retains the last known-good cached launcher and states that fallback; a new
  install without one fails clearly. This closes the stale-launcher path that
  otherwise kept former bilingual output after a launcher release.
- Stable and beta launchers now require an Administrator token and stop before
  downloading or starting the Agent without one. A present Defender exclusion
  remains a report-only success path; only a missing exclusion produces the
  optional UAC prompt.

## 2026-10-10 — START: Mining/Controller-Abstraktion (Adapter-Schichten, Service-Reduktion)

**Ziel (vom Owner beauftragt, läuft noch):** Das gesamte `mining`-Package und die
Controller-Ebene des PC-Agents auf Abstraktionspotenzial prüfen. Gewünschtes Ergebnis:
Adapter-Schichten für Pools, Wallets, Coins, Miner-Engines, damit die Anzahl der
Services sinkt und pro-Coin/pro-Miner-Logik aus Services UND Frontend verschwindet.
**Status: Analysephase, noch keine Code-Änderungen.**

Vorgehen / Andockpunkte für andere Agenten:
- Bestehende Closed Seams (nicht verletzen): `coin/Coin` (einzige Coin-Identität),
  `miner/MinerFactory` (einziger Beschaffungspfad für Miner), `miner/CoinMiner`
  (Lifecycle-Vertrag), `mining/PoolBalanceProvider` (Pool-Adapter-Seam),
  `mining/ProxyLifecycle`, `mining/LocalRunLock`.
- Analyse parallel delegiert in 3 Stränge: (1) `mining/`-Package-Duplikate &
  Merge-Kandidaten, (2) Miner-Engine-Duplikate Xmr/Pearl/GpuCoin +
  `MinerEngineAdapter`-Vorschlag, (3) Controller-/Endpoint-Oberfläche +
  Frontend-Polling und Aggregate-Endpoint-Vorschlag.
- Größte Dateien als Hotspots identifiziert: EfficiencySweepService (816),
  PearlMinerService (696), MiningController (668), MiningService (599),
  GpuCoinMinerService (543), LocalGpuPowerService (534), XmrMinerService (481),
  ManagedProxyService (461).
- Frontend-Hotspots: `src/main/resources/static/js/` (Polling-Last pro Refresh).
- Ergebnisse der Analyse-Stränge und der konsolidierte Refactor-Plan werden
  HIER nachgeliefert (Eintrag ergänzen, nicht ersetzen).
- Wire-Format-Gesetz bleibt: Enum-Wire-Namen sind REST-Verträge für statisches UI
  UND Solar-Miner-Node; jede Konsolidierung braucht pinning-Tests bevor Endpoints
  bewegt werden (Vorbild: `ProxyOverviewWireFormatTest`).

## 2026-10-10 — NVIDIA-SRBMiner-Version und Worker-Konsolenpopup

- Die offizielle SRBMiner-MULTI-Matrix nennt NVIDIA Pascal, Turing, Ampere,
  Ada Lovelace, Hopper und Blackwell. Release 3.7.3 macht die neuen
  PearlHash-Kernel für SM86, SM89 und SM120 zum Standard und entfernt die
  vorübergehenden Kernel-Schalter. Der PC-Agent pinnt deshalb nun Windows und
  Linux auf den geprüften 3.7.3-Vertrag. Er übergibt weiterhin ausschließlich
  die per PCI-Adresse aus `--list-devices` ermittelte `--gpu-id`; SRBMiner wählt
  den Architektur-Kernel. Ein atomar geschriebenes Versionskennzeichen sorgt
  dafür, dass alte oder nicht versionierte Installationen erst nach einem
  ausdrücklichen, SHA-256-geprüften Update wieder als einsatzbereit gelten.
- Ein Klick auf eine Worker-Zeile öffnet nun die persistierte, pro Worker
  getrennte Miner-Konsole als Dialog. Der Client lädt binäre UTF-8-Blöcke
  inkrementell, verwirft bei einem neuen Miner-Lauf die alte Ansicht, folgt
  neuer Ausgabe am unteren Rand und bietet den vollständigen Log-Download.
  `Einrichten`/`Ändern` bleibt die separate Zuweisungsaktion. Sämtliche neue
  Dialog-, Status- und Accessibility-Texte sind im deutschen/englischen
  Frontend-Katalog geschlossen; Miner-Rohausgabe bleibt unverändert.

## 2026-10-10 — Release-owned one-command installers

- Added stable-release bootstrap assets for native Linux, Docker and Windows. Native Linux installs missing base tools through apt/dnf/yum/zypper/pacman/apk, provisions a private Adoptium x64 JRE 21, verifies its published SHA-256, then verifies and starts the PC-Agent JAR. Windows downloads the existing release launcher, which already provisions and verifies its private JRE and Agent. Docker installs Engine/Compose through the system package manager when absent, downloads release-owned base/NVIDIA/AMD Compose files, validates the selected stack and starts it.
- GPU/kernel drivers are an explicit safety boundary: the Docker bootstrap checks existing NVIDIA driver + Container Toolkit or AMD `/dev/kfd` + `/dev/dri`, but never installs or replaces host drivers. The new POSIX installer diagnostics are bilingual German/English. Package-manager/Docker output remains external verbatim text, and the delegated pre-existing Windows launcher startup checks remain English-only as already recorded in `standalone/README.md`. Starting the Agent still does not download or start a miner.
- The stable release workflow now publishes all three bootstrap scripts and all three Compose assets, and syntax-checks both POSIX shell installers before upload. Landing and documentation consumers use `Solarminer-app/pc-agent/releases/latest/download/**`; these URLs require the first stable release from this repository before public rollout.
- Stable/beta channel selection is explicit end to end. The beta branch bootstrap resolves the newest published `pc-agent-beta-*` prerelease, uses separate native/Docker data directories and selects `latest-beta`; the stable path continues to use GitHub's latest stable release and `latest`. The beta workflow now publishes the same installer and Compose asset set as the stable workflow.
- Verification: `sh -n` and `bash -n` for both shell scripts, release-asset staging checks, landing tests/typecheck/lint/build, Compose base/NVIDIA/AMD validation and `git diff --check`. PowerShell and Docusaurus build tools were unavailable locally; the Windows bootstrap, package-manager mutation, Docker daemon, UAC, GPU drivers and future stable-release downloads were not executed.

## 2026-10-10 — PC-Agent-Frontend DE/EN-Durchgang

- Alle neun HTML-Seiten sowie alle JavaScript-Dateien unter `src/main/resources/static/js/` auf sichtbare Texte, Statusmeldungen, Formulardetails und Accessibility-Labels geprüft. Fehlende statische und dynamische Katalogeinträge ergänzt; variable Sätze verwenden nun Platzhalter statt fertig zusammengesetzter, nicht auflösbarer Übersetzungsschlüssel.
- Benchmark-, Sweep-, Hardware-, Telemetrie-, Proxy-, Wallet-, Software- und Worker-Meldungen aus der Agent-API werden an den sichtbaren Stellen über `s()` lokalisiert. Bekannte deutsche und englische Agent-Status sowie variable Diagnosepräfixe sind im geschlossenen Katalog bzw. in `i18n.js` abgedeckt. Bereits übersetzte dynamische Meldungen werden beim Weiterreichen durch gemeinsame UI-Helfer nicht erneut als fehlende Übersetzung gemeldet.
- `scripts/check-i18n.cjs` prüft alle neun statischen Seiten, literale `t()`-Schlüssel, Platzhalter sowie die Umwandlung in beide Sprachrichtungen; Vertrag und Ausnahmen stehen in `docs/agent-wiki/frontend-i18n.md`. Externe Miner-Rohausgabe, Diagnoseausgabe fremder Tools sowie technische Hardware-/Pool-/Wallet-Kennungen bleiben unverändert.
- Verifikation: `node scripts/check-i18n.cjs`, `node --check` für alle Frontend-JavaScript-Dateien und `git diff --check` erfolgreich. Ein visueller Browserlauf mit Agent-API war auf diesem Host nicht verfügbar.
