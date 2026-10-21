# Worker benchmark workflow / Benchmark-Ablauf pro Worker

> Current GPU setup and presentation (2026-10-21): see [Overclocking center](overclocking-center.md).
> The wizard and standalone GPU tabs supersede earlier inline-page descriptions;
> QUICK/MEDIUM/SLOW budgets and per-device release apply to efficiency sweeps.
> Deutsch: GPU-Einrichtung, Presets und unabhängiger Abschluss sind dort aktuell
> beschrieben. Frühere Inline-Oberflächenbeschreibungen sind historisch.


Source checked 2026-10-21. This describes local source and automated evidence,
not a deployed release or hardware/pool certification.

## Operator contract / Bedienvertrag

**English:** Power sweeps establish an independent full descending curve per
physical GPU and selected compatible coin/software pair. The measurement page
now defaults to `autoTuneAfterSweep:true`: each curve is followed automatically
by synchronous clock/memory refinement inside the same GPU slot and exclusive
measurement lock. Unchecking the option runs power sweeps only. Ordinary fixed-
point benchmarks (including CPU/XMRig and LIVE observation) remain available.
The standalone tuning tool is an advanced manual entry point.

**Deutsch:** Power Sweeps erstellen pro physischer GPU und ausgewählter
kompatibler Coin-/Software-Kombination eine eigene vollständige absteigende
Kurve. Die Messseite verwendet standardmäßig `autoTuneAfterSweep:true`: Nach
jeder Kurve folgt automatisch synchrones Takt-/Speicher-Tuning im selben GPU-
Platz und unter derselben Messsperre. Ohne diese Option laufen nur Power Sweeps.
Normale Vergleichsbenchmarks einschließlich CPU/XMRig und LIVE-Beobachtung
bleiben verfügbar. Das separate Tuning-Werkzeug dient weiterhin einzelnen
manuellen Untersuchungen.

**English:** `/benchmarks.html` exposes per-worker coin/software checkboxes.
Workers exposes the same preferences and saves them before its assignment
submission. An empty saved combination list explicitly excludes that worker;
a missing policy preserves legacy behavior. The measurement page selects workers
and edits their test combinations directly. It saves selected policies before
starting, then derives the request's coin scope from the selected installed
combinations. The former browser-global coin filter is absent from this page;
old localStorage coin selections cannot silently restrict a new run. Effective
targets still require installed software, hardware compatibility and usable routing. Software may be selected before installation; measuring
still requires an installed binary. No binary is downloaded by saving a policy.
Model compatibility is decided by the backend, not duplicated in the editor.

**Deutsch:** `/benchmarks.html` bietet pro Worker Checkboxen für Coin und
Miner-Software. Die Worker-Seite zeigt dieselben Regeln und speichert sie vor
der Zuweisung. Eine gespeicherte leere Liste schließt den Worker ausdrücklich
aus; ohne gespeicherte Regel bleibt das bisherige Verhalten erhalten. Die
Messseite wählt Worker und deren Testkombinationen direkt. Vor dem Start werden
die ausgewählten Regeln gespeichert; die Coin-Liste der Anfrage entsteht aus
den ausgewählten installierten Kombinationen. Den bisherigen globalen Coin-
Filter gibt es hier nicht mehr. Alte localStorage-Coin-Auswahlen begrenzen keinen
neuen Lauf. Installation, Hardwarekompatibilität und nutzbare Route bleiben nötig. Software darf vor der Installation vorgemerkt werden; zum
Messen muss sie installiert sein. Das Speichern lädt keine Binary herunter.
Die Modellkompatibilität bestimmt das Backend statt einer zweiten Browserlogik.

## Goals and permissions / Ziele und Freigaben

**English:** The GPU editor presents efficiency and approximate daily profit as
the two automatic goals, alongside manual operation. Existing persisted modes
remain API-compatible; the old Auto Tuning mode can continue for saved workers,
while new tuning runs in the combined workflow or the standalone tool. CPU efficiency
retains its existing measured hashes/W behavior; daily-profit selection remains
GPU-only. CPU has only the existing Monero/XMRig combination.

- Efficiency (`COIN_REVENUE_PER_KWH`) compares estimated USD/kWh. Raw hashes/W
  from different algorithms cannot be compared economically.
- Daily profit (`COIN_PROFIT_PER_DAY`) compares estimated gross revenue minus
  electricity cost per day. Current market inputs and a configured tariff are
  prerequisites. Existing fee-display/estimate semantics are unchanged: fees
  are informational and are not deducted from this approximate figure.
- Independent `allowCoinSwitch` and `allowMinerSwitch` flags permit neither,
  either, or both dimensions to change. A fixed dimension stays at the saved
  worker assignment. Automatic assignment updates change only permitted
  dimensions; absent/unmeasured/unready choices are never invented.
