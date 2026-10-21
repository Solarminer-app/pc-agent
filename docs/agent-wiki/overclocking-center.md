# Overclocking center / Overclocking-Zentrale

Current source, 2026-10-21: `/benchmarks.html` is a four-step GPU setup wizard
plus compact links to GPU workspaces. It does not mount the previous benchmark,
curve or Auto Tuning components. `/gpu-lab.html?device=<deviceId>` is a separate
full-width GPU page without app navigation, header, wallet strip or chrome;
`&view=results` opens its result view. Every GPU link uses `target="_blank"`
and `rel="noopener"`. The old `/autotuning.html` redirects to the wizard.
The existing comparison archive remains at `/benchmark-results.html`.

**Deutsch:** Die Zentrale führt durch GPU-Auswahl, Tempo, Tests/Grenzen und
Prüfung/Start. Live-Prozess und GPU-Ergebnisse öffnen in eigenen Browser-Tabs
mit neuem Design ohne App-Navigation oder Kopfzeile. Alte Benchmark-/Tuning-
Komponenten werden für diese Ansichten nicht eingebunden. Das bestehende
Vergleichsarchiv bleibt separat erreichbar.

## Frontend ownership / Frontend-Zuständigkeit

- `js/pages/overclocking.js`: wizard, compatible installed tests per selected
  GPU, maximum temperature, concurrency, optional clock/memory tuning and
  optional mining after completion. The review names the actual per-GPU scope.
  Independent automatic coin/miner switching permissions are refreshed before
  saving policies. A failed save prevents run admission.
- `js/pages/gpu-lab.js`: one physical GPU, stage/sample activity, measured
  hashrate/power/temperature, explicitly labelled requested clocks, curve,
  per-test diagnoses and confirmed profiles. Power/clock targets stay distinct
  from observed clocks and measured watts. The curve never combines different
  coin/miner pairs. Search/sample windows do not become confirmed profiles.
- `js/core/oc-lab.js`: new view helpers, API access and shared REST/SSE subscriptions.
  Workers and efficiency use their existing SSE channels with REST/watchdog
  recovery. Tuning sessions use REST every four seconds; a changed sample,
  candidate, step count or run lifecycle refreshes durable measurements.
- `css/oc-lab.css`: instrument-style mint/violet/copper surfaces, physical GPU
  activity rings and real sample segments. Activity stops when a device is
  finished, waiting or stale. Both OS reduced-motion preferences and a saved
  manual motion preference disable animation. Full DE/EN catalog and language
  selector also work in the standalone tab. Failed refreshes retain previous
  measurements with a stale notice; missing telemetry stays unavailable.

**Deutsch:** Die neuen Controller übernehmen Darstellung und Einrichtung,
keine Treiber- oder Sicherheitslogik. Taktziele, tatsächlich beobachtete Takte,
gemessene Watt und Power-Limits bleiben getrennt. Diagramme vergleichen nur
denselben Coin/Miner. Animationen zeigen reale Aktivität und berücksichtigen
reduzierte Bewegung. Verbindungsfehler lassen frühere Ergebnisse sichtbar,
kennzeichnen sie aber als veraltet. Texte und Sprachwechsel unterstützen DE/EN.

## Presets and local contract / Presets und lokaler Vertrag

`POST /api/agent/local/efficiency?mode=restart|resume` additively accepts
`preset: QUICK|MEDIUM|SLOW` and `autoStartMining: boolean`, alongside the existing
`coins`, `workers`, `maxConcurrentGpus` and `autoTuneAfterSweep`. Omitted presets
retain the exhaustive SLOW path; omitted auto-start is false. No LAN/Node,
proxy, coin, currency or central accounting contract changes.

| Preset | Power points | Search candidates / samples per candidate | Long confirmations | Measurement budget per GPU |
| --- | --- | --- | --- | --- |
| QUICK / Schnell | up to 3 / bis zu 3 | 3 / 8 | up to 3 × 32 samples / bis zu 3 × 32 Messwerte | 18 min |
| MEDIUM / Mittel | up to 6 / bis zu 6 | 12 / 12 | up to 6 × 32 samples / bis zu 6 × 32 Messwerte | 55 min |
| SLOW / Langsam | existing full curve / bisherige volle Kurve | existing adaptive search, up to 24 / 16 | existing up to 9 × 32 / bisher bis zu 9 × 32 | existing exhaustive limits / bisherige vollständige Grenzen |

Faster presets retain curve endpoints and shorten coverage/search windows.
Temperature, driver limits, share checks and independent 32-sample confirmation
acceptance remain active. SLOW preserves the previous search/refinement behavior.
QUICK defaults to one available coin/miner per GPU, preferring its assignment;
additional tests remain selectable. The preset's budget covers all selected
combinations on a device, divided among remaining tasks. Its monotonic clock
starts at that device's first task. Waiting before its first slot, synchronous
driver/miner calls and safe cleanup can add wall time: 20 min and 30–60 min are
operator targets, not hardware-certified end-to-end guarantees.

