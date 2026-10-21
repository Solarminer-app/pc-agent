# GPU Auto Tuning (Beta)

> Combined GPU setup (2026-10-21): [wizard and standalone GPU workspaces](overclocking-center.md).
> Faster combined presets reduce search coverage/windows; all long confirmations
> still use 32 samples. Standalone/automatic tuning retains its existing full search.
> Deutsch: Schnellere kombinierte Presets verkürzen die Suche; lange Bestätigungen
> behalten 32 Messwerte. Einzelnes/automatisches Tuning behält seinen bisherigen Umfang.


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
  Live calibration details and measurement graphs are shown only while that
  worker has a running calibration. They are removed when its run completes,
  fails, or is cancelled; compact saved profiles, measurement-history counts,
  and the local raw-data export remain available on the worker card.
  A worker must have live
  hash telemetry, writable power control, at least one driver-reported clock
  control, and a readable GPU temperature. The active process stays managed by
  the regular miner adapter.
- The Auto Tuning page persists an operator-selected concurrency limit from 1
  through 64 GPUs. Each active calibration gets its own session and cancellation
  state; queued workers begin until that limit is reached. `LocalRunLock` gives
  these sessions a shared Auto Tuning lane while worker mode changes, benchmarks,
  sweeps and manual power actions remain exclusive and cannot race any session.
  Leaving the mode or stopping a queued worker removes its pending request.
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
- When power tuning is selected, calibration covers three safe power tiers including
  the manual starting target. Each tier has six initial search windows. Driver-safe
  memory boundary anchors remain; the chooser covers three widely separated points
  before local refinement, and reserves every fourth subsequent trial for distant
  exploration. Other slots alternate Gaussian-process expected improvement with
  refinement around up to three spatially distinct stable regions. Refinement step
  sizes shrink on successive turns. Absolute memory settings and memory offsets
  are separate model categories. Score normalization is independent of hash units.
  For pairs with at least a 20% efficiency difference and exactly one varying
  dimension, an available interior midpoint can be probed to resolve a performance
  cliff. Neither side is discarded and monotonicity is not assumed.
- Standalone tuning: after eighteen base search windows, tiers with at least three stable points and
  a best score at least 5% above their median may receive six additional search
  windows in total, rotating between eligible tiers. Flat runs proceed directly
  to confirmation. The two strongest efficiency settings and the fastest measured
  setting per tier (deduplicated and still supported) are independently reapplied and measured for 32 five-second samples (160 seconds).
  Only these successful confirmations become new operating profiles. Failure to
  confirm any candidate in a required tier fails the run with an explicit reason;
  raw search data survives. Existing legacy profiles remain usable.
- Search is bounded to 24 windows; confirmation adds at most nine more (33 total).
  Eighteen base windows plus nine confirmations take 48 minutes of sampling, or
  56 minutes with all six refinement windows, before driver/process overhead. The absolute
  deadline remains 60 minutes. Refinement reserves time for confirmation plus
  three minutes of overhead. Candidate count and current window length are exposed
  to the local UI; its remaining-time estimate includes longer final windows.
- Candidate generation retains driver-supported pairs and safety bounds. Offset
  ranges use sixteen subintervals plus zero where allowed; generic clock ranges
  use eight subintervals, allowing bounded midpoint and shrinking-step refinement.
  Matching confirmed history seeds the model without excluding remeasurement in
  a new run. Search results are local append-only `SEARCH_RESULT` records; `RESULT`
  retains its existing persisted operating-profile meaning and JSON shape. Only
  confirmations from the current run satisfy completion. Local history counts
  include both kinds and distinguish search measurements from profile verification.
