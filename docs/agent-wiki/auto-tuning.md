# GPU Auto Tuning (Beta)

Auto Tuning is a local per-worker operating mode and automatic calibration flow. It is
scoped by GPU device ID, assigned GPU coin/algorithm, and selected miner
software. The saved Power Limits target is its starting point, not its
operating cap. The driver-reported default power limit is the automatic
exploration ceiling. Other operating modes and the power sweep remain available.

## Calibration flow

- Selecting `AUTO_TUNING` starts an uncalibrated worker at its saved manual
  target, waits for the miner to report `MINING`, then starts calibration.
  Calibration is controlled by the worker operating mode, and the Auto Tuning
  page also allows an operator to rerun an active worker with its saved tuning
  selection. Manual bounded clock controls live on the Power Limits page and
  use the same driver capability service; voltage writes remain unavailable.
  Live measurement
  graphs are shown only while the worker is in `AUTO_TUNING` mode.
  A worker must have live
  hash telemetry, writable power control, at least one driver-reported clock
  control, and a readable GPU temperature. The active process stays managed by
  the regular miner adapter.
- A single `LocalRunLock` protects a calibration. Worker mode changes,
  benchmarks, sweeps, and power actions already using that lock cannot race it.
  When multiple workers enter `AUTO_TUNING`, their calibration requests queue
  per GPU and run sequentially after the lock is released. Leaving the mode or
  stopping a queued worker removes its pending request. The page's worker
  history switcher shows the most recent saved sampling run for each GPU in
  this mode; the live progress header still identifies the one active session.
- Each GPU has a persisted tuning selection for core clock, memory clock and
  power limit. All three are enabled by default. Unselected candidate dimensions
  stay fixed at their start value during sampling. The direct calibration action
  reruns an already calibrated, actively mining worker. Power-only sampling is
  supported. With power tuning disabled, the run holds the starting power cap
  and spends its candidate budget on the selected clocks. Partial-selection
  runs restore driver defaults and the starting power cap at completion, and
  do not apply a full Auto Tuning operating profile.
- Stopping the worker or leaving `AUTO_TUNING` cancels an active session and
  waits for its recovery path before restoring driver clock defaults. The
  selected operating mode then applies its own power target. A recovery timeout
  or failed restore blocks the stop/mode change with a localized error.
- When power tuning is selected, the candidate search tests three distinct
  power-cap tiers that include the
  manual starting target when it is inside the safe range. The first tier
  sampled is the starting target. Each power tier first measures the minimum
  and maximum driver-reported memory setting when writable, then uses up to two
  Bayesian-selected clock points (twelve candidates total). NVIDIA
  candidates use only supported memory/core pairs reported by the driver; when
  memory locks are unavailable they use pairs at the highest listed memory
  clock no higher than the currently observed memory clock. A writable clock
  is not capped at the frequency observed before calibration: every reported
  supported pair within the driver bounds remains eligible, including memory
  and core clocks above the current operating point. AMD/Intel keep their probed
  discrete ranges. Matching stable measurements from earlier runs seed the
  Bayesian chooser, while each new run may remeasure the same setting. The
  model scales observed efficiency scores before expected-improvement selection,
  so H/s/W magnitude does not suppress exploration. The objective is median
  hashrate divided by measured GPU watts at each tier.
  Twelve sampled combinations make this an exploratory search, not proof of
  the absolute global optimum across all supported clock pairs.