- The existing 5% gain gate, 30-minute cooldown and operator-stop authority
  remain. A single remaining valid efficiency choice can now replace an
  expired current point; it no longer requires a second candidate.

**Deutsch:** Der GPU-Editor zeigt Effizienz und ungefähren Tagesgewinn als zwei
automatische Ziele neben manuellem Betrieb. Gespeicherte alte Modi bleiben
API-kompatibel. Der alte Auto-Tuning-Modus funktioniert für gespeicherte Worker
weiter; neues Tuning ist über das Werkzeug für Schritt zwei erreichbar.
CPU-Effizienz behält gemessene Hashes/W; die Tagesgewinn-Auswahl bleibt GPU-
bezogen. Für CPUs existiert weiterhin nur Monero/XMRig.

- Effizienz (`COIN_REVENUE_PER_KWH`) vergleicht geschätzte USD/kWh. Hashes/W
  verschiedener Algorithmen sind wirtschaftlich nicht direkt vergleichbar.
- Tagesgewinn (`COIN_PROFIT_PER_DAY`) vergleicht geschätzten Bruttoertrag minus
  Stromkosten pro Tag. Aktuelle Marktdaten und Stromtarif sind Voraussetzungen.
  Gebühren werden angezeigt, aber nicht von dieser Schätzung abgezogen.
- `allowCoinSwitch` und `allowMinerSwitch` erlauben getrennt keinen, einen oder
  beide Wechsel. Eine feste Dimension bleibt bei der Worker-Zuweisung. Die
  Auswahl erfindet keine fehlenden, ungemessenen oder nicht startbereiten Werte.
- 5-%-Verbesserungsschwelle, 30 Minuten Wartezeit und die Stopp-Absicht bleiben
  bestehen. Ein einzelner verbleibender gültiger Effizienzkandidat darf einen
  abgelaufenen Betriebspunkt ersetzen; ein zweiter Kandidat ist nicht mehr nötig.

## Temperature and lifecycle / Temperatur und Lebenszyklus

**English:** Policies accept a finite 40–90 °C limit per GPU. Sweep and tuner use
the lower of that limit and their server ceiling. Equality already trips the
limit; absent, non-finite and nonpositive temperatures are unsafe. Tuning checks
thermal safety even when rate or power is invalid, and stops a thermally failed
worker before restoring driver settings. It does not reset the GPU merely for
heat. An unsafe tuning device retains the existing attention gate.

Sweeps cool a stopped GPU below the threshold minus 3 °C before a lower-power
retry. Missing temperature or unsuccessful cooldown blocks subsequent work on
that GPU and records a persistent local stop before runtime restoration. Changed
limits invalidate incompatible resume checkpoints. Regular worker starts check
the saved temperature limit; regular mining checks every two seconds. The latter
owns the run lock briefly; active measurements own their own thermal handling.
A thermal pause follows existing persistent-stop semantics and requires an
explicit local start after resolving the cause. These checks are software
sampling, not a replacement for firmware thermal protection.

Benchmark admission now owns the shared measurement lock **before** default
configuration preparation. Failed submission and exceptions before snapshots
release it and finish the session. Guard release is idempotent, preventing a
second cleanup from unlocking another run. Baseline sampling has a 240-second
startup grace and a hard measurement deadline 120 seconds later. MINING without
a finite positive rate no longer bypasses timeout. Explicit GPU selection uses
physical inventory instead of silently intersecting a miner's saved devices;
temporary benchmark overrides still leave that saved device list intact.

**Deutsch:** Pro GPU gilt eine endliche Temperaturgrenze von 40–90 °C. Sweep und
Tuner nutzen den kleineren Wert aus Nutzergrenze und Servergrenze. Bereits das
Erreichen der Grenze löst aus; fehlende, nicht endliche oder nicht positive
Temperaturen sind unsicher. Der Tuner prüft Temperatur auch bei ungültiger
Hashrate oder Leistung. Bei einem thermischen Fehler stoppt er den Worker vor
der Wiederherstellung und führt allein wegen Hitze keinen GPU-Reset aus. Die
bestehende Aufmerksamkeitssperre für unsichere Tuning-Geräte bleibt bestehen.

Vor einem Versuch mit kleinerem Power-Limit muss eine gestoppte GPU mindestens
3 °C unter die Grenze abkühlen. Fehlende Temperatur oder erfolglose Abkühlung
sperren weitere Arbeit auf dieser GPU und speichern vor der Wiederherstellung
einen lokalen Stopp. Geänderte Grenzen verwerfen unpassende Fortsetzungsdaten.
Normale Starts prüfen die Grenze, laufendes normales Mining alle zwei Sekunden.
Diese Prüfung hält kurz die Messsperre; aktive Messläufe überwachen sich selbst.
Nach thermischem Stopp ist nach Beheben der Ursache ein ausdrücklicher lokaler
Start erforderlich. Software-Abfragen ersetzen keinen thermischen Firmware-Schutz.