- Search candidates use sixteen five-second samples (80 seconds); confirmations use
  thirty-two (160 seconds). A search candidate is
  unstable when more than two valid hashrate readings differ from the median
  by over 20%, fewer than fourteen readings have valid hash and power telemetry,
  or temperature exceeds 85 C (configurable within 65–90 C). Confirmation requires
  at least 28 valid samples and permits at most four hashrate outliers, preserving
  the same one-eighth tolerance. Share counters
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
  restoration and restart succeed. The session stops after 60 minutes. A run that
  cannot confirm a stable point in each required tier before the deadline fails,
  but its individual measurements remain saved.
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
  memory setting using median hashrate and power. Per GPU, the operator can
  select either the best resulting hashes per watt or the highest approximate
  daily profit for its exact
  device/model/vendor, coin/algorithm and miner software/version. The complete
  stable candidate set remains available for later profit repricing; the latter
  evaluates every distinct stable clock/memory/power point after repeated
  measurements of that exact setting are median-combined, rather than reducing
  each power tier to its efficiency winner first. It
  selection uses fresh market data and the configured electricity tariff at
  application time, subtracts measured 24-hour electricity cost, and remains
  unavailable if either input is missing or stale. Missing profit data never
  blocks Auto Tuning or profile calibration: the saved profit preference
  temporarily uses the measured efficiency winner and resumes profit selection
  when fresh inputs return. It excludes fees, rejected shares and actual pool accounting. Unstable results remain visible for
  diagnosis and never become operating profiles.
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

## Kandidatensuche und Bestätigung / Candidate search and confirmation (2026-10-20)

Deutsch: Die Suche prüft zunächst weit auseinanderliegende Punkte und erkundet
später regelmäßig weitere Bereiche. Bis zu drei gute Regionen werden abwechselnd
mit kleineren Schritten verfeinert. Deutliche Leistungsunterschiede entlang einer
einzelnen Einstellung werden durch Zwischenmessungen eingegrenzt; es wird keine
monotone Effizienzkurve vorausgesetzt. Pro Leistungsstufe werden die zwei
effizientesten und der schnellste gemessene Kandidat erneut angewendet und jeweils
160 Sekunden statt 80 Sekunden gemessen; Duplikate entfallen. Nur erfolgreiche
Bestätigungen werden neue Betriebsprofile. Höchstens 24 Such- und neun
Bestätigungsfenster passen in das unveränderte 60-Minuten-Zeitlimit;
Treiberwechsel und Miner-Neustarts benötigen zusätzliche Zeit. Alte Profile bleiben
nutzbar. Suchdaten und Fehlschläge bleiben lokal erhalten. Deutsch und Englisch
zeigen die Bestätigungsphase sowie deren Fehlerursache an.

English: The calibration-flow section above describes the current search and
confirmation contract. Earlier dated audit entries below are historical. The
new regression tests cover continued exploration, distinct regions, controlled
midpoint probes, score-unit invariance, a lucky search winner losing confirmation,
failed confirmations publishing no profiles, and persisted search/profile separation.
Real GPU convergence, thermal settling and pool behaviour still need live validation.

Verifikation: Die automatischen Tests prüfen Exploration, getrennte Suchregionen,
Zwischenmessungen, Einheitenunabhängigkeit, Rangwechsel bei der Bestätigung und
die Speicherung. Konvergenz auf echter Hardware, thermisches Einschwingen und
Pool-Verhalten sind weiterhin live zu prüfen. Externe Miner-/Treibertexte bleiben
unverändert; neue Agent- und UI-Texte liegen auf Deutsch und Englisch vor.

## Java optimizer library choice

Smile provides Gaussian-process regression, but still requires the application
to implement the discrete candidate grid and acquisition function. Its core
libraries are dual-licensed under GPLv3 or commercial terms; this change keeps
the Agent's dependency set unchanged and implements the small RBF Gaussian
process plus expected-improvement step locally for a maximum of twenty-four search
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

The native path is intentionally restricted to NVIDIA SRBMiner workers. PeakMiner now has the native adapter described below. AMD and remaining miner adapters retain their existing driver paths. SRBMiner command acceptance and physical clock/power behaviour still require validation on each supported driver and GPU.

