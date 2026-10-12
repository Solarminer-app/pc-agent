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
| [ ] | P1 | BUG-001 | Starting a miner without a configured wallet ends in an unexplained generic error state. |
| [x] | P2 | BUG-002 | The frontend lacks a deliberate cache and invalidation strategy for stable or rarely changing data. |
| [ ] | P1 | BUG-003 | An orphaned Stratum-proxy process can retain its port after the PC-Agent exits, preventing the next startup. |

## BUG-001 — Explain missing-wallet startup failures

**Status:** Open (`[ ]`)
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

- **Reproduction evidence:** Not yet recorded.
- **Known cause:** Not yet investigated.
- **Implementation direction:** Validate the required wallet before process
  launch and expose a localized, actionable error through the existing UI/API
  error path. Ensure the state shown by the UI remains consistent with the
  rejected start.

### Verification required

- Add or update a focused test for a miner start without a wallet.
- Verify the German and English operator-facing message and resulting state.
- Verify that a configured valid wallet still permits startup.

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

**Status:** Open (`[ ]`)
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

- **Reproduction evidence:** Reported for Windows; no command, log, or process
  evidence is recorded yet. Linux behaviour is unconfirmed.
- **Known cause:** Likely incomplete child-process cleanup when the parent is
  terminated. This remains a hypothesis until traced.
- **Implementation directions to evaluate:**
  1. Detect and safely terminate only a stale proxy owned by this PC-Agent.
  2. Run the proxy as a separately managed native companion with coordinated
     start/stop scripts and clear ownership rules.
  3. Use platform-appropriate process groups/job objects so a parent shutdown
     also terminates its child.

  Docker is different: container teardown removes the process namespace, so it
  does not demonstrate safe lifecycle behaviour for native installations.

### Verification required

- Reproduce and capture the stale process/port-owner evidence on Windows; test
  Linux independently if it is supported by the change.
- Verify normal shutdown, interrupted termination recovery, and a consecutive
  restart without manually freeing the port.
- Verify that cleanup never terminates an unrelated process using the same
  executable or port.
- Verify Docker behaviour separately if launcher/process-management code
  changes.