Benchmarks halten die Messsperre jetzt bereits vor der Vorbereitung von
Standardkonfigurationen. Fehlende Executor-Annahme und Fehler vor der
Zustandsaufnahme beenden den Lauf und geben die Sperre frei. Die Freigabe ist
idempotent. Die Startwartezeit beträgt 240 Sekunden; spätestens weitere 120
Sekunden später endet die Messphase. MINING ohne gültige positive Hashrate
umgeht die Wartezeit nicht mehr. Ausdrücklich gewählte GPUs stammen aus dem
physischen Inventar statt einer stillen Schnittmenge mit der gespeicherten
Miner-Geräteliste. Temporäre Overrides erhalten die gespeicherte Auswahl.

## Local API and storage / Lokale API und Ablage

`WorkerBenchmarkPolicyController` serves only same-origin local routes:

| Route | Contract / Vertrag |
| --- | --- |
| `GET /api/agent/local/benchmark-policies` | Saved policy map by device ID / Gespeicherte Regeln nach Geräte-ID |
| `GET /api/agent/local/benchmark-policies/options/{id}` | Hardware/model-compatible software options / Hardware-/modellkompatible Software |
| `PUT /api/agent/local/benchmark-policies/{id}` | `{combinations:[{coin,minerSoftwareId}],allowCoinSwitch,allowMinerSwitch,maxTemperatureC}` |

**English:** Writes acquire the shared run lock and validate the current worker,
coin/software and hardware/model. Rejection never releases another measurement's
lock. Policies persist atomically in `./solarminer-agent/benchmark-policies.json`
(`solarminer.agent.benchmark-policies-file`). A failed write retains the previous
in-memory policy. An unreadable existing file fails initialization instead of
silently enabling unrestricted automatic selection; repair that file from a
known-good copy before restarting. No Node, proxy, fee, pool or central telemetry
schema changes, and no coin/ASIC support claim, are part of this change.

**Deutsch:** Schreiben hält die gemeinsame Messsperre und validiert Worker,
Coin/Software und Hardware/Modell. Eine Ablehnung gibt keine fremde Sperre frei.
Die Regeln werden atomar unter `./solarminer-agent/benchmark-policies.json`
gespeichert (`solarminer.agent.benchmark-policies-file`). Bei Schreibfehlern
bleibt die vorherige Regel im Speicher. Eine unlesbare vorhandene Datei bricht
die Initialisierung ab, statt automatische Wechsel still freizugeben; vor dem
Neustart ist sie aus einer gültigen Kopie zu reparieren. Node-, Proxy-, Fee-,
Pool- und zentrale Telemetrieverträge sowie Coin-/ASIC-Freigaben ändern sich nicht.

## Evidence / Nachweise

- Java regressions: `WorkerBenchmarkPolicyServiceTest`,
  `WorkerBenchmarkPolicyControllerTest`, `WorkerTemperatureSafetyServiceTest`,
  `WorkerCoinRevenueModeServiceTest`, `BenchmarkSessionServiceTest`,
  `AutoTuningSessionServiceTest`.
- `scripts/check-benchmark-policy-browser.cjs`: Chromium, real editor and language
  code, mocked APIs; DE/EN persistence/reload, independent switches, vendor
  filtering and explicit empty selection. No hardware or external API writes.
- `scripts/check-i18n.cjs`, `check-operations-i18n.cjs`,
  `simulate-i18n-render.cjs`, `check-dashboard-startup.cjs`.
- External miner names/output, hardware model names and device IDs remain
  verbatim / Externe Miner-Ausgaben, Hardwarebezeichnungen und Geräte-IDs
  bleiben unverändert; surrounding UI is translated / die Bedienoberfläche
  ist übersetzt.
- Live multi-GPU, Windows driver/thermal tests and real pool acceptance remain
  unverified / Reale Mehr-GPU-, Windows-/Temperatur- und Pooltests stehen aus.


## Measurement page layout / Aufbau der Messseite (2026-10-21)

**English:** The page orders configuration, power measurement, and use of results.
Selected worker cards show coin-grouped software choices and the temperature
ceiling. Operating coin/miner switching permissions are edited in Workers;
measurement saves preserve the latest permissions, including changes from
another tab. An unchanged temperature field also preserves a newer saved limit.
The page summarizes selected workers and installed combinations instead of
unloaded profile counters. Unsupported CPU-only sweeps are disabled; short
comparison benchmarks and LIVE observation remain under additional measurements.
The installed-benchmark post-start checkbox never applies to LIVE observation.