When the driver provides both a safe fixed-memory range and a memory-offset range, candidate generation now includes both independent control forms. Per power tier it first samples the low/high offset anchors, then the low/high fixed-memory anchors; remaining slots use Bayesian expected improvement. A candidate supplies either `--gpu-moffset0` or `--gpu-mclock0`, never both.

SRBMiner's NVIDIA adapter may also probe the fixed-memory values listed by the driver when the direct driver lock is unavailable, which covers Turing systems where SRBMiner can apply `--gpu-mclock0` through a different supported path. This does not trust command acceptance: the candidate is accepted only when the effective memory-clock sensor readback reaches the requested value within the existing tolerance. A failed readback removes memory candidates and continues core/power calibration.

For this SRBMiner probe, listed core values and listed fixed-memory values form a bounded cross-product rather than being restricted to the driver's reported core/memory pairs. Thus a low fixed memory can be measured with a core value that the direct driver API would not pair with it. The optimizer remains bounded to 18 candidates and validates every applied combination through telemetry.

## Combined benchmark entry / Gemeinsamer Benchmark-Einstieg (2026-10-21)

The benchmark page now starts optional automatic refinement after each selected
GPU/coin/software sweep. See [benchmark-workflow.md](benchmark-workflow.md) for
lock/concurrency ownership, cancellation, safe thermal/power bounds, exact
confirmed-point ranking/application and resume limits. Native miner restarts get
bounded warmup before sampling. The separate tool remains available for manual
single-worker investigation. Confirmed settings feed both efficiency and daily-
profit goals; a tuned live observation must not become an unqualified cap-only
baseline profile. These are local-source and mocked-test contracts, not hardware
or pool certification.

Die Benchmark-Seite startet optional nach jeder GPU-/Coin-/Software-Kurve
automatisch die Nachoptimierung. Messsperre, Parallelität, Abbruch, Temperatur-/
Power-Grenzen, exakte bestätigte Betriebspunkte und Fortsetzungsgrenzen stehen
in [benchmark-workflow.md](benchmark-workflow.md). Native Miner erhalten nach
Taktänderungen eine begrenzte Anlaufzeit vor der Messung. Das separate Werkzeug
bleibt für manuelle Einzeluntersuchungen bestehen. Bestätigte Einstellungen
fließen in Effizienz und Tagesgewinn ein; getunte Live-Werte werden nicht als
reine Watt-Profile abgelegt. Dies belegt Quellcode und automatisierte Tests,
keine Hardware- oder Poolabnahme.

### Curve-guided search / Kurvengeführte Suche (2026-10-21)

