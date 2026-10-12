# PC-Agent REST-Controller Architektur-Audit

**Datum:** 2026-10-11 · **Scope:** `pc-agent` (Branch `beta`, Stand 41b7013 + uncommitted Phase-5d-Refactoring)
**Status:** Abgeschlossen — Audit (A–F), Refactoring R1–R8 (G), Ausnahme-Register (H), Tests (I), Re-Audit (J). Build + 175 Tests grün.
**Audit-Regel:** REST-Endpunkte lesen ausschließlich konsistente In-Memory-Snapshots oder übergeben
Commands an unabhängige Verarbeitungskomponenten. Kein direkter oder indirekter I/O im
Request-Thread und in keiner transitiven Aufrufkette eines Query-Endpunkts.

---

## A. Executive Summary

Untersucht: **15 Controller-Klassen, 106 HTTP-Endpunkte** (inkl. SSE-Subscribe und
Reverse-Proxy-Route). Alle `@RestController` im Package `de.verdox.solarminer.pcagent.controller`
inventarisiert; keine funktionalen Router, keine geerbten/programmatisch registrierten Endpunkte
gefunden (`AgentWriteAccessFilter` ist ein Filter, kein Endpunkt).

Der PC-Agent hat bereits eine teilweise konforme Push-Architektur: `AgentStatusService`
(Sektions-Cache + Dirty-Flags), `AgentEventController` (SSE mit Scheduled Publisher), sowie
Snapshot-gepflegte Services (`HardwareTelemetryService`, `NetworkDashboardService`,
`EnergyJournalService`, `LocalGpuPowerService.snapshot()`, `MarketDataService`-Netzwerk-Snapshots,
`WalletsSnapshot` im `MiningController`).

**Kernproblem:** Die Sektions-Produzenten und mehrere REST-Query-Endpunkte rufen weiterhin
Methoden auf, die im Aufrufer-Thread Netzwerk-I/O (HTTP zum Stratum-Proxy und zum
Currency-Service), Treiber-Prozesse (`nvidia-smi`/`amd-smi` über `discover()` mit 2-s-TTL) und
Dateisystem-Lesens (`Files.isRegularFile`, Config-Dateien) ausführen. Da der SSE-Publisher und
`GET /local/status` dieselben Produzenten nutzen, gilt das auch für den Aggregat-Endpunkt.

Verstöße nach Kategorie (Endpunkte, nicht Controller):

| Kategorie | Anzahl |
|---|---|
| COMPLIANT | 62 |
| DIRECT_IO (Controller-Code selbst) | 4 (Console×2, Proxy-Dashboard-Proxy, Defender-Exclusion-Loopback-Check) |
| INDIRECT_IO (transitive Kette) | 21 |
| BLOCKING (join/get/Prozess-/Netzwerk-Warten im Request-Pfad) | 12 (Übermenge mit INDIRECT_IO: 9) |
| STATE_DESIGN_ISSUE | 4 |
| COMMAND_DESIGN_ISSUE | 3 |
| REQUIRES_INVESTIGATION → im Audit aufgelöst | 0 |

**Kritischste Befunde:**

1. `GET /local/proxy` und `GET /local/overview` (und deren External-Spiegel):
   `ProxyConfigurationService.isReachable()` + `feeReady(coin)` + `ProxyFeeClient.feeTargets()`
   führen bis zu 8 parallele HTTP-Requests mit 2–3 s Timeout pro Aufruf; `overview` verdrahtet
   zusätzlich `payout-defaults` (Fee-Backend-HTTP pro Coin) und `feeTransparency.overview()`.
   Ein nicht erreichbarer Proxy macht jeden Dashboard-Request langsam — genau das, was die
   Zielarchitektur verbietet.
2. `GET /local` (`getMiningStats`) und `GET /local/power-control`: `LocalGpuPowerService.discover()`
   mit 2-s-TTL kann im Request-Thread `nvidia-smi`/`amd-smi`-Prozesse starten (`waitFor`).
3. `GET /local/fees`, `/fee-tier`, `/payout-defaults`: Fee-Backend-HTTP im Request-Pfad.
4. `MarketDataService.prices()` führt synchronen HTTP-Call (8 s Timeout) im Aufrufer-Thread aus,
   wenn der TTL abgelaufen ist; `forecasts()` triggert bei TTL-Ablauf eine neue Erfassung aus dem
   Request-Pfad heraus (Regel 2.2: Requests dürfen keine neue Datenerfassung auslösen).
5. `MinerCatalogService.state()` prüft bei jedem Lesezugriff Binärdateien auf der Platte
   (`binaryAvailable()` → `Files.isRegularFile` + Versionsdatei-Lesen) — `GET /miner-catalog`,
   `GET /miner-options` und der `miner-options`-SSE-Kanal.

Gesamtbewertung: Die Architektur ist in der richtigen Richtung entstanden (Sektions-Cache,
Dirty-Invalidierung, Background-Refresher für Telemetrie/GPU/Netzwerk), aber die
Proxy/Fee/Payout/Market-Lesezugriffe sind noch request-getrieben. Phase 3 führt dafür
Snapshot-Store-Schichten ein, ohne die API-Verträge zu ändern.

---

## B. Controller-Inventar

Legende: ✅ COMPLIANT · D DIRECT_IO · I INDIRECT_IO · B BLOCKING · S STATE_DESIGN_ISSUE · C COMMAND_DESIGN_ISSUE
Risikobewertung: hoch = externe Netzwerk-I/O im Request-Pfad; mittel = Prozess-/Datei-I/O; niedrig = dokumentierte Ausnahme.