Both benchmark and sweep status must be known before enabling measurement.
Either active run locks all test inputs and all competing start actions. Start
responses update local state immediately, avoiding the window before an SSE
notification. Saves are sequential; load/validation/save failures prevent the
measurement request. Inline load retries are available. Active sweep curves and
progress are shown next to their start controls; durable comparisons remain on
the results page. Sharing status lives inside the optional-sharing panel.
Desktop and mobile keep the same order, without clipped worker editors.

**Deutsch:** Die Seite ordnet Einrichtung, Power-Messung und Nutzung der
Ergebnisse. Ausgewählte Worker-Karten zeigen nach Coin gruppierte Software und
die Temperaturgrenze. Wechselrechte für laufendes Mining werden bei Workers
bearbeitet; das Speichern eines Messlaufs erhält die aktuellen Rechte auch bei
Änderungen in einem anderen Tab. Ein unverändertes Temperaturfeld erhält eine
inzwischen neu gespeicherte Grenze. Die Übersicht zählt ausgewählte Worker und
installierte Kombinationen statt ungeladener Profile. Reine CPU-Auswahlen
können keinen GPU-Sweep starten. Kurze Vergleichsbenchmarks und LIVE-Beobachtung
stehen unter weiteren Messarten. Die Start-Checkbox des installierten Benchmarks
wirkt niemals auf eine LIVE-Beobachtung.

Messaktionen werden erst bei bekanntem Benchmark- und Sweep-Status freigegeben.
Jeder aktive Lauf sperrt Testeingaben und konkurrierende Starts. Die Startantwort
aktualisiert den lokalen Zustand unmittelbar, ohne auf SSE zu warten. Speichern
erfolgt nacheinander; Lade-, Validierungs- und Speicherfehler verhindern die
Messanfrage. Laden kann direkt an der Worker-Karte wiederholt werden. Laufende
Sweep-Kurven und Fortschritt stehen bei den Startaktionen, dauerhafte Vergleiche
auf der Ergebnisseite. Freigabestatus steht im optionalen Teilen-Bereich.
Desktop und Mobilansicht erhalten dieselbe Reihenfolge ohne abgeschnittene Editoren.

Verification / Nachweis: `scripts/check-benchmark-workflow-browser.cjs` exercises
real static pages and the live-data controller with mocked APIs: DE/EN,
worker-specific saves before starts, preservation of newer operating permissions,
legacy global-filter exclusion, save failure, LIVE without post-start, CPU-only
sweep rejection, mutual locking, actual live curve rendering, mobile navigation,
no horizontal overflow, and the shared results page. No hardware or external
writes / Keine Hardware oder externen Schreibzugriffe.

## Automatic refinement and operating points / Automatische Nachoptimierung (2026-10-21)

**English:** `POST /api/agent/local/efficiency?mode=restart|resume` accepts
`{coins,workers,maxConcurrentGpus,autoTuneAfterSweep}`. Omitted/false retains the
power-only API behavior. Status appends `optimization:{enabled,runs}` with
per-pair state, actual error/phase and candidate/sample progress. A failed or
unsupported tuner preserves a saved stable sweep; the UI reports partial
completion and the actual prerequisite. Resume can reuse complete curves even
when only refinement remains unfinished. Resume checkpoints are local to the
current process; persisted measurements survive restart, but the task queue does
not automatically resume after agent restart.

The tuner borrows the parent lock synchronously; it cannot release it or free
the GPU slot before cleanup. Parent cancellation reaches samples and startup.
The configured GPU concurrency applies across both phases (the tuner may impose
an additional lower capacity). Power settings are pinned across native-miner
restarts and bounded by user maximum and driver default. Missing/unsafe thermal
telemetry blocks tuning; unsafe GPU/restoration errors persist a manual stop.
The full workflow resets clocks to defaults before baseline curves and after
refinement. It restores earlier power limits/process states, not arbitrary
external overclocks. Existing automatic goals reevaluate after cleanup without
an old cooldown; stopped workers are not automatically opted into mining.

