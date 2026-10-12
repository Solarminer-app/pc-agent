# Refactoring-Programm: Adapter-Schichten für Pools, Wallets, Coins, Miner-Engines

**Zweck:** Maßgebliche Arbeitsanleitung für alle KI-Agenten, die am
PC-Agent-Refactoring arbeiten: was analysiert wurde, was zu tun ist, was bereits
getan ist, wie zu verifizieren ist. Fortschritt wird in den Phasen-Checklisten
(**`[x]` = erledigt + verifiziert**) und im `docs/agent-wiki/work-log.md`
festgehalten. Plan-Stand: Codebasis `41b7013` (2026-10-10).
Analysen: drei parallele Code-Audits über `mining/` (37 Services, ~6.500 LOC),
Miner-Engines (`xmr/`, `pearl/`, `miner/`) und Controller/Frontend
(16 Controller + `static/js/`).

## Auftrag (Owner, 2026-10-10)

> „Gehe das gesamte mining package durch und schaue ob es Code gibt den wir
> abstrahieren können, um die Anzahl der Services zu reduzieren und damit auch
> Logik auf dieser Ebene zu reduzieren. Wir wollen Abstraktionsschichten bauen,
> die man über Adapter implementieren kann (Pools, Wallets, Coins, Miner …).
> Das entlastet auch das Frontend. Dasselbe für die Controller."

## Harte Regeln (gelten für JEDE Phase, unverletzlich)

1. **Closed Seams:** `coin/Coin` ist die einzige Coin-Identität; Orchestrierung
   beschafft Miner ausschließlich über `miner/MinerFactory`; Lifecycle läuft über
   `miner/CoinMiner`. Coin-Literale in Services/Controllern sind Defekte — neue
   Adapter-Klassen dürfen sie nur adapter-intern haben.
   Weitere intakte Seams, die ausgebaut statt umgangen werden:
   `mining/PoolBalanceProvider`, `mining/ProxyLifecycle`, `mining/LocalRunLock`,
   `mining/MinerStopContext`.
2. **Wire-Format-Gesetz:** Enum-Wire-Namen (Coin-IDs, `MinerStatus`,
   `DownloadState.wireName()`), die `@JsonAnyGetter`-Flachfelder (`<coinId>Url`,
   `<coinId>FeeReady`) und die Feldnamen von `MinerStats`/`PowerStatus` sind
   **dreifach verankert**: statisches UI (`static/js/`), Solar-Miner-Node-Java
   (`core/.../MinerAgentController.java`, `AgentEarningsSource.java`,
   `MinerDiscoveryService.java`) und SSE-Kanalnamen. Umbenennen/Löschen nur
   additiv mit Deprecation-Fenster; **vor** jeder Endpoint-Bewegung einen
   Pinning-Test schreiben (Vorbild: `ProxyOverviewWireFormatTest` mit
   `@JsonAnyGetter`-Rückausstrahlung).
3. **`ExternalAgentController` ist der Node-Fassade-Vertrag** — er bleibt dünn
   über denselben Services; seine Wire-Felder sind eingefroren.
4. **i18n-Pflicht:** Jede neue UI-/API-Meldung DE+EN im geschlossenen Katalog;
   `node scripts/check-i18n.cjs` muss grün bleiben.
5. **Verhaltensneutralität pro Phase:** Phasen 0–2 und 4 ändern kein
   beobachtbares Verhalten. Phase 3 schließt Drifts (Pearl-Watchdog,
   Payout-Refresh) — Owner-Abnahme O2 nötig.
6. **Parallel-Agenten-Disziplin:** NEVER `git add -A`; nur eigene Pfade stageen.
   Region vor jedem `patch` neu lesen; Worklog-Tail vor Anhängen lesen.

## Analyse-Befunde (Beweislage, file:line)

### A. Duplizierte Mechanik im `mining`-Package

| Muster | Vorkommen | Ziel |
|---|---|---|
| A1 Persistenz temp→ATOMIC_MOVE→Fallback→Cleanup, 12 Stellen | `ReferralConfigurationService` 33–44 ✔umgestellt, `NodeAssessmentService` 40–51, `AgentIdentityService` 59–73, `AgentControlSettingsService` 144–160, `MiningPerformanceProfileStore` 53–68, `GpuEfficiencyStore` 69–87, `BenchmarkSharingService` 244–260, `PayoutDefaultsService` 202–220, `ProxyConfigurationService` 104–115 + 331–349 + 351–369 (dreimal in einer Klasse), `MiningService` 421–434, `MinerCatalogService` 170–180, `EnergyJournalService` 209–223 | `support/AtomicFileStore` ✔ |
| A2 Proxy-REST + Fee-Target-Parsing 4× | `FeeTierService` 39/91–97 (`POST /api/v1/fees/tier`); `FeeTransparencyService` 33/69–82 + 101–116 (zwei Kopien in einer Klasse); `PayoutDefaultsService` 45/131–166; `ProxyConfigurationService` 34/226–240 (`feeReady`); 6 eigene `HttpClient`s insgesamt (auch `ProxyDiscoveryService` 38/110–113, `ManagedProxyService` 51/181–188 + 293–301); `validPool` dupliziert: `PayoutDefaultsService` 169–179 ≡ `ProxyConfigurationService.validPoolAddress` 295–306 | `support/ProxyFeeClient` + Record `FeeTarget` |
| A3 Currency-HTTP + TTL-Cache | `FiatRateService` 30–38/47–66 ≡ `EarningsForecastService` 42/72–96/131–165 (gleicher Upstream `currency.solarminer.app`, UA-Header, Stale-Fallback); gleiches Cache-Muster `WalletBalanceService.cached` 64–76, `PayoutDefaultsService.CachedPayout` 91–107 | `support/TtlCache` bzw. `MarketDataService` |
| A4 Prozess-Terminations-Eskalation (destroy→Deadline→forcibly→Descendants) | `MinerProcessRegistry.stop` 71–88 ✔, `ManagedProxyService.stopProcess` (447–460) ✔ + `terminateOrphanedProxyProcesses` 258–284 ✔ | `support/ProcessTermination` ✔ |
| A5 Mess-Sessions (Benchmark↔Sweep) | `LocalRunLock.tryBegin/end`: `BenchmarkSessionService` 65/226 ≡ `EfficiencySweepService` 120/249; Single-Thread-Executor + volatile session + cancel-Flag: BSS 37–39/78 ≡ ESS 74–76/157–159; Sample-Schleife+Median: BSS 160–192/270–278 ≡ ESS 521–551/755–759 (Median wörtlich dupliziert); Pause/Restore: BSS 104–124/206–222 ≡ ESS 256–276/288–312 | `MeasurementRunGuard`/`SampleCollector` (Phase 3) |
| A6 Bounded-Log-Read (FileChannel-Chunk) | `MinerConsoleService.read` 70–84 ✔ ≡ `ManagedProxyService.readLog` 384–397 ✔ | `support/LogTailer` ✔ |