Automatic sweep refinement now consumes the whole curve: efficient, fastest and
intermediate measured points seed three power tiers. After clock/memory search,
up to six small up/down power probes retain the tuned settings. A shared list
of at most nine confirmations covers the anchors and efficient/fast trade-offs,
including newly measured watt caps. The budget remains 24 search windows,
33 windows total and 60 minutes. This supersedes the standalone-only per-tier
extension/confirmation description above for combined runs. See the detailed
[workflow contract](benchmark-workflow.md#curve-guided-power-refinement--kurvengeführte-power-nachoptimierung-2026-10-21).

Die automatische Sweep-Nachoptimierung nutzt nun die ganze Kurve: effiziente,
schnelle und mittlere Messpunkte bilden drei Leistungsstufen. Nach Takt-/Speicher-
Suche folgen bis zu sechs kleine Power-Schritte nach oben/unten bei denselben
getunten Einstellungen. Bis zu neun gemeinsame Bestätigungen decken Anker und
effiziente/schnelle Kompromisse einschließlich neuer Watt-Limits ab. Das Budget
bleibt bei 24 Suchfenstern, insgesamt 33 Fenstern und 60 Minuten. Für kombinierte
Läufe ersetzt dies die oben beschriebene separate Erweiterung/Bestätigung pro
Leistungsstufe; der ausführliche Vertrag steht in `benchmark-workflow.md`.

### Combined measurement graph / Gemeinsamer Messgraph (2026-10-21)

Benchmark and Results now overlay sweep, provisional tuner samples, search outcomes
and confirmed settings in one GPU/coin-scoped graph. Observed core/memory MHz and
absolute tuning targets are explicitly selectable; missing readbacks and memory
offsets are never presented as measured absolute clocks. The compact read-only
curve endpoint and profile-selection contract are documented in
[benchmark-workflow.md](benchmark-workflow.md).

Benchmark und Ergebnisse zeigen Sweep, laufende Tuner-Werte, Suchergebnisse und
bestätigte Einstellungen im gemeinsamen GPU-/Coin-Diagramm. Gemessene Kern-/
Speichertakte und absolute Sollwerte sind ausdrücklich getrennt auswählbar.
Fehlende Rücklesewerte und Speicher-Offsets werden nicht als gemessene absolute
Takte dargestellt. Endpunkt und Profilvergleich sind im Benchmark-Vertrag beschrieben.

### PeakMiner native tuning / Natives PeakMiner-Tuning (2026-10-21)

English: PeakMiner 2.19.1 documents native core/memory locks, memory offsets and
power limits in its [versioned CLI reference](https://github.com/peakminer/peakminer/blob/v2.19.1/README.md#overclocking--temperature-limits).
`PeakMinerService` now implements `MinerNativeTuning`, so standalone calibration,
automatic sweep refinement and selected operating profiles use the same native
path as other capable adapters. Startup maps absolute core MHz to `--gpu-lcore0`,
absolute memory MHz to `--gpu-lmem0`, memory offsets to `--gpu-mem0`, and watts
to `--gpu-power0`. Fixed memory and offset are mutually exclusive. Each worker
retains its CUDA UUID isolation and local device 0; global OC flags are omitted.
A change stops/restarts only the affected live process. Stopped workers cache
settings for their next start; clearing them cannot restart a paused worker.
Stop failures retain the previous settings; failed launches discard the candidate.
The shared optimizer still requires driver bounds, effective-clock readback,
temperature/power/hash telemetry and confirmed measurements. PeakMiner does not
opt into SRBMiner's provisional fixed-memory probe without driver lock support.
No new coin, ASIC, proxy, fee or LAN contract is introduced. Experimental Beta
availability follows branch promotion; installer/version and existing accounting
gates remain as documented in `peak-wildrig-beta.md`.

Deutsch: PeakMiner verwendet jetzt für Einzel-Tuning, automatische Benchmark-
Nachoptimierung und Betriebsprofile seine eigenen Startparameter. Absolute Kern-
und Speichertakte, Speicher-Offset und Watt-Limit bleiben getrennt; ein fester
Speichertakt und ein Offset werden nicht gleichzeitig übergeben. Nur der laufende
Prozess der betroffenen GPU startet neu. Ein pausierter Worker bleibt beim Löschen
seiner Einstellungen pausiert. Scheitert der Stopp, bleiben die bisherigen Werte;
scheitert der Start, wird der Kandidat verworfen. Treibergrenzen, Temperaturprüfung,
Takt-Rücklesung und Messbestätigung gelten weiterhin. Die versuchsweise feste
Speichersteuerung ohne Treiber-Lock bleibt SRBMiner vorbehalten.

Verification / Verifikation: `PeakNativeTuningTest` covers parameter semantics,
negative offsets, target-only restart, pause preservation and stop/start failures.
Full Java tests and the standalone build passed, plus both-language catalogue,
render and literal checks. New generic native-tuning failures are translated in
both UI catalogues; external miner/driver output remains verbatim. No live clock
writes were performed. Physical clock effects, OC index/UUID mapping on a
nonzero GPU with unaffected siblings, reset/restoration and Windows/Linux driver
behaviour still require hardware verification. Diese Hardware-Nachweise sowie
Pool-/Fee-Gutschriften sind weiterhin offen; automatisierte Tests ersetzen sie
nicht. Rollback: stop calibration, restore driver defaults, select manual mode
or another installed miner; saved pool and wallet data are retained.

The separate telemetry/results presentation now supersedes the combined
measurement graph above; see the final section of `benchmark-workflow.md`.
Die getrennte Telemetrie-/Ergebnisansicht ersetzt den gemeinsamen Messgraphen;
siehe den letzten Abschnitt in `benchmark-workflow.md`.

### Peak handoff and native recovery follow-up / Peak-Übergabe und native Wiederherstellung

2026-10-21: [PeakMiner audit](peak-wildrig-beta.md#peakminer-tuning-audit--peakminer-tuning-prüfung-2026-10-21)
records a live five-GPU refinement snapshot and fixes to SRB pause-preserving
cleanup, atomic managed/external process classification, bounded refinement
handoff, per-device native failure diagnosis and durable recovery admission for
native memory locks/offsets. Native writes now use `GpuTuningService.prepareNativeTuning`
before invoking the adapter, including measured operating-point and Auto Tuning
profile application. Final confirmations and temperature limits are unchanged;
one stable power point remains insufficient for a curve-guided search and gets
an explicit localized skip reason. Full source/504-test verification is distinct
from the still-open controlled hardware rerun.

Deutsch: Der verlinkte Audit dokumentiert den laufenden Fünf-GPU-Test und die
Korrekturen. Native Speicheränderungen erfassen vorab den ursprünglichen Offset
und berührte feste Takte für Bereinigung/Neustart. SRB-Bereinigung erhält einen
vorherigen Stopp. Fehlgeschlagene native Neustarts behalten die konkrete Ursache.
Temperaturgrenzen und lange Bestätigungen bleiben unverändert; ein einzelner
stabiler Power-Punkt wird mit genauer Ursache übersprungen. 504 Tests ersetzen
keinen erneuten Hardwarelauf.

## 2026-10-21 21:42 UTC — GPU 2 combined run: curve complete, tuning thermally blocked

Read-only inspection of worker 192.168.178.126 after the operator's log report
identified separate SRBMiner and PeakMiner Pearl tasks on GPU
`GPU-32b2d5ad-eeec-f785-fd2c-44a8841601ca`. SRB's power curve finished at
21:19:23 UTC with a best efficiency cap of 160 W (155.92 W measured, about
60.38 TH/s, maximum 78 °C). Its later tuning session started at 21:19:41 UTC
and ended with `auto_tuning_temperature_limit`: candidate 14 of 33 targeted
2100 MHz core, +100 MHz memory offset and a 280 W cap. The previous candidate
at the same cap but 930 MHz core / -1500 MHz offset had 16 valid samples at
80 °C. The failing candidate has sample index zero, so the stored history does
not establish its exact triggering temperature. No confirmed tuning result exists.

The outer SRB task log at 21:42:45 reports the curve's `COMPLETE` state and
omits the failed refinement. The session-level phase is correctly
`benchmark_optimization_partial`. `executeSweepTask` marks this physical GPU
blocked after the unsafe refinement, so the queued PeakMiner task is skipped
before measuring. Its generic `auto_tuning_gpu_requires_attention` is the
consequence of SRB's thermal failure, not a new PeakMiner ownership failure.
The GPU is not released (`BLOCKED / lab_restore_failed`) and is paused; the
coarse restoration label does not itself prove a failed driver reset.

Open gaps: task completion logs omit miner identity and refinement outcome;
a cold 280 W curve sample (maximum 80 °C) remains nominally stable although
subsequent 265–190 W steps reached the configured 84 °C limit. This is separate
from the corrected promotion of incomplete LIVE observations to profit points.
Thermal steady-state validation and preserving safe lower-tier confirmations
need follow-up; do not claim these are fixed by the LIVE selection change.
No miner/driver control was issued for this inspection.

Deutsch: Die Power-Kurve von SRBMiner ist abgeschlossen (160 W bester
Effizienzpunkt), die folgende Taktoptimierung scheitert am Temperatur-Limit.
Der letzte Kandidat fordert 280 W, 2100 MHz Kern und +100 MHz Speicher-Offset;
der konkrete auslösende Temperaturwert wurde vor dem ersten Sample nicht
persistiert. Deshalb wird PeakMiner für dieselbe GPU vorsorglich übersprungen.
Die COMPLETE-Logzeile beschreibt nur die Kurve; die Gesamtsitzung meldet korrekt
einen Teilerfolg. Miner-ID und Tuning-Fehler fehlen in der Aufgaben-Logzeile.
Die frühe kalte 280-W-Messung und die spätere Überhitzung zeigen eine offene
Lücke bei der thermischen Dauerprüfung. Kein Live-Eingriff bei der Analyse.

## Sustained thermal validation / Temperatur-Dauerprüfung (2026-10-22)

`ThermalStabilityTracker` now gates every power-sweep step and each final tuning
confirmation at unchanged settings. It requires at least 180 seconds of valid
continuous load, with the last 90 seconds within a 2 °C range and an absolute
linear temperature trend at most 0.2 °C/min. The final window needs at least
3 °C headroom below the worker ceiling, and the peak of the entire continuous
validation must remain below that ceiling. Invalid load/clock data and reading
gaps above 15 seconds reset the accumulated evidence. Each setting gets a new
tracker; a previous point's warmup cannot qualify its successor. Missing sensors
and actual over-temperature retain their immediate stop behavior.

A point that does not settle within 300 seconds is recorded with
`benchmark_temperature_not_settled`, never as a stable operating point. Normal
search samples remain provisional; only final confirmations undergo this longer
acceptance and retain 32 valid recent measurements. Sweep statistics similarly
use the last 12 valid readings. The explicit version-1 `thermalValidation` object
records loaded/window durations, final minimum/maximum/trend and overall peak
on sweep steps, tuning measurements and generated tuning profiles.

Revenue/profit selection, GPU efficiency/maximum-hashrate sweep selection and
cached Auto Tuning matrices reject missing or invalid evidence. Existing records
remain visible as history and model-training inputs; they must be measured again
before automatic selection or checkpoint reuse. Legacy and validated tuning
repeats are aggregated separately so old cold measurements cannot contaminate
new approved statistics. The GPU results page labels historical profiles needing
revalidation and shows a distinct warmup/temperature-validation phase in DE/EN.

QUICK/MEDIUM retain their 18/55-minute GPU work budgets, including warmup. The
safety threshold is identical for all presets. Budget/cancellation can yield
partial coverage with fewer approved points; they do not shorten validation or
promote a provisional point. Driver/startup/cleanup calls keep their existing
bounded-operation caveat. Successful short fixture tests do not certify ambient
conditions, fan behavior or sustained operation on physical hardware.

Deutsch: Jeder Sweep-Punkt und jede abschließende Tuning-Bestätigung wird unter
unveränderten Einstellungen mindestens drei Minuten belastet. Die letzten
90 Sekunden müssen innerhalb von 2 °C und maximal ±0,2 °C/min stabil sein;
3 °C Reserve zur Grenze und ein während der Prüfung zulässiger Spitzenwert
sind erforderlich. Datenlücken über 15 Sekunden setzen den Nachweis zurück.
Nach fünf Minuten ohne Stabilisierung wird der Punkt nicht freigegeben. Alte
Daten bleiben sichtbar, benötigen aber eine erneute Prüfung. Schnell/Mittel
halten ihre Budgets ein und prüfen nötigenfalls weniger Punkte. DE/EN erklären
Aufwärmen, fehlende Stabilisierung und historische Ergebnisse. Eine bestandene
Softwareprüfung ersetzt keinen Dauerlauf unter den realen Kühlbedingungen.
