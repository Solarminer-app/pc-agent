# REFACTORING-HANDOVER — pc-agent Clean-Code & Miner-Abstraktion

**Zustand bei Übergabe: alle Phasen des Refactoring-Auftrags sind umgesetzt und grün verifiziert
(`JAVA_HOME=/home/lukas/.jdks/temurin-21 sh gradlew test standaloneJar --no-daemon` → BUILD
SUCCESSFUL, 127 Tests). Alles ist UNCOMMITTED im Arbeitsbaum auf `beta` — Commit nur nach
Eigentümer-Freigabe, niemals `git add -A` (fremde IDE-WIP im Tree; explizite Pfade stagings).**

⚠️ Parallele Agenten: Vor jedem Patch Regionen neu lesen, Work-Log-Tail vor Anhängen prüfen.

## Was existiert jetzt (Architektur)

### `de.verdox.solarminer.pcagent.coin` — geschlossene Coin-/State-Enums
- `Coin`: einzige Coin-Identität (id, ticker, algorithm + Aliases, DeviceKind, experimental,
  `miningCoins()`, `gpuCoins()`, `sharedSrbCoins()`, `assignableTo(cpuWorker)`,
  `byAlgorithm()`, `byIdOrNull()`). Neuer Coin = ein Enum-Eintrag + Adapter.
- `DownloadState` (PENDING/CHECKING/DOWNLOADING/READY/FAILED/UNSUPPORTED/BLOCKED_BY_ANTIVIRUS),
  `SweepRunState` (+ `terminal()`), `SweepMode` (FULL/VALIDATION), `ProxyMode`, `FeeRollMode`,
  `BenchmarkMode`, `WorkerCoinPolicy`, `WorkerIds`.
- `mining/ProxyLifecycle` (external/starting/running/checking/downloading/failed) für
  `ManagedProxyService`.
- **Eisernes Gesetz:** jeder Enum-Wire-Name ist REST-Vertrag (UI liest
  `QUEUED/RUNNING/COMPLETE/...`, `READY/DOWNLOADING/...`, `running/starting/...`). Enums intern
  benutzen, an Getter-Grenzen `.wireName()` zurückgeben.

### `de.verdox.solarminer.pcagent.miner` — Adapter-Seam
- `CoinMiner` (Lifecycle, `setPowerCap`, `updateProxyRoute`, `setupComplete()`,
  `appendBenchmarkEvent`), `CpuMiner` (+ `readyForStart`, `savedRoute()` — XMRig-Login-Parsing
  lebt im Adapter), `GpuCoinMiner` (per-GPU-Steuerung, `configuration/applyConfig/validateConfig`,
  `reportedAlgorithm`).
- `MinerFactory`: `miner(Coin|id)`, `cpuMiner(id)`, `gpuMiner(id)`, `srbGpuMiner(id)`,
  `gpuMiners()`, `supports(id)`. Orchestration (MiningService, EconomicPlanningService,
  Benchmark/Sweep, WorkerAssignment, MiningController) fragt nur noch die Factory.
- Adapter: `XmrMinerService implements CpuMiner`, `PearlMinerService implements GpuCoinMiner`,
  `SrbGpuCoinView` (delegiert pro-Coin auf `GpuCoinMinerService`).

### REST-Konsolidierung (local-API)
- `/{coin}/download`, `/{coin}/remove`, `/{coin}/configuration` ersetzen die monero/pearl-Spezialrouten
  (GPU-Entfernen stoppt wegen gemeinsamer SRBMiner-Installation alle GPU-Runs — wie vorher).
- `ProxyOverview` = `host, reachable, mode, feeRollMode, managedStatus/Detail/Version, coinRoutes[]`
  + `@JsonAnyGetter` → alte flache Felder `<coinId>Url`, `<coinId>FeeReady` bleiben auf dem Draht
  (Solar-Miner-Node `MinerAgentController.prepareProxy(urlField)` liest genau die!).
  Geschützt durch `ProxyOverviewWireFormatTest`.
- `MinerCatalogService.MinerOption` hat neues Feld `coinName` (UI-Tabelle `coinName`-Fallback entfernt);
  rückwärtskompatibler 15-Arg-Konstruktor vorhanden.
- Externe Node-Routen (`/api/agent/external/monero|pearl/configuration`) sind unverändert.
  Die Pearl-Spezialrouten der LOCAL-API (`/pearl/gpus/...`, `/pearl/download`, `/pearl/remove`,
  `/pearl/configuration`, `/monero/download`, `/monero/remove`, `/monero/configuration`) wurden
  entfernt; `/{coin}/gpus/...` bzw. `/{coin}/download|remove|configuration` decken alles über
  die Factory ab.

