# Known bugs — PC-Agent

This is the active, repository-local backlog for confirmed PC-Agent defects and
well-defined reliability gaps. It is written for both maintainers and AI
agents: read it after the repository entry documentation, then work on a bug
only when it is relevant to the assigned task and enough context capacity
remains. The assigned task always takes precedence.

## How to use this file

- `[ ]` means open, `[~]` means under investigation, and `[x]` means fixed and
  verified. Do not mark an item fixed merely because it compiles or a likely
  cause was identified.
- Start with the highest priority applicable open item. `P0` is service or
  safety critical; `P1` is a significant broken user flow; `P2` is an
  important quality, performance, or maintainability issue.
- Before changing code, reproduce or otherwise confirm the behaviour where
  practical. Preserve unrelated work already present in the worktree.
- Every investigated item needs a section below. Record evidence, diagnosis,
  proposed or implemented remedy, and verification. Keep unknowns explicitly
  labelled as hypotheses.
- On completion, update the item status and add durable implementation details
  and verification evidence to `docs/agent-wiki/work-log.md`.

## Open items

| Status | Priority | ID | Summary |
| --- | --- | --- | --- |
| [x] | P1 | BUG-001 | Starting a miner without a configured wallet ends in an unexplained generic error state. |
| [x] | P2 | BUG-002 | The frontend lacks a deliberate cache and invalidation strategy for stable or rarely changing data. |
| [~] | P1 | BUG-003 | An orphaned Stratum-proxy process can retain its port after the PC-Agent exits, preventing the next startup. |
| [~] | P1 | BUG-004 | Worker status or displayed miner can lag behind the active CPU/GPU process after starting or changing the operating mode. |
| [~] | P1 | BUG-005 | A power-limit sweep may stop its queue before testing a second coin on the same GPU. |
| [~] | P1 | BUG-006 | The elevated Windows launcher can stall waiting for another administrator prompt. |
| [~] | P2 | BUG-007 | Dashboard worker status labels benchmark runs as normal mining. |
| [x] | P1 | BUG-008 | PC-Agent Beta fails to start because worker revenue beans form a Spring dependency cycle. |
| [~] | P1 | BUG-009 | “Start mining after benchmark” can report a start while no worker is hashing. |
| [~] | P1 | BUG-010 | Selected Pearl or Quantus GPUs can be rejected from a power sweep despite a working miner route. |
| [~] | P1 | BUG-011 | PeakMiner sweep can report a managed-miner ownership conflict and an implausible PH/s rate. |
| [~] | P1 | BUG-012 | Saving one worker's assignment can stop other GPUs sharing its coin/miner configuration. |
| [~] | P1 | BUG-013 | Auto Tuning drops calibration requests for GPUs while another GPU is being calibrated. |
| [~] | P1 | BUG-014 | External power status reports paused/zero target while automatic-mode GPUs are mining; reported remote mass-stop trigger remains unconfirmed. |
| [x] | P1 | BUG-015 | Every power-sweep step rejects an already paused worker as manually stopped. |
| [x] | P2 | BUG-016 | Combined sweep/tuning ETA remains on “calculating” for the entire run. |
| [x] | P1 | BUG-017 | Profit selection reuses incomplete LIVE operating points and can repeatedly hit the worker temperature ceiling. |

## BUG-001 — Explain missing-wallet startup failures

**Status:** Fixed and verified (`[x]`, 2026-10-12)
**Priority:** P1
**Area:** Miner start flow, validation, and operator-facing diagnostics

### Observed behaviour

If an operator starts a miner before configuring a wallet, the miner reaches a
generic error state. The UI does not explain that the wallet configuration is
the blocking prerequisite.

### Expected behaviour

The start action should be prevented or fail with a clear, localized message
that tells the operator to configure a wallet. The error must be available in
both supported UI languages (German and English).

### Investigation

- **Current behaviour:** A personal wallet is optional by design. Saving an
  empty wallet resolves and stores the coin's SolarMiner default payout as one
  atomic pool/wallet/worker route. Starting therefore never hands an empty
  wallet to XMRig or SRBMiner. If no validated default payout is available, the
  save/start preparation is rejected with an actionable default-payout error.
- **Localization:** The Wallet/Worker UI explains the default-payout behaviour
  through the closed German/English catalog. Dynamic default-payout failures
  are translated in both directions by `i18n.js`.
- **Regression evidence:** `MiningControllerValidationTest` covers empty-wallet
  default routes for Monero, Pearl and Ravencoin, missing-default rejection for
  Pearl and Ethereum Classic, and preservation of explicitly configured
  Monero/ETC wallets.

### Verification performed

- `MiningControllerValidationTest` passed on JDK 21 on 2026-10-12.
- `scripts/check-i18n.cjs` and `scripts/scan-i18n-literals.cjs` passed on
  2026-10-12; both supported locales remain complete.

## BUG-002 — Define frontend caching and invalidation

**Status:** Fixed and verified (`[x]`, 2026-10-11, loading plan P1–P6)
**Priority:** P2
**Area:** Static frontend data loading

### Observed behaviour

Frontend requests currently have no explicit caching strategy. Some data, such
as worker information, is stable or changes only after a deliberate operator
action; other data changes infrequently. Re-fetching all of it can cause
unnecessary requests and UI work.

### Expected behaviour

Each frontend data source should have an intentional freshness policy:
cacheable data is reused safely, while live operational data remains fresh and
mutations invalidate or refresh the affected view.

### Investigation

- **Reproduction evidence:** Architecture/performance gap; the baseline was the
  tick-driven model documented in `docs/agent-wiki/rest-sse-loading-plan.md`
  (every subscribed section recomputed on its historical tick regardless of
  change; mutations stayed invisible up to the tick interval).
- **Known cause:** No cache or invalidation policy had been defined.
- **Implemented remedy (2026-10-11, Sitzung 10):** three explicit load classes
  (session-static via REST once per page load, configuration via REST plus
  applying the POST response directly, live data via event-driven SSE). Every
  local write under `/local/**` reports its affected sections through
  `AgentStatusService.invalidate(...)`; the SSE publisher re-evaluates dirty
  sections on its 500 ms sweep, and the per-section TTLs are now only the
  safety net for unreported external changes (proxy heartbeat, Node pushes,
  download progress, sensors). The first paint of a page uses one aggregate
  `GET /local/status`; the `live-data.js` watchdog remains the only REST
  fallback. Dead channels (`coin-catalog`, `status`) were deleted after a
  consumer grep (owner decision O3).

### Verification performed

- Policy documented per resource in `docs/agent-wiki/rest-sse-loading-plan.md`
  (Soll-Architektur table) and in the `AgentStatusService` code comments.