- Each candidate uses sixteen five-second samples (80 seconds). A candidate is
  unstable when more than two valid hashrate readings differ from the median
  by over 20%, fewer than fourteen readings have valid hash and power telemetry,
  or temperature exceeds 85 C (configurable within 65–90 C). Share counters
  are saved when available but do not determine candidate stability: a share
  can take longer than the measurement window on some coins. NVIDIA effective
  core/memory clocks are read under load. A core clock below its requested
  ceiling is accepted when the power cap holds it lower and the observed core
  remains stable. A core clock more than 12% or 75 MHz above the requested
  ceiling is rejected; more than two core readings that deviate by that amount
  from the observed median also reject the window. The memory tolerance is 1%
  or 50 MHz. If three successive
  valid samples show that a requested memory lock did not take effect, that
  trial ends early and the session restores driver defaults before continuing
  with core-only candidates at the observed memory clock. This prevents a
  no-op memory write from being treated as a successful memory-clock experiment.
  For a completed window, more than two out-of-tolerance memory samples reject
  the point. AMD/Intel effective-clock readback still needs
  driver-specific validation. A worker exit scores the point zero; the agent
  restores defaults, attempts a bounded miner restart and continues only if
  restoration and restart succeed. The session stops after 40 minutes.
  Twelve candidate windows of sixteen five-second samples need sixteen minutes
  of sampling; driver writes, temperature/clock telemetry, miner settling and
  recovery add to that time. The first live Quantus run reached candidate 12
  with only three of sixteen samples after 25 minutes, so the old deadline was
  insufficient for that host. A run that cannot confirm one stable point in
  each tier before the deadline
  fails, but its individual measurements remain saved.
- Every raw sampling step and every candidate result is appended to
  `auto-tuning-profiles.json.measurements.jsonl` and forced to disk before the
  next step. The record includes run ID/time, GPU/model/vendor, coin/algorithm,
  miner software/version, requested cap/clocks, available hash/power/temperature,
  effective clocks, share counters, stability and typed failure reason. Invalid
  or unavailable readings stay explicit instead of being stored as zero.
  Completed windows that fail the hashrate or clock stability check retain
  their valid sample count and diagnostic hash/power/clock aggregates in the
  result row; they still cannot become operating profiles.
  A write failure aborts calibration and triggers the existing driver restore.
  Existing `auto-tuning-profiles.json` matrices are loaded as legacy verified
  points; they are never discarded by a new session.
- Every stable candidate becomes a reusable profile without waiting for a share,
  including points from a run that later fails or is cancelled. The current
  operating mode combines repeated measurements of the exact same cap/core/
  memory setting using median hashrate and power, then selects the best
  resulting hashes per watt for its exact
  device/model/vendor, coin/algorithm and miner software/version. The complete
  stable candidate set remains available for later profit repricing; unstable
  results remain visible for diagnosis and never become operating profiles.
  The three-tier readiness check still applies before autonomous profile
  selection. On completion, cancellation or failure, calibration
  restores driver clock defaults and the power limit captured at session start.
  In Auto Tuning mode, successful calibration then applies the most efficient
  confirmed tier and its core/memory clocks. On later starts the matching
  profile is reused; no calibration is required before first selecting the mode.
  The `gpu-tuning-recovery.json` marker is written before clock changes and
  retried at Agent startup/shutdown if restoration was interrupted.

## Driver adapters and limits

Capabilities are queried for each detected device rather than inferred from
vendor alone. The current adapters use the installed host tools already used by
the Agent's power-control path:

- NVIDIA: `nvidia-smi` supported-clock query and GPU clock lock. The reported
  memory bounds are retained independently of write support. Memory locking is
  offered when `--lock-memory-clocks-info` reports `runtime modifiable` or the
  newer `Memory Clock Switching Type: Runtime` response.
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
  assignment, current power telemetry, probed tuning capabilities and saved
  tuning selection. `PUT /{deviceId}/selection` persists the next calibration's
  core, memory and power dimensions to `auto-tuning-selection.json`.
- `POST /api/agent/local/gpu-tuning/start` starts an asynchronous session for a
  device and assigned coin; `/session` returns phase, candidate, sample progress
  and completed observations. `/cancel` requests safe cleanup.
- `GET /api/agent/local/gpu-tuning/profiles` returns local cached profiles.
- `GET /api/agent/local/gpu-tuning/measurement-summary` returns per-GPU counts
  and the five latest candidate results for the page. `GET /measurements`
  under the same route returns the latest 100 events by default; `limit=0`
  returns all events, optionally filtered by `deviceId`. Both are local-only.
