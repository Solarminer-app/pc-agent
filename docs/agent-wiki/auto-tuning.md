# GPU Auto Tuning (Beta)

Auto Tuning is a local per-worker operating mode and calibration flow. It is
scoped by GPU device ID, assigned GPU coin/algorithm, and selected miner
software. It does not replace the existing operating modes or power sweep yet;
those remain available while this path is exercised.

## Calibration flow

- The operator opens `/autotuning.html` and starts a calibration for an assigned
  GPU that is already mining the assigned algorithm. A worker must have live
  hash telemetry, writable power control, at least one driver-reported clock
  control, and a readable GPU temperature. The active process stays managed by
  the regular miner adapter.
- A single `LocalRunLock` protects a calibration. Worker mode changes,
  benchmarks, sweeps, and power actions already using that lock cannot race it.
- The test candidate searches three distinct power-cap tiers, with up to four
  Bayesian-selected clock points per tier (twelve candidates total). NVIDIA
  candidates use only supported memory/core pairs reported by the driver; when
  memory locks are unavailable they use pairs at the highest listed memory
  clock. AMD/Intel keep their probed discrete ranges. The objective is median
  hashrate divided by measured GPU watts at each tier.
- Each candidate uses eight five-second samples. A candidate is unstable when
  hashrate varies by more than 20%, temperature exceeds 85 C (configurable
  within 65–90 C), any rejected share is reported, share counters are absent,
  or no accepted share arrives during the window. NVIDIA effective core/memory
  clocks are read under load; more than two samples outside a 12%/minimum-MHz
  tolerance invalidate a point. AMD/Intel effective-clock readback still needs
  driver-specific validation. A worker exit scores the point zero; the agent
  restores defaults, attempts a bounded miner restart and continues only if
  restoration and restart succeed. The session stops after 15 minutes.
  Twelve candidate windows of eight five-second samples need eight minutes of
  sampling; driver writes, miner settling and recovery make roughly 9–12
  minutes a practical estimate. A run that cannot confirm one stable point in
  each tier before the deadline fails without replacing the prior matrix.
- Every tier needs a stable candidate with share evidence before the three-row
  matrix is persisted. On completion, cancellation or failure, calibration
  restores driver clock defaults and the power limit captured at session start.
  The `gpu-tuning-recovery.json` marker is written before clock changes and
  retried at Agent startup/shutdown if restoration was interrupted.

## Driver adapters and limits

Capabilities are queried for each detected device rather than inferred from
vendor alone. The current adapters use the installed host tools already used by
the Agent's power-control path:

- NVIDIA: `nvidia-smi` supported-clock query and GPU clock lock. The reported
  memory bounds are retained independently of write support. Memory locking is
  offered when `--lock-memory-clocks-info` reports runtime-modifiable support.
  On older CLIs where that query is unrecognized, the read-only fallback
  requires both `-lmc` and `-rmc` commands in help and compute capability 7.x
  or 8.x. That path is labelled provisional until an explicit write succeeds;
  deferred-only and unknown architectures are not treated as runtime writable.
  Automatic recovery resets a memory lock only after this Agent touched it;
  the explicit operator restore also clears a supported external memory lock.
- AMD: AMD SMI (`amd-smi static --clock`, `set --perf-level MANUAL`, and
  `set --clk-limit` for SCLK/MCLK). A clock change is not advertised if the
  installed AMD SMI/driver cannot report usable bounds.
- Intel: XPU-SMI (`xpu-smi config --frequencyrange`) for core clock ranges.
  The current XPU-SMI configuration interface does not expose a portable
  memory-clock range through this adapter.
- Voltage offsets are not exposed. They do not have a consistent, readable,
  reversible interface across these vendor adapters and their supported
  Windows, Linux, and container host-driver combinations.

These commands run in the Agent's current host/container namespace and
privileges. Docker must pass through the GPU device and management utility with
write access; discovery/read-only telemetry does not establish that clock
writes will work. A rejected write is surfaced per device, and the UI does not
claim support from vendor identity alone. Driver-specific availability still
needs live validation on each OS and GPU family.

## Local API and UI

- `GET /api/agent/local/gpu-tuning` returns each detected GPU's worker
  assignment, current power telemetry and probed tuning capabilities.
- `POST /api/agent/local/gpu-tuning/start` starts an asynchronous session for a
  device and assigned coin; `/session` returns phase, candidate, sample progress
  and completed observations. `/cancel` requests safe cleanup.
- `GET /api/agent/local/gpu-tuning/profiles` returns local cached profiles.
- `POST /{deviceId}/apply` and `/restore` expose bounded local clock controls
  for diagnostics and manual preparation. These writes share the run lock and
  are protected by the same-origin local-write filter.
- The worker operating mode `AUTO_TUNING` is selectable on GPUs. It requires a
  matrix matching the current device, model/vendor, coin/algorithm, miner software and
  pinned miner version. It applies the highest tier no greater than an assigned
  per-GPU budget before the miner starts. CPU workers cannot select this mode.
- C10 Node economic plans may include `autoTuningBudgets:{workerId:watts}` for
  active Auto Tuning workers. The Agent publishes each calibrated tier with
  local market forecast and validates the exact budget against its own matrix.
  Node chooses the highest economic tier within the current PV budget and only
  sends watts; it never sends driver commands. Older on/off plans remain valid.