- `AgentStatusServiceTest` (5 tests) proves: clean sections stay cached until
  their TTL, an invalidated section re-evaluates immediately, unrelated
  sections stay cached, and non-section channels wake correctly.
- Boot proof with the standalone JAR: `POST /local/economics/settings` pushed
  on the `energy` channel 208 ms after the response; `POST
  /local/power-control/settings` pushed on `settings` 193 ms after the
  response (both < 1 s). Evaluation counters via `GET /local/status/stats`
  show zero recomputations for idle/clean sections over a 60 s window.
- Live mining/telemetry safety: telemetry and proxy-gate keep their short
  1 s TTLs and the client-side age rules (`maxAgeMs`) are unchanged, so a
  silent push still degrades the UI to "stale" instead of showing dead
  numbers.
- P7 extension (2026-10-11, Sitzung 11): every SSE channel value is now
  mirrored to `sessionStorage` and rehydrated before the first subscribe
  on the next page in the same tab, so page switches paint instantly
  (age rule keeps stale data flagged); the closed coin catalog is cached
  per tab. Harness evidence in `docs/agent-wiki/work-log.md`.
- Full evidence trail: `docs/agent-wiki/work-log.md`, entry
  "REST/SSE-Ladeplan P1–P6 (Sitzung 10)" + "Ladeplan P7 (Sitzung 11)".

## BUG-003 — Clean up or manage the Stratum-proxy lifecycle

**Status:** Implemented, Windows live verification pending (`[~]`, 2026-10-12)
**Priority:** P1
**Area:** Native launcher/process lifecycle and proxy startup

### Observed behaviour

When the PC-Agent is shut down on Windows or killed by an operator, its
Stratum-proxy child can remain running. On the next PC-Agent startup, proxy
startup can fail because the previous process still owns the configured port.
The same failure mode may apply on Linux and must be confirmed separately.

### Expected behaviour

Starting the PC-Agent after a normal shutdown or an interrupted prior run
should leave it able to establish the required proxy service without a manual
port cleanup. Process ownership and recovery must be explicit on every
supported native platform.

### Investigation

- **Reproduction evidence:** Reported for the native Windows launcher when its
  console is closed with X. A live PID/port capture is still required before
  this item can be marked fully verified. Linux behaviour remains unconfirmed.
- **Known cause:** Closing a Windows console can terminate the PowerShell
  launcher and Agent JVM together without allowing the JVM's shutdown hook
  enough time to terminate the separately launched proxy/miner children.