### B. Coin-Verzweigungen gegen die Closed-Seam-Regel

- **Port-Wiring 3×:** `ProxyConfigurationService` 46–51 (@Value je Coin) +
  59–66 (EnumMap, Kommentar 26 behauptet „the only place") vs.
  `ManagedProxyService` 77–84 + 236–242 (`--proxy.coins.<coin>.port=`) vs.
  `ProxyDiscoveryService` 41–44 + 98–102. → `ProxyPorts` als einzige Quelle;
  Bequemlichkeits-Methoden `moneroUrl()`…`quantusUrl()` 160–165 löschen
  (`coinUrl(Coin)` existiert bereits 168; Nutzer vorher grep-pen).
- **Dev-Fee doppelte Wahrheit:** `FeeTransparencyService.forCoin` 119–135
  (MONERO 1.0, RVN 0.85, ETC 0.65, DCR, QTC 2.5) ≡ `MinerCatalogService.
  definitions()` 111–145 (`developerFeePercent`). → eine Wahrheit.
- **`FeeTransparencyService.poolFor` 140–144** verzweigt Pearl/GPU/XMR → Adapter.
- **`WalletBalanceService` 56–59 + 78–85:** PEARL-On-Chain-Sonderfall umgeht die
  eigene `PoolBalanceProvider`-Seam → Adapter (Phase 4).
- **Pearl-Sonderfälle in Messdiensten:** `BenchmarkSessionService` 88–89/109–112/
  210–218 und `EfficiencySweepService` 114/119/177/595
  (`runningGpuDeviceIds`, `manuallyPausedGpuKeys`, `restoreWorkerState`,
  Pearl-Binary als GPU-Gate) → generische
  `GpuCoinMiner.captureState()/restoreState()` + Referenz-Binary-Prädikat (Phase 3).
- Kleinere: `WindowsDefenderExclusionService` 21–23 (Coin-Check → `Coin`-Prädikat),
  `MiningService` 58/279–281/371–374/505 (Legacy-Aktiv-Coin-Pfad; Worker-Coin-Pfad
  `controls.coinFor` ist der moderne), `AgentControlSettingsService.Settings.coinFor`
  49–54 (Dateiformat-Migration, vertretbar), `MinerConsoleService.file()` 97–105
  (bewusst, Kommentar), `WorkerAssignmentService` 114 (einzelne PEARL-Ausnahme).
- Vorbildlich generic (als Blaupausen): `WorkerAssignmentService`,
  `EconomicPlanningService`, `EnergyJournalService`, `EarningsForecastService`
  (COINS aus Enum 37–39), `StandaloneFeeGuard` 26, `KryptexPoolBalanceProvider`
  23–28 (Adapter-lokale Slug-Map = korrekt).

### C. Miner-Engine-Duplikate (`xmr/`, `pearl/`)

Pearl↔GpuCoin ~65–70 % Near-Copies (≈340 von ≈540 Zeilen), XMR teilt
Start/Stop/Drain/Download zusätzlich (~150 Zeilen). Belegte Paare:

| Mechanik | XmrMinerService/Xmr | PearlMinerService | GpuCoinMinerService |
|---|---|---|---|
| Start (läuft-Check→Registry→console.started→Preflight→ProcessBuilder→Status-Reset) | 199–299 | 291–353 | 269–324 |
| Output-Drain + Redaction | 254–264 | 419–434 + `redact` 670–674 | 371–379 (Redaction wortgleich Pearl 673) |
| onExit-Crash | 266–276 | 341–348 | 313–318 |
| Stop/Kill-Eskalation | `hardStopMining` 301–327 | `stopGpu` 536–565 | `stopGpu` 453–470 (Pearl 553–555 ≡ GpuCoin 464–466) |
| Monitor+Watchdog (Poll 5 s, Startup 90 s, Stall 120 s) | `fetchHashrateFromApi` 329–370 + `handleApiError` 364–370 | `monitor` 368–417 | `monitor` 381–433 — **driftet**: nur GpuCoin hat `reportedHashrate()` 435–442, `hasUsablePoolJob()` 449–451, Hashrate-Grace 444–447, `HASHRATE_STARTUP_WATCHDOG_SECONDS=300` (:56); Pearl kann bei KAWPOW falschalarmen |
| `fail()` | – | 355–366 | 363–369 |
| `updateShares` | – | 631–637 | 526–531 (exakter Duplikat) |
| Run-State-Klasse | – | `GpuRun` 75–103 | `Run` 75–93 (gleiche Felder; GpuCoin +relay/coin) |
| Download+SHA256+Entpacken | `XmrDownloadService`: Zustandsblock 26–37, retry 52–67, Download-Schleife 98–109, `extractBinary` 116–139 | – | `SrbDownloadService`: Zustandsblock 48–93 ≡, retry 58–86 (~95 % ≡), Download 239–253 ≡, `extract` 256–277 + `copyEntry` 279–295 (**sicherer**: Traversal+Symlink-Wall — die gewinnt) |
| writeConfig atomar | – | 170–183 | 238–246 (+ `LocalGpuPowerService.save` 407–418) |
| `updateProxyRoute` | `XmrConfigService` 109–135 (JSON-Rewrite) | 158–168 | 227–236 (Pearl ≡ GpuCoin Near-Copy) |
| Validierung poolUrl/proxyUrl/worker/devices | – | 207–222 | 145–167 (identische Regex; nur Wallet-Regel coin-spezifisch: prl1 :216 vs. Switch :154–160) |
| Login-Encoding | – | `encodedWorker` 531–534 (`sm1.<b64>.worker`) | `encodedLogin` 337–341 (`wallet.sm1.<b64>.worker`) |
| Default-Payout-Refresh | 185–197 | 189–205 | **fehlt komplett** (Funktionslücke durch Drift) |
| Worker-Suffix-Truncation | – | 319–320 | 287–288 (wortgleich) |
| Sweep-Override | – | 72/224–231 | 70/130–135 |
| Pause/Resume-Set, selected/eligible-Filter | – | 279–289/237–250 | 478–487/248–258 |
| Status-Aggregation + workerStats-Gerüst | – | 601–607/609–629 | 489–494/507–525 |
| Crash-Restart | `restartAfterUnexpectedExit` 103–110 (`@Scheduled` 30 s, max 3) | **keiner** | **keiner** |
| Extern-Miner-Erkennung | 432–440 (`MinerProcessRegistry.running("xmrig")`) | `hasExternalMiner` 639–643 | – |

API-Port-Schemas (bleiben engine-intern): XMR fix 1999; Pearl `12000+srbId`
(:316); GpuCoin `13000+offset+gpuId` (:286).
Bewusst NICHT zu abstrahieren: XMRig-JSON (`XmrConfigService.configureXmrig`
34–73), Thread-/TDP-Heuristik (`XmrMinerService` 372–430), HugePages
(`WindowsHugePagesService` komplett), SRB-Flag-Dialekte (`buildCommand`
343–357, `--esm 2`/`--nicehash`), GPU-Id-Mapping via `--list-devices`/PCI
(`PearlMinerService` 436–529, nur einmal vorhanden), `GpuStratumRelay` nur im
GpuCoin-Pfad.

### D. Controller-/Frontend-Ebene

- 16 Controller (15 REST + `AgentWriteAccessFilter`); `MiningController` 668 Z.,
  ~40 Endpunkte, **20 injizierte Services** (God-Controller als Symptom).
- Duplizierte Formen:
  - Start/Pause/Resume: `MiningController` 618/623/654/659/406/412,
    `WorkerController` 35/42, `AgentPowerController` 98–108,
    `ExternalAgentController` 83/86 → **9 Endpunkte, 4 Controller**.
  - Power-Target: Mining 628/639–652, Power 73, External 89–95 → 8 Endpunkte.
  - Config: Mining 244 (`/{coin}/configuration`, dispatcht intern), Mining 120
    (Legacy `setPoolConfiguration`), External 110/115, Worker 26 vs. Power 149
    → **zwei Worker-Zuweisungs-Ressourcen**.
  - Proxy: Mining (`/proxy*`) + `ProxyGateController` — derselbe Service, zwei Controller.
  - Messläufe: `BenchmarkController` ≡ `EfficiencySweepController`
    (GET status / POST start / POST cancel).
- `AgentEventController` ruft Controller-Methoden direkt als Produzenten
  (`mining::overview`, `power::status`, `gate::gate` …) — Controller sind
  faktisch Services; 13 deklarierte SSE-Kanäle; Kanal `overview` recomputet
  `mining::overview()` 1×/s (GPU-Discovery + Forecasts).
- Nur wegen Service-Fragmentierung vorhanden: `ExternalAgentController`
  (Node-Fassade, bleibt), `ProxyGateController`, `MiningOptimizationController`
  (CPU-Spezifik → gehört als Capability in den Adapter), `WorkerController`,
  `EnergyController`+`FiatRateController` (Mikro-Read-Models).
- Frontend-Polling: `benchmarks.js:409` 1 s-Zyklus → **3 Fetch/s**
  (`/benchmarks` :70, `/benchmarks/sharing/upload-status` :116, `/efficiency` :224);
  `workers.js:238` Console-Modal 1 Fetch/s; `proxy-gate.js:111/115` ~1,3/s;
  `telemetry.js:109` 3 s; `hardware.js:207` 5 s; `software.js:103` 5 s;
  `workers.js:349` 3 parallele Fetches/5 s; `proxy.js:79` volles `/overview`
  alle 12 s; Overview-Seite abonniert 5+3 Kanäle; nach jeder Mutation
  `refreshAll()` = 8 parallele Fetches (`live-data.js:136`).
- UI-seitiges Coin-Branching hartkodiert: `workers.js:79,165,173`
  (Ticker-/Algorithmus-Maps), `wallets.js:7` (gpuCoins-Set), `wallets.js:13–18`
  (liest AnyGetter `proxy[coin.id+'Url']`), `software.js:63/68`
  (`/{coin}/remove`, `/{coin}/miners/{id}/download`), `overview.js:29`
  (`'ERROR'`-Stringvergleich), `overview.js:51` (`settings.workerCoins['*']`).
- Node-seitig gekoppelt (Verträge, eingefroren): `/external/status` →
  `MinerStats` ist als Record **doppelt definiert** (pc-agent `dto/MinerStats` +
  Node `miner/data/MinerStats`; Feldnamen 1:1: `miningStatus`,
  `powerTargetWatts`, `min/default/maxPowerTarget`,
  `approximatedPowerUsageWatts`, `terahashPerSecond`, `workers[].deviceId/
  hardwareType/currentAlgorithm/acceptedShares/rejectedShares`, `pools`,
  `PoolTelemetry`); `/external/power-control` (Node 198–215: `minPowerWatts`,
  `maxPowerWatts`, `currentTargetWatts`, `currentUsageWatts`,
  `defaultPowerWatts`, `dynamicPowerScalingEnabled`, `externalControlEnabled`,
  `Identity.powerControlProtocolVersion`); `/external/proxy` (Node 110/125/
  136–146: **AnyGetter `moneroUrl`/`pearlUrl`** + `mode/host/reachable`);
  `/external/overview` (Node 155–190: `moneroConfiguration`/`pearlConfiguration`
  mit `poolUrl/wallet/worker/devices`); `/external/telemetry` (Node 249–268:
  `metrics{key}.available/value/unit=="°C"`-Heuristik); POSTs:
  pause/resume/power-target(+increment/decrement)/proxy/referral/
  pool-configuration/monero|pearl configuration/economic-plan/node-assessment.

## Zielarchitektur

```
coin/Coin · miner/CoinMiner · miner/MinerFactory          [unverändert]
miner/engine/MinerEngineAdapter + AbstractMinerEngine     [NEU]
   Template: startProcess, drainOutput(+redact-Hook), monitorLoop
   (parseTelemetry→TelemetrySnapshot, WatchdogPolicy), stopProcess, onExit
   Hooks: buildCommand, apiPort, encodeLogin, watchdog(), restartPolicy()
   Engines: XmrigEngine · SrbGpuEngine (shared coins) · PearlEngine
miner/engine/MinerBinaryInstaller                         [NEU]
   downloadWithSha256, extract (Traversal/Symlink-Wall), atomarer Move,
   DownloadState/retry()/WindowsAntivirusBlock; Hooks: releaseQuery, installLayout
miner/engine/RouteValidator + WalletRule                  [NEU]
support/AtomicFileStore ✔ · ProcessTermination ✔ · LogTailer ✔
support/ProxyFeeClient + FeeTarget                        [NEU]
support/TtlCache · MarketDataService                      [NEU]
mining/PoolBalanceProvider (+ OnChainPearlAdapter)        [Phase 4]
GET /api/agent/status (Aggregat, Sektionen mit stale-Flag) + SSE-Kanal `status` [Phase 5]
```

Aggregat-Read-Model: Sektionen `overview/settings/assessment/telemetry/energy/
proxyGate/feeTier/wallets`, je `{payload, generatedAt, stale}`; SSE-Kanal
`status` additiv zu den alten Kanälen. Abgeleitete UI-Logik
(`renderStatusStrip`/`renderAlerts` `overview.js:13–66`, Coin-Zusammenzählungen
`workers.js:51–53`, `automation.js:17–24`) wird zu Backend-Feldern
`alerts[]`/`summary{}` → entfernt pro-Coin-Verzweigungen aus dem JS.

Konsolidierte REST-Fläche (alt bleibt als Deprecated-Forwarding; Pinning-Tests zuerst):

```
GET  /api/agent/status
GET  /api/agent/miners/{coin}                    Status+Config+Katalog
POST /api/agent/miners/{coin}/actions            {start|pause|resume|switch}
PUT  /api/agent/miners/{coin}/configuration      (ersetzt /{coin}/configuration,
                                                 setPoolConfiguration, monero/pearl-Sonderpfade)
POST /api/agent/miners/{coin}/devices/{vendor}/{index}/actions
POST /api/agent/miners/{coin}/binary             {minerId} | {remove:true}
GET/PUT /api/agent/workers/{deviceId}            (merged WorkerController + power-control/workers/*)
POST /api/agent/workers/{deviceId}/actions       {start|pause}
GET/PUT /api/agent/power
GET/PUT /api/agent/proxy                         (+ /discover, /gate)
GET  /api/agent/measurement + /actions           (benchmark+sweep, ein Session-Konzept)
GET/PUT /api/agent/economics                     (energy+fiat+fees+forecasts)
```

Fallende Controller: `WorkerController`, `ProxyGateController`,
`MiningOptimizationController` (→ Adapter-Capability), `EnergyController` +
`FiatRateController` (→ Economics). Fallende Services (phasenweise):
`ProxyStartupService` (→ ProxyConfigurationService), `StandaloneFeeGuard`
(→ FeeTierService), `FiatRateService`+`EarningsForecastService` (→
MarketDataService), `XmrDownloadService`+`SrbDownloadService` (→ Installer),
`FeeTransparencyService`-Parsing (→ ProxyFeeClient). Erwarteter LOC-Effekt:
≈450–550 Zeilen Kopie verschwinden; ein neuer SRB-Coin = ein `Coin`-Eintrag +
eine `WalletRule` + ggf. Dialekt-Flags in `buildCommand`.

## Phasen-Checkliste (Fortschritt HIER pflegen)

### Phase 0 — Leaf-Helfer, verhaltensneutral — ✔ teilweise erledigt

- [x] `support/AtomicFileStore` (writeText mit Temp-Präfix, readText, fail-soft)
- [x] `support/AtomicFileStore.writeJson` (Mapper-Version, gleiches Fail-soft)
- [x] `support/ProcessTermination` (Shared-Deadline-Eskalation inkl. Descendants)
- [x] `support/LogTailer` (bounded FileChannel-Chunk-Read)
- [x] Umgestellt: `ReferralConfigurationService.set`; `MinerProcessRegistry.stop`;
      `ManagedProxyService.terminateOrphanedProxyProcesses` + `stopProcess` +
      `readLog`; `MinerConsoleService.read` (Semantik 1:1, Graces 3 s/5 s wie vorher)
- [x] Alle restlichen Persistenzblöcke umgestellt (2026-10-10):
      `NodeAssessmentService` · `AgentIdentityService` ·
      `AgentControlSettingsService` · `MiningPerformanceProfileStore` ·
      `GpuEfficiencyStore` · `BenchmarkSharingService` (wirft weiter
      `IllegalStateException` bei Fehler) · `PayoutDefaultsService`
      (Pretty-JSON jetzt via writeValueAsString + writeText, Präfix `.tmp` wie vorher) ·
      `ProxyConfigurationService` (configure + writeRollMode + writeMode) ·
      `MiningService.persistSelectedCoin` + `MinerCatalogService.persistSelections`
      (werfen weiter `IOException`, Aufrufer-Handling unverändert) ·
      `EnergyJournalService.writeAtomic`
- [x] VERIFIKATION Batch 2 (2026-10-10): Compile HEAD+allen 15 umgestellten
      Dateien + `support/` (javac `-parameters`) OK; JUnit-Console über
      `mining`+`controller`+`pearl`: **127/127 grün**.
- [ ] Optional `support/TtlCache` für `WalletBalanceService` 64–76 +
      `PayoutDefaultsService` 91–107

### Phase 1 — ProxyFeeClient + eine Port-/Fee-Wahrheit — teilweise erledigt

- [x] `support/ProxyFeeClient` + Record `FeeTarget` (2026-10-10): stateless,
      Verbraucher erzeugen ihn aus ihrem vorhandenen ObjectMapper
      (Konstruktor-Signaturen/Tesfixtures unverändert); Methoden `feeTargets`
      (fail-closed auf leere Liste), `parseTargets`, `pushTier`,
      `validPoolAddress`. Umgestellt: `FeeTransparencyService` (beide Kopien),
      `PayoutDefaultsService.fetch/toPayout`, `FeeTierService.pushTier`,
      `ProxyConfigurationService.feeReady`. Verifikation: Compile +
      **127/127 Tests grün** (mining/controller/pearl).
      Hinweis: `ProxyConfigurationService.feeReady` sendet jetzt zusätzlich
      `tier` NICHT (wie vorher) und ignoriert Tier — Verhalten identisch;
      `httpClient` bleibt dort für `/api/network/ip` + `/api/dashboard` in
      Nutzung (Phase-1-Rest, siehe unten).
- [x] `validPool`-Duplikat aufgelöst (eine Stelle:
      `ProxyFeeClient.validPoolAddress`; beide ehemaligen Kopien leiten durch)
- [x] `ProxyPorts`-Record (2026-10-10, Sitzung 2): `mining/ProxyPorts` ist die
      einzige Coin→Port-Wahrheit (eine @Value-Quelle, EnumMap im Record);
      `ProxyConfigurationService`, `ManagedProxyService` (inkl.
      `--proxy.coins.<coin>.port=` jetzt generisch über `Coin.miningCoins()`) und
      `ProxyDiscoveryService` konsumieren nur noch dieses eine Objekt.
      `moneroUrl()`…`quantusUrl()` gelöscht; alle Nutzer umgestellt:
      `MiningController` (2×), `XmrMinerService` (3×), Tests
      (`MiningControllerValidationTest` → `coinUrl(Coin.MONERO)`). Wire-Felder
      unverändert: AnyGetter-Flachfelder laufen über `ProxyOverview.coinRoutes`
      (`ProxyOverviewWireFormatTest` bleibt grün), `ProxyCandidate`-Felder unverändert.
- [x] Dev-Fee-Zahlen (2026-10-10, Sitzung 2): `FeeTransparencyService`-Switch
      gelöscht; `MinerCatalogService.MinerOption` trägt zusätzlich
      `developerFeeLabel`/`developerFeeSource` (additiv zu
      `developerFeePercent`) und ist damit die einzige Quelle inkl. Label und
      Quelle; DECRED bleibt `known=false`. Neue deutsche Agent-Texte sind im
      `agentGerman`-Katalog übersetzt (check-i18n grün).
      Hinweis: MINER-Fee zeigt jetzt die Gebühr des AUSGEWÄHLTEN Miners
      (vorher hart je Coin) — bewusste Vereinheitlichung, Werte identisch,
      solange xmrig/srbminer-multi ausgewählt ist.
- [x] Merges (2026-10-10, Sitzung 2): `ProxyStartupService` gelöscht, sein
      @EventListener-Delegat (`configureInitialProxy`) lebt in
      `ProxyConfigurationService`; `StandaloneFeeGuard` gelöscht, die Guard-Logik
      läuft als `FeeTierService.enforceFeeRoute()` (@Scheduled 10 s wie vorher;
      MinerFactory über `ObjectProvider` lazy — sonst Bean-Zyklus
      FeeTierService→MinerFactory→Xmr→Payout→FeeTierService).
- [x] Optional: `FiatRateService`+`EarningsForecastService`→`MarketDataService`
      (2026-10-10, Sitzung 3): Ein Service `mining/MarketDataService` über dem
      gleichen Upstream (exchange-rates 1 h TTL + mining-networks/coin-prices
      10 min TTL, jeweils gleiches Stale-Fallback-Verhalten wie vorher). Die
      beiden alten Services sind gelöscht; `FiatRateController`,
      `MiningController`, `ExternalAgentController` und
      `NetworkDashboardService` konsumieren den einen Service. Wire-Felder
      (`Forecast`, `Snapshot`) feldgleich übernommen; REST-Routen unverändert.
      Tests umbenannt zu `MarketDataServiceTest`/`MarketDataServiceFiatTest`.

### Phase 2 — MinerBinaryInstaller + RouteValidator/WalletRule — ✔ erledigt (2026-10-11, Sitzung 4)

- [x] `MinerBinaryInstaller` (2026-10-11, `miner/engine/`): gemeinsamer
      Zustandsblock (DownloadState/detail/progress = Wire-Felder unverändert),
      `retry()` mit `WindowsAntivirusBlock`, `downloadAndVerify()`
      (DigestInputStream + Größenkappung + exakter Größen-/SHA-256-Vergleich),
      Zip+tar.gz mit SRBs Traversal/Symlink-Wall (279–295 — die sicherere
      Implementierung gewinnt, jetzt auch für XMRig), `installBinary()`
      (Temp + setExecutable + atomarer Move mit Fallback, Leer-Binary-Wall).
      Hooks: `binaryAvailable()`, `install()` (Release-Query + Install-Layout),
      `removeInstalled()`, `installerName()`, `downloadThreadName()`.
      Umgestellt: `XmrDownloadService` (152→~100 Z.) und `SrbDownloadService`
      (313→~180 Z.; Version/Manifest via `AtomicFileStore`).
      Nebeneffekte (Sicherheit, keine normalen Releasefälle): XMRig jetzt mit
      1-GiB-Entpackkappung + Link-Wall; SRB-Version/Manifest nutzen jetzt
      überall REPLACE_EXISTING.
- [x] `RouteValidator` (identische Pool-/Proxy-/Worker-Regex je einmal) +
      `WalletRule`-Strategie je Coin über `WalletRule.forCoin(Coin)`
      (prl1, base58-RVN, 0x-ETC, base58-DCR, qz-QNS); RVN/DCR-Base58-Helfer
      nach `WalletRule.RouteValidation` verlagert. Beide `validate()`s leiten
      durch, Fehlermeldungen wortgleich (Wire-/UI-Texte). Pinning-Test
      `miner/engine/RouteValidatorTest`.
- [x] `updateProxyRoute`-Near-Copies (Pearl ≡ GpuCoin) auf
      `RouteValidator.withProxyRoute()`; `updateShares` (Pearl ≡ GpuCoin,
      wortgleich) als `MinerShareTelemetry.applySrbShares(pool, ShareSink)` —
      `GpuRun`/`Run` implementieren `ShareSink` (Phase-3-Vorbereitung).
- [x] `writeConfig`-Kopien → `AtomicFileStore`: `PearlMinerService`,
      `GpuCoinMinerService` (werfen weiter `IOException`; neue EN-Meldungen im
      `agent`-Katalog) und `LocalGpuPowerService.save` (fail-soft wie vorher).
- [x] VERIFIKATION Phase 2 (2026-10-11): `sh gradlew test --offline`
      **144/144 grün** (4 Klassen mehr als Referenz 140: Network-Dashboard-WIP
      anderer Agenten + RouteValidatorTest), `check-i18n.cjs` grün,
      `scan-i18n-literals.cjs` 0, `git diff --check` sauber. Unvercommitet.

### Phase 3 — AbstractMinerEngine (VERHALTENSÄNDERND, Owner-Abnahme O2!) — ✔ Code erledigt (2026-10-11, Sitzung 7; nur Live-Hardware-Gate offen)

- [x] `miner/engine/WorkerRunState` (2026-10-11): vereinheitlicht
      `GpuRun` (Pearl) und `Run` (GpuCoin) — ein Satz Felder, optionales
      `relay`-Slot (typisiert `GpuStratumRelay`), `restartAttempts`-Budget,
      `ShareSink` integriert; Pearl-Map-Key bleibt `vendor:index`,
      GpuCoin-Key `coin:vendor:index`.
- [x] `miner/engine/AbstractMinerEngine` + `SrbMonitorPolicy`:
      `startOutputDrain` (Redaction-Hook als Parameter — Pearl zeigte früher
      Rohzeilen, GpuCoins redigierte; bleibt pro Engine erhalten),
      `monitorLoop` (Poll 5 s; Watchdog-Zahlen/Detailtexte pro Engine via
      Policy-Record), `stopProcess` (5 s/5 s-Eskalation; Pearl-Dual-Console
      vs. GpuCoin-Einzelzeile per Flag), `unexpectedExit`,
      `reportedHashrate`/`displayedHashrate`/`hasUsablePoolJob` zentral.
      Reihenfolge wie geplant: erst `GpuCoinMinerService`, dann
      `PearlMinerService` umgestellt. Bewusst engine-intern geblieben:
      `buildCommand`, `mappedGpuId`/PCI-Mapping, `encodedLogin`/`encodedWorker`,
      Status-Aggregation, Pause-Buchführung, GpuStratumRelay nur GpuCoin.
      Pearl verliert den `outputThread.join(1000)` NICHT — er bleibt vor
      onExit-Diagnose erhalten.
- [x] Drifts bewusst festgeschrieben (O1/O2, Owner-Freigabe 2026-10-11):
      **O1** Pearl + GPU-Coins erhalten `restartAfterUnexpectedExit`
      (@Scheduled 30 s, max 3/Worker, `LocalRunLock.busy()`-Sperre während
      Messläufen; Budget-Auffüllung bei Operator-Start/Stop/Resume wie XMRig);
      **O2a** Pearl-Monitor übernimmt GPU-Coin-Usable-Job-Regel
      (KAWPOW-Falschalarme behoben), `reportedHashrate`-Fallback
      (gpu.total→1m→1min) und 20-s-Hashrate-Grace; Pearl behält
      Job-Freshness-Stall-Uhr, Job-basierte Pool-Health, EN-Detailtexte und
      die 90-s-/120-s-Zahlen (kein Hashrate-Abort — `hashrateStartupSeconds=0`).
      **O2b** `refreshDefaultPayout(coin)` für GPU-Coins (nur bei
      Fee-Backend-Standardziel; `validate`-Schutz vor defektem Haus-Ziel;
      Konsolenzeile „Payout updated …" wie Pearl/XMR).
      Bewusste Nebenfolge O2a: Pearl-`poolHealthy` nutzt jetzt die
      hashrate-rettbare Job-Regel (vorher streng `last_job_received<120`).
- [x] `GpuCoinMiner.captureState()/restoreState()` (`WorkerPauseSnapshot`) +
      Referenz-Binary-Prädikat `MinerFactory.srbBinaryAvailable()/
      srbExternalMinerRunning()` → Pearl-Sonderfälle in
      `BenchmarkSessionService` (generischer Snapshot-Loop über alle
      GPU-Adapter) und `EfficiencySweepService` (Pearl-Capture/-Restore +
      Binary-/External-Gate über die Factory) gelöscht.
      **Nebenbei gefixt:** `SrbGpuCoinView.runningGpuDeviceIds()` lieferte
      `vendor:index`-Keys statt Device-Ids — `restoreWorkerState` konnte sie
      nie matchen; liefert jetzt die im Interface zugesicherten Device-Ids.
      Tests `MinerEngineDriftTest` (O1-Budget/-Sperre, O2b-Refresh inkl.
      Operator-Schutz, Policy-Zahlen) + `GpuCoinMinerServiceTest` angepasst.
- [x] `restartAfterUnexpectedExit` für XMR auf gemeinsame Basis ziehen
      (2026-10-11, Sitzung 7): XMR-Watchdog nutzt `restartBudgetLeft(...)` +
      `MAX_CRASH_RESTARTS` aus `AbstractMinerEngine` (Verhalten wortgleich,
      Konsole „XMRig watchdog restart n/3“ unverändert).
- [x] `XmrMinerService` als dritter Engine-Adapter (2026-10-11, Sitzung 7):
      Basisklasse geteilt — `AbstractMinerEngine` trägt jetzt nur noch das
      Miner-Unabhängige (console/mapper/logger-Zugreifer, `drainProcessOutput`,
      Restart-Budget); SRB-Mechanik (monitorLoop, stopProcess, Hasrate-Helfer,
      `startOutputDrain`) lebt in `AbstractSrbMinerEngine` um. XMR nutzt die
      gemeinsame Drain-Schleife (virtueller Thread statt Cached-Pool-Task;
      onExit wartet 1 s via `join` wie vorher) und die gemeinsamen Accessoren.
      JSON-Config, Thread-/TDP-Heuristik, API-Polling bleiben XMR-intern
      (Absicht laut Analyse C).
- [x] `MeasurementRunGuard`/`SampleCollector` (A5) extrahieren
      (2026-10-11, Sitzung 6): `mining/MeasurementRunGuard` (Single-Thread-Executor
      + Cancel-Flag + `LocalRunLock`-Buchführung, Thread-Namen unverändert) und
      `mining/SampleCollector` (Median + Valid-Hashrate-Regel) — beide Messdienste
      nutzen sie; `withExternalControl`-Gate läuft über `guard.lockBusy()`.
      Pinning-Test `MeasurementRunGuardTest` (153/153 grün).
- [ ] `XmrMinerService` als dritter Engine-Adapter (zuletzt; JSON/Threads/
      HugePages bleiben XMR-intern)
- [ ] Live-Rollout-Gate: Miner-Start/Stop an echter Hardware verifizieren —
      Build allein ist kein Support-Nachweis. **O1/O2 sind am Rechner nicht
      abgenommen worden** (kein Miner-Start auf echter HW in dieser Sitzung).

### Phase 4 — Wallet-Balance-Adapter — ✔ erledigt (2026-10-11, Sitzung 8)

- [x] PEARL-On-Chain (`WalletBalanceService` 56–59/78–85) als
      `PoolBalanceProvider`-Implementierung; Service dann vollständig generic
      (2026-10-11, Sitzung 8): `mining/PearlChainBalanceProvider` (id
      `pearl-chain`, `supportsOnChain(pearl)`, parsePearlBalance dorthin
      verlagert; `parsePearlBalance`-Seam im Service bleibt). Seam erweitert um
      `supportsOnChain`/`onChainBalance` (default false/UnsupportedOperation —
      Kryptex-Adapter unverändert). Coordinator sucht jetzt generisch pro Ziel
      einen Pool- UND einen Chain-Provider; Coin-Literal im Service gelöscht.
      Wire-Felder (`poolStatus`/`onChainStatus`-Werte) unverändert.
      Verifikation: `sh gradlew test --offline` 154/154 grün.

### Phase 5 — Controller/Read-Model/Frontend (Pinning-Tests ZUERST!) — ✔ erledigt (2026-10-11, Sitzung 9; nur Live-Hardware-Gate offen)

- [x] Pinning-Tests für alle Wire-Felder (AnyGetter-Flachfelder, `MinerStats`,
      `MinerStatus`, `DownloadState.wireName`, PowerStatus) schreiben
      (2026-10-11, Sitzung 8): `controller/AgentWireFormatPinningTest` —
      vollständige Feldmengen von `MinerStats` (+Worker/PoolTelemetry/
      MinerIdentity), `MinerStatus`-Enum, `DownloadState.wireName`,
      `PowerStatus`/`GpuStatus`/`Identity` und `AgentOverview`
      (`moneroConfiguration`/`pearlConfiguration`-Sektionen).
      Nebenbefund: `singleAlgorithmMined` ist ein De-facto-Wire-Feld
      (Record-Getter wird serialisiert; im Repo ungenutzt, Node parst
      lenient) — jetzt bewusst gepinnt. AnyGetter-Flachfelder sind bereits
      durch `ProxyOverviewWireFormatTest` abgedeckt.
- [x] REST-Fläche oben einführen; alte Routen als Deprecated-Forwarding
      (2026-10-11, Sitzung 8, gemäß O3 ohne Forwarding geplant): NEU additiv
      `GET /api/agent/local/status` (Aggregat), `/status/ui` (alerts/summary),
      `/status/coin-catalog` (Closed-Enum-Katalog für UI-Coin-Rendering).
      Alte lokale Routen bleiben bis zum vollständigen UI-Umstieg stehen und
      werden dann gelöscht (O3) — REST-Flächen-Merges der Controller folgen
      in 5d, UI-Routenwechsel dort in einem Schritt.
- [x] Aggregat `GET /api/agent/status` + SSE-Kanal `status` (additiv)
      (2026-10-11, Sitzung 8): `controller/AgentStatusService` sammelt alle
      Dashboard-Sektionen (overview/settings/telemetry/proxy-gate/fee-tier/
      energy/node-assessment/wallets/coin-catalog) mit historischem Takt und
      liefert sie je einmal pro Tick — die Einzelkanäle im
      `AgentEventController` lesen jetzt denselben Cache statt jeweils neu zu
      berechnen. NEUE Kanäle `status` (Aggregat mit generatedAt/stale pro
      Sektion) und `ui` (alerts[]/summary abgeleitet im Backend, existierende
      i18n-Keys). `overview`-Kanal von 1 s auf 2 s gedrosselt;
      `spring.task.scheduling.pool.size=4` (Ein-Thread-Scheduler war die
      Ursache der Tomcat-„non-recycled request"-Stürme und des trägen
      Frontends: ein langsamer Kanal blockierte alle).
- [x] `alerts[]`/`summary{}`-Felder liefern; UI-Coin-Maps löschen
      (2026-10-11, Sitzung 8): `overview.js` rendert die Backend-Alerts
      statt eigener Regeln; `workers.js` (Ticker-Chips, Editor-Optionen,
      Algorithmus-Map) und `wallets.js` (gpuCoins-Set) lesen den
      Coin-Katalog vom Backend (`/status/coin-catalog`, Closed Enum Coin).
- [x] Polling senken: benchmarks 3 Fetch/s → SSE; `refreshAll()` →
      gezieltes `refresh(section)`; `overview`-Kanal-Neuberechnung drosseln
      (2026-10-11, Sitzung 8): NEUE SSE-Kanäle `efficiency` + `coin-catalog`;
      benchmarks.js (1-s-Timer mit 3 Fetches), telemetry.js (3 s),
      hardware.js (5 s), software/mining.js (5 s), workers.js (5 s) auf
      Live-Data-Kanäle umgestellt — Watchdog füllt nur bei Stille nach.
      live-data.js: Kanal-Registrierungen coaleszieren jetzt in EINEN
      Reconnect statt close()/open() pro Kanal (Hauptquelle des
      Tomcat-Request-Churns).
- [x] `WorkerController`+power-control-Worker-Routen mergen;
      `MiningOptimizationController`→Adapter-Capability;
      `EnergyController`+`FiatRateController`→Economics;
      `ProxyGateController`→Proxy-Ressource
      (2026-10-11, Sitzung 9, gemäß O3 ohne Forwarding — alte lokale Routen
      gelöscht, `/external/**` unverändert): EIN `WorkerController`
      (`/local/workers/**`) trägt Inventar, Zuweisung, Start/Pause UND die aus
      `AgentPowerController` mergede Route `/{workerId}/external-control`
      (Automation-Seite umgestellt); `power-control/workers/{id}/coin` und
      `/coin-policy` hatten nach dem 5c-Umstieg auf `/workers/{id}/assignment`
      keinen Consumer und wurden gelöscht. `EconomicsController`
      (`/local/economics` + `/settings` + `/rates`) ersetzt `EnergyController`
      + `FiatRateController` (Payloads feldgleich; UI-Kanäle `energy` zeigen
      auf `/local/economics`, i18n-Fiat-Read auf `/economics/rates`).
      Proxy-Gate lebt als `/local/proxy/gate{,/log,/retry}` in der einen
      Proxy-Ressource des `MiningController`; `ProxyGateController` gelöscht,
      `ProxyGateControllerTest` auf die neue Route gezogen.
      `MiningOptimizationController` gelöscht: RandomX/HugePages gehört zum
      Adapter — neues Interface `miner/RandomXCapability`, das
      `XmrMinerService` implementiert (`WindowsHugePagesService` dorthin
      verlagert); generischer `MinerOptimizationController`
      (`/local/miners/{coin}/optimizations`) löst die Capability nur über
      `MinerFactory` und hält Restart-Besitz beim Orchestrierer
      (`restartRequiredAfterToggle`). Nebenbefund: die alte
      `/optimizations/randomx`-Route hatte KEINEN UI-Consumer (kein JS ruft
      sie; i18n-Keys vorhanden, aber keine aufrufende Seite) — Route ist
      trotzdem erhalten geblieben, jetzt coin-agnostisch.
      UI-Bereinigung: Proxy-Seite von 12-s-`/overview`-Poll auf den
      `overview`-SSE-Kanal umgestellt; damit kein Seiten-Timer-Polling und
      kein `refreshAll()`-Aufruf mehr außer dem bewussten
      Übersichts-Refresh-Button. `MeasurementRunGuard`/`SampleCollector` +
      Test ins Sub-Package `mining/measurement/` verschoben (Sichtbarkeit
      public, Wire unverändert).

## Offene Owner-Entscheidungen

- **O1: ERLEDIGT — Owner-Freigabe 2026-10-11: JA.** SRB-Engines (Pearl +
  GPU-Coins) erhalten den Crash-Restart wie XMRig (max 3 Versuche, 30 s
  Abstand, übersprungen während Messläufen).
- **O2: ERLEDIGT — Owner-Freigabe 2026-10-11: JA (a und b).** Pearl erhält
  Hashrate-Grace + `reportedHashrate()`-Fallback + Usable-Pool-Job-Logik
  (KAWPOW-Falschalarme); GPU-Coins erhalten `refreshDefaultPayout`.
- **O3: ERLEDIGT — Owner-Entscheid 2026-10-11: `status` als neuer Standard.**
  Die SSE-Kanäle und die lokalen REST-Routen (`/api/agent/local/**`) werden nur
  vom mitgelieferten Static-UI im selben JAR konsumiert (kein Versions-Drift
  möglich); Solar-Miner-Node hängt ausschließlich an der `/external/**`-Fassade.
  Daher: neue aggregierte `GET /api/agent/status` + SSE-Kanal `status` führen,
  UI im selben Phase-5-Schritt umstellen, alte Kanäle/Routen beim UI-Umstieg
  löschen (kein Dauer-Forwarding, kein Deprecation-Fenster nötig).
  `/external/**` bleibt unverändert eingefroren (Node-Vertrag).
- **Commit-Freigabe 2026-10-11:** Owner möchte die Phasen 0–3 noch NICHT
  committen — Arbeit bleibt unvercommitet im Worktree, Commits erst auf
  ausdrücklichen Wunsch.

## Verifikation (pro Phase ausführen, Evidence im Worklog)

Bevorzugt: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test standaloneJar`.
**Fallback auf diesem Host** (Gradle-Distribution uncached + Netzwerk blockiert —
so verifiziert seit 2026-10-10):

1. Classpath dumpen: Init-Script mit Task, das
   `configurations.testCompileClasspath.files.join(":")` ausdruckt
   (`sh gradlew -q --offline --no-daemon -I <init> printTestCp > /tmp/tcp.txt`).
2. **HEAD-Baum + nur die eigenen Dateien** kompilieren (nicht den Worktree —
   WIP anderer Agenten kann rot sein):
   `git archive HEAD src/main/java | tar -x -C /tmp/head-tree`, eigene Dateien
   drüberkopieren, dann `javac -nowarn -parameters -cp "$CP" -d out @sources`.
   `-parameters` ist Pflicht, sonst scheitern MockMvc-Tests
   („Name for argument of type [String] not specified").
3. Tests aus HEAD compilen und mit JUnit-Console laufen lassen
   (`junit-platform-console` + `-engine` + `-launcher` + `junit-jupiter-engine`
   aus `~/.gradle/caches`, Versionen 1.11.4/5.11.4 passen zum Projekt):
   `ConsoleLauncher execute -p de.verdox.solarminer.pcagent.mining -p
   de.verdox.solarminer.pcagent.controller -p de.verdox.solarminer.pcagent.pearl`
   → Ziel **127/127 grün**.
4. `node --check` bei JS, `node scripts/check-i18n.cjs` bei UI-Texten,
   `git diff --check`.

## Bisheriger Fortschritt (Kurzstand, Details im Worklog)

- **2026-10-10, Sitzung „Abstraktion":** Analyse fertig (drei Audits), dieses
  Dokument erstellt; Phase 0 teilweise umgesetzt (`support/`-Paket + 6 Umstellungen,
  127/127 Tests grün, unvercommitet — Owner-Commit-Freigabe steht aus).
- **2026-10-10, Sitzung „Abstraktion 2":** Phase 1 (Rest) umgesetzt:
  `ProxyPorts`-Record als einzige Port-Wahrheit, Dev-Fee-Einzelwahrheit im
  Miner-Katalog, Merges `ProxyStartupService` und `StandaloneFeeGuard`;
  verifiziert mit vollem `sh gradlew test --offline` (140/140 Tests grün,
  inkl. Network-Dashboard-WIP) und `check-i18n.cjs`. Alles unvercommitet.
- **2026-10-11, Sitzung „Abstraktion 4":** Phase 2 umgesetzt:
  `miner/engine/MinerBinaryInstaller` (beide Download-Services darauf
  umgestellt), `miner/engine/RouteValidator` + `WalletRule`, gemeinsame
  `updateProxyRoute`-/`updateShares`-Pfade, `writeConfig` → `AtomicFileStore`.
  Verifiziert: `sh gradlew test --offline` 144/144 grün, i18n-Checks grün,
  `git diff --check` sauber. Unvercommitet; Phase 3 wartet auf O1/O2.

- **2026-10-11, Sitzung „Abstraktion 6“:** Phase-3-Rest A5 umgesetzt:
  `mining/MeasurementRunGuard` + `mining/SampleCollector` aus
  `BenchmarkSessionService`/`EfficiencySweepService` extrahiert (Guard-Mechanik
  und Median/Hashrate-Regel je einmal; Mess-Loops bleiben bewusst
  dienst-spezifisch). Verifiziert: `sh gradlew test --offline` 153/153 grün
  (neu `MeasurementRunGuardTest`), i18n-Checks grün, `git diff --check` sauber.
  Unvercommitet. Phase-3-Rest: XMR-Engine-Adapter + Live-Hardware-Gate.

- **2026-10-11, Sitzung „Abstraktion 7“:** Phase-3-Rest (Code) umgesetzt:
  `AbstractMinerEngine` in zwei Ebenen geteilt (miner-unabhängige Basis +
  `AbstractSrbMinerEngine`), `XmrMinerService` als dritter Adapter darauf
  (gemeinsame Drain-Schleife, gemeinsames O1-Restart-Budget; XMR-Verhalten
  unverändert). Verifiziert: `sh gradlew test standaloneJar --offline`
  154/154 grün (neu `sharedRestartBudgetPins…`-Test), i18n-Checks grün,
  `git diff --check` sauber. Unvercommitet. Phase 3 offen: nur noch das
  Live-Hardware-Gate (Miner-Start/Stop an echter HW abnehmen).

- **2026-10-11, Sitzung „Abstraktion 9“:** Phase 5d umgesetzt (letzter
  offener Phase-5-Punkt): Worker-Routen in EINEM `WorkerController` merged,
  `EnergyController`+`FiatRateController`→`EconomicsController`,
  `ProxyGateController`→Proxy-Ressource (`/proxy/gate*`),
  `MiningOptimizationController`→`miner/RandomXCapability` am
  `XmrMinerService` + generischer `MinerOptimizationController`; alte lokale
  Routen gelöscht (O3), `/external/**` unverändert. Proxy-Seite vom
  12-s-Poll auf SSE umgestellt; `MeasurementRunGuard`/`SampleCollector` ins
  Sub-Package `mining/measurement/` verschoben. Verifiziert:
  `sh gradlew test standaloneJar --offline` 159/159 grün (40 Klassen),
  i18n-Checks grün, `git diff --check` sauber; zwei Agent-Instanzen live
  gestartet und gegenseitige UDP-Findung (8093) real nachgewiesen.
  Unvercommitet. Offen: Live-Hardware-Gate (Phase 3). Folgearbeit
  (Owner-Frage REST vs. SSE, 2026-10-11): Lade-Strategie-Plan
  `rest-sse-loading-plan.md` (P1–P6) angelegt.

## Bekannte Nebenbaustellen (nicht diesem Refactor zuzuordnen)

- Die WIP des Network-Dashboard-Agenten (`AgentNetworkDiscoveryService`,
  `NetworkDashboardService`, `NetworkDashboardController`) kompiliert inzwischen
  (Enumeration-Import und `networkSharingEnabled` sind gesetzt; verifiziert am
  2026-10-10 mit grünem Gesamtbau). Direkt gegen den Worktree compilieren ist
  daher wieder möglich — bei erneut rotem Worktree trotzdem wie oben beschrieben
  gegen HEAD + eigene Dateien verifizieren.
