# Worker process lifecycle / Lebenszyklus der Worker-Prozesse

Source audit and implementation: 2026-10-21. This is a source/test record, not
hardware, pool or deployment acceptance. / Quellcodeanalyse und Umsetzung vom
21.10.2026; kein Hardware-, Pool- oder Deployment-Nachweis.

## Analyse des bisherigen Verhaltens

Die Grenzen sind `WorkerController` und `ConsoleWorkerOperations` für lokale
Bedienung, `NetworkDashboardService` für ausdrückliche LAN-Bedienung und
`AgentPowerController`/`EconomicPlanningService` für Node-Automatik.
`WorkerAssignmentService` verbindet die physische Geräte-ID mit Coin, Software
und Leistungsmodus. `MiningService` verteilt übergreifende Leistungsziele und
führt Adapterwechsel aus. Die Prozesse selbst gehören XMRig (eine CPU),
Pearl/SRBMiner, dem gemeinsamen SRBMiner-GPU-Coin-Service, PeakMiner, QuanPool
und WildRig (je ein Prozess pro GPU). Pool-/Wallet-Konfigurationen können
mehrere GPUs desselben Coin-/Software-Paars gemeinsam verwenden.

Der gemeinsame SRB-Monitor fragt alle fünf Sekunden die lokale Miner-API ab.
PeakMiner, QuanPool und WildRig haben eigene API-Parser und Monitorregeln.
Jede Minerfamilie besitzt einen Watchdog mit höchstens drei Neustartversuchen.
Benchmarks, Sweeps und Auto Tuning halten zusätzlich vorübergehende Zustände,
Konfigurationen oder Leistungsziele. Diese Pfade müssen dieselbe ausdrückliche
Stopp-Absicht respektieren wie der Watchdog.

| Befund vor der Änderung | Korrektur und Implementierungsort |
| --- | --- |
| Änderungen der GPU-Liste stoppten beim gemeinsamen SRB-Service sowie PeakMiner, QuanPool und WildRig alle GPUs; die Zuweisung startete Nachbarn anschließend neu. Pearl hatte bereits einen gezielteren Auswahlpfad. | `MinerConfig.sameRoute` unterscheidet Geräteauswahl und Route. Alle fünf Adapter behalten unveränderte GPU-Prozesse; `WorkerAssignmentService.synchronizeConfiguredDevices` benötigt keinen Nachbar-Neustart mehr. |
| Dasselbe Laufobjekt wurde für neue Prozesse wiederverwendet. Alte Exit-/API-Ereignisse konnten neue Zustände überschreiben; XMRig prüfte beim Exit nur den globalen Status. | `WorkerRunState.nextAttempt` erzeugt einen eigenen Zustand je Versuch. GPU-Monitore prüfen nach HTTP-I/O den aktuellen Prozess und die Stopp-Absicht unter derselben Zustands-Sperre. XMRig bindet Exit und Poller an den gestarteten Prozess. |
| Watchdog und Ertragsoptimierung konnten beide neu starten. Wiederholte `resumeGpu`-Aufrufe konnten Neustartbudgets zurücksetzen. | Die Ertragsoptimierung bewertet nur laufende Worker. `MinerStartContext` unterscheidet Bedienung, Watchdog und gewöhnliche automatische Aufrufe. Fehler werden nur durch einen neuen Benutzerstart oder einen budgetierten Watchdog-Versuch erneut gestartet. |
| Ein Stopp erfasste vor allem lebende Adapter und benötigte eine erfolgreiche neue GPU-Erkennung. | `GpuCoinMiner.stopDevice` verwendet die im Lauf gespeicherte Geräte-ID. Der physische Stopp erfasst alle Adapter dieses Geräts, einschließlich fehlgeschlagener Läufe. Fehler eines Adapters verhindern die übrigen Stopps nicht. |
| Die manuelle Stopp-Markierung wurde erst nach einem erfolgreichen Stopp und nur bei aktiviertem Boot-Start gespeichert. | Lokale und LAN-Bedienung speichern vor dem Beenden die Stopp-Absicht unabhängig vom Boot-Schalter. `WorkerStartPolicy` gilt direkt in jedem Adapter, auch bei Messungs-Wiederherstellung. Node-Fähigkeiten und Ertragsoptimierung respektieren die Markierung. |
| Der Prozessbaum-Stopp wartete nur ungefähr 50 ms je Prozess, konnte nach dem Eltern-Exit Kinder verlieren und prüfte den Abschluss eines Force-Kill nicht zuverlässig. XMRig wartete unbegrenzt. | `ProcessTermination` erfasst Kinder vor dem Signal, verwendet gemeinsame Fristen pro Phase und wartet auch nach dem Force-Kill. XMRig und alle GPU-Adapter verwenden diese begrenzte Eskalation. |
| Mehrere Minerfamilien konnten zwischen Belegungsprüfung und Start dieselbe GPU beanspruchen. | `MinerProcessRegistry.startGpu` führt Prüfung, Start und Registrierung gemeinsam aus. Die Registrierung hält Prozess und Geräte-ID zusammen; ein verspäteter Callback entfernt keinen anderen Prozess mit wiederverwendeter PID. |
| XMRig wertete einen fremd gestarteten Prozess als erfolgreichen eigenen Start und beendete beim Stopp Prozesse anhand ihres Namens. | Ein fremder XMRig blockiert den eigenen Start mit konkreter Diagnose. Normale Adapter-Stopps beenden ausschließlich eigene Prozesse; die Beobachtung fremder Miner bleibt vorhanden. Pearl führt ebenfalls keinen namensweiten SRBMiner-Kill mehr aus. |