- `DELETE /api/agent/local/gpu-tuning/{deviceId}/calibration-data` removes all
  saved profiles and raw measurement events for that physical GPU, across its
  coin/miner combinations. The page asks for confirmation and refreshes the
  view. It is rejected while a calibration or other run-lock operation is
  active. If the GPU is already mining in `AUTO_TUNING`, a fresh calibration
  request is made after deletion; otherwise its next active start can calibrate.
- `POST /{deviceId}/apply` and `/restore` expose bounded local clock controls
  for diagnostics and manual preparation. These writes share the run lock and
  are protected by the same-origin local-write filter.
- The worker operating mode `AUTO_TUNING` is selectable on GPUs. It uses a
  matrix matching the current device, model/vendor, coin/algorithm, miner software and
  pinned miner version when available. Local operation chooses the tier with
  the highest measured hashes per watt within the safe driver stock limit.
  CPU workers cannot select this mode.
- The current Node evaluates the Agent's most efficient tier and sends only
  on/off decisions in C10 plans. The Agent still accepts the older optional
  `autoTuningBudgets:{workerId:watts}` field for compatibility with existing
  Node versions; that legacy field selects a tier within the supplied budget.
  New Node plans do not send it and never send driver commands.

The local UI reports calibration phase, candidate/sample progress, approximate
remaining sampling time and saved history after reload without displaying raw
miner output. It paginates through the measurement timeline for workers in
`AUTO_TUNING`, including prior saved runs, while live progress identifies the
currently active GPU. During and after a session it also plots the raw sample timeline
for observed core/memory clocks, temperature, measured power, hashrate and
estimated gross fiat revenue per kWh. Hovering or tapping a point reveals its
time and candidate settings. Charts refresh with the session and use the same
local-only measurement endpoint; unavailable telemetry/market data is left
blank. German and English text uses the closed frontend catalog.

The device view groups each GPU's identity, power telemetry, probed clock
capabilities, calibration action, saved candidate history, and manual clock ranges. A
disabled calibration action names the missing assignment, mining worker, or
driver clock capability. Driver bounds appear beside their own core or memory
inputs; unsupported controls remain disabled. The history endpoints add
read-only local API data; the run-lock contract is unchanged.
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
candidate selection, legacy-matrix loading and append-only history replay with
version invalidation, Node tier
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