Curve-guided search starts from efficient, fastest and intermediate sweep points,
then probes nearby power limits with their tuned clock/memory settings. Up to nine
longer confirmation windows retain the three anchor tiers, global efficiency and
throughput winners, and additional measured trade-offs. Standalone tuning keeps
its per-tier confirmation strategy. Only stable longer `RESULT` windows qualify
as operating points: at least 32 valid samples, exact hardware,
algorithm and pinned miner version, no older than 30 days, positive thermal
evidence below the current user ceiling, and within the current power range.
Search-only, legacy profiles without thermal evidence and stale versions are
excluded. Every distinct confirmed core/memory/offset/P-state setting remains
available alongside baseline points, including points sharing a watt cap.
`MeasuredOperatingPointService` applies these exact settings; switching clocks
stops the GPU first, verifies writes and restores the preceding point on failure.
A failed stop prevents clock writes and a failed restoration prevents restart.
The two worker goals select their best allowed measured point with fresh market,
route and tariff prerequisites. This is the best confirmed tested setting,
not proof of a global optimum or of long-term pool stability.

**Deutsch:** Der gemeinsame Start-/Fortsetzen-Endpunkt nimmt zusätzlich
`autoTuneAfterSweep` an. Ohne Option bleibt die API beim reinen Sweep. Der Status
enthält unter `optimization` je Kombination Zustand, tatsächlichen Fehler bzw.
Phase sowie Kandidaten-/Messfortschritt. Fehlendes oder gescheitertes Tuning
löscht keine stabile Power-Kurve. Fortsetzen kann fertige Kurven übernehmen,
wenn nur das Tuning fehlt. Messdaten bleiben nach Neustart erhalten; die
Fortsetzungswarteschlange gilt nur für den aktuellen Agent-Prozess.

Beide Phasen halten dieselbe Messsperre und denselben GPU-Platz bis zum Ende der
Bereinigung. Abbruch erreicht auch Miner-Anlauf und Tuning-Messwerte. Die GPU-
Parallelitätsgrenze umfasst beide Phasen; die Tuner-Kapazität darf zusätzlich
begrenzen. Native Miner-Neustarts behalten den gemessenen Power-Punkt. Nutzer-
Maximum und Treiber-Standard begrenzen das Tuning. Fehlende oder zu hohe
Temperaturen verhindern Tuning; unsichere GPUs bleiben dauerhaft gestoppt.
Der vollständige Lauf setzt Takte vor der Basiskurve und nach dem Tuning auf
Standard zurück. Vorherige Power-Limits und Prozesszustände werden restauriert,
beliebige externe Übertaktungen dagegen nicht. Automatische Ziele prüfen nach
der Bereinigung ohne alten Cooldown erneut. Zuvor gestoppte Worker starten
nicht allein durch den Messlauf.

Die kurvengeführte Suche beginnt an effizienten, schnellen und mittleren Sweep-
Punkten und prüft danach benachbarte Power-Limits mit ihren getunten Takten.
Bis zu neun längere Bestätigungen erhalten die drei Ankerstufen, die globalen
Effizienz-/Hashrate-Sieger und weitere gemessene Kompromisspunkte. Das separate
Tuning behält seine Bestätigung je Leistungsstufe. Betriebsprofile benötigen mindestens 32 gültige Bestätigungsmesswerte, exakte Hardware,
Algorithmus und Miner-Version, ein Alter unter 30 Tagen sowie gültige Temperatur-
und Power-Nachweise innerhalb der aktuellen Grenzen. Reine Suchwerte, alte
Versionen und Profile ohne Temperaturnachweis bleiben ausgeschlossen. Auch
unterschiedliche Takte bei gleichem Watt-Limit bleiben eigene Kandidaten.
Betriebswechsel wenden die vollständigen Takt-/Speicher-/Offset-Einstellungen
an, stoppen vor Taktänderungen und stellen bei Fehlern den vorherigen Punkt
wieder her. Fehlerhafter Stopp verhindert Taktänderungen; misslungene
Wiederherstellung verhindert Neustarts. Effizienz und Tagesgewinn wählen den
besten erlaubten bestätigten Messpunkt mit aktuellen Markt-/Routen-/Tarifdaten.
Ein globales Optimum oder reale Langzeit-/Poolstabilität wird damit nicht belegt.

## Worker measurement visibility / Sichtbarkeit der Messung bei Workers

**English:** `MinerFactory.MeasurementContext` attributes the physical GPU to
its actual test coin, software and phase (`POWER_SWEEP`, `BENCHMARKING`,
`AUTO_TUNING`). The sweep used to omit this attribution, so general mining stats
filtered out an alternate miner's telemetry. The temporary attribution now spans
the complete curve and refinement and is removed before restoration, including
failure paths. `WorkerView.measurement` exposes it; saved coin/miner fields use
the configured selection. Workers displays the test pair, saved assignment,
phase and telemetry even when they differ. Startup gaps show the measurement
phase with a waiting message. Unrelated workers are not labelled benchmarking.
Formatted telemetry and external identifiers in shared UI builders no longer
trigger missing-translation warnings. New authored text is DE/EN; external
software names, version IDs and raw miner output remain verbatim.