A reached budget is `lab_budget_reached`, not a successful exhaustive result.
Interrupted curves cannot replace a fully measured stored curve; already
confirmed points retain their evidence. A resume with a different preset
remeasures the curve and does not reuse the earlier preset's tuning checkpoint.
`Session.preset` and `Session.devices[]` expose the selected preset and per-device
release/start outcome without changing existing run fields.

**Deutsch:** Schnell/Mittel begrenzen Messarbeit pro GPU auf 18/55 Minuten,
verteilt auf deren ausgewählte Kombinationen. Warteschlange vor dem ersten
Test, blockierende Treiber-/Miner-Aufrufe und sichere Wiederherstellung kommen
hinzu; reale Laufzeitabnahme fehlt. Langsam behält den bisherigen Umfang.
Ein Budgetende bleibt als Teilmessung gekennzeichnet. Suchfenster sind kürzer,
die langen Bestätigungen und Sicherheitsgrenzen bleiben wirksam. Ein Wechsel
des Presets beim Fortsetzen übernimmt keine früheren Mess-/Tuning-Checkpoints.

## Device completion / GPU-Abschluss

`EfficiencySweepService` snapshots caps and adapter-local running/paused state,
then releases each GPU after **all** of its coin/miner tasks and synchronous
tuning/cleanup finish. Release stops that GPU's measurement processes, removes
its cap pin, restores its own cap, and restores its own prior runtime state or
starts its saved worker configuration if auto-start was requested. No whole-
adapter `restoreState` call can restart an already completed sibling. Session
cleanup and later cancellation skip devices already released.

Sweep overrides apply only inside `MinerStartContext.measurement`; ordinary
starts/recovery and released GPUs use saved configuration. Restoration and
post-benchmark start run outside measurement start authority. Requested auto-
start can clear an initial manual pause only while the captured stop revision
remains valid; a repeated operator stop wins. Thermal/recovery faults keep the
GPU stopped. `devices[].released` reports device release, while `miningState`
reports `RESTORED`, provisional `STARTED`, `FAILED`, or `BLOCKED`. STARTED is a
successful start call, not proof of positive hashrate or pool credit. Details
show the actual start/recovery cause. Session-wide measurement admission and
external power-dispatch guards remain held while other GPUs still measure.

**Deutsch:** Jede GPU wird nach ihrem letzten Test unabhängig wiederhergestellt
und gegebenenfalls mit gespeicherter Konfiguration gestartet. Bereits freigegebene
GPUs werden beim späteren Gesamtende/Abbruch nicht erneut gestoppt. Ein neuer
manueller Stopp überstimmt Autostart, Sicherheitsfehler halten die Karte an.
„Gestartet“ bestätigt den Startaufruf; Hashrate/Pool-Gutschrift bleiben gesonderte
Nachweise. Die globale Mess-/Fernsteuerungssperre bleibt für die übrigen Tests
bestehen.

## Verification / Verifikation

- JDK 21 `sh gradlew test standaloneJar`: full JVM suite and executable artifact.
- `SweepDeviceCompletionTest`: real asynchronous scheduling with synthetic
  measurement bodies; starts/releases one GPU while the sibling is blocked,
  cancellation leaves the released GPU alone, newer operator stop prevents
  start, expired QUICK budget prevents sampling and complete-curve persistence.
- `BenchmarkPresetTest`, controller, sweep/refinement, tuning, selection and
  measurement-authority tests verify request compatibility, coverage and safety.
- `scripts/check-overclocking-browser.cjs`: real new assets with fake local APIs;
  guided validation, scope saves and exact start payload, new tabs, results,
  stale data, independent GPU completion, motion, language switching and 390px.
  `check-benchmark-workflow-browser.cjs` keeps the former audit command as an
  entry point to this replacement workflow test.
- `scripts/check-benchmark-profiles-browser.cjs`: standalone curve geometry,
  observed/requested clocks, live sample updates plus existing comparison
  archive and fleet regressions. DE/EN and mobile fixtures only.
- Catalog, render simulation, literal scan, JS syntax and `git diff --check`.

**Deutsch:** Automatisierte JVM-/Browserprüfungen mit simulierten Messdaten;
keine echten Miner-/Treiberbefehle, kein Deployment und keine Hardware-/Pool-
Abnahme. Externe Miner-/Betriebssystemdiagnosen und Hardware-/Softwarekennungen
bleiben nach bestehender Ausnahme unverändert; neue eigene Texte sind DE/EN.