On 2026-10-19 the operator supplied read-only output from two Windows GeForce
RTX 2080 Ti cards using NVIDIA driver 617.14 (compute capability 7.5). Both
reported `Memory Clock Switching Type: Runtime`; GPU 0 listed memory clocks
405, 810, 5000, 6870 and 7070 MHz, while GPU 1 listed 405, 810, 5000, 6800
and 7000 MHz. The former parser recognized only `runtime modifiable`, so it
incorrectly disabled both memory controls despite this explicit policy. The
parser now recognizes the 617.14 response and regression tests cover both
reported ranges plus a deferred-only response. These are supported operating
clocks, not offsets. The later Quantus raw export confirms the Agent requested
405 and 810 MHz memory locks on GPU 1, yet every available NVIDIA clock
readback remained at 6800 MHz. Core clocks did respond to requested changes.
The driver command therefore did not establish effective memory control on
this Windows setup, despite the runtime policy report. The newer session
detects this after three valid readbacks and switches to core-only tuning; a
live rerun is needed to verify the fallback. Earlier stable points with a
requested and observed 6800 MHz memory clock do not prove the lock worked,
because 6800 MHz was already the operating clock.
The operator later reported that ASUS GPU Tweak III can change the memory
clock on both RTX 2080 Ti cards, and clarified that it uses an offset. This
rules out a general inability of those cards to adjust memory frequency; it
does not establish that NVIDIA's `--lock-memory-clocks` path works on this
Windows driver/setup. `nvidia-smi -lmc` requests an absolute lock range,
whereas an offset changes the nominal operating clock. NVIDIA documents
`nvmlDeviceGetClockOffsets` and `nvmlDeviceSetClockOffsets` for Maxwell and
newer GPUs on driver 555+; the former reports the device/P-state-specific
offset bounds and current value. NVIDIA's current NVML guide describes the
absolute memory lock as fully supported on Ampere and newer devices, unlike
these Turing cards. The Agent now uses the shared `de.verdox.gputoolkit`
NVML adapter for NVIDIA core targets and memory offsets during offset
calibration on Windows and Linux when the native NVML library and per-device
offset controls are available. It reads the active P-state and driver limits,
uses the active P-state range reported by the driver and checks the offset
after every write. Automatic calibration limits unverified offsets to 250 MHz
either side of the starting value, even if the driver reports a wider writable
range. Candidate offsets include both resulting boundaries,
quarter points and zero when it falls in range. Both bounded boundaries are sampled at
each power tier before Bayesian selection continues. Absolute memory-clock
control likewise samples the lowest and highest writable driver-reported memory
settings at every tier. The first nonzero trial also checks
that the effective memory clock actually moves. If the adapter is unavailable
on an RTX 20 card, calibration explores core and power only; it does not
try absolute memory locks. Raw samples and profiles store the requested
offset/P-state separately from the observed effective memory MHz. A recovery
marker preserves the original offset across a crash and restores it on
startup. A Windows RTX 2080 Ti live rerun is still required to establish
that NVML writes work on the operator's driver and GPU. Linux now tries the same
NVML offset probe when the native library is visible; a live Ubuntu/Docker
rerun on the TITAN RTX is still needed to establish library visibility,
privileges, write/readback and effective clock movement. The GPU Tweak result
has not yet been paired with an Agent readback under the same workload.

The operator's 2026-10-19 Windows RTX 2080 Ti and Ubuntu/Docker TITAN RTX
exports are baseline evidence from before the current full-range candidate and
write-guard changes. In the Windows absolute-lock runs, requests for 405, 810
and 5000 MHz did not move the observed memory clock from 6800 MHz; the 5000 MHz
trial was explicitly classified `auto_tuning_memory_lock_ineffective` and later
trials fell back to core-only tuning. A later Windows NVML-offset run requested
-100/0/+100 MHz and read back 6750/6800/6849 MHz, so the offset changed the
effective clock by about 50 MHz in either direction. At the 100 W tier the
three results were approximately 97.0–97.3 W and 167.6–168.3 MH/s, but core
targets differed, so this is not a controlled estimate of memory-offset power
savings. On the Ubuntu/Docker TITAN RTX, an 810 MHz absolute-lock request left
the measured clock at 6500 MHz and was classified ineffective; all later
core-only candidates also read 6500 MHz. These exports do not test the newer
full-boundary sampling or establish a working Linux memory-control method.
Do not infer an offset range from `--query-supported-clocks` or relabel
absolute lock settings as offsets.

The operator reports a separate RTX 3080 observation: the effective memory
clock was 9251 MHz at baseline, 8751 MHz after requesting a -1000 MHz NVML
memory offset, and 8251 MHz after requesting -2000 MHz. On that card and setup,
each -1000 MHz input step therefore corresponded to a 500 MHz lower reported
effective clock. Keep requested NVML offset and observed effective MHz as
separate values; this single-device observation is not a conversion guarantee
for other cards or driver versions.

