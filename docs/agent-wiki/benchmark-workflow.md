# Worker benchmark workflow / Benchmark-Ablauf pro Worker

Source checked 2026-10-21. This describes local source and automated evidence,
not a deployed release or hardware/pool certification.

## Operator contract / Bedienvertrag

**English:** Power sweeps are the baseline: every selected physical GPU obtains
its own descending curve for each selected compatible coin/software pair.
Identical GPU models already use independent full curves (`targetGroups` creates
singleton groups); the legacy reference/validation helpers do not establish
current scheduling behavior. Ordinary benchmarks remain available as quicker
fixed-point measurements, including CPU/XMRig and live observation. Optional
clock/memory tuning is a second, separately initiated step in `/autotuning.html`.
Its existing confirmation/profile application remains separate from baseline
benchmark/sweep candidate ranking; this change does not introduce an automatic
sweep-to-tuner pipeline or blend tuned clock points into baseline economics.

**Deutsch:** Power Sweeps bilden die Grundlage: Jede ausgewählte physische GPU
misst für jede ausgewählte kompatible Coin-/Software-Kombination eine eigene
absteigende Kurve. Auch baugleiche GPUs bekommen bereits vollständige eigene
Kurven (`targetGroups` erzeugt Einzelgruppen). Die alten Referenz-/Validierungs-
Hilfen beschreiben nicht die aktuelle Planung. Normale Benchmarks bleiben als
schnellere Messung einzelner Betriebspunkte einschließlich CPU/XMRig und laufender
Miner verfügbar. Optionales Takt-/Speicher-Tuning ist der gesondert gestartete
zweite Schritt unter `/autotuning.html`. Dessen Bestätigung und Profilanwendung
bleiben von der wirtschaftlichen Auswahl normaler Benchmark-/Sweep-Punkte
getrennt. Eine automatische Sweep-zu-Tuner-Pipeline oder Vermischung beider
Messarten wird nicht behauptet.

**English:** `/benchmarks.html` exposes per-worker coin/software checkboxes.
Workers exposes the same preferences and saves them before its assignment
submission. An empty saved combination list explicitly excludes that worker;
a missing policy preserves legacy behavior. The global coin/worker selection
still limits the run: effective targets are the intersection of that selection,
the saved per-worker combinations, installed software, hardware compatibility,
and usable routing. Software may be selected before installation; measuring
still requires an installed binary. No binary is downloaded by saving a policy.
Model compatibility is decided by the backend, not duplicated in the editor.

**Deutsch:** `/benchmarks.html` bietet pro Worker Checkboxen für Coin und
Miner-Software. Die Worker-Seite zeigt dieselben Regeln und speichert sie vor
der Zuweisung. Eine gespeicherte leere Liste schließt den Worker ausdrücklich
aus; ohne gespeicherte Regel bleibt das bisherige Verhalten erhalten. Die
globale Coin-/Worker-Auswahl begrenzt den Lauf zusätzlich. Gemessen wird die
Schnittmenge aus Auswahl, Regeln, installierter Software, Hardwarekompatibilität
und nutzbarer Route. Software darf vor der Installation vorgemerkt werden; zum
Messen muss sie installiert sein. Das Speichern lädt keine Binary herunter.
Die Modellkompatibilität bestimmt das Backend statt einer zweiten Browserlogik.

## Goals and permissions / Ziele und Freigaben

**English:** The GPU editor presents efficiency and approximate daily profit as
the two automatic goals, alongside manual operation. Existing persisted modes
remain API-compatible; the old Auto Tuning mode can continue for saved workers,
while new tuning is reachable through the second-step tool. CPU efficiency
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