- **Implemented remedy (2026-10-12):** Both stable and beta PowerShell
  launchers create a Windows Job Object with
  `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`, start the Agent via `Start-Process`,
  assign it to the job before waiting, and keep the job handle for the entire
  Agent lifetime. Windows therefore terminates the Agent and every descendant
  (managed proxy and miners) when the launcher exits, including an X-close.
  Assignment failure stops the just-created Agent and fails closed instead of
  running an unguarded process tree. This follows Microsoft's documented
  [Job Object process-tree inheritance](https://learn.microsoft.com/windows/win32/procthread/job-objects)
  and
  [`JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`](https://learn.microsoft.com/windows/win32/api/winnt/ns-winnt-jobobject_basic_limit_information)
  semantics.
- **Recovery defence:** The existing startup cleanup remains deliberately
  narrow: it first confirms the loopback service identity, then terminates only
  Java proxy commands from the managed release directory. An unrelated port
  owner or merely similar command line is not killed.
- Docker remains separate: container teardown owns its process namespace and
  does not validate native launcher behaviour.
- **Update-path review (2026-10-17):** UI-triggered native updates call
  `SpringApplication.exit` before returning exit code 42 to the supervising
  launcher. Both Windows launchers keep each Java process in a kill-on-close
  Job Object across the update loop; Linux keeps a supervising shell process.
  This code-path check did not reproduce or close the original orphan-process
  report. The live PID/port capture below remains required after an update as
  well as after ordinary shutdown.

### Verification required

- On Windows, start proxy and miner children, close the launcher with X, and
  capture that their PIDs and listeners disappear; then restart without manual
  port cleanup.
- Verify normal tray exit and an interrupted prior-run recovery on Windows.
- Test Linux independently if native interrupted-exit cleanup is claimed.
- Verify that cleanup never terminates an unrelated process using the same
  executable or port.
- Verify Docker behaviour separately if launcher/process-management code
  changes.

### Verification performed

- `WindowsLauncherLifecycleTest` pins the stable and beta launcher ordering:
  create kill-on-close job, start Agent, assign Agent, wait, close job. It also
  prevents regression to direct invocation outside the job.
- Focused `WindowsLauncherLifecycleTest` and
  `MiningControllerValidationTest` passed on JDK 21 on 2026-10-12.
- Release and beta workflows now parse the shipped launcher PowerShell file in
  addition to the installer before publishing assets.

## BUG-004 — Show the live worker after a mode or start change

**Status:** Under investigation (`[~]`, 2026-10-16)
**Priority:** P1
**Area:** Workers page, CPU/GPU runtime read model

### Observed behaviour

Originally reported in the operator's implementation note: after starting a worker in
an operating mode, the Workers page can show stale information. The operator
expects the current miner on each GPU and CPU; the report suggests the display
may only change when no miner is running.

### Expected behaviour

Every worker row shows its saved assignment and operating mode separately from
the currently running miner. A start or mode change refreshes the row promptly.

### Investigation

- `WorkerAssignmentService.view` already selects the live `MINING` telemetry by
  physical device ID before considering the saved assignment. The Workers UI
  rendered that telemetry, but suppressed every incoming worker snapshot while
  either the setup dialog or miner console was open. This could leave a row
  stale indefinitely while the console was open. The suppression is removed,
  and an explicit start/pause now awaits the worker refresh. Check the CPU and
  GPU live read model with an actual miner before marking the report resolved.
- `scripts/check-i18n.cjs` and `scripts/scan-i18n-literals.cjs` pass for both
  supported languages. A live UI session is still required.
- A 2026-10-17 start/transition review found that the running-worker mode-change
  path tried to acquire its own already held run lock and returned `power_mode_busy`.
  Revenue modes now use the controller's lock. LAN peer starts also use the
  revenue selection path. A locally started coin-revenue worker that stops
  unexpectedly is retried on the next scheduler pass; explicit pause paths
  clear that intent. The switch stop reason is now recorded in the miner
  console in German and English. Focused service/assignment tests pass, but a
  live process and pool check is still required before closing this bug.
- A local or LAN worker start now marks its assigned worker `STARTING` while the
  start request is in progress. The Workers and network dashboard views show the
  localized status and suppress a second Start action; completion or failure
  clears it. Focused read-model and dashboard tests pass. CPU/GPU hardware timing
  and visual confirmation remain part of the live verification below.
- The two Beta daily-profit modes use the same Worker mode-change, start and
  worker-refresh paths as existing GPU revenue modes. This implementation did
  not reproduce a new stale-row issue; live GPU mode switching and immediate row
  refresh still require the hardware/browser verification listed below.

### Verification required

- Start and switch modes for CPU and GPU workers, including a coin change, and
  compare each row with the live process and miner console on target hardware.

### 2026-10-20 — Independent operations-console handling / Eigenständige Betriebskonsole

The new console read model keeps assigned coin/software separate from the live
algorithm and uses `WorkerView.telemetry().currentAlgorithm()` while mining.
`ConsoleWorkspaceServiceTest.observedAlgorithmWinsOverAssignmentWithoutChangingConfiguredCoin`
covers an assigned Pearl worker reporting live KawPow. The new command UI also
keeps pending/accepted commands distinct from confirmed live telemetry. This is
not evidence that every legacy page or the underlying mode-switch issue is fixed;
BUG-004 remains under investigation. See [console audit](agent-wiki/frontend-console-audit.md).

Deutsch: Die neue Konsole trennt gespeicherte Zuweisung und tatsächlich gemeldeten
Algorithmus. Ein Test deckt die abweichende Live-Meldung ab. Bestätigte Befehle
werden nicht als Nachweis aktiven Minings dargestellt. Der alte Fehler bleibt
für bestehende Seiten und den eigentlichen Moduswechsel weiter offen.

### 2026-10-21 — Alternate miner visibility during measurements / Andere Test-Miner

**English:** Confirmed a separate cause of missing GPU rows/telemetry: sweeps
started alternate adapters without `MinerFactory`'s temporary software selection,
so `MiningService.getWorkerStats` filtered their values against the saved miner.
Sweeps now publish a per-GPU measurement context spanning the full curve and
refinement; cleanup removes it before ordinary restoration. Worker views retain
the saved coin/miner and expose the test context separately. Workers displays
its actual test pair and phase during startup and mining, with live telemetry
from the test adapter. Unrelated mining workers do not inherit a global benchmark
badge. Factory, sweep and read-model regressions plus the DE/EN full-page browser
fixture pass. Real process/driver/pool acceptance remains open for BUG-004.

**Deutsch:** Eine weitere Ursache ist belegt: Sweeps starteten abweichende Miner
ohne temporäre Software-Zuordnung. Die allgemeine Statistik filterte deren Werte
gegen den gespeicherten Miner heraus. Eine Messzuordnung pro GPU gilt jetzt für
Kurve und Nachoptimierung und wird vor Wiederherstellung entfernt. Die Worker-
Ansicht zeigt Testpaar, Phase und echte Test-Telemetrie zusätzlich zur gespeicherten
Zuordnung, auch beim Anlauf. Unbeteiligte Worker erhalten kein globales Benchmark-
Label. Factory-, Sweep-, Lesemodell- und DE/EN-Browsertests sind erfolgreich;
reale Prozess-/Treiber-/Poolabnahme für BUG-004 bleibt offen.

## BUG-005 — Continue the power-limit test queue across coins

**Status:** Under investigation (`[~]`, 2026-10-16)
**Priority:** P1
**Area:** Efficiency sweep scheduling and miner transitions

### Observed behaviour

Originally reported in the operator's implementation note: after testing the first coin on one GPU, the
power-limit test appears not to start the second coin, as though the queue
ended. No session run snapshot or miner log accompanied the report.

### Expected behaviour

Every eligible selected coin/GPU pair reaches a terminal run state. A failed
coin run records its own cause; recoverable failures do not silently discard
the remaining queue. Unsafe stop, power restoration or sensor failures still
abort the sweep safely.

### Investigation

- `referenceBatches` and `takeRunnable` do schedule multiple coins on one
  physical GPU sequentially. A thrown task exception currently exits
  `executeTasks`, after which `finalizePendingRuns` marks queued runs as failed
  without attempting them. Whether this is the reported hardware failure is
  unconfirmed. Capture the first run's `RunStatus.detail`, proxy/miner logs and
  the second run's status on a live GPU.
- A focused queue test confirms that a second coin stays queued while the GPU
  is busy and is selected when it becomes free. This does not reproduce the
  reported live failure. No unsafe continuation rule was introduced without
  the first run's failure evidence.
- 2026-10-17: the new sweep concurrency setting can force sequential testing
  with a limit of one GPU task, but focused queue tests still cannot establish
  why a reported live second-coin run did not begin. Live run status and miner
  logs remain required before changing failure continuation.
- 2026-10-18: found a reproducible plan/execution mismatch. The initial run
  plan used the current, user-bounded GPU cap, but execution required a driver
  default and threw when it was absent or above the configured maximum. That
  exception cancelled the queue and marked untouched coins failed. Both paths
  now use a shared, user-bounded reference start cap; a missing driver default
  uses the verified current cap. A two-coin service test confirms both runs are
  attempted sequentially on one GPU when the first coin cannot start and the
  second coin's power point fails. Whether this
  explains the operator's live report still needs live status and miner logs.
- A read-only `nvidia-smi` probe on the workspace host found five GPUs with
  current caps below their driver defaults (for example 145 W vs 280 W). This
  makes the mismatch plausible on this host, but does not reveal each Agent
  user's configured maximum or prove the reported run hit that exception.
- 2026-10-18: a reproducible multi-GPU failure path was fixed: a runtime error
  inside one card's task used to exit the whole completion queue, marking
  untouched GPU/coin runs failed. Each task now records its own failure and
  allows queued work to proceed only after all managed miners on its card stop
  successfully. Failed cleanup still aborts the full run. The new four-run,
  two-GPU test verifies that one thrown driver error does not discard the
  other card or later coins. The operator's v1.1.16 Spring logs show only an
  unrelated SSE disconnect exception, so a real hardware run and per-run
  status snapshot are still needed to confirm the reported symptom is fixed.

### Verification required

- Run two configured coins on one writable GPU and confirm both start and
  finish; repeat with a first-coin startup failure and verify safe continuation
  on real miner and driver processes.
- Capture `RunStatus` and the miner console if the queue still stops; a failed
  miner stop remains a safety-critical whole-session abort.

## BUG-006 — Avoid a second Windows elevation request

**Status:** Under investigation (`[~]`, 2026-10-16)
**Priority:** P1
**Area:** Stable and beta Windows launchers

### Observed behaviour

Originally reported in the operator's implementation note: an already elevated PC-Agent startup can wait on an
administrator prompt and become stuck.

### Expected behaviour

After the launcher confirms its administrator token, optional Defender setup
does not invoke another elevation prompt. Declining the optional exclusion
still allows the documented startup flow.

### Investigation

- Both launchers check `WindowsPrincipal.IsInRole(Administrator)` before the
  Defender step, but that step called `Start-Process powershell.exe -Verb RunAs
  -Wait` after the operator opted in. Both launchers now call
  `Add-MpPreference` directly with the already elevated token. The changed
  prompt is bilingual and accepts English `y/yes` and German `j/ja`.
- `WindowsLauncherLifecycleTest` passed on JDK 21 and checks both launchers for
  the admin-token gate, direct Defender call and absence of `-Verb RunAs`.
  A Windows live run is still needed to verify the full startup flow.
- `standalone/install-windows.ps1` also launched the downloaded batch file via
  unconditional `-Verb RunAs`. It now reuses an already elevated installer
  token and requests elevation only for a non-elevated installer. The focused
  lifecycle test checks both branches.

### Verification required

- Run stable and beta launchers as administrator with an exclusion missing,
  opt in and confirm the command completes without a second UAC prompt. Test
  the decline path too.

## BUG-007 — Distinguish benchmarking from ordinary mining

**Status:** Implemented, live verification pending (`[~]`, 2026-10-16)
**Priority:** P2
**Area:** Local and LAN dashboard worker status

### Observed behaviour

Originally reported in the operator's implementation note: local and remote dashboard
workers display the ordinary “Mining active” status during a benchmark. The
same ambiguity can occur during a power-limit sweep.

### Expected behaviour

An active worker participating in a benchmark shows “Benchmarking” / “Benchmark
läuft”; one participating in a power sweep shows “Power sweep running” / “Power
Sweep läuft”. Idle workers retain their actual paused/stopped/error status.

### Investigation and remedy

- The dashboard and physical Workers page subscribe to local benchmark and sweep
  SSE channels. A mining worker in a sweep run receives its own display status,
  based on the run's device ID, while the underlying miner state remains `MINING`.
- LAN snapshots retain nullable `benchmarking` for benchmarks and add nullable
  `sweeping` for active workers in a sweep run. Older peers omit the new field
  and keep the previous status display. The dashboard polls its cached LAN board
  every five seconds, matching the service's five-second refresh cycle.
- The status label uses the closed German/English catalog; the protocol field
  is additive and does not change the miner's actual `MINING` process state.

### Verification performed and required

- `NetworkDashboardServiceTest` covers active versus stopped peer rows and
  passed with the focused JDK 21 suite. Both i18n checks passed (1,420 keys,
  zero unkeyed browser literals); `overview.js` passed `node --check`.
- Confirm on two real agents that a local benchmark and sweep label only the
  mining workers, the remote board follows, and both revert after completion.
- On 2026-10-17, focused `NetworkDashboardServiceTest` passed for sweep targets,
  stopped workers and the existing benchmark flag. Both locale checks and the
  static render simulation passed; real miner/browser verification remains open.

### 2026-10-20 — New console / Neue Konsole

The independent console obtains benchmark/sweep flags from the domain session
services and displays localized measurement labels separately from the miner's
`MINING` status. It does not reuse the old table renderers. Live measurement
validation remains open; this does not change BUG-007's legacy/hardware status.

Deutsch: Die neue Konsole übernimmt Messzustände direkt aus den Sitzungsdiensten
und zeigt sie getrennt vom Mining-Zustand an. Eine Hardware-Prüfung steht noch
aus; daraus wird keine Behebung der alten Oberfläche abgeleitet.

## BUG-008 — Break the worker revenue startup dependency cycle

**Status:** Fixed and verified (`[x]`, 2026-10-17)
**Priority:** P1
**Area:** Spring application startup, worker revenue wiring

### Observed behaviour and cause

The operator's Windows Beta standalone JAR failed during context initialization:
`MiningService` setter injection requested `WorkerCoinRevenueModeService`, whose
constructor requested `CoinHoppingService`, then `EconomicPlanningService`,
which constructor-injected the still-creating `MiningService`. The revenue
service also refers back to `WorkerAssignmentService`.

### Remedy and verification

The three optional setter references from `MiningService`,
`WorkerAssignmentService` and `EconomicPlanningService` to
`WorkerCoinRevenueModeService` now use lazy injection. The operational methods
resolve the proxy to the initialized revenue service after context startup.

`standaloneJar` compiled, and the packaged JAR reached `Started
PcAgentApplication` in a fresh temporary directory with a random HTTP port.
The complete JDK 21 suite ran 241 tests with one previously documented,
unrelated QuanPool GPU-selection failure. Focused revenue/assignment tests and
both language-catalog checks are recorded in the 2026-10-17 work log.

## BUG-009 — Verify mining after the benchmark

**Status:** Under investigation (`[~]`, 2026-10-18)
**Priority:** P1
**Area:** Benchmark restoration and post-run worker start

### Observed behaviour

The operator sees “Start ausgelöst” but no mining after the benchmark. The
previous result only reflected a successful start method return; it did not
check subsequent miner telemetry or positive hashrate.

### Investigation and remedy

- After a successful start call, the benchmark now checks all selected workers
  against live telemetry under one shared 240-second deadline. Only a `MINING`
  worker with positive hashrate receives the confirmed result. Miner errors and
  absent hashrate produce separate localized reasons. A transient stats read
  is retried rather than erasing a start.
- The restart path now stops a restored process without the ordinary operator
  pause's extra configured-power reset. The saved operating mode reapplies its
  own target at start.
- `SrbGpuCoinView.restoreWorkerState` previously ignored the return value of
  `resumeGpu` and a missing device, reporting success after a failed restore.
  Both conditions now fail restoration so the benchmark skips unsafe auto-start.
- Pearl's `startGpu` previously returned success when it saw an externally
  started SRBMiner, even though it launched no PC-Agent process. This is a
  direct false-success path consistent with the reported Pearl symptom. It now
  fails with a concrete, localized blocking reason. Whether that branch caused
  the operator's specific run is unproven without its miner console. A focused
  test reproduces the false-success branch with an externally detected process.
- The benchmark now checks stop results before starting measurements and after
  GPU batches. It reports a stop failure instead of treating a possibly live
  process as safely stopped.

### Verification required

- On a real agent, complete a benchmark with auto-start for CPU and GPU
  separately, then with multiple GPUs. Confirm positive hashrate and pool jobs,
  as well as both German and English result text.
- Reproduce a miner that exits after its start call. Confirm the result names
  the miner error or missing hashrate and includes no false success.

## BUG-010 — Selected Pearl or Quantus GPU has no sweep target

**Status:** Under investigation (`[~]`, 2026-10-18)
**Priority:** P1
**Area:** Power-sweep target planning and operator diagnostics

### Report and code diagnosis

The operator selected only Pearl and an NVIDIA RTX 2080 worker, but starting a
power sweep returned `benchmark_no_selected_targets`. This exact key is emitted
only after `EfficiencySweepService.targets` returns an empty list with explicit
worker selection. It occurs before a sweep session or miner start. The browser
selection is a measurement filter, not a persisted miner assignment.

`targets` first requires a discovered GPU with `supportsDynamicPowerScaling`
and a matching selected `deviceId`, plus `miners.srbBinaryAvailable()`. For a
configured Pearl miner it then requires that the same GPU appear in
`PearlMinerService.selectedGpus()` (the saved `devices` field). If no Pearl
config exists, it tries a temporary house-payout route, which can fail because
the payout, proxy host/URL, or adapter validation is unavailable. The current
generic message does not reveal which condition removed the last target.

The screenshot proves only the UI's installed label and selected worker; it
does not contain the agent's writable-capability result, saved Pearl GPU list,
or proxy/payout status. Its exact failing predicate is therefore unconfirmed.
The operator subsequently confirmed that a Quantus sweep still starts on this
same GPU. If both requests hit the same running Agent, that rules out the
shared writable-GPU filter and shared SRBMiner availability gate at the time
of the Quantus request. The remaining Pearl-specific paths are a saved Pearl
configuration whose `devices` excludes this card, or an absent Pearl config
whose temporary payout/proxy route cannot be validated. This is a deduction
from the code and report, not a captured Agent-state diagnosis.
The operator also reports a Kryptex pool and wallet for Pearl, with Quantus
assigned to the GPU in `COIN_REVENUE_PER_KWH` mode. If that pool/wallet is the
saved Pearl `MinerConfig`, the configured branch is certain and the selected
GPU is being removed by the intersection with Pearl's persisted `devices`.
The measured GPU checkbox does not override this selection during a sweep,
although an installed benchmark does use a temporary device override.
`synchronizeConfiguredDevices` deliberately retains a coin's old config when
no worker is assigned to it, so a valid route and a stale device list can
coexist. Capture the config's non-secret `devices` field to confirm.

Architecture review: Pearl is a distinct `GpuCoinMiner` adapter and
`GpuCoinMinerService` owns the other SRBMiner coins. Both extend
`AbstractSrbMinerEngine`, sharing process drain, monitor, stop and restart
mechanics. The sweep target intersection is generic across adapters; moving
Pearl wholesale into the shared service would not itself correct it. A
session-scoped device override or target-planning contract is the focused
remedy, with saved routes and post-sweep miner state preserved. Avoid widening
unverified hardware support while implementing it.

### Focused remedy (2026-10-18)

The generic planner now admits an explicitly selected, writable GPU for each
compatible selected miner even when the saved coin `devices` list excludes it.
It adds selected devices in a temporary config while preserving pool, proxy,
wallet and worker. Pearl, SRBMiner and QuanPool prioritize that override during
the sweep; saved route files remain untouched. The no-explicit-worker path
retains its saved-device behavior.

Further operator reports name RTX 2080, RTX 3080 and Titan RTX; Quantus can
fail in the opposite direction. All three workers use SRBMiner-MULTI, but a
Quantus coin-wide miner selection can differ from the worker's saved software.
The sweep now resolves each target through the worker's miner selection, stops
all managed adapters on the physical GPU, and keeps completed resume results
separate by miner software. German and English errors now distinguish a GPU
without a writable limit from an unavailable selected miner; route/config
failure retains a separate message.

Service and real-adapter tests cover temporary device selection, worker miner
selection, adapter cleanup, unchanged config files, and resume isolation. A
real sweep on each reported GPU, its saved `devices` values, and the
post-benchmark revenue decision still need capture; status remains under
investigation.

### Live reproduction on the reported Docker agent (2026-10-18)

The operator confirmed selecting all five GPUs for a Pearl sweep. Read-only
local API inspection of `192.168.178.126:8084` showed four Titan RTX and one
RTX 3080, all with writable power limits and no regulation error. Pearl's
saved `devices` field is `NVIDIA:1`. The running sweep had `phaseCount: 1` and
one `pearl` run on Titan RTX index 1; the other four cards were not queued.
The running Docker Beta tag is `pc-agent-beta-07a9fccfe4f0`. At that commit,
the configured-miner branch intersects selected workers with
`miner.selectedGpus()`, explaining the observed single target exactly. The
runtime also serves the old i18n catalog and uses the old run ID format,
confirming the fix from commit `b7bed7d` is not deployed there. Its update
status reports `update_check_failed`, so availability of a newer published
image was not established by the Agent. The running sweep was left untouched.

After a Beta image containing `b7bed7d` is installed, repeat with all five
selected. The session must plan five Pearl runs; with concurrency set to one,
only one GPU mines at a time and the other four remain queued. This is a
deployment and live-verification gate, not evidence that the fixed planner
still rejects these cards.
The sweep rejection can help explain the later benchmark auto-start outcome:
the worker's `COIN_REVENUE_PER_KWH` mode can switch from its saved Quantus
assignment to Pearl only when Pearl has a valid measured performance point,
configured/fee-ready capability, and fresh market forecast. A failed Pearl
sweep supplies no Pearl sweep profile, but an earlier valid benchmark profile
could still qualify. The post-benchmark checkbox applies the worker's saved
operating mode, not the selected benchmark coin. Inspect candidates and
`autoStartResults` before claiming that missing Pearl sweep data caused this
specific start outcome. See BUG-009 for start verification.

### Verification required

- On the affected agent, inspect the selected GPU's
  `/api/agent/local/power-control` entry (`supportsDynamicPowerScaling` and
  `regulationError`), `/api/agent/local/workers` assignment, and Pearl miner
  configuration/device selection. Capture the actual `POST /efficiency`
  response without wallet or proxy secrets.
- Reproduce the missing target with each relevant precondition and give the
  operator a localized, specific reason. Confirm German and English output.
- Independently capture the benchmark `autoStartResults` and Pearl miner
  console/worker state after the Quantus phase to establish why Pearl did not
  hash; a sweep preflight rejection is not evidence of that cause.

## BUG-011 — PeakMiner sweep ownership and hashrate units

**Status:** Under investigation (`[~]`, 2026-10-18)
**Priority:** P1
**Area:** PeakMiner / Pearl power sweep on a Windows NVIDIA GPU

The operator reported `210 W unstable: Miner konnte nicht gestartet werden`.
The PeakMiner console repeatedly showed `Another miner already controls
NVIDIA:0`. Assigning that worker to PeakMiner manually allowed a subsequent
run. The generic sweep note discarded the adapter's actual start error; this
is corrected. During a sweep, a managed-miner ownership collision now triggers
one additional stop of all managed adapters on that physical GPU and one
bounded start retry. The PeakMiner/QuanPool/WildRig crash watchdogs no longer
retry an ownership conflict every 30 seconds after the sweep. The existing
external-process guard remains in place. Whether this handoff eliminates the
operator's particular first collision needs a Windows rerun; the exact
competing process was not captured.

The operator also saw PH/s for PeakMiner. Its pinned 2.19.1 `/summary`
adapter multiplied the numeric hashrate by 1,000 before the shared H/s UI
formatter. The operator's observation points to an H/s payload in that build;
the multiplier was removed and a TH/s-scale fixture added. PeakMiner's public
README still says kH/s, so a raw `/summary` response and console rate from
the affected Windows build are required to settle this upstream contract and
certify the corrected unit. Sweep console rate formatting had independent
1,000-fold label errors (MH vs GH, kH vs MH), which were corrected.

### 2026-10-21 — Native OC follow-up / Native OC-Anbindung

PeakMiner now uses the shared native-tuning interface and its versioned startup
OC flags. New mocked lifecycle/command tests cover per-worker restart and
stop/start failure, but cannot settle the Windows competing-process identity or
hashrate unit. BUG-011 remains under investigation; no physical OC write or
Windows pool session was performed. See `docs/agent-wiki/auto-tuning.md`.

Deutsch: Die native Tuning-Anbindung und neue Prozess-/Parametertests klären die
offene Windows-Übergabe und Hashrate-Einheit nicht. BUG-011 bleibt offen;
Hardware-Taktänderungen und eine Windows-Pool-Sitzung wurden nicht ausgeführt.

## BUG-012 — Preserve sibling workers when saving a worker assignment

**Status:** Automated regression verified; hardware acceptance pending (`[~]`, 2026-10-21)
**Priority:** P1
**Area:** Worker assignment and shared GPU miner configuration

### Report and diagnosis

Changing a worker in the Workers page can make other workers appear stopped.
`WorkerAssignmentService.assign` synchronizes the shared device list for each
affected coin/miner pair. The adapter's `applyConfig` stops the entire
coin-specific miner process before writing that list, but the assignment path
only restarted the selected worker from the browser. Even saving an unchanged
assignment could rewrite the same list and stop all its workers.

### Remedy

`synchronizeConfiguredDevices` now skips a write when the shared device list
has not changed. When it must change, it captures currently mining workers
still assigned to that coin/miner and starts them again after the configuration
write. A worker moved away is not restarted by the old configuration.

### Stronger remedy and automated verification (2026-10-21)

Selection-only writes now retain unchanged processes directly in Pearl,
shared SRBMiner, PeakMiner, QuanPool and WildRig. The assignment service no
longer stops and restarts sibling workers. `WorkerConfigurationIsolationTest`
verifies all five adapters with live controlled process handles and preserves
the sibling's process, status and hashrate. The same test covers delayed API
responses, inventory loss, operator-stop policy and automatic retry budgets.
See [worker lifecycle](agent-wiki/worker-lifecycle.md) for the complete audit.

Deutsch: Änderungen nur an der GPU-Auswahl behalten unveränderte Nachbarprozesse
bei. Automatisierte Tests prüfen alle fünf Adapter. Reale Mehr-GPU-Prüfung und
beide Sprachen auf dem Zielgerät stehen weiterhin aus.

### Hardware verification required

- With two GPUs assigned to the same coin and miner, change or save one worker
  and confirm the sibling resumes and continues hashing.
- Confirm changing one GPU's assignment does not start it under its old coin;
  check both German and English Workers-page status after the action.
- Automated adapter regressions pass; no live multi-GPU miner run was performed here.

## BUG-013 — Queue Auto Tuning for multiple workers and show each history

**Status:** Implemented, verification pending (`[~]`, 2026-10-19)
**Priority:** P1
**Area:** Per-GPU Auto Tuning scheduling and measurement history UI

### Report and diagnosis

When more than one GPU uses `AUTO_TUNING`, only one worker's measurement chart
is visible, and the RTX 3080 has no measurement data. `LocalRunLock` correctly
serializes calibrations, but `startWhenReady` returned immediately whenever a
session was running. The second GPU's request was therefore discarded rather
than waiting for the first calibration to finish. The Auto Tuning page also
plotted only the current session's samples.

This explains missing RTX 3080 data if it was the second GPU whose mode/start
requested calibration. If it was first, its specific missing sample cause is
not established by this report and still needs its per-device API/measurement
record inspected.

### Remedy

Automatic calibration requests are now queued per device and started one at a
time after the active session releases the run lock. Stopping/leaving the mode
removes that device's queued request. The Auto Tuning page now paginates through
workers in that mode and graphs the most recent saved sample run for each GPU;
a worker without samples receives a localized empty-state message.

### Verification required

- Put two GPUs in `AUTO_TUNING`; confirm both are calibrated sequentially and
  each has sample records and a selectable graph.
- Confirm leaving the mode while queued removes that GPU from the queue and
  both locales render the navigation, progress and empty state correctly.
- No automated test or live RTX 3080 calibration was performed for this remedy.


### 2026-10-21 benchmark follow-up / Benchmark-Nachprüfung

BUG-010: The ordinary installed benchmark also intersected an explicit worker
selection with each miner's saved GPU list. It now plans from physical inventory
and uses temporary overrides; `explicitWorkerIsMeasuredEvenWhenMissingFromSavedMinerSelection`
verifies the selected second GPU without rewriting saved configuration. Existing
live rollout and pool/auto-start evidence gates above remain open.

BUG-010: Auch normale installierte Benchmarks schnitten ausdrücklich gewählte
Worker mit der gespeicherten Miner-Geräteliste. Die Planung nutzt nun das
physische Inventar und temporäre Overrides; der genannte Regressionstest prüft
die zweite GPU ohne Umschreiben der Konfiguration. Reale Rollout-, Pool- und
Auto-Start-Nachweise bleiben weiterhin offen.

The current sweep scheduler already gives every GPU its own full curve. The
legacy validation helpers are not evidence of current shared-reference behavior.
Der aktuelle Sweep-Planer misst bereits pro GPU eine eigene vollständige Kurve;
die alten Validierungshilfen belegen keine gemeinsame aktuelle Referenzmessung.


## BUG-014 — Remote mass-stop report and inconsistent external power status

**Status:** Under investigation (`[~]`, 2026-10-21). Diagnosis only; no runtime change.

**English:** Read-only inspection of the local Docker Beta
`pc-agent-beta-8a81f9eb46d0` found all five GPUs reporting `MINING` at about
1092 W combined, while `/external/power-control` reported
`miningPaused:true`, `currentTargetWatts:0` and application status `IDLE`.
The local settings select `PROFIT_PER_DAY` for these five GPUs. In source,
`MiningService.resumeExternally` starts workers without updating desired power
or application state, and `AgentPowerController.status` infers paused solely
from the desired global target. This confirms a status defect, not the cause
of the reported remote mass stop.

The exact `Mining paused remotely` messages originate in
`AgentPowerController.externalPause/externalTarget`, reached through external
POST `/pause` or `/power-target`. `MiningService.pauseExternallyInternal`
stops selected, externally permitted GPUs sequentially. A dynamic positive
budget can also reach this stop path when rejected or when applying GPU limits
fails. This dynamic allocation branch is bypassed for the currently observed
automatic modes. `Remote control API` identifies a control path, not a caller
IP or authenticated Node identity. No historical remote-stop line survives in
the accessible current logs. `MinerConsoleService.started` truncates each
console on start, so subsequent starts can erase the evidence.

**Verification:** Source/caller tracing and read-only live status/settings/log
inspection only. No stop/start command, deployment, or implementation test was
performed. To establish the reported incident's trigger, capture its exact
console line/time and correlate external POST requests with the sending Node's
control decision; the current log does not record caller provenance. Do not
attribute a feedback loop to the status defect without that evidence.

**Deutsch:** Der laufende Docker-Beta-Agent meldet alle fünf GPUs als aktiv
(ca. 1092 W), aber gleichzeitig `miningPaused:true`, Ziel `0 W` und `IDLE`.
Der Startpfad der automatischen Betriebsmodi aktualisiert den globalen Ziel-
und Anwendungszustand nicht; die Pausenanzeige wird allein daraus berechnet.
Dieser Statusfehler ist belegt. Die Ursache der gemeldeten Fernabschaltung
bleibt offen. Die konkrete Meldung kommt aus den externen Pause-/Leistungsziel-
Endpunkten; die Pause stoppt freigegebene GPUs nacheinander. Auch abgelehnte
dynamische Budgets können pausieren, dieser Zweig wird bei den aktuell aktiven
automatischen Modi jedoch übersprungen. Der Konsolen-Neustart überschreibt
alte Belege. Nur Quellcode und laufende Leseschnittstellen/Logs geprüft;
keine Mining-Befehle, Implementierung oder Bereitstellung. Für die eindeutige
Zuordnung fehlen die genaue Meldung/Zeit und der zugehörige eingehende
POST-Aufruf samt Steuerentscheidung des Absenders.


## BUG-015 — Explicit sweeps cannot start paused workers / Sweep startet pausierte Worker nicht

**Status:** Fixed in source, target-hardware verification pending (2026-10-21).

**English:** Every step called `resumeGpu`, which removed the adapter-local pause
but still hit the persisted `WorkerStartPolicy` stop. Explicit sweep admission now
captures per-device permits. `MinerStartContext.measurement` scopes that authority
to the actual task thread and selected device, including synchronous refinement;
ordinary automatic starts and final state restoration keep the normal stop gate.
A repeated operator pause invalidates the captured permit, including failed
persistence. The stored pause and start-on-boot preferences are unchanged. Failed
steps can start a fresh measurement attempt without resetting operator retry budgets.

**Deutsch:** `resumeGpu` entfernte nur die Pause im Adapter; die gespeicherte
Stoppsperre blockierte weiterhin jeden Messschritt. Der ausdrücklich gestartete
Sweep erhält jetzt eine temporäre Freigabe je Gerät und Task-Thread, auch für die
synchrone Verfeinerung. Automatische Starts und die abschließende Wiederherstellung
beachten weiter die Stoppsperre. Ein erneutes Pausieren widerruft die Freigabe auch
bei Speicherfehlern. Gespeicherte Pause und Autostart bleiben unverändert.

**Evidence / Nachweise:** `EfficiencySweepServiceTest` verifies asynchronous
admission of an already paused worker and absence of measurement authority during
restoration. `MeasurementStartAuthorityTest` exercises the real shared engine gate,
device/thread isolation, exception cleanup, persisted pause after reload and
revocation after a repeated pause with failed storage. `WorkerStopAuthorityTest`
continues to verify the normal stop policy. No live GPU/pool run was performed;
external miner and operating-system text remains verbatim. No new UI messages.
Keine neue UI-Meldung; externe Miner-/Betriebssystemtexte bleiben unverändert.


## BUG-016 — Combined benchmark ETA / Restzeit bei Sweep und Tuning

**Status:** Fixed and verified (`[x]`, 2026-10-21). `EfficiencySweepService.status()`
explicitly replaced `secondsRemaining` with null for every running combined run.
The frontend therefore always selected its localized “calculating” fallback.
Status now combines remaining sweep samples and startup estimates with the tuner
search/confirmation budget. Per-device work is serial; allowed GPU slots run in
parallel. Completed/failed/skipped tuning work is excluded. The estimate updates
with candidate/sample progress; it is a planning estimate, not a deadline.

Deutsch: Der Status setzte die Restzeit bei kombinierter Messung ausdrücklich
auf null. Jetzt werden verbleibender Sweep und Tuning einschließlich Anlaufzeiten
und GPU-Parallelität geschätzt. Ein DE/EN-Hinweis erklärt die Grenzen der Schätzung.

Verification: focused sweep, refinement, tuner and controller tests plus standalone
JAR passed. Regression coverage checks numeric combined status (including tuning
after all sweep steps), progress and concurrency. DE/EN catalogue, literal and
render checks passed. No miners or hardware-control commands were started.

### 2026-10-21 — GPU workspaces and completion follow-up / GPU-Arbeitsplätze und Abschluss

BUG-010 / BUG-013: The new Overclocking wizard saves the selected GPU tests,
passes an explicit preset and mining-after-completion choice, and links to one
standalone GPU page per device. Each page shows that GPU's stored/live sweep
points, tuning records, diagnoses and confirmed profiles. Sweep completion now
restores/releases one GPU at a time, including an optional saved-assignment
start while siblings still measure. New asynchronous regressions cover release,
late cancellation, repeated operator stops and budget exhaustion. The old
inline history UI is replaced for this path; real multi-GPU/Windows, miner
hashrate, pool and queued automatic-calibration acceptance remain open.

Deutsch: Wizard und eigene GPU-Seiten ersetzen den bisherigen Inline-Ablauf.
Eine fertige GPU wird unabhängig freigegeben und optional gestartet. Tests
prüfen Abschluss, späteren Abbruch, erneuten manuellen Stopp und Budgetende.
Reale Mehr-GPU-/Windows-, Hashrate-, Pool- und automatische Warteschlangen-
Abnahme bleiben offen; die vorhandenen Hardware-Gates werden nicht geschlossen.

### 2026-10-21 — BUG-011 PeakMiner tuning audit / PeakMiner-Tuning-Audit

Read-only inspection confirmed four failed Peak refinements and one still
measuring GPU in the active local five-GPU run. GPU 1's native restart lost its
ownership-conflict cause behind a later `worker_stopped`; GPU 3 failed refinement
admission with the same conflict. GPU 2 remained blocked after the prior SRB
thermal/recovery failure; GPU 4 had only one stable power point.

Source fixes and regressions now cover pause-preserving SRB native cleanup
(previously a stopped SRB could restart during Peak preparation), atomic
classification across a managed spawn's registration window, a bounded
per-device refinement handoff retry, native memory recovery markers and
preservation of the actual native start failure. Single-point curves now show
a localized insufficient-measurement skip cause. Full Java build/tests pass
(504 tests) and DE/EN checks pass. No live miner/OC command or deployment; the
historical competing PID and controlled hardware rerun remain open. BUG-011 is
not closed. See [Peak audit](agent-wiki/peak-wildrig-beta.md).

Deutsch: Vier Peak-Nachoptimierungen sind im laufenden Test fehlgeschlagen,
eine GPU misst weiter. Übergabe-/Bereinigungsrace, native Wiederherstellungsdaten
und verlorene Startursachen wurden lokal mit Regressionstests korrigiert. Die
Temperatur-/Recovery-Sperre bleibt erhalten; ein einzelner stabiler Power-Punkt
wird korrekt als unzureichende Messbasis erklärt. 504 Tests/Sprachprüfungen
bestanden, aber kein Deployment und kein neuer Hardwarelauf. Der historische
konkurrierende Prozess bleibt unbekannt; BUG-011 bleibt offen.

### 2026-10-21 — BUG-011 worker 126 child-process reproduction / Kindprozess-Reproduktion

Current read-only host inspection identifies a concrete additional cause:
PeakMiner on GPU 0 has a registered launcher and a separate mining child.
The child was treated as an external miner, blocking PeakMiner on idle GPU 2
with `Another miner already controls NVIDIA:2`. The registry now recognizes
descendants of live registered GPU miners, with a real-process regression
that preserves blocking of unrelated processes. See the [worker 126 evidence](agent-wiki/peak-wildrig-beta.md).
No live miner/driver action or deployment; hardware confirmation after update
and the other BUG-011 acceptance gates remain open.

Deutsch: Der eigene PeakMiner-Kindprozess auf GPU 0 wird bisher als fremd
erkannt und blockiert GPU 2. Die Erkennung wurde lokal mit Regressionstest
korrigiert; unabhängige Prozesse bleiben gesperrt. Kein Live-Eingriff oder
Deployment. Bestätigung nach Update und weitere BUG-011-Abnahmen bleiben offen.

## BUG-017 — LIVE operating point selected before a thermal stop / LIVE-Betriebspunkt vor Temperaturstopp

**Status:** Fixed and regression verified locally (`[x]`, 2026-10-21); deployment/hardware acceptance pending
**Priority:** P1

Read-only observation of worker `192.168.178.126`, GPU 2
(`GPU-32b2d5ad-eeec-f785-fd2c-44a8841601ca`) captured `MINING / peakminer`
at 82 °C and 278 W at 20:35:38 UTC, 84 °C and 272 W at 20:35:43 UTC,
then `PAUSED` at 20:35:45 UTC. The configured policy ceiling was 84 °C;
the miner console reports an Agent pause and graceful shutdown at 20:35:44 UTC.
The Agent thermal guard pauses at the ceiling (and also on unreadable temperature).
The worker cooled to about 59 °C / 29 W without intervention. No miner start,
stop, driver write or policy change was issued during this investigation.

The comparison API's highest-profit permitted Pearl candidate was a LIVE
observation from 2026-10-19T16:12:44Z: PeakMiner, 280 W cap, 197 W measured,
87.307 TH/s, `tunedProfile:null`. The current process consumed roughly 270–285 W.
GPU 2 has no stored PeakMiner Auto Tuning profile; its stored SRB profiles
have only 14 samples each. The current `CoinHoppingService.candidates` path
accepts performance observations after 12 observations without the temperature
filter used for sweep points. Such observations do not retain a full restorable
clock configuration. This means the selected LIVE row does not establish a
reproducible, thermally validated operating point. The exact historical reason
for the 197 W reading at a 280 W cap remains unknown.

The shutdown is consistent with the guard doing its job, not a new PeakMiner
ownership rejection. Successful guard stops currently retain only the generic
worker-pause console reason; they do not retain the triggering sensor value or
distinguish a missing reading. A paused worker is excluded from subsequent
revenue evaluations, so this guard does not automatically resume after cooling.

Correction: Revenue/profit selection excludes all ordinary LIVE/benchmark
observations; stored history is preserved. Sweep points require a positive peak
temperature below the worker ceiling even without an explicit policy. Confirmed
tuning results retain their exact settings/32-sample evidence. Unknown baseline
clock state requires a stopped worker and verified driver defaults; new curves
reset even without refinement. TITAN offsets without recovery markers are
explicitly cleared/read back when selecting driver defaults. Thermal stops now
retain measured temperature/limit, separately diagnose an unreadable sensor and
persist the exact failed GPU point in `operating-point-safety.json`. Reuse needs
fresh validated evidence after the failure. Missing sensor data does not reject
a profile. Rejection-history failures block automatic selection with a specific
DE/EN error and never bypass the emergency stop. Cooling does not auto-resume.
See [the operating-mode contract](agent-wiki/worker-power-modes.md#thermal-failure-of-a-measured-point-2026-10-21).

Verification: full JDK 21 `test standaloneJar --offline --no-daemon`, DE/EN
catalog/literal/render/runtime checks and fixture-browser results/wizard checks
pass. No deployment or live miner restart was performed; a sustained hardware
run after update remains an acceptance step.

Deutsch: GPU 2 erreichte am 21.10. um 22:35:43 Uhr deutscher Zeit die
konfigurierten 84 °C und wurde unmittelbar danach vom Agent pausiert. Der
Profit-Modus bevorzugt einen alten LIVE-Messpunkt mit 280 W Limit, damals
197 W gemessen, ohne gespeicherte Taktkonfiguration. Heute zieht der Prozess
rund 280 W. Für diese GPU existiert kein PeakMiner-Auto-Tuning-Profil. Die
Auswahl gewöhnlicher Leistungsmessungen prüft keine gespeicherte Temperatur
und garantiert keine reproduzierbaren Einstellungen. Die Temperaturabschaltung
ist plausibel und live zeitlich belegt; die Profilauswahl und die fehlende
konkrete Stoppdiagnose sind die offenen Softwareprobleme. Keine Live-Änderung;
Die Korrektur ist lokal umgesetzt und mit Regressionstests geprüft: LIVE-Daten
werden nicht als automatische Betriebspunkte verwendet, Basistakte werden
hergestellt und konkrete Temperaturfehler je GPU-Punkt dauerhaft gesperrt.
Neue geprüfte Messungen können den Punkt wieder freigeben. Die Grenze bleibt
erhalten. Deployment und Dauerlauf auf der Hardware stehen noch aus.