The operator also reports the TITAN RTX at 6500 MHz effective memory clock,
6000 MHz with a -1000 MHz NVML offset, and 5500 MHz with a -2000 MHz offset.
This is the same observed 500 MHz effective-clock change per -1000 MHz input
step on that card. The report does not specify OS, driver version, or workload;
do not merge it with the earlier Ubuntu/Docker absolute-lock export as though
they were the same run.
The operator confirms that the UI's most-negative NVML offset bound is also
-2000 MHz on the RTX 3080. Both reported cards therefore expose -2000 MHz as
the lower bound on their current driver setups; the UI obtains this limit from
NVML at runtime rather than assuming it for other GPUs or drivers.
See the [ASUS GPU Tweak III guide](https://www.asus.com/us/support/faq/1048435/),
[NVIDIA NVML changes](https://docs.nvidia.com/deploy/nvml-api/change-log.html)
and [NVIDIA memory lock contract](https://docs.nvidia.com/deploy/nvml-api/latest/api/group__nvmlDeviceCommands.html).
The raw export also shows a requested 1710 MHz core at the 180 W cap running
near 1380 MHz while producing about 370 MH/s at 175 W. The former symmetric
target comparison discarded that power-limited point, although its measured
efficiency was about 2.11 MH/s/W against 1.74 MH/s/W for the 260 W point.
The revised core check permits a stable lower effective clock and retains the
measured clock for diagnosis. The same issue affected the 100 W, 1710 MHz
request, whose core ran near 615 MHz. Neither rejected result is retroactively
promoted to a stable profile; a live rerun must confirm it under the revised
rule.

### TITAN RTX Linux crash recovery (2026-10-19)

The operator's Quantus/SRBMiner 3.7.3 export on Linux shows that an absolute
810 MHz memory lock left the effective clock at 6500 MHz and was correctly
rejected. A later offset run completed a stable -2000 MHz candidate (effective
memory 5500 MHz) at 14:06:03 UTC. The next requested offset was +6000 MHz;
there is no sample/readback for it. The kernel logged Xid 62 and Xid 45 for
SRBMiner at 14:06:04 UTC and Xid 154, GPU Reset Required, at 14:06:34 UTC.
The Agent recorded `gpu_clock_restore_failed` at 14:06:36 UTC. The export
cannot prove whether the +6000 MHz write took effect before the GPU failed.

The Agent now limits unverified automatic offset exploration to ±250 MHz from
the run's starting offset. After a write/restore failure or two consecutive
missing NVIDIA clock readbacks, it records an unstable result, pauses the
affected miner, attempts a reset of that GPU UUID only, then independently
restores clock defaults and the original power limit. Failed restoration keeps
the existing recovery marker for startup retry. The device remains blocked
from another calibration in the running Agent after this failure. NVIDIA's
reset command requires root and no active clients on the target GPU; if it
fails, the session reports the reset failure and does not restart the miner.
The raw miner output is external text and remains untranslated; all new Agent
reason codes have German and English UI copy.

Verification: focused session and tuning-service tests cover the bounded grid,
pause/reset/restore sequence, reset command targeting and driver rejection.
The live reset and post-reset GPU health are still unverified on this host.

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
# SRBMiner-native NVIDIA tuning (2026-10-19)

For SRBMiner-MULTI on NVIDIA, independent of the configured coin, the Auto Tuning candidate and selected profile are applied through SRBMiner's documented startup parameters: `--gpu-cclock0`, `--gpu-mclock0`, `--gpu-moffset0` and `--gpu-plimit0`. The Auto Tuning service addresses the active miner-software adapter through `MinerNativeTuning`; it has no Pearl-specific branch. A candidate change restarts only that GPU's SRBMiner process, which makes the miner itself the clock-control owner. SRBMiner has no documented runtime OC API, so a restart is required for each change.

The native path is intentionally restricted to NVIDIA SRBMiner workers. AMD and other miner adapters remain on their respective existing driver paths until their miner provides an equivalent documented control interface. SRBMiner command acceptance and physical clock/power behaviour still require validation on each supported driver and GPU.

When the driver provides both a safe fixed-memory range and a memory-offset range, candidate generation now includes both independent control forms. Per power tier it first samples the low/high offset anchors, then the low/high fixed-memory anchors; remaining slots use Bayesian expected improvement. A candidate supplies either `--gpu-moffset0` or `--gpu-mclock0`, never both.