The local UI reports calibration phase, candidate/sample progress, approximate
remaining sampling time and saved tiers after reload without displaying raw
miner output. German and English text uses the closed frontend catalog.

The device view groups each GPU's identity, power telemetry, probed clock
capabilities, calibration action, saved tiers, and manual clock ranges. A
disabled calibration action names the missing assignment, mining worker, or
driver clock capability. Driver bounds appear beside their own core or memory
inputs; unsupported controls remain disabled. These are presentation changes
only; the local tuning API and run-lock contract are unchanged.
Driver-related capability and action errors now pair the localized cause with
an actionable next step. NVIDIA read-only or provisional memory support points
to the local `nvidia-smi` help and supported-clock queries. Write-permission,
range, write, recovery-file, reset, and clock-readback failures have distinct
advice. The UI uses only the typed reason code; it does not show or translate
raw external driver output. Advice never bypasses the backend's capability,
run-lock, recovery-marker, or write checks.

## Verification and open acceptance

Automated evidence covers NVIDIA supported-clock pairs and effective-clock
parsing, out-of-range rejection, driver-default reset commands, finite
candidate selection, matrix persistence/version invalidation, Node tier
selection, core-only NVIDIA recovery without an unsupported memory-clock reset,
Agent plan validation and both German/English page rendering. Live
changed-clock writes, accepted/rejected pool-share behavior, crash/driver-timeout
recovery, exact clock convergence and Docker permission setup remain hardware
acceptance tasks. Do not describe Beta support for a particular GPU/OS
combination until those paths are exercised.

On the available five-GPU NVIDIA host, a read-only supported-clock query
returned memory/core pairs, while `--lock-memory-clocks-info` was not recognized
by the installed `nvidia-smi` (driver 580.178.04). The older-CLI fallback sees
both memory lock/reset commands and compute capability 7.5 on TITAN RTX and
8.6 on RTX 3080. The reported memory ranges are 405–7001 MHz and 405–9501 MHz,
respectively. These are supported operating clocks, not overclock offsets.
The host is not the user's RTX 2080 Ti, and no live clock or power-cap writes
were made. Agent permissions and actual write acceptance remain to verify on
that machine. If the memory lock cannot be used, candidate generation still
uses core-clock pairs at the highest reported memory clock and leaves memory
controls untouched.

### Initial implementation audit (2026-10-18; superseded by test-candidate work above)

At the initial audit, the Beta path was a single-point calibration prototype
without the profile-matrix architecture in `docs/autotuning.md`:

- `AutoTuningSessionService` picks one globally best efficiency observation and
  `AutoTuningProfileStore` keeps one profile per device/coin/software ID. The
  runtime mode applies that one cap and clock setting; no power-tier matrix or
  solar-driven profile switch exists. The cache key does not include miner
  binary version or GPU identity/model validation.
- Calibration accepts a candidate with no accepted or rejected shares during
  its 40-second window, and accepts absent share counters as zero. Worker exit
  aborts the entire session after restoring defaults; the failed region is not
  used to continue exploration. A driver command's exit status is not clock
  readback, as noted above.
- The UI shows the active session and its result, but does not load the saved
  `/profiles` endpoint, so a cached profile is not shown after page reload.
  The optimizer, session, and profile invalidation lack end-to-end tests; the
  existing tests cover the optimizer chooser, driver parsing, and mode apply
  with mocks.

Audit verification: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew
test standaloneJar --offline` passed; `check-i18n.cjs`,
`scan-i18n-literals.cjs`, and `simulate-i18n-render.cjs` passed in both locales.
The host exposes five NVIDIA GPUs, and one read-only supported-clock query
returned clock pairs. No write, calibration, miner-share, or solar-switch test
was performed. This path is ready for controlled component exploration, but
does not meet the planned end-to-end acceptance criteria yet.

## Java optimizer library choice

Smile provides Gaussian-process regression, but still requires the application
to implement the discrete candidate grid and acquisition function. Its core
libraries are dual-licensed under GPLv3 or commercial terms; this change keeps
the Agent's dependency set unchanged and implements the small RBF Gaussian
process plus expected-improvement step locally for a maximum of twelve
observations per run. This bounded matrix size keeps the solver straightforward
and avoids adding a second ML runtime to the standalone Agent. ojAlgo provides
LP/QP/MIP modeling and linear algebra, but not a ready Gaussian-process Bayesian
optimization loop, so it does not reduce the code needed for this search.
Primary references: [Smile Gaussian Process API](https://github.com/haifengl/smile/blob/master/core/src/main/java/smile/regression/GaussianProcessRegression.java),
[Smile license](https://github.com/haifengl/smile/blob/master/LICENSE),
[ojAlgo project](https://github.com/optimatika/ojAlgo),
[NVIDIA SMI clock locks](https://docs.nvidia.com/deploy/nvidia-smi/index.html),
[AMD SMI clock controls](https://rocm.docs.amd.com/projects/amdsmi/en/develop/how-to/amdsmi-cli-tool.html), and
[Intel XPU-SMI frequency range](https://intel.github.io/xpumanager/2.0/xpu-smi/config.html).