## Aktueller Vertrag

- **Benutzer-Stopp bleibt bestehen:** Nur ein ausdrücklicher neuer Start hebt
  ihn auf. Das Ändern der Boot-Präferenz hebt ihn nicht auf. Die vorhandene
  Einstellung `workerManualStop` wird weiterverwendet, kein zweites Kanal-Flag.
- **Speicherfehler:** Der Stopp wird trotzdem ausgeführt. Die Sperre bleibt im
  laufenden Agenten aktiv; die lokalisierte Fehlermeldung weist darauf hin,
  dass die dauerhafte Speicherung vor einem Agent-Neustart geprüft werden muss.
  Ein nicht gespeicherter Start hebt eine solche Sperre nicht auf.
- **Automatische Pause:** Node-Auswahl, Planablauf und Messungsbereinigung sind
  keine dauerhafte Benutzer-Stopp-Markierung. Normale automatische Starts
  können einen fehlerhaften Lauf nicht am Watchdog vorbei neu starten.
- **Wiederherstellung:** GPU-Versuche warten nach Fehlern mindestens 30, 60
  beziehungsweise 120 Sekunden; der 30-Sekunden-Scan kann die Wartezeit
  verlängern. Es gibt maximal drei Wiederanläufe. XMRig behält seinen
  30-Sekunden-Scan und das Dreierbudget; er respektiert jetzt auch die
  Messungssperre und eine ausdrückliche Startabsicht ohne Watt-Ziel.
- **Fehlgeschlagener Stopp:** Der Prozess bleibt referenziert, der Fehler ist
  sichtbar und die automatische Wiederherstellung bleibt gesperrt. Ein noch
  lebender Prozess wird nicht durch einen zweiten Prozess ersetzt.
- **Umfang:** Eine physische Worker-Aktion verändert nur dessen Geräte-ID.
  Coinweite oder globale Schaltflächen haben ausdrücklich einen größeren
  Umfang. Eine tatsächliche gemeinsame Pool-/Wallet-/Proxy-Änderung darf die
  betroffenen Routen weiterhin stoppen; eine reine GPU-Auswahl tut das nicht.
- **Grenzen:** Keine Änderungen an Stratum, Gebühren-, Coin-, Hardware- oder
  externen JSON-Schemas. Neue Coin-/ASIC-Integrationsschritte sind daher nicht
  anwendbar. Der Node sieht eine manuell gestoppte wirtschaftliche Fähigkeit
  als derzeit nicht steuerbar; seine Schemaform bleibt unverändert.

## English contract

An operator stop is persisted before termination, independently of boot-start
preferences, and is checked inside every CPU/GPU adapter, including benchmark
restoration. Only an explicit operator start releases it. If storage fails,
termination still proceeds and the current process keeps the stop in memory;
the UI reports that it could not persist across an agent restart. Automatic
pauses remain temporary. Automatic target renewal cannot reset or bypass crash
budgets. Miner engines alone own crash recovery; revenue selection only retunes
running workers. GPU retries use 30/60/120-second minimum delays with at most
three retries; XMRig retains its bounded 30-second scan and now observes the
measurement lock, including explicit starts without a watt target.