### MiningController (`/api/agent/local`) — `controller/MiningController.java`

| # | Methode | Pfad | Klassifizierung | Risiko | Detail |
|---|---|---|---|---|---|
| 1 | GET | `/identify` | ✅ | – | C1 |
| 2 | POST | `/setPoolConfiguration` | C | mittel | C2 |
| 3 | GET | `/proxy` | I, B | **hoch** | C3 |
| 4 | GET | `/referral` | ✅ | – | C4 |
| 5 | POST | `/referral` | ✅ (Command, in-memory + invalidate) | – | C4 |
| 6 | GET | `/fees` | I, B | **hoch** | C5 |
| 7 | GET | `/fee-tier` | I, B | **hoch** | C5 |
| 8 | POST | `/proxy` | Command, lokal begrenzt (Datei + Prozess-Steuerung) | mittel | C6 |
| 9 | POST | `/proxy/mode` | Command, lokal begrenzt | mittel | C6 |
| 10 | POST | `/proxy/roll-mode` | Command, lokal begrenzt | mittel | C6 |
| 11 | POST | `/proxy/discover` | D, B | mittel | C7 |
| 12 | GET | `/proxy/gate` | ✅ (volatile Lifecycle-Flags) | – | C8 |
| 13 | GET | `/proxy/gate/log` | D (Datei-Lesen) | niedrig | C8 |
| 14 | POST | `/proxy/gate/retry` | Command, asynchron angestoßen | – | C8 |
| 15 | POST | `/{coin}/configuration` | C (Fee-Backend-HTTP via `resolve`) | mittel | C9 |
| 16 | POST | `/{coin}/download` | ✅ (startet Background-Download, gibt Start-Flag zurück) | – | C10 |
| 17 | POST | `/{coin}/miners/{minerId}/download` | ✅ | – | C10 |
| 18 | POST | `/{coin}/defender-exclusion` | D (InetAddress + PowerShell-Prozess) | mittel | C11 |
| 19 | POST | `/{coin}/remove` | Command, lokal begrenzt (Prozess + Datei) | mittel | C12 |
| 20 | POST | `/{coin}/gpus/{vendor}/{index}/resume` | Command, lokal begrenzt (Treiber-Call) | mittel | C13 |
| 21 | POST | `/{coin}/gpus/{vendor}/{index}/pause` | Command, lokal begrenzt | mittel | C13 |
| 22 | GET | `/coin` | ✅ | – | C14 |
| 23 | GET | `/miner-catalog` | I (Datei-Checks) | mittel | C15 |
| 24 | GET | `/miner-options` | I (Datei-Checks) | mittel | C15 |
| 25 | POST | `/{coin}/miner` | ✅ (in-memory + Datei-Persist, lokal) | – | C15 |
| 26 | GET | `/overview` | I, B | **hoch** | C16 |
| 27 | GET | `/payout-defaults` | I, B | **hoch** | C17 |
| 28 | GET | `/wallet-targets` | I (Datei-Lesen XMRig-Config) | niedrig | C18 |
| 29 | GET | `/wallet-snapshot` | ✅ | – | C19 |
| 30 | GET | `/wallet-balances` | ✅ | – | C19 |
| 31 | GET | `/earnings` | I (triggert Erfassung bei TTL-Ablauf) | mittel | C20 |
| 32 | GET | `/market-prices` | ✅ | – | C19 |
| 33 | POST | `/miners/{coin}/resume` | Command, lokal begrenzt | mittel | C13 |
| 34 | POST | `/miners/{coin}/pause` | Command, lokal begrenzt | mittel | C13 |
| 35 | POST | `/miners/{coin}/power-target` | Command, lokal begrenzt (Treiber) | mittel | C13 |
| 36 | POST | `/coin` | Command, lokal begrenzt | mittel | C13 |
| 37 | POST | `/setPowerTarget` | Command, lokal begrenzt | mittel | C13 |
| 38 | POST | `/increasePowerTarget` | Command, lokal begrenzt | mittel | C13 |
| 39 | POST | `/decreasePowerTarget` | Command, lokal begrenzt | mittel | C13 |
| 40 | POST | `/pause` | Command, lokal begrenzt | mittel | C13 |
| 41 | POST | `/resume` | Command, lokal begrenzt | mittel | C13 |
| 42 | GET | `` (MiningStats) | I, B (discover() → Treiber-Prozess) | mittel | C21 |

### ExternalAgentController (`/api/agent/external`) — Node-Vertrag