**Deutsch:** Eine temporäre Messzuordnung hält tatsächlichen Test-Coin, Miner
und Phase pro GPU fest. Bisher fehlte sie im Sweep; dadurch filterte die normale
Statistik Werte einer abweichenden Software heraus. Sie gilt jetzt für die
gesamte Kurve und das Tuning und wird vor Wiederherstellung auch bei Fehlern
entfernt. `WorkerView.measurement` liefert sie zusätzlich zur unveränderten
Betriebszuordnung. Workers zeigt Testpaar, gespeicherten Coin/Miner, Phase und
Messwerte. In Anlaufpausen bleibt der Messstatus mit Wartehinweis sichtbar.
Unbeteiligte Worker erhalten keinen Benchmark-Status. Formatierte Telemetrie
und externe Bezeichnungen lösen in gemeinsamen UI-Bausteinen keine falschen
Übersetzungswarnungen mehr aus. Eigene Texte sind DE/EN; externe Namen,
Versionskennungen und Miner-Rohtexte bleiben unverändert.

Verification: focused tests in `SweepRefinementServiceTest`,
`AutoTuningSessionServiceTest`, `AutoTuningProfileStoreTest`,
`MeasuredOperatingPointServiceTest`, `WorkerCoinRevenueModeServiceTest`,
`CoinHoppingServiceTest`, `EfficiencySweepControllerTest`,
`EfficiencySweepServiceTest`, `MinerFactorySelectionTest` and
`WorkerAssignmentServiceTest`; the workflow browser fixture exercises automatic
option/progress and alternate-miner Workers visibility in DE/EN. No real GPU,
Windows driver or pool acceptance is established by these mocked tests.

Additional hardening / Zusätzliche Absicherung: Native clock changes get up to
240 seconds for valid matching hashrate, bounded by the overall tuning deadline,
with continuous cancellation and temperature checks. Known tuned live/fixed-
point observations are not persisted as cap-only baseline profiles; confirmed
clock points remain their source of operating evidence. Failed final stop or
power restoration blocks restarting affected session workers. Native settings
are recorded as exact miner launch arguments; real post-start driver behavior
still needs hardware validation.

Native Taktänderungen erhalten bis zu 240 Sekunden für gültige passende Hashrate,
begrenzt durch die Gesamtlaufzeit und laufende Abbruch-/Temperaturprüfungen.
Bekannt getunte Live-/Vergleichswerte werden nicht als reine Watt-Profile
abgelegt; Betriebsnachweise bleiben die bestätigten Taktpunkte. Misslungener
abschließender Stopp oder Power-Restore sperrt Wiederanläufe betroffener Worker.
Native Einstellungen werden als exakte Miner-Startargumente gesetzt; reales
Treiberverhalten nach dem Start ist weiterhin an Hardware zu prüfen.

## Curve-guided power refinement / Kurvengeführte Power-Nachoptimierung (2026-10-21)

**English:** `SweepTuningPlan` now receives the full baseline curve rather than
only its winning watt cap. It filters stable, positive-rate/power, temperature-
verified points against current user/driver bounds. Up to three starting power
levels cover best hashes/W, maximum hashrate (ties prefer lower measured watts),
and an intermediate non-dominated point. If fewer distinct anchors exist, measured
coverage or interior interpolation within the stable curve envelope fills the
three-tier contract. Missing safe points or a range narrower than two watts
reports its actual cause; it does not fall back to arbitrary hardware extremes.

Eighteen clock/memory search windows precede at most six power probes: up/down
steps for the best tuned efficiency, fastest and intermediate trade-off points.
Step size is half the median sweep spacing, bounded to 1–10 W. Probes preserve
exact core/memory/offset/P-state settings, stay within the measured stable envelope,
and skip already attempted settings. Baseline hash rates only select anchors;
they never become Gaussian-process observations for different tuned clocks.

The overall budget stays at 24 search plus at most nine 32-sample confirmations,
60 minutes per pair. The common finalist list covers each original anchor,
global efficient/fast winners (including neighbor caps), fastest points at the
anchors and further trade-offs. An unsupported memory control discards queued
power probes with the rejected control. Confirmation failure at an anchor still
fails completion; partial evidence and stable sweep results remain saved.
The power-refinement phase and failure reasons are translated in both frontends.

**Deutsch:** `SweepTuningPlan` übernimmt die vollständige Basiskurve. Nur stabile
Punkte mit positiver Hashrate/Leistung, gültigem Temperaturnachweis und Einhaltung
der aktuellen Nutzer-/Treibergrenzen fließen ein. Bis zu drei Anker decken beste
Hashes/W, höchste Hashrate (bei Gleichstand geringere gemessene Leistung) und
einen mittleren nicht dominierten Punkt ab. Fehlende verschiedene Anker werden
aus gemessener Abdeckung oder innerhalb des stabilen Bereichs ergänzt. Fehlende
sichere Werte oder weniger als zwei Watt Spannweite melden den konkreten Grund;
beliebige Hardware-Minima/-Maxima ersetzen keine Kurvennachweise.