Every launch attempt owns its state and callbacks. Late API responses cannot
undo a stop or mutate a replacement process. GPU ownership check/spawn/register
is atomic across adapter families. Physical stops use the captured device ID,
including when driver discovery fails, and disarm failed adapters too. A failed
stop stays visible and cannot authorize a duplicate/recovery process. Device
selection edits retain sibling processes; real shared route edits still stop
the affected route. External processes are observed but are neither adopted
as successful managed starts nor killed by ordinary adapter stops.

## Verification / Verifikation

- `WorkerConfigurationIsolationTest`: all five GPU adapters retain sibling
  processes on selection edits; reject invalid routes before stopping; respect
  operator launch policy; discard API responses arriving after a stop; stop by
  captured identity during inventory loss; automatic renewals cannot refill an
  exhausted budget, while an explicit operator start can begin a new cycle.
- `WorkerLifecycleTest`: attempt isolation, stopped/failed-stop recovery gates,
  retained process references, bounded retry and backoff isolation.
- `MinerProcessRegistryTest`: two concurrent real Linux child-process launches
  for one GPU admit exactly one; another GPU stays alive and restart ownership
  is released after exit. These are `sleep` processes, not mining/hardware tests.
- `ProcessTerminationTest`: actual grace-period waiting, retained descendants,
  force completion and interruption behavior using controlled process handles.
- `XmrLifecycleTest`: external-process rejection without killing it, delayed
  exit isolation, explicit-stop recovery suppression, measurement admission and
  recovery for an explicitly started CPU without a watt target.
- `WorkerStopAuthorityTest` and `WorkerBootSettingControllerTest`: persisted and
  in-memory stop authority, restart preference isolation, continued stopping
  after adapter/storage failure and physical-device isolation.
- Full JDK 21 `sh gradlew test standaloneJar`: 421 tests in 94 suites,
  no failures/errors/skips; DE/EN catalog, both-locale page
  render, operations catalog, untranslated-literal scan and `git diff --check`.

No live mining, real pool accounting, Windows process-tree termination or GPU
driver power write was performed. Real mixed CPU/multi-GPU crash, driver-loss,
benchmark/cancel and storage-failure acceptance remains necessary on target
hosts. External miner output and arbitrary operating-system diagnostics remain
verbatim; new surrounding status/errors are translated in German and English.

Kein Live-Mining, keine Pool-Abrechnung, keine echte Windows-Prozessbeendigung
und kein GPU-Treiber-Schreibzugriff wurden ausgeführt. Die genannten
Ausfallfälle müssen zusätzlich auf Zielhardware geprüft werden. Externe
Miner-Ausgaben und beliebige Betriebssystemdiagnosen bleiben unverändert;
neue erklärende Status- und Fehlertexte sind deutsch und englisch vorhanden.


## Sweep authority / Sweep-Freigabe (2026-10-21)

An explicitly admitted power sweep may temporarily start its selected paused
workers without clearing their persisted manual stop. Admission captures
`AgentControlSettingsService.measurementStartPermit(deviceId)`; the parallel task
uses `MinerStartContext.measurement` through the shared engine start gate. This
also covers synchronous sweep refinement. The scope is thread-local, device-bound
and removed on every exit. Watchdogs, other devices and final worker restoration
receive no such authority. A new manual pause invalidates the original permit,
even if the worker was already paused or the settings write fails. Measurement
attempts may retry failed starts without becoming new operator restart cycles.

Ein ausdrücklich gestarteter Power-Sweep darf ausgewählte pausierte Worker für
den Messlauf starten. Die gespeicherte Stoppsperre bleibt bestehen. Die Freigabe
gilt nur im Task-Thread für das jeweilige Gerät einschließlich synchroner
Verfeinerung. Watchdogs, andere Geräte und Wiederherstellung erhalten sie nicht.
Ein erneuter manueller Stopp widerruft sie auch bei bereits pausiertem Worker
und bei Speicherfehlern. Nachweise: `MeasurementStartAuthorityTest`,
`EfficiencySweepServiceTest`, `WorkerStopAuthorityTest`; reale GPU-Abnahme offen.