## Verifikation (Stand bei Übergabe)
- `sh gradlew test standaloneJar --no-daemon` (JAVA_HOME=~/.jdks/temurin-21): BUILD SUCCESSFUL.
- `node --check` für `proxy.js` und `software.js`: OK.
- Manuelles UI-Rauchen (Proxy-Seite, Software-Seite, Wallet-Editor) steht aus: Frontend nutzt jetzt
  `coinRoutes[]` (proxy.js) und `/remove` generisch (software.js).

## Restarbeiten / Feinschliff (optional, nicht blockierend)
1. **Finaler Eintrag in `docs/agent-wiki/work-log.md`** (datiert, mit Verifikationsnachweis) +
   ggf. Wiki-Abschnitt zur neuen Adapter-Architektur in `docs/agent-wiki/pc-agent.md`.
   ✅ ERLEDIGT (2026-10-10): Work-Log-Eintrag „Clean-code refactoring: closed coin/state enums
   and the miner adapter seam“ + Wiki-Abschnitt „Miner adapter architecture (2026-10-10)“;
   Build erneut grün (127 Tests, 0 Failures), `node --check` proxy.js/software.js OK.
2. `XmrConfigService:60` `poolNode.put("coin", "monero")` ist XMRig-Configdatei-Syntax (bewusst
   gelassen — kein Agent-Wire-String); bei Bedarf auf `Coin.MONERO.id()` ziehen.
3. `MinerCatalogService.definitions()` nutzt jetzt `Coin.X.id()`-Literale; mittelfristig könnte der
   Katalog pro Coin aus `Coin`-Metadaten + Miner-Deskriptoren gebaut werden (1 Eintrag pro Miner-Variante
   bleibt aber bewusst, weil TeamRedMiner als NOT_INTEGRATED gelistet ist).
4. `GpuCoinMinerService`/`PearlMinerService` bleiben bewusst in ihren Packages (`pearl/`); ein Umbenennen
   in `adapter/` wäre Kosmetik mit großem Diff — nur auf Wunsch.
5. `BenchmarkSessionService.PHASE_LIVE` und Phasen-Strings sind Benchmark-interne Phasen-Labels;
   Enum-Umstellung optional.
6. Cross-Repo: Solar-Miner-Node liest `moneroUrl/pearlUrl` (via AnyGetter abgedeckt) und postet
   `/external/monero|pearl/configuration` (bewusst beibehalten). Wenn Node je auf generische Routen
   migriert werden soll: erst Node ändern, dann pc-agent-Routen löschen (beide Repos, eigener Auftrag).

## Fallstricke (aus diesem Refactoring gelernt)
- Record-Änderungen (neue Komponenten) brechen alle Call-Sites inkl. Test-Fixtures → Komfort-Konstruktor
  alter Arity anbieten (siehe `MinerOption`).
- `@JsonAnyGetter` in Records funktioniert; Feld-Reihenfolge im JSON ist egal, Namen nicht.
- Coin-Wechsel-Routen (`/coin`, Worker-Zuordnung) validieren Hardware-Kompatibilität jetzt zentral über
  `Coin.assignableTo(...)` — CPU darf nur MONERO/NONE, GPU alles außer MONERO.
- Sweep/Checkpoint-Persistenz: `RunStatus.mode/status` sind Enums; JSON serialisiert Wire-Namen,
  aber **In-Memory-Checkpoint über Session-Grenzen hinweg** bleibt kompatibel, da dieselbe JVM.
- MiningController-Konstruktor hat jetzt `MinerFactory` als letzten Parameter — Test-Fixtures
  (`MiningControllerValidationTest`) bauen eine echte Factory aus den Mocks.

## Prompt für den nächsten Agenten

> Führe den pc-agent-Refactoring-Auftrag zu Ende. Lies zuerst
> `pc-agent/REFACTORING-HANDOVER.md` und `pc-agent/worklog.md` (Phasen 0–3 abgeschlossen, Build grün,
> alles uncommitted auf `beta`), dann `pc-agent/AGENTS.md`. Erledige die dort unter „Restarbeiten“
> gelisteten Punkte 1 (finaler `docs/agent-wiki/work-log.md`-Eintrag mit Verifikationsnachweis +
> Wiki-Abschnitt zur Adapter-Architektur) und `node --check` für die geänderten JS-Dateien; prüfe
> Punkte 2–6 nur auf expliziten Wunsch. Verifiziere mit
> `JAVA_HOME=/home/lukas/.jdks/temurin-21 sh gradlew test standaloneJar --no-daemon`.
> Halte das Enum-Wire-Name-Gesetz ein und änderkeine REST-Felder ohne Kompatibilitätsnachweis
> (ProxyOverviewWireFormatTest als Vorbild). Commite/PR erst nach Freigabe, mit expliziten Pfaden.
> Aktualisiere `worklog.md` fortlaufend.