Auf 18 Takt-/Speicher-Suchfenster folgen höchstens sechs Power-Prüfungen nach
oben/unten an den effizienten, schnellen und mittleren getunten Punkten. Die
Schrittweite ist halb so groß wie der mittlere Sweep-Abstand, begrenzt auf
1–10 W. Takte, Offset und P-State bleiben erhalten. Der gemessene stabile Bereich
wird eingehalten, bereits versuchte Einstellungen werden übersprungen. Basis-
Hashraten bestimmen nur die Anker und trainieren kein Modell anderer Takte.

Das Budget bleibt bei 24 Suchfenstern und höchstens neun längeren Bestätigungen
mit 32 Messwerten, insgesamt maximal 60 Minuten je Kombination. Die Finalisten
enthalten alle ursprünglichen Anker sowie effiziente/schnelle Sieger und weitere
Kompromisspunkte einschließlich neuer Watt-Limits. Fällt eine Speichersteuerung
aus, entfallen vorgemerkte Power-Versuche mit dieser Steuerung. Fehlende
Ankerbestätigung verhindert den Abschluss; Teilnachweise und stabile Sweep-
Kurven bleiben erhalten. Status und Fehler sind in beiden Oberflächen DE/EN.

**Evidence / Nachweis:** `SweepTuningPlanTest` covers anchor selection, thermal/
power/invalid-data filtering, sparse curves, two-sided probes, exact offset and
P-state retention, no repeated attempts, and bounded finalist coverage. The real
service/store regression `curveGuidedRunProbesNeighborCapsAndConfirmsAllAnchorTiersWithinTheSameLock`
uses synthetic telemetry whose optimum shifts from the 100 W anchor to 110 W;
it verifies the new 110 W operating point is independently confirmed and saved,
without releasing the sweep lock. `SweepRefinementServiceTest` verifies full-curve
handoff and safe warmup even when old best-cap metadata exceeds current bounds.
These tests do not certify real driver behavior, long-term stability or pool yield.

## Multi-GPU results and combined curves / Mehrere GPUs und gemeinsame Kurven (2026-10-21)

**English:** The measurement page retains selection independently of its worker
search and selected-only filter. One expanded editor, a bounded worker list and
at most four concurrent policy-option requests keep large GPU selections usable.
Compact per-GPU progress buttons select the detailed sweep/tuner display; GPU
and coin scope also apply to the combined curve. Filtering never silently removes
a worker from the selected measurement scope.

`GET /api/agent/local/measurements/tuning-curve?deviceId=…` is a read-only local
projection of the newest run per coin/miner on that GPU. It returns search and
confirmation outcomes plus a bounded 32-sample provisional window per setting,
not the full sample history. Closing outcomes reset their sample window. The UI
polls only the displayed GPU every five seconds while visible, cancels stale
requests and distinguishes provisional samples, search results and confirmed
points. Sweep steps optionally persist observed core/memory clocks. Legacy
profiles and unsupported readbacks retain null clocks. Readback occurs in the
sweep worker, never in the HTTP request.

The shared SVG uses power cap on X, hashes/W on the left and MHz on the right.
Its clock selector explicitly distinguishes observed clocks from absolute tuning
targets. Memory offsets remain offsets; absent readbacks are never invented from
targets. Point details retain exact settings, sample status and measured power.
A selected economic winner can be overlaid from an older confirmed profile without
claiming that its target clocks were observed. Equal caps with different miners
or settings remain distinct points. This local optional-field extension does not
change the central benchmark-sharing DTO or LAN candidate schema.

Results and Fleet use one selector per coin: efficiency maximizes gross USD/kWh;
daily profit maximizes finite net USD/day. Both independently select the best
currently usable measured miner/settings; the configured miner is not preferred.
Server eligibility includes installation, selectability and GPU-model support.
Missing tariffs remain unknown and negative profit remains negative. Fleet totals
compare both modes over the same GPU subset with usable data for both modes;
missing workers/agents are disclosed. These are read-only scenarios and do not
change operating assignments or the worker's coin/miner switching permissions.
Raw measurements remain available in a collapsed disclosure.

**Deutsch:** Suche und Filter verändern die gewählten Worker nicht. Eine geöffnete
Einstellungskarte, eine begrenzte Liste und höchstens vier parallele Optionsabfragen
halten die Auswahl vieler GPUs bedienbar. Kompakte Fortschrittsknöpfe wählen die
GPU für Sweep-/Tuner-Details und Kurven; der Coin wird ebenfalls gefiltert.

