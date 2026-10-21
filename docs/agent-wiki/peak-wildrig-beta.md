# PeakMiner and WildRig NG on Beta

Windows operator follow-up (2026-10-18): A PeakMiner 210 W sweep step failed
with repeated `Another miner already controls NVIDIA:0` while the worker was
assigned to a different miner; assigning PeakMiner manually let a later run
start. The sweep now retains the adapter start reason, retries one managed
handoff collision, and suppresses watchdog spam for ownership conflicts.
The PeakMiner 2.19.1 API hashrate is currently treated as H/s after the
operator observed an implausible PH/s value with the former ×1,000 conversion.
The upstream README describes kH/s; capture raw Windows `/summary` and the
miner-console rate before considering the unit contract hardware verified.
See BUG-011 for the remaining reproduction.

Status: installed, selectable GPU adapters on `beta` (2026-10-18). These are
experimental mining routes. The operator can install and assign them; release
promotion still requires credited user, house and referral shares. The PC-Agent
never treats a positive local hashrate as pool credit.

| Software | Coin / algorithm | Pinned official release | Initial device scope | Miner fee |
| --- | --- | --- | --- | --- |
| PeakMiner | Pearl / PearlHash | `peakminer/peakminer` 2.19.1, Linux direct binary and Windows ZIP | NVIDIA RTX 2000+ and compatible data center GPUs, x86-64 | 2% |
| WildRig NG | Quantus / QPoW | `andru-kun/wildrig-ng` 1.0.0k Beta, Linux TAR.GZ and Windows ZIP | NVIDIA Pascal or newer, x86-64 | 2% |