| # | Methode | Pfad | Klassifizierung | Detail |
|---|---|---|---|---|
| 43 | GET | `/network-snapshot` | ✅ (volatile Snapshot) | E1 |
| 44 | GET | `/identity` | ✅ | E1 |
| 45 | GET | `/status` | I, B | E2 (= #42) |
| 46 | GET | `/power-control` | I, B | E2 (= #… `gpus.discover()`) |
| 47 | GET | `/telemetry` | ✅ | E1 |
| 48 | GET | `/overview` | I, B **hoch** | E2 (= #26) |
| 49 | GET | `/proxy` | I, B **hoch** | E2 (= #3) |
| 50 | GET | `/earnings` | I | E2 (= #31) |
| 51 | GET | `/capabilities` | I (`gpus.discover()`) | E3 |
| 52 | POST | `/economic-plan` | Command, lokal begrenzt | E3 |
| 53–58 | POST | `/pause`,`/resume`,`/power-target`,`/power-target/increment`,`/power-target/decrement`,`/proxy` | Command, lokal begrenzt | E4 |
| 59 | POST | `/referral` | ✅ | E4 |
| 60 | POST | `/pool-configuration` | C | E4 (= #2) |
| 61 | POST | `/monero/configuration` | C (Fee-Backend-HTTP via `resolve`) | E4 (= #15) |
| 62 | POST | `/pearl/configuration` | C | E4 (= #15) |
| 63 | POST | `/node-assessment` | ✅ (in-memory) | E1 |

### AgentStatusController (`/api/agent/local/status…`)

| # | Methode | Pfad | Klassifizierung | Detail |
|---|---|---|---|---|
| 64 | GET | `/status` | I, B (Produzenten-Kette) | S1 |
| 65 | GET | `/status/ui` | I (via `payload("overview")`) | S1 |
| 66 | GET | `/status/coin-catalog` | ✅ (statisches Enum) | S1 |
| 67 | GET | `/status/stats` | ✅ | S1 |

### AgentEventController (`/api/agent/local/events`)

| # | Methode | Pfad | Klassifizierung | Detail |
|---|---|---|---|---|
| 68 | GET (SSE) | `` | ✅ Request-Pfad (SseEmitter sofort; Initial-Snapshots auf dediziertem Virtual-Thread-Executor); Produzenten erben die I/O-Probleme der Sektionen | S2 |

### AgentPowerController (`/api/agent/local/power-control`)

| # | Methode | Pfad | Klassifizierung | Detail |
|---|---|---|---|---|
| 69 | GET | `/identity` | ✅ | P1 |
| 70 | POST | `/identity` | ✅ (persistiert lokal, klein) | P1 |
| 71 | GET | `` (PowerStatus) | I, B (`gpus.discover()`) | P2 |
| 72 | POST | `/target` | Command, lokal begrenzt | P3 |
| 73 | GET | `/settings` | ✅ (volatile Settings) | P1 |
| 74 | POST | `/settings` | ✅ (persistiert lokal) | P1 |
| 75 | POST | `/network-sharing` | ✅ | P1 |
| 76 | POST | `/gpus/{deviceId}/limits` | Command, lokal begrenzt (Treiber); Antwort liest `status()` → `discover()` | P2 |

### WorkerController (`/api/agent/local/workers`)

| # | Methode | Pfad | Klassifizierung | Detail |
|---|---|---|---|---|
| 77 | GET | `` | I (Datei-Lesen via `getWorkerStats`: XMRig-Config, CPU-Temp-Datei) | W1 |
| 78 | POST | `/{deviceId}/assignment` | Command, lokal begrenzt (Prozess-Stop + Datei) | W1 |
| 79 | POST | `/{deviceId}/start` | Command, lokal begrenzt | W1 |
| 80 | POST | `/{deviceId}/pause` | Command, lokal begrenzt | W1 |
| 81 | POST | `/{workerId}/external-control` | I (`gpus.discover()`-Validierung) + Command lokal | W1 |

### BenchmarkController (`/api/agent/local/benchmarks`)

| # | Methode | Pfad | Klassifizierung | Detail |
|---|---|---|---|---|
| 82 | GET | `` | ✅ (volatile Session) | B1 |
| 83 | GET | `/match` | D, B (HTTP zum Benchmark-Backend) | B2 |
| 84 | POST | `` (start) | Command, startet Worker-Thread | B1 |
| 85 | POST | `/cancel` | ✅ (Cancel-Flag) | B1 |
| 86 | GET | `/sharing` | ✅ | B1 |
| 87 | POST | `/sharing` | ✅ (persistiert lokal) | B1 |
| 88 | GET | `/sharing/upload-status` | ✅ (volatile Status) | B1 |
| 89 | POST | `/sharing/retry-manual` | C (synchrone Uploads im Request-Thread) | B3 |
| 90 | POST | `/sharing/upload-manual` | C (synchrone Uploads im Request-Thread) | B3 |

### EfficiencySweepController (`/api/agent/local/efficiency`)

| # | Methode | Pfad | Klassifizierung | Detail |
|---|---|---|---|---|
| 91 | GET | `` | ✅ | F1 |
| 92 | POST | `` (start/resume) | Command, startet Worker-Thread | F1 |
| 93 | POST | `/cancel` | ✅ | F1 |
| 94 | GET | `/profiles` | ✅ (volatile Map) | F1 |

### TelemetryController, EconomicsController, NetworkDashboardController, NodeAssessmentController

| # | Methode | Pfad | Klassifizierung | Detail |
|---|---|---|---|---|
| 95 | GET | `/local/telemetry` | ✅ (Snapshot + `emptySnapshot()`-Fallback) | T1 |
| 96 | POST | `/local/telemetry/restart` | Command, lokal begrenzt (Prozess-Neustart) | T1 |
| 97 | GET | `/local/economics` | ✅ (`overviewSnapshot`) | T2 |
| 98 | POST | `/local/economics/settings` | ✅ (persistiert lokal) | T2 |
| 99 | GET | `/local/economics/rates` | ✅ (`fiatCached` volatile) | T2 |
| 100 | GET | `/local/network/agents` | ✅ (`snapshot` volatile) | T3 |
| 101 | GET | `/local/node-assessment` | ✅ (volatile Assessment) | T3 |

### MinerConsoleController, MinerOptimizationController, ProxyDashboardController

| # | Methode | Pfad | Klassifizierung | Detail |
|---|---|---|---|---|
| 102 | GET | `/local/console/{miner}` | D (Log-Tail-Lesen) | X1 (dokumentierte Ausnahme) |
| 103 | GET | `/local/console/{miner}/download` | D (Datei-Download) | X1 |
| 104 | GET | `/local/miners/{coin}/optimizations` | I (Datei-Checks RandomX/HugePages) | X2 |
| 105 | POST | `/local/miners/{coin}/optimizations/{setting}` | Command, lokal begrenzt (Config-Datei + Miner-Neustart) | X2 |
| 106 | GET | `/proxy-dashboard/**` | D, B (Reverse-Proxy, `http.send` 5 s) | X3 (dokumentierte Ausnahme) |

(Nummern 1–106; Zeilen mit Bereichsangabe fassen identische Endpunkte zusammen.)

---

## C. Einzelanalysen (Auszug der relevanten Ketten)

### C3 `GET /local/proxy` — INDIRECT_IO + BLOCKING (hoch)
`MiningController.proxy()` (MiningController.java:191–210) öffnet pro Request einen
`newVirtualThreadPerTaskExecutor` und ruft:
- `ProxyConfigurationService.isReachable()` → `httpClient.send(...)` 3 s Timeout (ProxyConfigurationService.java:196–207)
- `ProxyConfigurationService.feeReady(coin)` → `ProxyFeeClient.feeTargets()` HTTP 3 s, TTL nur 3 s (ProxyConfigurationService.java:212–227)
Ergebnis wird per `CompletableFuture::join` im Request-Thread eingesammelt. Bei nicht
erreichbarem Proxy: bis zu 3 s Latenz pro Request, wiederholt alle 3 s.

### C16 `GET /local/overview` — INDIRECT_IO + BLOCKING (hoch)
MiningController.java:576–616. Kette: `this::proxy` (C3) + `this::payoutDefaults` (C17) +
`feeTransparencyService.overview()` (C5) + `earningsForecastService.forecasts()` (C20) +
`minerCatalog.selected/options` (C15, Datei-Checks) + `savedMoneroConfiguration()` →
`XmrMinerService.savedRoute()` → `configService.readUserPoolFromConfig()` (Datei-Lesen) +
`gpuPowerService.snapshot()` (✅ korrekt) + `xmrDownloadService.status()/detail()/progress()`
(volatile Installer-State, ✅). Der Kommentar „REST uses the last driver snapshot only“ gilt nur
für GPUs, nicht für Proxy/Fee/Payout.

### C5 `GET /local/fees`, `/fee-tier` — INDIRECT_IO (hoch)
`FeeTransparencyService.overview()` (FeeTransparencyService.java:44–50): pro Coin ein
`feeClient.feeTargets(...)` HTTP 3 s, parallelisiert, `join` im Request-Thread.
`devFeeSummary()` (Zeile 62–77): ein HTTP-Call 2 s im Request-Thread.

### C17 `GET /local/payout-defaults` — INDIRECT_IO (hoch)
MiningController.java:632–646 → `PayoutDefaultsService.view()/resolve()` mit
Fee-Backend-HTTP (TTL 60 s/15 s, PayoutDefaultsService.java:36–107). Bei erstem Aufruf oder
TTL-Ablauf HTTP pro Coin im Request-Thread.

### C20 `GET /local/earnings` — INDIRECT_IO (mittel)
`MarketDataService.forecasts()` ruft `refreshIfNeeded()` (MarketDataService.java:205–224), das bei
TTL-Ablauf **aus dem Request-Pfad heraus** eine neue Erfassung auf einem Virtual Thread startet
(Non-Blocking, aber Regel 2.2: Requests dürfen keine Erfassung auslösen) und liefert den
alten Cache. `prices()` (Zeile 144–168) ist **synchron blockierend**: bei TTL-Ablauf HTTP 8 s im
Aufrufer-Thread (betrifft aktuell nur den Background-Refresh des Wallet-Snapshots, aber die
Methode ist öffentlich und wird in der SSE-Sektionskette indirekt erreichbar sein).

### C15 `GET /miner-catalog`, `/miner-options` — Datei-I/O (mittel)
`MinerCatalogService.state()` (MinerCatalogService.java:160–172) →
`xmrMiner.binaryAvailable()` (`XMRIG_DIR.resolve(...).toFile().isFile()`, XmrMinerService.java:445–448) und
`pearlMiner.binaryAvailable()` (`Files.isRegularFile` + `Files.readString(versionsdatei)`,
PearlMinerService.java:517–523). Jeder Lesezugriff = Dateisystem-Stat + Datei-Lesen.

### C21 `GET /local` + `GET /external/status` + `GET /local/power-control` — Treiber-Prozess im Request-Pfad (mittel)
`MiningService.getStats(null)` → `getWorkerStats(null)` → `gpuPowerService.discover()`
(LocalGpuPowerService.java:76–85): TTL 2 s, danach `detect()` mit `nvidia-smi`/`amd-smi`
Prozessen inkl. `waitFor` im Aufrufer-Thread. `AgentPowerController.status()` (Zeile 63–82) ruft
direkt `gpus.discover()`. Zusätzlich `XmrMinerService.getWorkerStats()` (Zeile 124–134):
`readCPUTemperature()` (Datei-/Sysfs-Lesen) und `configService.readUserPoolFromConfig()`
(JSON-Datei-Lesen) pro Stats-Aufruf.

### S1 `GET /local/status` — Aggregat erbt die Produzenten-Kette
`AgentStatusService.aggregate()` (AgentStatusService.java:197–228) startet pro Sektion einen
Virtual Thread und joined im Request-Thread. Die Produzenten `mining::overview`,
`mining::feeTier`, `mining::proxyGate` etc. enthalten die obigen I/O-Ketten. Nach deren
Snapshot-Umbau wird das Aggregat automatisch konform.

### C2/C9/C15-POST `/{coin}/configuration`, `/setPoolConfiguration` — COMMAND_DESIGN_ISSUE (mittel)
`withFeeBackendPayout()`/`withGpuCoinFeeBackendPayout()`/`setMoneroConfiguration()` rufen
`payoutDefaultsService.resolve(...)` → Fee-Backend-HTTP bis 8 s im Request-Thread, bevor
lokal konfiguriert wird. Commands dürfen externe Netzwerk-I/O nicht synchron im Request-Pfad
ausführen.

### B3 `/sharing/retry-manual`, `/sharing/upload-manual` — COMMAND_DESIGN_ISSUE (mittel)
`BenchmarkSharingService.uploadManualResultsOnce()`/`retryManualResults()` führen HTTP-Uploads
synchrone im Request-Thread aus; bei langsamem Backend hängt der Request.

### C7 `POST /proxy/discover` — DIRECT_IO (UDP-Broadcast, ~2 s)
Bewusst interaktiv (Discovery-Button). Dokumentierte Ausnahme: der Endpunkt *ist* die
Discovery-Operation (Command mit Ergebnis-Rückgabe), kein State-Read.

### X1 Console-Endpunkte, X3 Proxy-Dashboard — DIRECT_IO, dokumentierte Ausnahmen
Konsolen-Tail/Download lesen lokal geschriebene Logdateien (begrenzte Chunk-Größe 64 KiB,
kein externes System). `/proxy-dashboard/**` ist ein absichtlicher Reverse-Proxy auf den
Loopback-Port des Proxy-Kindprozesses (5 s Timeout, Pfad-Allowlist). Beide sind keine
State-Reads, sondern Streaming-/Forwarding-Ressourcen; sie bleiben mit dokumentierter
Begründung erhalten.

---

## D. Zielarchitektur

```
Query-Pfad (Request-Thread, niemals I/O):
  REST Controller ──> AgentStatusService / Query-Fassaden ──> AtomicReference<Immutable Snapshot>

Erfassungs-Pfad (unabhängig vom Request):
  @Scheduled Worker / Virtual-Thread-Executor ──> I/O-Adapter (HTTP, Treiber, Dateien)
        └──> Snapshot-Store (volatile/AtomicReference, konsistente Veröffentlichung)
  Mutationen ──> invalidate(sectionen) + Store.invalidate() ──> nächster Sweep/Refresh
```

Konkrete Zuordnung im PC-Agent:

| Datenfamilie | Store (neu/bestehend) | Erfasser | Konsumenten |
|---|---|---|---|
| Proxy-Erreichbarkeit + Fee-Ready je Coin | **ProxyHealthStore** (neu) | @Scheduled 2 s + invalidate | `GET /proxy`, `overview`, `status`-Aggregat |
| Fee-Übersicht + DevFeeSummary | **FeeSnapshotStore** (neu, in FeeTransparencyService) | @Scheduled + invalidate | `GET /fees`, `/fee-tier` |
| Payout-Defaults je Coin | PayoutDefaultsService-Cache + **prewarm-Scheduled** | @Scheduled 60 s + invalidate | `GET /payout-defaults`, Command-Pfade lesen nur Cache |
| Fiat-Rates, Coin-Preise, Netzwerk-Snapshots | MarketDataService-Caches (bestehend) | **@Scheduled statt Request-Trigger** | `forecasts()`, `prices()`, `rates()` rein lesend |
| Miner-Installationsstatus | **MinerCatalogService State-Cache** (volatile) | @Scheduled 10 s + invalidate bei download/remove | `GET /miner-catalog`, `/miner-options` |
| GPU-Kartenliste | LocalGpuPowerService `snapshot()` (bestehend) | @Scheduled 30 s + warm | alle REST-Reads; `discover()` nur noch für Mutationen |
| Worker-Stats | MiningService (nutzt snapshot() + gecachte Config/Temp) | SSE-Tick liest nur Cache | `GET /local`, `/external/status`, `/workers` |
| Wallets | MiningController.walletSnapshotCache (bestehend) | @Scheduled 60 s | `/wallet-snapshot`, `/wallet-balances`, `/market-prices` |

Commands bleiben bewusst synchron, wo sie lokal begrenzte, deterministische Operationen
ausführen (Prozess-Steuerung, Treiber-Limits, lokale Datei-Persistenz), weil der bestehende
API-Vertrag das Ergebnis als `boolean`/DTO zurückgibt und die Operationen hart lokal sind.
Netzwerkabhängige Command-Bestandteile (Fee-Backend-Auflösung, Benchmark-Upload) werden auf
Cache-Lesen bzw. Hintergrund-Ausführung umgestellt.

---

## E. Refactoring-Plan (Umsetzungs-Checkliste)

- [x] **R1 ProxyHealthStore**: Neue Klasse `mining/proxy/ProxyHealthService` — sammelt
      `reachable`, `feeReady(coin)`, `stratumListenerOnline(coin)` auf @Scheduled-Basis in einem
      `AtomicReference<Snapshot>`. `ProxyConfigurationService` behält Konfiguration + Live-Prüfung
      für Miner-Start-Gates (`miningReady` bleibt live, ist kein REST-Pfad). `MiningController.proxy()`
      liest nur noch den Store. Evidence: Unit-Test mit Fake-Client.
- [x] **R2 FeeSnapshotStore**: `FeeTransparencyService` bekommt volatile
      `overviewSnapshot`/`devFeeSummarySnapshot` + @Scheduled-Refresh + `invalidate()`;
      REST-Endpunkte lesen Snapshots. Live-HTTP nur noch im Scheduler.
- [x] **R3 PayoutDefaults**: @Scheduled-Prewarm aller Coins; `view()` bleibt Cache-Read mit
      Hintergrund-Refresh statt synchronem Fallback; Command-Pfade (`withFeeBackendPayout` etc.)
      verwenden ausschließlich den Cache (`resolveCached`) und scheitern definiert, wenn kein
      Ziel bekannt ist (Dokumentation: Verhalten bei leerem Cache).
- [x] **R4 MarketDataService**: `prices()` und `refreshIfNeeded()` zu @Scheduled-Methoden;
      öffentliche Lese-Methoden (`prices()`, `forecasts()`, `rates()`) sind rein.
- [x] **R5 MinerCatalogService**: Installationsstatus (`installed`, `downloadStatus`,
      `downloadDetail`) in volatile State-Map; @Scheduled-Refresh 10 s + invalidate bei
      Download/Remove/Select.
- [x] **R6 GPU-Snapshot für alle Reads**: `AgentPowerController.status()`,
      `EconomicPlanningService.capabilities()`, `WorkerController.workerControl()`,
      `MiningService.getWorkerStats(null)` nutzen `snapshot()` statt `discover()`.
- [x] **R7 XmrMinerService.getWorkerStats**: CPU-Temperatur aus `HardwareTelemetryService`-Cache;
      `XmrConfigService.readUserPoolFromConfig()` cached das geparste Ergebnis bis zur nächsten
      `configureXmrig()`.
- [x] **R8 Benchmark-Sharing-Uploads**: `retry-manual`/`upload-manual` akzeptieren den Auftrag
      (Status `QUEUED`) und führen den Upload auf dem bestehenden Sharing-Executor aus;
      `upload-status` liefert den Fortschritt. API bleibt DTO-kompatibel.
- [x] **R9 Console/Proxy-Dashboard/Discovery**: dokumentierte Ausnahmen (kein Code-Wechsel),
      im Bericht verankert.
- [x] **R10 Tests**: ArchUnit (Controller referenzieren keine `java.net.http`/`Files`/
      `ProcessBuilder`-Primitives außer dokumentierten Ausnahmen), Controller-Verhaltenstests
      (Proxy-Endpunkt antwortet ohne Netzwerk, Overview bei ausgefallendem Proxy schnell,
      Snapshot-Veröffentlichung atomar), Scheduler-Tests (kein Request-getriebener Refresh).
- [x] **R11 Re-Audit** sämtlicher Endpunkte + Bericht aktualisieren.

## F. Architekturentscheidungen

1. **Snapshot-Stores in den bestehenden Services statt neuer „Query-Service“-Schicht.**
   Die Services sind bereits die Owner ihrer Daten; eine zusätzliche Fassaden-Schicht würde
   Zustandskopien und Invalidierungsfehler erzeugen (verboten laut 2.3: redundante
   Zustandskopien). Der Snapshot-Store bleibt im Owner-Service, REST liest ihn direkt.
2. **Kein Command-Queue-Framework.** Bestehende Verträge liefern synchron Ergebnisse
   (`boolean`, Session-DTOs). Eine 202+Polling-Umstellung aller ~40 Mutationen bräche UI und
   Node-Vertrag ohne Sicherheitsgewinn, weil die Operationen lokal und hart begrenzt sind.
   Nur die zwei netzwerkabhängigen Command-Endpunkte (R3, R8) werden asynchron gemacht.
3. **Virtual Threads bleiben, aber nur im Scheduler/Background.** Der Request-Pfad verliert die
   `supplyAsync(...).join()`-Fan-outs, weil die Daten bereits im Speicher liegen.
4. **`discover()` bleibt für Mutationen erhalten** (Treiber-Grenzwerte müssen beim Setzen aktuell
   sein); nur Lese-Pfade wechseln auf `snapshot()`.

---

## G. Umgesetztes Refactoring (Phase 3, Stand 2026-10-11)

Alle Planpunkte R1–R8 sind implementiert; R9 ist die dokumentierte Ausnahmenliste (H), R10 die
Tests (I), R11 das Re-Audit (J).

| Ref | Umsetzung | Klassen |
|---|---|---|
| R1 | `ProxyHealthService` (neu): sammelt `reachable` + `feeReady(coin)` pro Coin auf @Scheduled (2 s, Single-Flight auf Virtual Thread) in einen `AtomicReference<Snapshot>`. `MiningController.proxy()` und `EconomicPlanningService.worker()` lesen nur noch den Store. Live-Checks (`isReachable`, `feeReady`, `miningReady`) bleiben in `ProxyConfigurationService` für Miner-Start-Gates (kein REST-Pfad). | `mining/proxy/ProxyHealthService.java`, `MiningController`, `EconomicPlanningService` |
| R2 | `FeeTransparencyService` erhält volatile `overviewSnapshot`/`devFeeSummarySnapshot` + @Scheduled-Collector (5 s, Virtual Thread) + Startup-Warm; `GET /fees` und `/fee-tier` lesen Snapshots. Vor dem ersten Durchlauf liefert `devFeeSummarySnapshot()` denselben „resolved=false“-Ladezustand, den das UI bei nicht erreichbarem Proxy bereits zeigte. | `mining/fee/FeeTransparencyService.java`, `MiningController` |
| R3 | `PayoutDefaultsService`: neue `resolveCached()` (reiner Cache-Read) + @Scheduled-Prewarm (15 s, Single-Flight, Virtual Thread) + Startup-Warm. `view()` (REST) und alle Command-Pfade (`withFeeBackendPayout`, `setMoneroConfiguration`, `WorkerAssignmentService.ensureGpuDefault`) nutzen ausschließlich den Cache. `resolve()` (live) bleibt nur für Nicht-REST-Startpfade (Pearl-Refresh, Sweep-Fallback). | `mining/wallet/PayoutDefaultsService.java`, `MiningController`, `WorkerAssignmentService` |
| R4 | `MarketDataService`: `prices()` und `forecasts()` sind rein lesend; Erfassung läuft in `refreshPricesIfNeeded()`/`refreshNetworksIfNeeded()` (@Scheduled) + `warmCaches()` bei ApplicationReady. Kein Request triggert mehr eine Erfassung. | `mining/economics/MarketDataService.java` |
| R5 | `MinerCatalogService`: Installationsstatus in volatile Flags; Probe in `refreshInstallState()` (Startup, @Scheduled 10 s, nach `download()`/`removeCoinMiner()`). Katalog-Reads sind dateifrei. | `mining/MinerCatalogService.java`, `MiningController` |
| R6 | REST-Reads auf `LocalGpuPowerService.snapshot()` umgestellt: `AgentPowerController.status()/logExternalChange`, `WorkerController.workerControl`, `EconomicPlanningService.capabilities`, `MiningService.getWorkerStats(null)`/`selectedGpus(true)`. `discover()` bleibt in Kommando-/Messpfaden (Sweep, Assignment-Validierung, Miner-Steuerung). | wie links |
| R7 | `XmrConfigService.readUserPoolFromConfig()` cached das geparste Ergebnis bis Datei-MTime-Änderung; alle fünf Schreibpfade invalidieren. `XmrMinerService.readCPUTemperature()` mit 1-s-Cache. | `xmr/XmrConfigService.java`, `xmr/XmrMinerService.java` |
| R8 | `BenchmarkSharingService.retryManualResults()`/`uploadManualResultsOnce()`: nehmen den Auftrag an (`UPLOADING`-Status, Single-Flight) und führen den HTTP-Upload auf einem Virtual Thread aus; Ergebnis über den bestehenden `benchmark-upload`-Kanal. UI-Zweig für `UPLOADING` ergänzt (i18n-Katalog-Key `uploading_benchmark_data` existiert bereits, de/en). | `mining/measurement/BenchmarkSharingService.java`, `static/js/pages/benchmarks.js` |
| X2 | `XmrMinerService.randomXStatus()` mit 5-s-Cache; Toggle invalidiert. | `xmr/XmrMinerService.java` |

Invalidierungs-Verdrahtung: `setReferral`, `configureProxy`, `configureProxyMode` rufen zusätzlich
`proxyHealth.invalidate()` und `feeTransparencyService.invalidate()`; `removeCoinMiner` ruft
`minerCatalog.refreshInstallState()`.

## H. Dokumentierte Ausnahmen (Ausnahme-Register)

Diese Endpunkte führen bewusst I/O im Request-Pfad aus; sie sind keine State-Reads, sondern
selbst die angeforderte Operation bzw. ein Forwarding-Adapter. ArchUnit-Allowlist:
`RestControllerArchitectureTest.ALLOWLIST`.

| Endpunkt | Begründung |
|---|---|
| `POST /local/proxy/discover` (C7) | UDP-Broadcast-Discovery; die Anfrage *ist* der Suchauftrag, Ergebnis wird zurückgegeben (~2 s, UI-Button). |
| `POST /local/{coin}/defender-exclusion` (C11) | Kommando mit lokalem PowerShell-Prozess; Loopback-/Origin-Guard nutzt `InetAddress`-Prüfung der Request-Adresse (kein externes Nachladen). |
| `GET /local/console/{miner}` + `/download` (X1) | Liest lokal geschriebene Miner-Logdateien in begrenzten Chunks (64 KiB) bzw. lädt sie herunter; Streaming-Ressource, kein externer Zustand. |
| `GET /proxy-dashboard/**` (X3) | Bewusster Loopback-Reverse-Proxy auf den Proxy-Kindprozess (Allowlist von 5 Pfaden, 5 s Timeout). |
| `GET /local/benchmarks/match` (B2) | Interaktive Suche gegen das zentrale Benchmark-Backend mit freien Suchschlüsseln (Hardware-Modell/Algorithmus); nicht vorab sammelbar. Kein State-Read, sondern Such-Command mit Ergebnis-Rückgabe. |
| `ProcessHandle.allProcesses()` in `MinerProcessRegistry` | Adapter-interner Blick auf eigene Prozess-Lebenszyklen (Service-Ebene, nicht Controller); Prozess-Owner bleibt der jeweilige Miner-Adapter. |

## I. Tests (Phase 5)

Neu:
- `architecture/RestControllerArchitectureTest` (ArchUnit 1.3.0): Controller referenzieren keine
  `java.net.http`-Klassen, keine `ProcessBuilder`, keine `CompletableFuture` (join-Fan-outs),
  keine `InetAddress`/Sockets — jeweils mit dokumentierter Allowlist.
- `controller/RestReadModelNonBlockingTest`:
  1. `GET /proxy` antwortet aus dem (leeren) Health-Snapshot und ruft nachweislich
     `isReachable()`/`feeReady()` **nicht** auf (Mockito `verify(never())`).
  2. `ProxyHealthService.refresh()` gegen eine tote Quelle veröffentlicht einen konsistenten,
     unveränderlichen Snapshot; wiederholte Reads liefern dieselbe Instanz.
  3. `forecasts()`/`prices()` lösen bei leerem Cache **keinen** Upstream-Call aus (HttpServer-Zähler = 0).
  4. `PayoutDefaultsService.view()` ist vor dem Prewarm rein lesend (Zähler = 0); nach `prewarm()`
     folgen aus Reads keine weiteren Upstream-Calls.
- `MarketDataServiceTest`: `prices()` nach explizitem Scheduler-Durchlauf + Nachweis der Cache-Wiederverwendung.

Angepasst (Vertragsanpassung der Cache-only-Pfade): `WorkerAssignmentServiceTest`,
`MiningControllerValidationTest` (mocken `resolveCached`), `MinerCatalogServiceTest`
(probt Installationsstatus explizit wie der Scheduler).

## J. Re-Audit (Phase 6, 2026-10-11)

Erneute Prüfung aller 15 Controller / 106 Endpunkte inkl. transitiver Ketten nach dem Refactoring:

**Query-Endpunkte (GET):** Alle State-Reads bedienen sich ausschließlich aus In-Memory-Zuständen:

| Kette (vorher → nachher) |
|---|---|
| `/proxy`, `/external/proxy`: `httpClient.send`×N → `ProxyHealthService.snapshot()` (AtomicReference) |
| `/overview`, `/external/overview`: 3× Fan-out-Join mit Fee-/Payout-/Proxy-HTTP → reine Snapshot-Komposition (`proxy()`, `payoutDefaults()`, `overviewSnapshot()`, `forecasts()`) |
| `/fees`, `/fee-tier`: HTTP pro Coin → `overviewSnapshot()`/`devFeeSummarySnapshot()` |
| `/payout-defaults`: Fee-Backend-HTTP → `resolveCached()` |
| `/earnings`, `/market-prices`: Request-getriebener Refresh → reine Cache-Reads |
| `/miner-catalog`, `/miner-options`, `miner-options`-SSE: `Files.isRegularFile` pro Read → volatile Installations-Flags |
| `/local` (Stats), `/external/status`, `/power-control`, `/external/power-control`, `/workers`, `/external/capabilities`: `discover()` (nvidia-smi/amd-smi-Prozesse) → `snapshot()`; XMRig-Config-Parse und CPU-Temp-Probe gecacht |
| `/local/miners/{coin}/optimizations`: Datei-Checks + `whoami`-Prozess → 5-s-Cache mit Toggle-Invalidierung |
| `/local/status`-Aggregat + SSE-Kanäle: erben automatisch die konformen Produzenten |

**Command-Endpunkte (POST):** Kein Command wartet mehr synchron auf externe Netzwerk-I/O:
Payout-Auflösung liest Cache (R3), Benchmark-Uploads sind Single-Flight-Hintergrundaufträge (R8).
Lokale, hart begrenzte Operationen (Prozess-Steuerung, Treiber-Limits, Datei-Persistenz) bleiben
bewusst synchron (API-Vertrag, Entscheidung F2).

**Frische/Verfügbarkeit:** Jeder Store publiziert seinen letzten erfolgreichen Durchlauf;
Fehler behalten den letzten Stand (stale) statt ihn zu löschen: `ProxyHealthService`
(`initialized`-Flag, `lastError`), `MarketDataService` (`asStale`), `FeeTransparencyService`
(Alter Snapshot bleibt), `PayoutDefaultsService` (MISSING-TTL 15 s), `MinerCatalogService`
(Probe-Fehler behält vorherige Beobachtung). Vor der ersten Kollektion liefern die Endpunkte
definierte Ladezustände (`reachable=false`, `available=false`, Forecast „loading“), nie eine
Ersatzabfrage.

**Scheduling/Worker-Lebenszyklen:** Alle neuen Kollektoren sind `@Scheduled(fixedDelay=…)` mit
Single-Flight (`AtomicBoolean`/Dirty-Flag) und führen blockierende I/O auf Virtual Threads aus,
blockieren also keinen der vier Scheduler-Threads (`spring.task.scheduling.pool.size=4`).
Kein Task akkumuliert: überlappende Ticks werden übersprungen. Shutdown folgt dem Spring-Lebenszyklus
(Virtual Threads sind daemon-artig, Executor des SSE-Publishers wird per `@PreDestroy` geschlossen).

**Verbleibende Einschränkungen / offene Risiken:**
1. `GET /local/benchmarks/match` bleibt Such-Command mit externem HTTP (Ausnahme-Register H).
2. `MinerProcessRegistry` scannt Prozesslisten (OS-Zustand) in Adaptern; bewusst belassen —
   Owner der Prozesszustände, Kosten klein, kein Netzwerk.
3. `overview`-Sektions-TTL (2 s) und Health-Refresh (2 s) sind bewusst kurz; auf sehr langsamen
   Systemen erhöht die Snapshot-Komposition die CPU-Leichtlast, nicht die Request-Latenz.
4. Frische nach Mutation: Proxy-/Fee-Änderungen werden per `invalidate()` an den Store gemeldet,
   erscheinen im UI aber erst nach dem nächsten Kollektor-Tick (≤ 5 s bei Fees, ≤ 2 s bei
   Proxy-Health). Vorher war der Wert „sofort, aber blockierend“; das ist der bewusste
   Trade-off des Audit-Ziels (F2/F3).
5. `ProcessHandle.allProcesses()`-Scans (externer-Miner-Erkennung) bleiben pro Stats-Aufruf;
   sie sind lokal und klein, könnten bei Bedarf in einen 1-s-Cache überführt werden.

**API-Kompatibilität:** Keine Drahtformate geändert. Einzige Verhaltensänderungen:
(a) `upload-manual`/`retry-manual` liefern zunächst `UPLOADING` statt des Endergebnisses
(UI angepasst, Status läuft über den bestehenden Kanal nach); (b) Lese-Endpunkte können nach
einer Konfigurationsänderung bis zu einem Kollektor-Intervaltick den alten Stand zeigen
(§J.4). Node-seitige `/external/**`-Verträge unverändert in Form und Feldern.