Der lokale Nur-Lese-Endpunkt liefert je GPU die neuesten Läufe pro Coin/Miner,
Such- und Bestätigungsergebnisse sowie laufende Fenster mit höchstens 32 Werten.
Die sichtbare GPU wird alle fünf Sekunden aktualisiert; veraltete Anfragen werden
abgebrochen. Sweep-Schritte speichern optional gemessene Kern-/Speichertakte.
Alte Profile oder fehlende Treiberwerte behalten unbekannte Takte. Das Diagramm
trennt gemessene Takte und absolute Sollwerte ausdrücklich; Speicher-Offsets
werden nicht zu absoluten MHz umgedeutet. Laufende Werte, Suche und bestätigte
Betriebspunkte bleiben unterscheidbar. Gleiche Watt-Limits mit anderen Minern
oder Einstellungen werden nicht zusammengeworfen. Zentrales Sharing und LAN-
Kandidatenvertrag bleiben unverändert.

Ergebnisse und Flotte wählen für jeden Coin je Modus automatisch die beste
nutzbare gemessene Software samt Einstellungen: Effizienz nach Brutto-USD/kWh,
Tagesgewinn nach Netto-USD/Tag. Installation, Auswählbarkeit und GPU-Modell werden
berücksichtigt. Fehlende Tarife werden nicht als null Kosten ausgegeben; negative
Gewinne bleiben sichtbar. Flottensummen vergleichen dieselbe vollständig
vergleichbare GPU-Teilmenge und nennen fehlende Worker/Agenten. Der Vergleich
ändert weder Worker-Zuordnungen noch erlaubte Coin-/Miner-Wechsel. Rohdaten
bleiben aufklappbar. UI, Fehler und Bedienhilfen sind DE/EN; externe Miner-Namen,
Versionskennungen und Hardware-Bezeichnungen bleiben unverändert.

### Separate telemetry and results / Getrennte Telemetrie und Ergebnisse (2026-10-21)

English: This supersedes the combined efficiency/clock graph description.
`benchmarks.html` now shows five independent telemetry plots (observed core,
observed memory, maximum temperature, measured watts, hashrate), with separate
scales and collection order per software/sweep or tuning series. The compact
curve endpoint supplies measurement-window summaries, not a raw sensor timeline.
Missing sensor values stay absent and break lines; targets/offsets are never
substituted for observed clocks. Both measurement and results pages plot raw
hashrate against measured watts. Click/keyboard details retain efficiency,
requested cap, observed clocks and separate requested settings. GPU/coin scope,
confirmation stages and exact profile highlighting remain intact.

Deutsch: Dies ersetzt die Beschreibung des gemeinsamen Effizienz-/Taktdiagramms.
Die Messseite zeigt fünf einzelne Telemetriegraphen für gemessenen Kern-/
Speichertakt, maximale Temperatur, Watt und Hashrate mit eigenen Skalen in
Messreihenfolge je Software und Sweep-/Tuning-Reihe. Die Daten sind zusammengefasste
Messfenster, keine rohe Sensorzeitreihe. Fehlende Sensorwerte unterbrechen Linien;
Sollwerte und Offsets ersetzen keine Messwerte. Mess- und Ergebnisseite zeigen
Hashrate über gemessenen Watt; Effizienz und getrennte Ist-/Sollwerte bleiben per
Klick oder Tastatur abrufbar. GPU-/Coin-Auswahl und Profilmarkierungen bleiben erhalten.

### Combined ETA / Gemeinsame Restzeitschätzung (2026-10-21)

Combined-run status now publishes numeric `secondsRemaining` instead of null.
It includes remaining sweep samples/startups and the tuning search/confirmation
budget using the configured sampling interval plus estimated startup per window.
It excludes terminal tuning rows, sums work per physical GPU and distributes it
across the requested concurrent GPU slots. It updates with candidate/sample
progress, including when sweep rows are complete but tuning continues. Adaptive
search and miner/driver waits make this approximate; the tooltip explains this
in both locales. No LAN or endpoint schema change.

Der kombinierte Lauf liefert jetzt eine numerische Restzeit für Sweep und Tuning.
Messfortschritt, Anlaufzeiten, abgeschlossene Tuning-Läufe sowie serielle Arbeit
pro GPU und die gewählte Parallelität fließen ein. Die adaptive Suche und
Miner-/Treiberwartezeiten machen dies zu einer Schätzung; beide Sprachen erklären
das im Tooltip. API-Schema und LAN-Vertrag bleiben gleich.