WildRig Multi 0.51.5 does not list `quantus`. The operator chose WildRig NG,
whose official [algorithm matrix](https://wildrig.cc/) does list it. AMD and
Intel QTC are documented by WildRig NG but remain outside this first managed
adapter: its `--gpu-list` uses miner-local indices, and no stable mapping to the
PC-Agent's AMD/Intel physical device IDs has been verified. PeakMiner's Pearl
scope excludes NVIDIA GTX, Pascal/Volta Tesla and Quadro P models. Hardware
unknown to the closed catalog still needs a real driver/kernel test.

Both installers select only the requested software ID, require HTTPS, pin the
GitHub release asset byte count and SHA-256, extract behind the shared traversal
and link guard, install just the executable, and remove only their own binary
and version marker. Neither download overwrites SRBMiner or QuanPool.

Each selected NVIDIA GPU runs in its own supervised process. The Agent sets
`CUDA_VISIBLE_DEVICES` to the discovered driver UUID and selects miner device
`0` inside that isolated process. A local probe with two TITAN RTX cards showed
PeakMiner `/summary` PCI bus `21:00.0` and WildRig NG `/2/summary` bus ID `33`
when the UUID for NVIDIA index 1 was selected. The adapter collects per-worker
hashrate, temperature, accepted/rejected shares and pool quality; it keeps
console output and diagnoses kernel/driver/pool failures. It stops on missing
metrics, absent jobs, an unexpected pinned version or an unexpected developer
fee. A local process and API response alone do not mark the pool healthy.

PeakMiner uses the existing Pearl proxy's semicolon login: `--url` points only
to the SolarMiner Pearl listener and `--user` carries
`<upstream>;<PRL-wallet>/<worker>;x`. A captured PeakMiner `mining.authorize`
object showed this login verbatim and an empty separate worker field. The
existing proxy rewrites both fields before upstream login. Its separate-worker
shape is also accepted by the newer proxy contract test. WildRig NG uses the
PC-Agent's loopback relay and QTC `wallet.sm1.BASE64URL_POOL.worker` envelope;
the captured `login` object matches the existing Quantus proxy dialect. Neither
adapter has a direct pool fallback. Both check the proxy and loaded fee target
again at the last start path.

The efficiency sweep now iterates every installed registered GPU adapter per
selected compatible card, retaining software ID in profile/cohort keys. It
restores the saved miner and power limit after the run. Missing binaries are
skipped; route/fee failures appear as failed or unavailable measurements. The
regular sequential benchmark already iterates adapter batches. The operator's
later power sweeps are still required to compare actual efficiency.

## Worker 126: PeakMiner child-process ownership (2026-10-21)

Read-only inspection of `192.168.178.126:8084` identified the idle TITAN RTX as
NVIDIA index 2 (`GPU-32b2d5ad-eeec-f785-fd2c-44a8841601ca`). Its saved assignment
was PeakMiner / `COIN_PROFIT_PER_DAY`, status `ERROR`, not manually stopped;
power was about 29 W. Its console retained a successful SRBMiner start at
19:25 UTC, about 94 TH/s and an accepted share, followed by a worker pause
and `Another miner already controls NVIDIA:2`. No compute process used GPU 2.

The host process tree revealed PeakMiner launcher PID 2363625 (child of Agent
PID 2361486) and PeakMiner worker PID 2363884 (child of that launcher).
`nvidia-smi` assigned the worker to GPU 0, not GPU 2. The registry tracked only
the launcher's PID, so PeakMiner's global external-miner guard mistook its own
child for a foreign miner and rejected additional starts. This is a concrete
current reproduction; it does not establish the PID behind older failures.

The registry now recognizes descendants of a live registered GPU-miner
launcher as managed. A shared Agent/JVM ancestor does not grant ownership to
unrelated miners. A real launcher/child regression covers classification,
unrelated external processes, and removal of ownership after unregistering.
No live start, stop, driver write, deployment or Agent restart was performed.
Applying this correction still requires deploying the updated Agent and
verifying an additional PeakMiner start on an otherwise free GPU.

Deutsch: Die ausgeschaltete TITAN RTX auf Worker 126 ist GPU 2. Ihr letzter
PeakMiner-Start scheitert an der Belegungsprüfung; zuvor lieferte SRBMiner noch
Hashrate und einen akzeptierten Share. PeakMiner auf GPU 0 startet einen
eigenen Kindprozess, den die bisherige Registry fälschlich als fremden Miner
einordnet. GPU 2 selbst besitzt keinen Compute-Prozess. Die korrigierte
Erkennung berücksichtigt Kinder eines registrierten, lebenden GPU-Miners;
unabhängige Prozesse bleiben fremd. Der Regressionstest verwendet echte
Testprozesse. Es gab keinen Eingriff in laufende Miner und kein Deployment;
ein zusätzlicher PeakMiner-Start nach dem Update muss noch bestätigt werden.

## Verification and open gates

- Official GitHub release API returned all four pinned sizes/digests; Linux
  and Windows assets were downloaded and independently SHA-256 checked. ZIP
  contents contain the expected standalone `.exe` files.
- The actual Linux binaries supplied captured loopback API and Stratum login
  fixtures on an NVIDIA host; no valid pool job or submitted share was created
  during these connection-refusal probes.
- `sh gradlew test standaloneJar --offline` and `node scripts/check-i18n.cjs`
  passed on Java 21. The Pearl proxy's captured combined-login contract test
  passed in its repository.
- Live gates: install/start/stop/restart with a real Pearl/QTC pool and managed
  proxy, sustained positive hash, accepted and rejected share comparison,
  user/house/referral pool credit, payout/fee accounting, Windows device test,
  and a multi-GPU power sweep. WildRig NG binds its unauthenticated metrics API
  to all interfaces; host firewall exposure needs operator review.

Rollback: pause affected workers, reassign them to SRBMiner-MULTI or QuanPool
Miner, then remove only the selected alternative package. Pool and wallet
configuration remains saved. No coin, payout currency, fee-backend schema,
Currency Service collector, Node coin enum or portal contract changed; the
workspace new-coin guide's unrelated steps are not applicable to these added
miners. Its existing Pearl/Quantus accounting gates remain open.

## Native PeakMiner Auto Tuning / Natives PeakMiner-Autotuning (2026-10-21)

PeakMiner's pinned 2.19.1 startup OC interface is now connected to the shared
calibration/refinement/profile path. See [Auto Tuning](auto-tuning.md#peakminer-native-tuning--natives-peakminer-tuning-2026-10-21)
for parameters, lifecycle tests and outstanding physical device-mapping gates.
The existing BUG-011 Windows handoff/hashrate-unit verification remains open.

Deutsch: Die eingebundene Version 2.19.1 nutzt nun ihre nativen OC-Parameter für
die gemeinsame Kalibrierung, Nachoptimierung und Profilanwendung. Parameter,
Tests und offene Hardware-Nachweise stehen im verlinkten Tuning-Vertrag.
Der Windows-Nachweis für Übergabe und Hashrate-Einheit (BUG-011) bleibt offen.

## PeakMiner tuning audit / PeakMiner-Tuning-Prüfung (2026-10-21)

Read-only local API/console inspection of the still-active combined run confirmed
four failed PeakMiner refinements and one active refinement. GPU indexes below
are the Agent's NVIDIA indexes, not PeakMiner's isolated local index 0.

| GPU | Model | Observed result / Beobachtetes Ergebnis |
| --- | --- | --- |
| 0 | TITAN RTX | Still measuring; stable samples show requested core changes and memory-offset effects / Läuft weiter; stabile Messwerte zeigen Takt- und Offset-Wirkung |
| 1 | TITAN RTX | `auto_tuning_worker_stopped`; its first native candidate failed and the console retained `Another miner already controls NVIDIA:1` / Erster nativer Kandidat scheiterte beim Neustart mit Belegungskonflikt |
| 2 | TITAN RTX | `auto_tuning_gpu_requires_attention` after the preceding SRB refinement reported `gpu_clock_restore_failed`; the latest SRB session also retained a thermal-limit result / Nach vorherigem Wiederherstellungs-/Temperaturfehler gesperrt |
| 3 | RTX 2080 Ti | `Another miner already controls NVIDIA:3` before refinement admission / Belegungskonflikt vor der Nachoptimierung |
| 4 | TITAN RTX | `auto_tuning_power_range_too_narrow`; its actual Peak sweep had exactly one stable point, 100 W / Tatsächlich nur ein stabiler Power-Punkt bei 100 W |

The pinned [2.19.1 CLI](https://github.com/peakminer/peakminer/blob/v2.19.1/README.md#overclocking--temperature-limits)
confirms the parameter meanings used by the adapter. Source audit and regression
reproduction found coordinator/lifecycle defects, rather than evidence that all
native OC parameters are ignored:

- Pearl/SRB `clearNativeTuning` restarted a previously stopped worker merely
  because a remembered run existed, even with no native settings. Combined
  preparation clears all adapters on the target GPU; that could reintroduce
  SRB during a Peak handoff. Cleanup now preserves stopped state, does nothing
  when there are no settings, and retains settings if stopping a live process
  fails. Shared SRB follows the same stop-failure rule.
- A visible process between `ProcessBuilder.start` and registry insertion could
  be classified as external by another adapter. `unmanagedGpuCoin` shares the
  spawn/register lock and checks current liveness, preserving the genuine
  external-process guard. The same safe classification is used by GPU adapters
  and the SRB external-stop filter. A real `sleep`-process concurrency test
  reproduces the visibility window; it is not a GPU/miner process test.
- Refinement startup now has the same single, device-scoped managed handoff
  retry as the sweep start. It never kills external processes or touches a
  sibling GPU; a failed managed stop blocks continuation.
- Native CLI memory locks/offsets had bypassed the direct-driver recovery marker.
  `prepareNativeTuning` now persists the touched domain and original offset
  before a native calibration or operating-profile write. Cleanup/interrupted
  startup restores that original offset and resets touched fixed memory.
  Persistence failure prevents the native write.
- A failed native restart no longer proceeds to cache another candidate onto a
  stopped process, eventually hiding the cause as `auto_tuning_worker_stopped`.
  The failed result retains the device-specific start error. Recovery faults
  still override the final status for safety.
- A single stable watt point now skips refinement with the localized
  `benchmark_optimization_insufficient_curve`, rather than suggesting that the
  driver's actual power range is too small. The measured-curve safety gate
  remains; no unmeasured higher caps are invented.

The live logs do not identify the historical competing PID. The reproduced
code defects can produce the observed conflicts; they do not establish which
one caused each historical collision. GPU 2's thermal/recovery block was not
cleared. Physical nonzero-GPU OC mapping, recovery and Windows/Linux acceptance
still need a controlled rerun with the patched build. No live stop/start, OC
write, deployment or restart was performed during this audit.

Verification: JDK 21 `test standaloneJar` passed, 504 tests, no failures/errors/
skips. Added native failure/recovery-marker, SRB cleanup, bounded refinement
handoff and atomic process-classification regressions. DE/EN catalog/render and
untranslated-literal checks pass; miner/OS console output remains verbatim.

Deutsch: Vier fehlgeschlagene Peak-Läufe sind am laufenden Agent belegt; eine
GPU optimiert weiterhin mit sichtbaren Takteffekten. Startparameter entsprechen
der versionierten Dokumentation. Lokal korrigiert wurden ungewollte SRB-Neustarts
beim Bereinigen, eine Registrierungsrace, Wiederherstellungsdaten für native
Speicheränderungen und verlorene Neustartdiagnosen. Ein einzelner stabiler
Watt-Punkt erhält eine genaue Überspringen-Ursache. 504 Tests und Sprachprüfungen
bestehen. Der damalige konkurrierende Prozess bleibt unbekannt; vorhandene
Temperatur-/Recovery-Sperren bleiben erhalten. Der laufende Test wurde nicht
verändert, der Patch nicht ausgerollt; reale Abnahme bleibt offen.
