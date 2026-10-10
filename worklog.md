# Refactoring-Worklog: Clean Code & Miner-Abstraktion (pc-agent)

Fortlaufender Arbeitsstand des großen Refactorings. Repo-Konvention: finale, verifizierte
Zusammenfassungen landen in `docs/agent-wiki/work-log.md`; diese Datei ist der
Live-Fortschrittszähler für den Auftraggeber. Detailierte Übergabe an den nächsten Agenten:
`REFACTORING-HANDOVER.md` (Repo-Root).

## Zielsetzung
1. Einheitliche Miner-Abstraktion (GPU & CPU): `CoinMiner`/`GpuCoinMiner`/`CpuMiner` +
   `MinerFactory`; keine Coin-Ifs im Hauptcode, Coin-Wissen nur in Adaptern.
2. Keine Magic Strings: typsichere Enums/Konstanten für Stati, Modi, Konfigurationsschlüssel.
3. Separation of Concerns, Duplikatsabbau, saubere Packages (`coin`, `miner`, `mining`,
   `controller`, `dto`, `lowlevel`).
4. Keine Regressionen: `sh gradlew test standaloneJar` muss grün bleiben; REST-Wire-Formate
   (Frontend- und Node-Verträge) bleiben abwärtskompatibel.

## Phase 0 — Bestandsaufnahme (ABGESCHLOSSEN)
- Vorgänger-Agent hatte bereits `coin/`- und `miner/`-Pakete samt Factory vorgelegt
  (uncommitted); Compile-Baseline grün.

## Phase 1 — State-Enums & Konstanten (ABGESCHLOSSEN)
- `coin/DownloadState`, `coin/SweepRunState`, `coin/SweepMode`, `mining/ProxyLifecycle`:
  Download-/Release-/Sweep-/Proxy-Stati durchgängig typisiert (Wire-Namen unverändert);
  `WorkerCoinPolicy` in `EconomicPlanningService`; `EnergyJournalService` nutzt
  `Coin.byAlgorithm(...)` statt Alias-Switch.

## Phase 2 — Coin-Literale aus Controllern/Services entfernt (ABGESCHLOSSEN)
- `MiningController`: Overview, MinerCatalog, PayoutDefaults, WalletTargets, Proxy-Routen-
  Migration, Remove/Download/Configuration/GPU-Pause-Routes — alles generisch über
  `Coin.miningCoins()` / `MinerFactory`; `ProxyOverview` coin-agnostisch (coinRoutes +
  `@JsonAnyGetter` für die alten `<coinId>Url`/`<coinId>FeeReady`-Felder, abgesichert durch
  `ProxyOverviewWireFormatTest`).
- `CoinMiner.updateProxyRoute(...)` + `CoinMiner.setupComplete()` + `CpuMiner.savedRoute()`
  als neue Adapter-Verträge (XMRig-Login-Parsing bleibt im XMR-Adapter).
- `AgentPowerController` validiert Coin-Zuordnung über `Coin.assignableTo(...)`;
  `GpuCoinMinerService` (Wallet-Validierung, API-Port-Offset, SRB-Flags, Shutdown-Loop),
  `PayoutDefaultsService`, `FeeTransparencyService`, `EarningsForecastService`,
  `KryptexPoolBalanceProvider`, `ProxyDiscoveryService`, `WindowsDefenderExclusionService`,
  `WalletBalanceService`, `MiningOptimizationController`, `MinerCatalogService` (inkl.
  `coinName` im Katalog-DTO) — alle auf `Coin`-Enum umgestellt.
- REST-Routen zusammengefasst: `/monero/download`, `/pearl/download` → `/{coin}/download`;
  `/monero/remove`, `/pearl/remove` → `/{coin}/remove`; `/monero/configuration` und
  `/pearl/configuration` (local) → `/{coin}/configuration` (dispatcht auf Adapter-Flows).
  Externe Node-Routen (`/api/agent/external/monero|pearl/configuration`) bewusst beibehalten.
  Frontend (`proxy.js`, `software.js`) auf die generischen Routen/Daten umgestellt.
- Verifikation: `sh gradlew test standaloneJar` komplett grün (BUILD SUCCESSFUL).

## Phase 3 — Doku & Übergabe (ABGESCHLOSSEN)
- `REFACTORING-HANDOVER.md` mit Restarbeiten, Verifikationsbefehlen und Fallstricken.

## Phase 4 — Restarbeit 1: finale Doku (ABGESCHLOSSEN)
- Finaler Eintrag in `docs/agent-wiki/work-log.md` (2026-10-10, mit Verifikationsnachweis:
  BUILD SUCCESSFUL, 127 Tests / 33 Suites, 0 Failures — aus `build/test-results/test`
  nachgezählt; `node --check` proxy.js/software.js OK; `git diff --check` sauber).
- Neuer Wiki-Abschnitt „Miner adapter architecture (2026-10-10)“ in `docs/agent-wiki/pc-agent.md`
  (Enum-Wire-Name-Gesetz, Adapter-Seam, generische Local-Routen, AnyGetter-Kompatibilität);
  veraltete `MiningService.setTarget`-Zeile („still directly depends on both miner services“)
  auf den Factory-Zustand korrigiert.
- Verifikation erneut ausgeführt: `JAVA_HOME=/home/lukas/.jdks/temurin-21 sh gradlew test
  standaloneJar --no-daemon` → BUILD SUCCESSFUL.

## Offene Restarbeiten (siehe HANDOVER)
- Punkt 1 erledigt (siehe Phase 4). Optionale Feinschliff-Punkte 2–6 bleiben liegen und werden
  nur auf expliziten Wunsch des Eigentümers angefasst.
- Commit/PR erst nach Freigabe des Eigentümers, mit expliziten Pfaden (kein `git add -A`).
