# PC-Agent UX and persistent Node mining profile

Source and browser verification: 2026-10-03. Scope is local PC-Agent UI and dispatch; hardware and pool certification are separate.

## Evaluation

The previous Mining page combined installation, pool configuration, live console, optimization tips and device controls in one long view. Installed GPU coins shared similar rail icons. Most significantly, the globally preferred coin influenced external GPU dispatch while installation and per-device eligibility did not communicate a durable default clearly. A manually paused worker was a session-level state, insufficient for an enduring benchmark-only decision.

## Implemented behavior

- The Discord-style installed-miner rail and + installer remain. GPU entries display PRL/RVN/ETC and expose selected state. The page heading names the viewed coin.
- A persistent Node profile assigns one coin per physical device or none (local/benchmarks only). The global external-control switch is separate from assignment. Local mining and benchmark configuration remain available for excluded devices.
- New control-settings files contain an empty workerCoins map: installation does not enroll CPU/GPU hardware automatically. Pre-profile files (missing map) preserve Monero CPU/Pearl GPU behavior and existing worker permission flags. When migrated by an assignment, a legacy GPU wildcard (*) retains other legacy GPU defaults; explicit device entries override it.
- GET /api/agent/local/power-control/settings returns workerCoins. POST /api/agent/local/power-control/workers/{workerId}/coin?coin=none|monero|pearl|ravencoin|ethereumclassic validates device/type, serializes with benchmark admission, stops the previous assigned worker, then persists the new selection. It does not start the replacement. Pool/binary/fee readiness gates still apply when a start is requested. POST /settings preserves assignments, including saves from older Hardware clients.
- External resume/pause/target dispatch through device assignments, independent of activeCoin. External worker stats filter by device permission AND its assigned algorithm, avoiding double-counting multiple configured coins on one GPU. Capacity calculations exclude none workers. Node core consumes the same endpoints and status keys through MinerAgentController; its Map-based status reader tolerates the added workerCoins field. No consumer code change is required.
- Managed GPU conflicts are checked per physical device. Node dispatch stops competing managed coin workers on that GPU before starting the assignment. RVN/ETC still require their existing proxy listener and fee gates; this does not release either path.
- Mining is grouped into Status & devices, Setup (including fees), and Console & diagnostics. The profile editor can collapse and retains its expanded state for the browser session. Hardware links back to the Mining profile for device choices. Keyboard focus is preserved while a profile select is open; save errors are shown in place. New core copy is translated to English.

## Evidence and limits

- NodeMiningProfileTest covers new-install opt-in, persistence and legacy migration, preserving choices when an older settings client saves, assignment dispatch independent of the selected coin, exclusion from external stats and watt budgets, and a GPU-only external power target that never sends a CPU watt command.
- Full :pc-agent:test with JDK 21 passes. JavaScript syntax checks and git diff --check pass.
- Browser fixture QA in workspace .codex-qa/pc-agent-workspace.cjs verifies CPU local-only assignment, unchanged profile on miner navigation, setup/diagnostic visibility, master consent, no JavaScript errors, and no page overflow at 390 px. Desktop/mobile screenshots were visually inspected. Fixture verification is not a live Node or hardware trial.
- Real multi-GPU mixed-coin operation, driver power limits, remote Node orchestration and pool accounting remain host verification work. The RVN/ETC integration gates remain in rvn-etc-integration.md.
- No new coin/algorithm or changed wallet, pool, fee, Stratum, discovery, public telemetry or heating contract is introduced. The new-coin guide's corresponding integration/rollout steps are not applicable to this UX/profile change; existing coin gates remain applicable. 21energy is unaffected.
