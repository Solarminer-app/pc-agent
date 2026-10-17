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

### Verification required

- Start and switch modes for CPU and GPU workers, including a coin change, and
  compare each row with the live process and miner console on target hardware.

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

### Verification required

- Run two configured coins on one writable GPU and confirm both start and
  finish; repeat with a first-coin startup failure and verify safe continuation.

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

An active worker participating in a benchmark or sweep shows “Benchmarking” in
English and “Benchmark läuft” in German. Idle workers retain their actual
paused/stopped/error status.

### Investigation and remedy

- The dashboard only had miner process state, with no measurement-run context.
  It now subscribes to the local benchmark and sweep SSE channels and overrides
  a `MINING` row's display status while either local run is active.
- LAN snapshots add nullable `benchmarking` to each worker row. A new peer sets
  it only for active workers during a benchmark/sweep. Older peers omit it and
  keep the previous status display. The dashboard polls its cached LAN board
  every five seconds, matching the service's five-second refresh cycle.
- The status label uses the closed German/English catalog; the protocol field
  is additive and does not change the miner's actual `MINING` process state.

### Verification performed and required

- `NetworkDashboardServiceTest` covers active versus stopped peer rows and
  passed with the focused JDK 21 suite. Both i18n checks passed (1,420 keys,
  zero unkeyed browser literals); `overview.js` passed `node --check`.
- Confirm on two real agents that a local benchmark and sweep label only the
  mining workers, the remote board follows, and both revert after completion.
