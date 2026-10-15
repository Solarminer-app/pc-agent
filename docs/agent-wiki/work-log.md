# PC-Agent work log

## 2026-10-14 — QTC forecast input alignment

- The Agent's existing gross QTC forecast consumes the Currency Service network snapshot. Its producer now pairs QTCScan's 24-hour estimated network hashrate with the average block interval from that same window, avoiding the prior mix of a short-window rate and nominal target interval.
- The Agent API/UI contract is unchanged; its estimate remains before pool/miner fees when unknown and does not guarantee pool credits. The Currency Service owns the parser correction and test. No Agent code or mining route changed.

## 2026-10-13 — Read-only Node-Telemetrie von Steuerfreigabe getrennt

- `externalControlEnabled` sperrt weiterhin sämtliche Node-Schreibpfade, unterdrückt aber nicht mehr die read-only Routen `GET /identity`, `/status`, `/power-control` und `/telemetry`. Weitere Datenverträge wie Overview, Proxy und Earnings behalten ihr bisheriges Gate.
- Der externe Mining-Status aggregiert alle lokalen CPU-/GPU-Worker unabhängig von globaler oder workerbezogener Steuerfreigabe. Dadurch kann der Node die tatsächliche Mining-Leistung für PV-Statistiken erfassen, ohne daraus eine Start-/Pause-/Leistungsberechtigung abzuleiten.
- Keine neue UI-Kopie: Die vorhandenen DE/EN-Schalter bleiben Steuerungsfreigaben; ihr ausgeschalteter Zustand ist weiterhin im read-only Power-Status sichtbar. API- und Wiki-Vertrag wurden aktualisiert.
- Verifikation: `AgentWriteAccessFilterTest`, `ExternalAgentControllerTest` und der neue `MiningServiceExternalStatsTest` erfolgreich; anschließend vollständige PC-Agent-Testsuite erfolgreich. Live-LAN-Probe bleibt offen.

## 2026-10-12 — Direct start/pause for LAN peer workers

- The network dashboard now includes assigned stopped workers and offers immediate Start/Pause buttons for remote workers. Actions execute on the first click without a confirmation dialog and show localized progress/success/failure feedback.
- No new consent was added: the target must already share its LAN snapshot, have global external control enabled and permit the individual worker. Older agents lack the additive device/control fields and remain view-only. The local agent proxies the command to `POST /api/agent/external/network-workers/{deviceId}/start|pause`; configuration and wallet writes remain unavailable.
- German and English automation copy now explains that the existing remote-control switches cover both Node and shared PC-Agents. Verification: the complete JDK 21 suite passed (187 tests, zero failures/errors/skips); JavaScript syntax, closed-catalog i18n, literal scan, legacy-dashboard startup and both-locale render simulation passed.

## 2026-10-12 — Network-wide consumption totals

- The consent-gated read-only LAN snapshot now adds aggregate energy for today, seven days and 30 days plus active watt-hours/runtime/session count. It does not expose historical session or device records. Older peers remain compatible because the additive `energy` field is nullable when deserialized.
- The overview energy card sums local and reachable shared-agent kWh, weights measurement coverage by measured/runtime seconds and converts every agent's locally configured electricity cost into the selected display currency before adding it. The local tariff editor continues to change only this agent.
- German and English visibility copy now names energy values explicitly. Verification: focused `NetworkDashboardServiceTest` and `EnergyJournalServiceTest` passed; JavaScript syntax, closed-catalog i18n and literal scans passed.

## 2026-10-12 — Worker default follows the Node label

- New, unsaved wallet routes no longer prefill the generic worker name `pc`. The Wallets page reads the existing local identity contract and derives the worker name from the operator-defined PC-Agent/Node label. If that label is absent or cannot produce a valid pool worker, the exact fallback is `Solarminer-PC-Agent`.
- The derived name follows the existing XMRig/SRBMiner contract (`[A-Za-z0-9_-]`, maximum 32 characters): unsupported characters become hyphens and accented Latin characters are folded where possible. Saved worker names remain untouched.
- The field explanation is available in German and English through the closed frontend catalog. No external Node API, miner route format or persisted configuration was changed.
- Verification: `node --check` for `wallets.js`, both frontend i18n checks, `git diff --check`, and the complete JDK 21 `sh gradlew test --offline --no-daemon` suite passed.

## 2026-10-12 — Windows-X beendet den vollständigen Agent-Prozessbaum

- Der Stable- und Beta-PowerShell-Launcher startet die Agent-JVM jetzt in einem
  Windows Job Object mit `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`. Der Handle bleibt
  bis zum Ende des Agenten offen; beendet Windows den Launcher beim Schließen
  des Konsolenfensters über X, beendet der Kernel dadurch Agent, verwalteten
  Stratum-Proxy, Miner und weitere Kindprozesse gemeinsam. Schlägt die
  Job-Zuordnung fehl, wird die gerade gestartete JVM beendet und der Start
  zweisprachig als Fehler gemeldet, statt einen ungeschützten Prozessbaum laufen
  zu lassen. Grundlage sind Microsofts dokumentierte
  [Job-Object-Prozessbaum-Vererbung](https://learn.microsoft.com/windows/win32/procthread/job-objects)
  und
  [`JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`](https://learn.microsoft.com/windows/win32/api/winnt/ns-winnt-jobobject_basic_limit_information).
- Die bestehende Recovery beim nächsten Start bleibt als zweite Schranke
  erhalten: Sie verlangt zuerst eine positive Proxy-Service-Identität und
  begrenzt die Prozessauswahl danach auf JARs im verwalteten Release-Verzeichnis.
  Ein fremder Portinhaber wird nicht beendet.
- `WindowsLauncherLifecycleTest` fixiert für beide Launcher die Reihenfolge
  Job anlegen → JVM starten → zuordnen → warten → Handle schließen und verbietet
  die frühere direkte JVM-Ausführung. Release- und Beta-Workflow parsen nun auch
  den jeweils ausgelieferten Launcher vor der Veröffentlichung.
- Der Bug-Backlog wurde vollständig gegengeprüft: BUG-001 ist durch die bereits
  implementierte atomare SolarMiner-Standard-Auszahlungsroute und vorhandene
  Monero-/Pearl-/GPU-Coin-Regressionstests behoben; BUG-002 war bereits
  abgeschlossen. BUG-003 bleibt bis zur PID-/Port-Verifikation auf einem echten
  Windows-Host formal `[~]`.
- Verifikation: fokussierte `WindowsLauncherLifecycleTest` und
  `MiningControllerValidationTest` mit JDK 21 grün. Neue Launcher-Ausgaben sind
  Deutsch/Englisch; die vorhandene UI nutzt weiterhin ausschließlich den
  geschlossenen zweisprachigen Katalog. Ein echter Windows-X-, Tray-Exit- und
  Neustart-Smoke-Test bleibt erforderlich.

## 2026-10-12 — PC-Agent-Name speichert mit korrekter Rückmeldung

- Die gemeinsame `postJson`-Hilfsfunktion wertete bislang ausschließlich die JSON-Antwort
  `true` als Erfolg. Der Identity-Endpunkt liefert dagegen absichtlich das gespeicherte
  Identity-Objekt zurück. Dadurch zeigte die Oberfläche nach einem erfolgreichen Speichern
  fälschlich „Der Agent hat die Änderung abgelehnt.“ an.
- Die Hilfsfunktion akzeptiert jetzt sowohl die bestehende boolesche Erfolgsantwort als auch
  ein zurückgegebenes Ressourcenobjekt und gibt dieses an die aufrufende Seite weiter; nur
  `false` oder eine fehlende JSON-Antwort bleiben ein Fehler. Die vorhandenen deutschen und
  englischen i18n-Einträge bleiben unverändert und decken echte Validierungsfehler weiterhin ab.
- Verifikation: `node --check src/main/resources/static/js/core/components.js`,
  `node scripts/check-i18n.cjs`, `node scripts/scan-i18n-literals.cjs` und
  `git diff --check` erfolgreich.

## 2026-10-12 — Gemeinsame Worker-Liste im Dashboard

- Das Dashboard führt die laufenden lokalen Worker und die schreibgeschützt über LAN
  freigegebenen Worker anderer PC-Agents in einer Liste zusammen. Jede Zeile zeigt den
  zugehörigen PC-Agent, Status, Hashrate, Leistung, Tagesertrag und Ertrag pro kWh.
- Die Kopfkennzahlen summieren Workerzahl, Leistung und Tagesertrag; der Ertrag pro kWh ist
  ausdrücklich der arithmetische Durchschnitt aller Worker mit vollständigem Ertrags- und
  Leistungswert. Remote-Erträge werden wie die bisherigen lokalen Werte anteilig über die
  Hashrate am je Agent veröffentlichten Coin-Forecast berechnet. Remote-Zeilen bleiben
  ausschließlich lesbar.
- Deutsche und englische Katalogeinträge ergänzen Überschrift, Kennzahlen, Hinweise und
  Kennzeichnung der Remote-Daten. Verifikation: `node --check
  src/main/resources/static/js/pages/overview.js`, `node scripts/check-i18n.cjs` (1.255
  Schlüssel, neun Seiten, beide Locales), `node scripts/scan-i18n-literals.cjs` (0) und
  `git diff --check` erfolgreich. Ein Browser-/LAN-Smoketest mit mindestens zwei Agents bleibt
  ein Rollout-Schritt.
- Die vier Kennzahlen sind verdichtet (82 px Mindesthöhe, kleinere Innenabstände und Schrift)
  und bleiben auf schmalen Ansichten als 2×2-Raster sichtbar statt einzeln untereinander zu
  stehen.
- Die Dashboard-Hauptspalte darf auf Desktop bis zu 1.480 px nutzen. Bis 1.650 px
  Fensterbreite steht die Worker-Liste allein in ihrer Zeile; dadurch erhält die sieben-spaltige
  Tabelle die volle verfügbare Breite und vermeidet die horizontale Scrollleiste auf normalen
  Desktopgrößen.

## 2026-10-12 — Pearl-Worker-Start und sichtbare API-Fehler

- Live-Diagnose des lokalen Worker-Starts ergab HTTP 400 vor dem SRBMiner-Prozessstart:
  Pearl erzeugte die interne Konsolen-ID `pearl-NVIDIA:0`, während
  `MinerConsoleService` und Worker-UI das gültige Format `pearl-NVIDIA-0`
  verwenden. Das Öffnen der Worker-Konsole warf deshalb `Unknown miner`, nachdem
  nur der Pearl-Sammellog zurückgesetzt worden war.
- Pearl und die übrigen SRBMiner-Adapter erzeugen Worker-Konsolen-IDs jetzt über
  dieselbe zentrale Factory. Der Worker-Controller liefert seine vorhandene
  Fehlermeldung bei HTTP 400/409 außerdem explizit als JSON-`message`, damit das
  vorhandene lokalisierte Fehlerbanner nicht nur den HTTP-Status anzeigen muss.
- Keine neue Bedienkopie: Das Frontend verwendet weiterhin den geschlossenen
  deutschen/englischen i18n-Katalog und dessen dynamische Fehlerübersetzungen.
  Verifikation: fokussierte Controller-, Konsolen- und Pearl-ID-Regressionstests,
  JavaScript-Syntax- und i18n-Prüfungen sowie `git diff --check`.
- Windows-Live-Reproduktion auf `Main-Gaming-PC` (Windows 11, RTX 2080 Ti):
  Der Start lieferte HTTP 400, setzte ausschließlich den Pearl-Sammellog auf
  `2026-10-12T09:52:48.556596Z` zurück und ließ den gültigen Worker-Log
  `pearl-NVIDIA-0` samt alter Run-ID unverändert. Damit ist der Absturz an der
  ungültigen internen ID `pearl-NVIDIA:0` auch auf dem Zielsystem belegt. Der
  dort laufende Build enthielt weder den ID-Fix noch den strukturierten
  Controller-Fehlerbody. Das korrigierte `standaloneJar` wurde anschließend
  erfolgreich gebaut; SHA-256:
  `7b78f15c2a396c328acfb1dd26ef1dd356dc305e0c9ec8ba656cef99f9a55380`.

## 2026-10-12 — Wallet-target sharing route regression coverage

- Pinned the local consent route used by the Automation-page **Share wallet targets** switch: `POST /api/agent/local/power-control/wallet-target-sharing?enabled=true` resolves successfully and persists the explicit wallet-target-sharing consent. This prevents a missing controller mapping from regressing into a browser-visible 404.
- No user-facing copy changed; the existing German and English catalog entries remain the closed UI contract. Verification: focused `AgentPowerControllerTest` and `AgentWriteAccessFilterTest` passed with JDK 21; `node --check src/main/resources/static/js/pages/automation.js`, `node scripts/check-i18n.cjs` (1,243 keys, nine pages, both locales), `node scripts/scan-i18n-literals.cjs` (0), and `git diff --check` passed.

## 2026-10-12 — LAN wallet-target import

- The Wallets page can now list discovered peer PC-Agents and load a selected peer's payout targets. A target is applied to an individual local form only on the operator's explicit action and still requires the existing local **Save wallet** confirmation; the import never writes configuration automatically.
- Wallets remain private by default. The source Agent must explicitly enable **Wallet-Ziele mit anderen PC-Agents im selben Netzwerk teilen** in Node-Automatisierung. The new external `GET /api/agent/external/wallet-targets` route is read-only, separately filtered by that consent, and is not included in the periodic worker/earnings/energy network snapshot.
- Added German and English copy for the source consent, peer selection, import state and failures. Verification: `node --check` for Wallets and Automation, `node scripts/check-i18n.cjs` (1,239 keys, nine pages, both locales), `node scripts/scan-i18n-literals.cjs` (0), and focused `AgentWriteAccessFilterTest` passed with JDK 21. A two-host LAN/browser smoke test remains a rollout gate.

## 2026-10-12 — Live benchmark dashboard and freshness handling

- The benchmark page consumes the dedicated `benchmarks` SSE channel (one-second producer)
  and keeps its existing four-second REST watchdog. It now displays the local receipt time of
  the newest status and changes the connection state to reconnecting when that value becomes
  stale; an operator can therefore distinguish a completed measurement from an old screen.
- Fixed the manual refresh control: it now refreshes benchmark, efficiency and upload channels
  through the shared live-data client instead of calling an undefined `poll` function.
- Results paint immediately, followed by comparison requests started in parallel. A render
  generation and connected-card check prevent an old comparison response from mutating a newer
  benchmark view. The result area adds an accessible, responsive hashrate bar chart and retains
  the current per-worker hashrate, watts, efficiency and sample count cards.
- Added German and English strings for the freshness timestamp and live median-hashrate chart;
  no external text remains untranslated. Verification: `node --check
  src/main/resources/static/js/pages/benchmarks.js`, `node scripts/check-i18n.cjs` (1,221 keys,
  nine pages, both locales), and `git diff --check` passed. A browser-level visual/live SSE
  smoke test remains a rollout gate on a machine running the Agent.

## 2026-10-12 — Dashboard-Ladezeit: Read-only-Befund

- Der laufende lokale Agent beantwortete `GET /api/agent/local/overview` mit
  1,949 s, während `GET /api/agent/local/telemetry` aus seinem Ein-Sekunden-
  Snapshot-Cache in 1,942 ms antwortete. Die Messung nutzte die reguläre
  Same-Origin-Prüfung; es wurde keine Anwendungs- oder Konfigurationsdatei
  verändert.
- Der aktuelle Overview-Pfad verwendet zwar den Hintergrund-GPU-Snapshot und
  parallelisiert Proxy-, Payout- und Fee-Lookups, blockiert die HTTP-Antwort
  aber anschließend mit `join()`. Payout-Defaults und Fee-Übersicht lesen dabei
  die Proxy-Fee-Ziele synchron (je Aufruf bis zu drei Sekunden); die
  Fee-Übersicht besitzt keinen Snapshot-Cache. Das ist auf diesem Host die
  belegte Erklärung für die etwa zwei Sekunden lange Overview-Antwort, nicht
  die bereits kontinuierlich gehaltenen Worker- oder Energieinformationen.
- Weitere weiterhin request-gebundene I/O: der erste Telemetrie-Snapshot
  durchsucht Linux `hwmon`/DRM und ruft `nvidia-smi` mit bis zu drei Sekunden
  Wartezeit auf; die Energieübersicht liest und parst unter einem synchronen
  Monitor alle Journaldateien bei jeder Cache-Neuberechnung. Wallet-Balances
  sind dagegen bereits ein asynchron erneuerter 60-s-Snapshot und gehören nicht
  mehr in den kritischen First-Paint-Pfad.
- Umsetzung: `MiningController` baut den vollständigen Overview-Snapshot beim
  Application-Ready-Event und danach im Hintergrund auf; `GET /overview` sowie
  der SSE-Overview-Kanal liefern ausschließlich den letzten vollständigen
  Snapshot. Ein Fehler behält den letzten Stand statt den HTTP-Request zu
  blockieren. Die gleichartige Entkopplung gilt für die Hardwaretelemetrie
  (Hintergrund-Refresh, leerer Start-Snapshot), die Energieübersicht (Journal-
  Dateien nur bei Sample/Änderung einlesen) und Fiatkurse (Currency-Service nur
  im Hintergrund). Die bestehenden Mutation-Invalidierungen sorgen weiterhin
  für einen sofortigen SSE-Abgleich. Eine explizite UI-Stale-Anzeige und
  Dauer-/Fehlermetriken pro Produzent sind sinnvolle nächste Beobachtungsarbeit.
- Verifikation nach Umsetzung: fokussierte `EnergyJournalServiceTest`,
  `MarketDataServiceTest` und `MarketDataServiceFiatTest` mit JDK 21 grün;
  `node scripts/check-i18n.cjs`, `node scripts/scan-i18n-literals.cjs` und
  `git diff --check` grün. Ein erneuter Laufzeitvergleich benötigt einen
  Neustart des lokalen Agenten, damit er die geänderten Klassen lädt.

## 2026-10-12 — Model-aware parallel GPU benchmarks

- Installed GPU benchmarks now run in model-aware waves. Cards of the same vendor/model
  receive different remaining coins in a wave, because one completed sample per
  `(model, coin)` is sufficient; the next wave reuses a card only for another coin.
  Different GPU models are independently scheduled into those waves, so they do not wait
  for one another.
- The selected devices are supplied through a benchmark-only in-memory miner configuration.
  It takes precedence only while the wave runs, is cleared before state restoration, and
  never writes the operator's pool, wallet, device assignment or saved miner configuration.
  CPU measurement remains on its existing sequential lifecycle.
- No new UI text was introduced. The running-state label continues to use the existing
  German/English `Measuring active miners` catalog entry; console/process messages use the
  existing measurement-stop wording.
- Verification: focused `BenchmarkSessionServiceTest` passed with JDK 21, including the
  planner regression for two identical cards plus a different model; `node
  scripts/check-i18n.cjs`, `node --check src/main/resources/static/js/pages/benchmarks.js`,
  and `git diff --check` passed. Live multi-GPU/pool validation remains a rollout gate.

## 2026-10-11 — Frontend responsiveness assessment (no product-code change)

- The operator UI is still a multi-page application: each navbar transition rebuilds the
  document and executes the common classic-script stack again (including the 283 KB
  uncompressed i18n catalog). Browser caching can avoid a repeat transfer after a warm
  load, but cannot avoid parse, initialization, DOM construction, or a new SSE connection.
- The present REST/SSE migration removes the former interval polls for many pages, but
  the first-paint aggregate has an overfetch regression: `live-data.js` requests the
  unparameterized `/api/agent/local/status` whenever any aggregate-backed channel is
  subscribed. `AgentStatusService.aggregate()` eagerly evaluates every section, so a
  telemetry/proxy page may also evaluate the heavy overview (GPU discovery, miner stats,
  forecasts, payout defaults, fee data) and wallet balance snapshot. Section caching
  helps only after the first collection and does not bound a cold or invalidated request.
- `proxy.js` retains a direct full `/overview` refresh in addition to its SSE/watchdog
  route; `wallets.js` uses the same full overview and has not joined the live-data route.
  These are concrete remaining sources of avoidable work and inconsistent navigation
  latency.
- Recommended next decision: complete the existing section-oriented REST/SSE design with
  a requested-sections initial endpoint (or individual initial reads), then measure
  browser navigation and server timings on target hardware. A Next.js migration is not
  justified by this evidence: it would retain the local Spring API/driver latency and add
  a Node runtime or a second build/deployment surface. If component navigation is desired
  after the loading work, consider a static React/Vite SPA served by Spring rather than
  SSR-oriented Next.js.
- Evidence: `static/js/core/live-data.js:101-130`,
  `controller/AgentStatusService.java:181-202`, `controller/MiningController.java:549-584`,
  `static/js/pages/proxy.js:47-61`, `static/js/pages/wallets.js:88-101`. Syntax-only check
  of all static JavaScript files passed; no live target performance trace was available.

## 2026-10-11 — Explicit runtime-agent i18n dictionary directions

- Renamed the runtime, text-keyed dictionaries from ambiguous `agent` and
  `agentGerman` to `agentEnglishToGerman` and `agentGermanToEnglish`. This is
  a nomenclature-only change: English Agent messages still render in German
  under the German locale, and German Agent messages still render in English
  under the English locale.
- Updated the renderer, both i18n verification scripts and the i18n contract.
  The catalog checks now reject either legacy ambiguous dictionary name. The
  static pages receive a matching cache-version update so a browser cannot mix
  a renamed catalog with stale renderer code. The verification also corrected
  the Fee-tier badge's legacy literal `t('Dev-Fee')` call to the existing
  `dev_fee` frontend key. A final distinct cache token was issued after the
  complete rename, because an open Chrome profile had retained an intermediate
  catalog/renderer combination even after a hard refresh; a fresh profile
  confirmed the completed resources render translations.

## 2026-10-11 — Host networking is the only supported Docker Compose mode

- The owner decision is implemented: `docker-compose.pc-agent.yml`, the
  release `pc-agent.compose.yml` asset and `install-docker.sh` now use Linux
  host networking only. The bootstrap no longer offers `--network bridge`.
  NVIDIA and AMD overlays continue to compose with the host-network base file.
- `docker-compose.pc-agent.standalone.yml` remains only as a same-behaviour
  legacy filename alias. New stable and beta releases no longer publish a
  bridge-mode Compose asset. The Docker README and wiki explicitly reject
  bridge deployments for the PC-Agent.
- This removes the Docker bridge namespace from the UDP discovery path; it does
  not make broadcast routable. Agents still need one Layer-2 broadcast domain,
  and routed subnets require a future relay or configured-peer design.
- Verification: `sh -n standalone/install-docker.sh`; `docker compose config
  --quiet` for the base file and its NVIDIA/AMD overlays; `git diff --check`.

## 2026-10-11 — Docker Compose port and LAN-discovery review

- Reviewed all PC-Agent Compose profiles, the Dockerfile and the peer/proxy
  discovery implementation. The normal bridge profile publishes the required
  Agent TCP endpoint (`8084`) plus the external-proxy heartbeat (`8092/udp`)
  and peer-agent discovery (`8093/udp`). GPU overlays preserve those mappings;
  the host-network compatibility profile needs no mappings.
- Found a deployment boundary: publishing UDP 8093 does not make the
  peer-agent broadcast emitted inside a Docker bridge namespace reach the
  physical LAN. Therefore peer auto-discovery must not be claimed for the
  ordinary Compose bridge profile across physical LAN subnets. Proxy broadcast
  discovery has the same limitation. Host networking on a Linux host, or a
  future explicit relay/configured-peer design, is required for reliable
  physical-LAN discovery. Host networking also cannot cross a routed IP subnet:
  UDP broadcast is limited to one Layer-2 broadcast domain. Direct
  configuration remains available for an external
  proxy; the peer dashboard has no configured-peer fallback today.
- Added [Docker networking and LAN discovery](docker-lan-discovery.md) with
  the port ownership, network-mode constraints and verification/rollout gates.
  The Docker bootstrap and its stable/beta release assets now default to a
  host-network Compose profile (`--network host`); bridge mode is an explicit
  `--network bridge` compatibility selection. NVIDIA and AMD overlays remain
  usable with both profiles. The existing `pc-agent.compose.yml` release asset
  remains a bridge-mode alias so older bootstrap scripts do not encounter a
  missing asset. The Docker guide and ownership wiki now state the same
  constraint.
  Documentation only; no user-visible UI/API text changed.
- Verification: `docker compose config --quiet` passed for the base, Intel,
  AMD, NVIDIA and host-network profiles; `git diff --check` passed. No
  two-host physical-LAN or host-firewall run was available, so this is not an
  end-to-end deployment certification.

## 2026-10-11 — Known-bug backlog and agent handoff

- Added `docs/KNOWN_BUGS.md` as the structured, repository-local PC-Agent
  defect backlog. It defines status/priority semantics, a triage order, and
  required evidence, diagnosis, remedy, and verification fields.
- Recorded the initial items: missing-wallet start diagnostics (BUG-001), a
  frontend cache/invalidation policy gap (BUG-002), and native Stratum-proxy
  lifecycle recovery (BUG-003). Existing statements that were not yet backed
  by a reproduction are explicitly labelled as unverified or hypotheses.
- `AGENTS.md` now directs agents to inspect this backlog after scoping their
  assigned work and to tackle an applicable item only when sufficient context
  capacity remains; the user task and existing work keep priority.
- Documentation-only change; no UI/API strings or executable behavior changed,
  so no locale runtime check was required. Verification: Markdown links and
  modified files inspected; `git diff --check` passed.

## 2026-10-10 — Clean-code refactoring: closed coin/state enums and the miner adapter seam

- `coin/Coin` is now the single coin identity (id, ticker, algorithm plus aliases, device kind, experimental flag, `miningCoins()`, `gpuCoins()`, `sharedSrbCoins()`, `assignableTo(cpuWorker)`, `byAlgorithm()`, `byIdOrNull()`); a new coin is one enum entry plus an adapter. Closed state enums `DownloadState`, `SweepRunState` (+`terminal()`), `SweepMode`, `ProxyMode`, `FeeRollMode`, `BenchmarkMode`, `WorkerCoinPolicy`, `WorkerIds` and `mining/ProxyLifecycle` replaced magic strings across controllers and services without changing any wire name. Iron rule: every enum wire name is a REST contract; getters return `.wireName()`.
- `miner/` adapter seam: `CoinMiner` (lifecycle, `setPowerCap`, `updateProxyRoute`, `setupComplete()`, `appendBenchmarkEvent`), `CpuMiner` (+`readyForStart`, `savedRoute()` — XMRig login parsing stays in the XMR adapter) and `GpuCoinMiner` (per-GPU control, `configuration/applyConfig/validateConfig`, `reportedAlgorithm`). `MinerFactory` (`miner/cpuMiner/gpuMiner/srbGpuMiner/gpuMiners/supports`) is the only lookup used by `MiningService`, `EconomicPlanningService`, benchmark/sweep services, `WorkerAssignmentService` and `MiningController`; coin-specific knowledge lives only in `XmrMinerService implements CpuMiner`, `PearlMinerService implements GpuCoinMiner` and the per-coin `SrbGpuCoinView` delegate.
- Local REST consolidation: `/{coin}/download`, `/{coin}/remove` and `/{coin}/configuration` replace the monero/pearl special routes (GPU removal still stops all GPU runs because SRBMiner is one shared installation). `ProxyOverview` is coin-agnostic (`coinRoutes[]` plus `@JsonAnyGetter` re-emitting the legacy flat `<coinId>Url`/`<coinId>FeeReady` fields that Solar-Miner-Node `MinerAgentController.prepareProxy(urlField)` reads), pinned by `ProxyOverviewWireFormatTest`. `MinerCatalogService.MinerOption` gained `coinName` with a backward-compatible 15-arg constructor. External Node routes (`/api/agent/external/monero|pearl/configuration`) are unchanged by design; the frontend (`proxy.js`, `software.js`) now consumes `coinRoutes[]` and the generic `/remove`.
- Verification: `JAVA_HOME=/home/lukas/.jdks/temurin-21 sh gradlew test standaloneJar --no-daemon` → BUILD SUCCESSFUL, 127 tests in 33 suites, 0 failures/errors/skipped (results re-counted from `build/test-results/test` on 2026-10-10). `node --check` passed for `proxy.js` and `software.js`; `git diff --check` clean. Manual UI smoke (proxy page, software page, wallet editor) remains an open rollout gate. Changes are uncommitted on `beta` pending owner approval; see `REFACTORING-HANDOVER.md` for optional follow-ups.

## 2026-10-10 — Worker inventory loading regression

- The Worker page now renders the `/api/agent/local/workers` result immediately. Miner catalog loading only gates assignment controls; an unavailable catalog no longer hides the inventory or local start/pause actions.
- The five-second refresh is single-flight. A slow request can complete instead of being discarded by a newer polling revision on every cycle. The catalog failure notice is localized in German and English.
- Verification: `node --check` for the changed page and catalog and `git diff --check` passed. A jsdom smoke run held the miner-catalog request pending and confirmed that the CPU row still rendered while assignment stayed disabled. The local live Agent returned one CPU and four GPUs from `/api/agent/local/workers`; it was still serving an older `workers.html`/`workers.js` resource copy, so the running process must be updated before this frontend fix is visible there.

## 2026-10-10 — Central Node automation control

- Node remote-control consent and per-worker permissions now live on the localized `/automation.html` page. Worker setup and the Worker overview retain only coin/miner assignment and local start/pause operations, so changing a worker profile cannot accidentally change Node access.
- The global consent remains the server-side gate for all Node routes. Worker-level permissions are configurable only after that gate is enabled; disabling it blocks every Node command while preserving the individual worker choices for a later explicit re-enable.
- The fee-tier contract now follows that explicit consent only. Revoking global remote control immediately pushes the reduced `proxy` tier (the 1% Dev-Fee tier) to the managed/external proxy; prior Node activity cannot keep the higher Node tier alive.
- German and English frontend catalog entries cover the new page, navigation, notices and accessibility-facing labels. The controls capture the selected value before rendering the pending state, so an enable action cannot be turned into a disable request. Verification: `node --check` passed for the changed frontend files; JDK 21 focused `FeeTierServiceTest` and `WorkerAssignmentServiceTest` passed; `git diff --check` passed.

## 2026-10-10 — Benchmark default-payout fallback and actionable failure

- Sequential benchmarks now cover every locally integrated coin (Monero, Pearl, Ravencoin, Ethereum Classic, Decred and Quantus). Before the run, every installed GPU miner with a detected GPU receives its SolarMiner default route when neither a worker assignment nor an operator wallet/pool route exists. An unavailable default payout skips only that coin's phase rather than aborting another ready miner; the skipped phase is recorded in its console and the final session summary. GPU-coin phases select only their own algorithm's workers and restore GPU miners that were running before the session.
- When no phase can run, the start endpoint reports the precise default-payout/proxy/fee-target reason instead of only saying that a miner must be configured. The frontend renders these dynamic Agent diagnostics in German and English, including nested skipped-coin messages.
- The default payout remains proxy-resolved at start time; no wallet or pool credentials are invented or persisted when the SolarMiner target is unavailable.
- Verification: JDK 21 focused `BenchmarkSessionServiceTest`, `node --check src/main/resources/static/js/core/i18n.js`, and `git diff --check` passed.

## 2026-10-09 — Worker start diagnostics, wallet-free start, Windows bootstrap, AMD Docker sensors

- Local worker starts now return HTTP 400/409 with a concrete reason instead of a bare `false`; the Workers UI displays the response and refreshes the worker state. Sequential benchmark skips retain the miner's start error in the session phase summary and console. The benchmark start UI reads Spring's `detail` response field. These messages remain local to the Agent API; Node protocol payloads did not change.
- Starting an assigned GPU worker without a saved wallet now resolves the marked house payout from the proxy and configures the assigned devices and matching pool, wallet and worker before launch. Sequential Pearl benchmarks prepare the same route; Monero already used `XmrMinerService.ensureDefaultConfiguration`. A missing house target or proxy route stays a visible failure, since an unverified destination cannot be substituted. The default route directs the entire user payout to SolarMiner; the existing fee and wallet UI must keep this clear.
- RVN/ETC command inspection confirmed `--algorithm-gpu` and the coin-specific `--nicehash true`/`--esm 2` flags. The recorded Linux Titan RTX runs reached nonzero hashrates after the GPU parameter correction. The earlier Windows RTX 2080 Ti epoch failure, pool accepted shares, fee switching and payout verification remain open; no new support certification is claimed. See [RVN/ETC integration](rvn-etc-integration.md).
- The native Windows standalone launcher now runs a once-per-install bootstrap. It checks folder write access, Administrator token and NVIDIA tool presence, reads Defender's exact exclusion state, and offers an explicit UAC action to add the install folder. It verifies the state after the helper exits and can be rerun with `-Bootstrap`. Microsoft documents that `Add-MpPreference -ExclusionPath` suppresses scanning for that folder and warns that exclusions reduce protection: [cmdlet](https://learn.microsoft.com/powershell/module/defender/add-mppreference), [exclusion guidance](https://learn.microsoft.com/en-us/defender-endpoint/configure-contextual-file-folder-exclusions-microsoft-defender-antivirus). The launcher cannot certify a GPU power write; `LocalGpuPowerService` remains the readback gate. Native Windows/UAC and third-party antivirus behavior still need host testing.
- The AMD Docker overlay now exposes the host powercap class and symlink target read-only. AMD CPU package watts require a host `amd-rapl` `energy_uj` counter; this workspace host exposed no readable counter, so a real AMD CPU watt reading was not demonstrated. Compose config resolved cleanly.
- Workspace and Agent `AGENTS.md` now require German/English review for every user-facing change. New Worker and benchmark error presentation is translated through the existing catalogs; further pre-existing untranslated strings need separate audit.
- Verification: JDK 21 `sh gradlew test --offline` passed; `node --check` passed for changed JavaScript; `docker compose -f docker-compose.pc-agent.yml -f docker-compose.pc-agent.amd.yml config --quiet` and `git diff --check` passed. Windows launcher execution, real Defender policy, AMD host sensor, and RVN/ETC accepted-share tests were not available in this Linux workspace.

## 2026-10-09 — C10 economic dispatch capability and cold-start guard

- Added the externally gated, versioned `GET /api/agent/external/capabilities` and `POST /api/agent/external/economic-plan` contract. It exposes only worker identity, allowed coins, fee/route readiness and optional performance observations; no wallet, pool credential or driver command crosses the Node boundary.
- `AUTO` is a new explicit local worker coin policy. Existing and newly discovered workers remain `FIXED`; a Node cannot enable `AUTO` remotely. A plan must have a 30-second to 30-minute TTL, a locally enabled AUTO worker, a configured fee-ready route and a positive local profile.
- Cold start fails closed: a profile is a positive live hashrate plus measured mining watts, or a stable local GPU efficiency-sweep result. Unknown candidates are reported as needing a benchmark and cannot be selected by a plan. Accepted plans restore their prior assignment/target at TTL expiry unless an operator changed it meanwhile. This prevents theoretical GPU profitability from displacing a measured ASIC/PC workload.
- Node Core and the Node client now proxy the additive payload via `/agent/economic-capabilities` and `/agent/economic-plan`, preserving the Agent as local safety authority. The Node scheduler has not been switched to automatic economic dispatch yet; it must consume durable benchmark/sweep profiles before enabling a coin-changing planner.
- Verification: focused `ExternalAgentControllerTest` and `WorkerAssignmentServiceTest` passed with JDK 21; Node `:compileJava :core:compileJava` passed. `git diff --check` passed.

## 2026-10-09 — Manual benchmark profiles feed economic dispatch

- A complete local manual benchmark now persists median H/s and measured watts under `(worker, coin, algorithm)` in `solarminer-agent/performance-profiles.json`. Incomplete, zero-power and zero-hashrate observations are deliberately omitted.
- C10 capability output prefers a fresh persisted `BENCHMARK` profile over an efficiency sweep and uses a stable sweep as fallback. This lets a newly benchmarked CPU or GPU participate in economic planning without relying on a theoretical hardware estimate.
- Verification: `MiningPerformanceProfileStoreTest` covers atomic persistence and case-insensitive algorithm lookup; focused PC-Agent tests remain required after the full Spring wiring change.

## 2026-10-09 — External proxy bypasses the local-proxy boot gate

- Selecting an external SolarMiner proxy now immediately marks the managed-local-proxy gate ready and stops any local child. The UI therefore remains operable while the remote proxy is selected; it no longer waits for a GitHub release download or a local proxy start.
- Mining readiness now requires the managed-proxy gate and child process only in local mode. External mode still verifies the configured remote proxy's API/listener and per-coin fee route before a miner can start.
- `ManagedProxyHealthTest` covers both a persisted external selection and a live switch away from local mode. Verification: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test standaloneJar --no-daemon` passed.
- Switching the shared proxy now rewrites existing XMRig, Pearl and GPU-coin routes to the selected host's per-coin Stratum endpoint, so a remote selection cannot leave miners targeting the old loopback route. Remote Monero and Pearl now also require their own advertised listener and fee target; an API-only connection is insufficient.
- `MiningControllerValidationTest.proxySetupDoesNotDependOnOptionalWindowsSensors` verifies every migration call; `ProxyConfigurationServiceTest.remoteMoneroRequiresItsOwnListenerAndFeeTarget` covers the remote Monero gate. `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test standaloneJar --no-daemon` passed.

## 2026-10-09 — Proxy heartbeat discovery and external-host mode fix

- Root cause for known addresses: `ProxyConfigurationService.configure` rejected every host while local proxy mode was active, although the UI must save the external host before switching modes. Saving is now allowed without changing the active loopback route; `setMode("external")` activates the saved host afterward.
- A second Windows-only blocker coupled proxy discovery/configuration to optional LibreHardwareMonitor readiness. Proxy search, host/mode selection and fee-roll selection no longer require the sensor helper; mining and power operations retain their own safety gates.
- Discovery now listens for the proxy's versioned heartbeat on UDP 8092 and sends the existing `SOLARMINER_PROXY_DISCOVER_V1` query to UDP 8091 from the same socket as a backward-compatible fallback. Candidate service/version/ports and the HTTP API are still verified before display.
- The standard container publishes `8092/udp`; its old `8091/udp` mapping was ineffective and conflicted with the proxy-owned query port. TCP 8084 remains the Node control/identity port. Native launchers now bind it explicitly to `0.0.0.0`; Windows operators still have to permit the Java/8084 listener on the Private firewall profile.
- Verification: full `gradle test --offline` passed under JDK 21 after adding the sensor-decoupling regression (10 seconds on the final incremental run). No two-host LAN or Windows Firewall test was available.

## 2026-10-08 — Post-benchmark one-time share prompt

- After a manual benchmark finishes while periodic benchmark sharing is OFF, `/benchmarks.html`
  now opens a modal asking whether to upload the just-measured results once, anonymously. The
  prompt appears only when the sharing consent is known to be disabled and at most once per
  page session; "Nicht teilen" (or Esc) dismisses it without any network call.
- `BenchmarkSharingService.reportManualResults` now retains the sampled workers even when
  sharing is off (`NOT_SHARED` status carries the real `sampleCount`), so the retained batch
  can be offered for upload. New `uploadManualResultsOnce()` + `POST
  /api/agent/local/benchmarks/sharing/upload-manual` send exactly that retained batch with
  `telemetryOptIn=true` while deliberately NOT flipping the persisted periodic consent — the
  admin ingest (`StandaloneBenchmarkIngestService`) treats the batch as one participant report;
  the next periodic interval still sends nothing because `state.sharingEnabled()` stays false.
  On success the retained samples are cleared; on failure the existing "Upload erneut
  versuchen" retry path stays available (it requires sharing to be on, matching its semantics).
- i18n: all new German strings (dialog title/body/buttons, notices) got frontend-catalog
  entries in alphabetical position; the dialog reuses the existing `.worker-editor`/
  `.dialog-actions` styles.
- Verification: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test` passed (full
  suite, exit 0) and `git diff --check` passed. Catalog coverage was verified by grepping every
  new `t()` literal and static-HTML string back into `i18n-catalog.js`. Live HTTP probe against
  a headless `bootRun` agent: `POST /benchmarks {"mode":"LIVE"}` returned 200 and completed;
  `upload-status` then showed `NOT_SHARED` with the real `sampleCount` (0 here, no active
  workers); `POST /sharing/upload-manual` returned `NO_DATA` as designed. The dialog itself was
  not clicked in a browser (no Chromium on this host); `node --check` on the page script passed.

## 2026-10-08 — Endpoint-confirmed orphan proxy cleanup

- On startup, the PC-Agent now queries the loopback proxy API before it cleans up a prior managed child. A current proxy must identify itself through `GET /api/health` as `solarminer-stratum-proxy`; only then are matching processes from the managed release directory terminated. This avoids killing a process solely because its command line resembles a proxy launch.
- Older published proxy releases fall back to their existing `GET /api/network/ip` endpoint, while the release-directory command-line marker still confines termination to a PC-Agent-managed proxy.
- Verification: focused `ManagedProxyHealthTest` covers the service identity distinction.

## 2026-10-08 — Managed proxy popup readiness

- The boot gate now reads the managed proxy's `/api/health` and checks a fresh per-launch instance ID plus service name. It rechecks directly when the gate API is requested, while the visible browser overlay refreshes that API every two seconds in addition to SSE. This closes the stale-popup path and prevents another process on the API port from confirming a new child. Older published proxy JARs still use `/api/network/ip` until the health endpoint ships.
- Verification: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test --offline --no-daemon` passed after four existing test fixtures were updated for the parallel fee-tier constructor change and the proxy-log fixture's literal `\\n` was corrected to a newline. `node --check` and `git diff --check` passed. No live Windows popup/proxy session was available; the sibling proxy's focused health-controller test passed.

## 2026-10-08 — Managed-proxy startup log in the boot gate

- The blocking startup gate now shows the managed Stratum proxy child's combined
  stdout/stderr as it starts. The browser reads only the fixed release-directory
  `proxy.log` through `GET /api/agent/local/proxy-gate/log?offset=…`; it cannot
  select a filesystem path. Reads are incremental and capped at 64 KiB per
  response, while the UI refreshes every 750 ms and stops when the gate closes.
- `ManagedProxyService` remains the owner of the child process and release log;
  no proxy implementation or cross-repository protocol changed. The agent's
  existing miner consoles remain separate.
- Verification: `compileJava` completed successfully as part of the targeted
  Gradle test invocation; `node --check src/main/resources/static/js/chrome/proxy-gate.js`
  and `git diff --check` passed. Test compilation is currently blocked by
  pre-existing uncommitted fee-tier changes whose three test fixtures still use
  the old `FeeTransparencyService` and `AgentWriteAccessFilter` constructors.

## 2026-10-08 — Repository split from Solar-Miner-Node

- Created this repository from `Solar-Miner-Node/pc-agent` (tracked files via
  `git archive`, 145 files; no `build/` or runtime `solarminer-agent/` state).
- Layout flattened to repository root: `src/`, `standalone/`, `Dockerfile`,
  `build.gradle.kts`, compose files, `MINER-INTEGRATION-GUIDE.md`.
- Added standalone Gradle root: `settings.gradle.kts` (`rootProject.name =
  "pc-agent"`), `gradle.properties` (`pcAgentVersion=1.1.7`,
  `pcAgentImage=verdox/solar-miner-pc-agent`), Gradle wrapper 8.14.5 copied
  from the Node repository, and `printPcAgentVersion`/`printPcAgentImage`
  tasks required by the release workflow.
- `.github/workflows/release.yml` adapted from the Node repo's
  `docker-deploy-pc-agent.yml`: same `pc-agent-v*` tag trigger, paths without
  the `pc-agent/` prefix, unused embedded-proxy checkout removed (the agent
  downloads the proxy JAR at runtime; the JAR-content guard stays).
- Windows launchers (`standalone/start-agent*.ps1|bat`) now resolve releases
  from `Solarminer-app/pc-agent` instead of `Solar-Miner-Node`. NOTE: until the
  first release is cut from this repository, the launchers find no release —
  keep the Node-repo release flow authoritative until then (removal from the
  Node repo is a separate, later step).
- Wiki pages copied from the Node repo's `docs/agent-wiki/` (pc-agent*.md,
  rvn-etc-integration.md); internal links adjusted, historical `pc-agent/...`
  paths documented as mapping to this repository root.
- LICENSE (AGPLv3) and TRADEMARK.md copied from the Node repository.
- NOT yet done (owner decision / follow-up): nothing was removed from
  `Solar-Miner-Node` — the module, its CI workflow and the Node wiki pages are
  still in place; this repository is currently a full copy plus standalone
  build wiring. Git history was not rewritten (plain copy commit pending).

## 2026-10-08 — Beta pipeline established; module removed from Solar-Miner-Node

- Added `.github/workflows/beta.yml` (push to `beta`): publishes
  `verdox/solar-miner-pc-agent` commit-tagged/`latest-amd64-beta`/`latest-beta`,
  builds the standalone JAR with the no-proxy-classes guard, and creates the
  `pc-agent-beta-<shortsha>` prerelease with beta launchers. Mirrors the former
  Node-repo `docker-beta.yml` pc-agent jobs exactly, with root-relative paths.
- Created local branch `beta` from `main` (push pending owner approval).
- Solar-Miner-Node side completed the same day: `pc-agent/` deleted, Gradle
  wiring (`include`, versions, print tasks), `docker-deploy-pc-agent.yml` and
  all pc-agent jobs in `docker-beta.yml` removed; Node compiles clean
  (`gradlew projects` shows only cgminerapi/core/proto/pv-api; compileJava
  EXIT 0). Node wiki pages for the agent moved to this repository
  (`docs/agent-wiki/`), `PC-AGENT-PV-POWER-CONTROL.md` moved to `docs/`, and
  the agent API contract section of the Node `docs/API.md` moved to
  `docs/API.md` here with a pointer left in the Node doc.

## 2026-10-08 — Fee tier enforcement + header status badge

- `FeeTierService` decides the effective dev-fee tier locally and pushes it to
  the proxy (`POST /api/v1/fees/tier`) on every consent-hook flip (listener on
  `AgentControlSettingsService.update`), on every external Node call
  (`AgentWriteAccessFilter` records presence; 15 min window) and via a 60 s
  self-heal. Rule fails toward the higher fee: consent hook on OR recent Node
  presence => `node`; only a genuinely Node-free agent runs `proxy`. The
  managed proxy child also starts with `--solarminer.fee.tier=` so a fresh
  child never resolves the wrong tier before the first push.
- Transparency: `FeeTransparencyService`, `PayoutDefaultsService` and the new
  `devFeeSummary()` read targets with the effective `tier`, so display and
  enforcement never diverge. Nothing hardcodes 2.5/1.0 — a referral code can
  shift or lower the total and the UI shows it live.
- Header badge (`js/chrome/fee-tier-badge.js`, all 8 pages): green lamp = Node
  attached, amber = proxy-only; label shows mode + live total dev fee; hover/
  focus tooltip lists SolarMiner/referrer shares, total and referral code.
  New SSE channel `fee-tier` (5 s) + `GET /api/agent/local/fee-tier`. i18n
  frontend catalog extended for every new string.
- Evidence: `sh gradlew test` green (JDK 21) incl. new `FeeTierServiceTest`
  (consent hook, presence window, proxy default). Browser rendering not
  exercised; badge verified by code review only.
- Open gates: end-to-end tier flip against a running proxy+fee-backend not yet
  observed on real hardware.

## 2026-10-09 — Linux powercap cycle fixed

- Diagnosed the running standalone Agent with same-origin API probes and a JVM
  thread dump. `/api/agent/local/overview` produced no response within 20 s and
  `/api/agent/local/workers` produced none within 30 s, while
  `/api/agent/local/miner-options` returned HTTP 200 in 4 ms. The owning HTTP
  thread had spent more than five minutes recursively scanning
  `/sys/class/powercap` in `LinuxSensorReader.energyFiles`; sysfs `device`,
  `subsystem` and `power` links formed ancestor cycles. Other Overview and Worker
  requests accumulated behind the synchronized sensor and worker monitors.
- Replaced the recursive, link-following directory test with a bounded
  `Files.find` traversal. The selected RAPL class entry is resolved once, links
  below that root are never followed, traversal depth is capped at eight, and
  counter selection remains deterministic.
- Added `LinuxSensorReaderTest`, which builds a temporary powercap tree with
  `device`, `subsystem` and `power` cycles and verifies within one second that
  the real `energy_uj` counter is found exactly once.
- Wallet and Worker initial reads now use the shared JSON client with its
  eight-second timeout. A later backend stall therefore produces the existing
  visible page error instead of keeping both configuration views blank forever.
- Verification: focused sensor and worker tests passed; full
  `sh gradlew test standaloneJar` passed on JDK 21; `node --check` passed for
  `wallets.js` and `workers.js`; `git diff --check` passed. The already-running
  JVM still contains the old loaded class and requires one restart before the
  runtime fix takes effect.

## 2026-10-09 — GPU power-limit efficiency sweep with stability gate

- New feature next to the benchmarks: `EfficiencySweepService` walks the driver
  power limit downward from the currently active cap (never upward; default 15 W
  steps, `solarminer.agent.sweep.step-watts`)
  for every configured GPU miner target (coin × GPU: pearl, ravencoin,
  ethereumclassic, decred, quantus) and keeps only steps that prove stable under
  real mining load.
- Hardware-protection gates (the hard constraint of every step): only
  driver-verified limits are written (`LocalGpuPowerService.setTotalPowerTarget`
  read-back check; driver and persisted user limits are never undercut); NVIDIA
  uses `nvidia-smi`, AMD uses AMD-SMI or Linux `amdgpu` hwmon; every 5 s sample reads the GPU
  temperature via the new `LocalGpuPowerService.readTemperatureC` and aborts the
  step above `solarminer.agent.sweep.max-temperature-c` (default 85 °C) or when
  the temperature is unreadable; a step is stable only with a live miner
  process, valid hashrate, pool connection and rejected-share ratio below
  `solarminer.agent.sweep.max-rejected-ratio` (default 0.10); the previous power
  limits and the previous miner running/paused state are restored and verified
  in the finally block, including cancel and failure paths. Incomplete restoration
  is reported as an error. External SRBMiner processes refuse the sweep entirely.
- Best stable value per device+algorithm (max H/J) is persisted atomically to
  `./solarminer-agent/gpu-efficiency-profiles.json` via `GpuEfficiencyStore`,
  together with the full step history. No wallets, pools or identities stored.
- `LocalRunLock` serializes the sweep against benchmark sessions; Node controls
  stay locked while either runs (`withExternalControl` now checks the shared
  lock). The benchmark's early external-miner return path releases the lock.
- API: `GET/POST /api/agent/local/efficiency`, `POST .../cancel`,
  `GET .../profiles` (`EfficiencySweepController`). UI: new "Effizienz-Sweep"
  panel on benchmarks.html with progress, cancel and per-device profile cards
  including the step history.
- Power writes are capability-probed with a same-value write/readback. Multi-GPU
  failures roll every card back to its live snapshot. Original limits are
  atomically persisted before the first change and recovered on graceful shutdown
  or the next process start after a crash.
- Verification: focused `LocalGpuPowerServiceTest` and `EfficiencySweepServiceTest`
  pass on JDK 21, covering NVIDIA permission probing, NVIDIA set/readback,
  multi-GPU rollback, AMD-SMI BDF control, Linux AMD sysfs fallback, crash
  recovery and a descending ladder that never exceeds the initial cap. Full
  `sh gradlew test standaloneJar` also passes on JDK 21.
- Open gates: sweep has not yet been run end-to-end against real GPUs on this
  host; temperature ceiling and step size should be validated on the target rig
  before recommending the stored best values as permanent limits. Native Windows
  AMD remains fail-closed: ADLX exposes the tuning limit as a percentage, not an
  absolute watt cap, so it cannot safely satisfy the existing watt-target contract
  without a separately shipped and hardware-validated native adapter.

## 2026-10-09 — Efficiency sweep: dev-fee fallback for unconfigured coins

- `EfficiencySweepService.targets()` now also measures coins the operator never
  configured: an ephemeral in-memory configuration is built from the fee-backend
  house target (`PayoutDefaultsService.resolve`) exactly like the empty-wallet
  path in the operator UI, so the sweep runs coin-by-coin over every supported
  GPU coin. `PearlMinerService` and `GpuCoinMinerService` gained a
  `sweepOverride`/`effectiveConfig()` layer: operator config always wins, the
  override is never written to disk, is applied per target and cleared after
  each target plus a safety-net clear for all targets in the finally block.
- Fallback configs pass the same `validate()` as operator configs; an
  unreachable fee target or invalid wallet simply drops that coin from the run.
- Verification: `sh gradlew test standaloneJar` passed on JDK 21.
- Open gates: unchanged — real-hardware end-to-end run still pending.

## 2026-10-09 — Worker UI dynamic strings use the localization catalog

- Shared table rendering now passes primitive cell values through `t()`, matching
  the existing element, status and table-header builders. This closes a shared
  gap that left dynamic worker status and control cells in German for English UI.
- Worker count/filter/help text now uses catalog lookups and `{count}` parameters;
  backend-provided miner availability reasons use the agent-text translator `s()`.
- Added the corresponding closed-catalog entries. Verification: `git diff --check`
  passed; no build or tests run (not needed for this frontend-only change).

## 2026-10-09 — Measurement-run lock now covers local UI controls too

- The benchmark/sweep lock previously only gated Node (external) controls. Local
  UI routes in `MiningController` (miner resume/pause, per-coin and global power
  targets, coin switch, GPU resume/pause, global pause/resume, Pearl and GPU-coin
  configuration) could start a second miner on a GPU while a benchmark or
  efficiency sweep owned it — corrupting the measurement and leaving a worker
  running against the operator's restore snapshot.
- All those routes now check the shared `LocalRunLock` (`localRunsFree()`);
  configuration changes answer HTTP 400 with a cancel-the-run hint, control
  toggles answer `false`. `AgentPowerController` GPU user-limit writes now go
  through `withExternalControl` as well (409 while a run holds the lock).
- Tests: `MiningControllerValidationTest` gained lock-held and lock-free cases
  (controls refused with no service interactions while held; allowed after
  release). `sh gradlew test standaloneJar` passed on JDK 21.

## 2026-10-09 — Efficiency-sweep start diagnosis on the Linux GPU host

- The live local API showed four NVIDIA TITAN RTX cards, SRBMiner installed and
  the managed proxy running, but every card reported
  `supportsDynamicPowerScaling=false`: the harmless same-value
  `nvidia-smi -pl` capability probe failed with `Insufficient Permissions`.
  This is the concrete reason the sweep had no eligible target; the previous
  generic install/configuration error pointed operators in the wrong direction.
- Sweep admission now distinguishes no GPU, no writable Power Cap, missing
  SRBMiner and missing ready route. For a driver-permission failure the HTTP 409
  detail tells the operator to run the Agent with the required
  administrator/root driver permission and includes a one-line per-model driver
  status (multiline tool output is collapsed). The controller now returns that
  detail explicitly as `{"message":"..."}`; Spring's default 409 response on
  this installation omitted `ResponseStatusException` messages, which forced
  the dashboard to show only its generic fallback.
- Verification: `EfficiencySweepServiceTest` covers the permission-specific,
  no-GPU and missing-miner diagnostics; `EfficiencySweepControllerTest` covers
  the HTTP 409 message consumed by the dashboard. Full
  `sh gradlew test standaloneJar` passed on JDK 21. The hardware sweep remains
  blocked until the Agent process can successfully write and read back an NVIDIA
  Power Cap.

## 2026-10-09 — Docker startup diagnosis and proxy-download backoff

- A Linux `latest-beta` container log showed that Tomcat was already serving on
  port 8084, while every proxy-release lookup failed with
  `UnresolvedAddressException`. The failing dependency was Docker DNS resolution
  for `api.github.com`, not Spring Boot or the NVIDIA capability configuration.
- The image healthcheck still called the removed shared
  `/api/agent/telemetry` route. It now calls the deliberately always-open,
  read-only `/api/agent/external/identity` endpoint, so a healthy Agent is no
  longer marked unhealthy by its own namespace access filter.
- Managed proxy release retries now back off for 60 seconds after an attempted
  lookup instead of logging a complete connection stack every five seconds.
  The dashboard's explicit retry remains immediate, and a cached verified proxy
  release remains the offline fallback.
- Docker documentation now records the `api.github.com` DNS probe and persistent
  cache behavior. Verification: `ManagedProxyHealthTest` covers the retry window;
  full `sh gradlew test standaloneJar` passed on JDK 21.

## 2026-10-09 — Model-cohort efficiency sweeps and live run plan

- Power-efficiency targets are now grouped only when coin, algorithm, vendor,
  model, driver range and effective user range all match. The first card runs the
  complete descending curve. Its siblings then validate the selected limit in
  parallel; an unstable sibling advances upward by the configured watt step up
  to its original cap. Every sibling still has to prove its own miner process,
  hashrate, temperature, pool health and rejected-share ratio, and profiles stay
  keyed by device ID plus algorithm.
- The session contract now exposes one `RunStatus` per GPU/coin/algorithm with
  full-vs-validation mode, queue/running/final state, planned limits, completed
  step results and live sample count. `secondsRemaining` estimates sequential
  reference work and counts sibling validations as parallel cohorts.
- The Benchmarks page renders that plan while the sweep runs: operators see
  completed and upcoming watt steps, measured hashrate/power for finished steps,
  current sample progress, per-device outcome and approximate remaining time.
  Persisted profiles continue to appear as soon as each device completes.
- Tests cover strict cohort separation, upward validation candidates, parallel
  ETA accounting and JSON exposure of queue/sample/ETA state. JavaScript syntax,
  focused tests and full `sh gradlew test standaloneJar` passed on JDK 21.

## 2026-10-09 — Breadth-first efficiency results across GPU coins

- Reference curves for separate coin/algorithm cohorts are now packed into
  conflict-free batches. Each concurrent reference owns a distinct physical GPU;
  when all available cards are occupied, remaining coins move to the next batch.
  This produces initial profiles across the supported GPU coins earlier while
  retaining the complete descending reference curve and per-device validation.
- The chosen reference is moved to the front of its cohort before execution, so
  later sibling validation uses exactly the matching reference profile. The run
  contract exposes its `referenceBatch`, and the ETA takes the longest run per
  parallel reference batch instead of summing those runs as sequential work.
- Focused scheduling tests verify that no batch assigns one device twice, all
  cohorts remain scheduled, and same-batch reference ETAs are counted in
  parallel. `node --check` for both changed JavaScript files, `git diff --check`
  and full `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test
  standaloneJar` passed. Live multi-coin SRBMiner and driver validation remains
  a hardware rollout gate.

## 2026-10-09 — Dependency-aware coin pipeline without batch barriers

- Replaced the two-stage "all references, then all validations" execution with
  a device-exclusive completion queue. Waiting sibling GPUs can run references
  for other coins; each completed reference immediately unlocks its own sibling
  validations, and unlocked validations have priority whenever their physical
  device becomes free. Later reference start groups no longer wait for every
  worker in an earlier group.
- Ephemeral fee-backend overrides now live for the complete sweep session. This
  avoids a concurrent task clearing a coin route while another card still needs
  it for a restart between validation limits; the existing final safety path
  still clears every override before miner and power-cap restoration completes.
- Pure scheduler tests cover validation priority, fallback to another coin while
  the validation device is occupied, and refusal to double-book the busy GPU.
  `node --check` for both changed JavaScript files, `git diff --check`, and full
  `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew clean test
  standaloneJar --no-daemon` passed. Live multi-coin scheduling remains a
  hardware rollout gate.

## 2026-10-09 — Resume or restart an interrupted efficiency sweep

- The local efficiency start endpoint now accepts `mode=resume` or
  `mode=restart` (restart is the default for existing callers). Resume carries
  forward only fully completed run/profile pairs from the interrupted in-memory
  session and reconstructs the dependency queue around them. Reference and
  validation roles must still match the newly discovered plan; incompatible,
  cancelled, failed and queued work is measured again.
- The Benchmarks page exposes both choices after an incomplete session:
  "Unterbrochenen Sweep fortsetzen" and "Komplett neu starten". Reused runs are
  visibly marked as checkpoint results while the remaining live queue and ETA
  continue normally.
- Cancelling after one or more stable steps retains those partial measurements
  in session status but marks the run `CANCELLED`; it no longer overwrites a
  previously complete persisted efficiency profile. Focused service/controller
  tests cover checkpoint filtering and the resume API. `node --check` for both
  changed JavaScript files, `git diff --check`, and full
  `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew clean test
  standaloneJar --no-daemon` passed. The checkpoint is lost on Agent restart by
  design; persistent resume would require an explicit versioned sweep-state
  contract rather than guessing from best-result profiles.

## 2026-10-09 — Consent-gated upload of complete efficiency curves

- `EfficiencySweepService` now hands every produced profile, including partial
  and no-stable-result step histories, to `BenchmarkSharingService` after miner
  and power-cap restoration. A cancelled partial profile is uploadable evidence
  but remains `CANCELLED`, is not persisted as the local best and is not reused
  by resume.
- The standalone payload adds `efficiencySweeps` with participant-scoped device
  pseudonym, GPU model, coin, algorithm, requested limit, measured H/s and watts,
  maximum temperature, stability and bounded note. Upload remains behind the
  existing default-off benchmark consent and one-time prompt. Payloads above 512
  points are chunked; backend upserts make a complete retry safe.
- `BenchmarkSharingEfficiencyTest` covers retention of both stable and unstable
  points while consent is disabled. The cross-repository receiver, schema and
  validation live in `admin-portal` migration V16 and its ingest service.

## 2026-10-09 — Sweep hashrate grace and compact run-list design

- The power-limit sweep restarts the miner at every step, but the per-step
  startup grace was only 95 s while SRBMiner legitimately needs longer: DAG
  build plus its one-minute average window keep the reported hashrate at zero
  for minutes on a healthy card. The sweep therefore declared working cards
  unstable ("Miner liefert trotz Pool-Jobs keine Hashrate"). The grace is now a
  configurable `solarminer.agent.sweep.startup-grace-seconds` (default 240,
  floor 60) and the ETA startup estimate rose from 20 s to 45 s per step.
- `GpuCoinMinerService`'s own first-hashrate watchdog was raised from 180 s to
  the named constant `HASHRATE_STARTUP_WATCHDOG_SECONDS = 300` so the sweep's
  grace (240 s) always decides stability before the miner monitor kills the
  process mid-measurement.
- Benchmarks page run list redesign: only RUNNING runs keep a full highlighted
  card; QUEUED runs collapse into a "Wartende Läufe (n)" `<details>` group and
  terminal runs into an expanded "Abgeschlossene Läufe (n)" group of one-line
  disclosure rows (best stable watts in the summary). Operator-opened
  disclosures survive the 1 s re-render via `openSweepGroups`/`openSweepRows`.
  New i18n keys added in alphabetical position; the sweep explainer paragraph
  was updated to state the four-minute hashrate grace and the collapsed layout.
- ETA unit tests updated for the 45 s startup estimate (160→210, 320→420).
  `node --check` for both changed JavaScript files, `git diff --check`, and
  full `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test
  standaloneJar --no-daemon` passed. Live multi-coin timing behaviour remains
  a hardware rollout gate.

## 2026-10-10 — Make GPU relay close test deterministic

- `GpuStratumRelayTest.closeClosesListenerAndActiveMinerConnections` continues
  to verify that closing the relay terminates an already active miner socket.
  Removed the immediate follow-up connect assertion: it raced listener shutdown
  against the OS TCP connect/accept path and was not a reliable contract check.
- The focused test could not be run in this environment because the Gradle
  8.14.5 distribution was not cached and outbound network access is blocked.

## 2026-10-10 — Apply Windows bootstrap to the beta launcher

- Both standalone launchers run the non-privileged permission, driver and
  Defender-state preflight on every start. If the exact install-directory
  exclusion is missing, the explicit UAC option is offered again; neither
  launcher silently changes Defender. Stable and beta retain separate markers
  only as timestamps of their most recent completed checks. `-Bootstrap`
  remains accepted for a manual invocation. Startup-check output is English-only;
  localized Windows exception messages remain external operating-system text.
- The startup preflight uses a console header, three numbered sections
  (installation directory, GPU power-control readiness and Defender), indented
  outcomes, and a highlighted action-required line before an optional UAC
  prompt. The output makes the no-change/default-safe path explicit.
- Stable and beta batch launchers now refresh their cached PowerShell launcher
  asset on each run using a temporary file and atomic replace. A failed refresh
  retains the last known-good cached launcher and states that fallback; a new
  install without one fails clearly. This closes the stale-launcher path that
  otherwise kept former bilingual output after a launcher release.
- Stable and beta launchers now require an Administrator token and stop before
  downloading or starting the Agent without one. A present Defender exclusion
  remains a report-only success path; only a missing exclusion produces the
  optional UAC prompt.

## 2026-10-10 — START: Mining/Controller-Abstraktion (Adapter-Schichten, Service-Reduktion)

**Ziel (vom Owner beauftragt, läuft noch):** Das gesamte `mining`-Package und die
Controller-Ebene des PC-Agents auf Abstraktionspotenzial prüfen. Gewünschtes Ergebnis:
Adapter-Schichten für Pools, Wallets, Coins, Miner-Engines, damit die Anzahl der
Services sinkt und pro-Coin/pro-Miner-Logik aus Services UND Frontend verschwindet.
**Status: Analysephase, noch keine Code-Änderungen.**

Vorgehen / Andockpunkte für andere Agenten:
- Bestehende Closed Seams (nicht verletzen): `coin/Coin` (einzige Coin-Identität),
  `miner/MinerFactory` (einziger Beschaffungspfad für Miner), `miner/CoinMiner`
  (Lifecycle-Vertrag), `mining/PoolBalanceProvider` (Pool-Adapter-Seam),
  `mining/ProxyLifecycle`, `mining/LocalRunLock`.
- Analyse parallel delegiert in 3 Stränge: (1) `mining/`-Package-Duplikate &
  Merge-Kandidaten, (2) Miner-Engine-Duplikate Xmr/Pearl/GpuCoin +
  `MinerEngineAdapter`-Vorschlag, (3) Controller-/Endpoint-Oberfläche +
  Frontend-Polling und Aggregate-Endpoint-Vorschlag.
- Größte Dateien als Hotspots identifiziert: EfficiencySweepService (816),
  PearlMinerService (696), MiningController (668), MiningService (599),
  GpuCoinMinerService (543), LocalGpuPowerService (534), XmrMinerService (481),
  ManagedProxyService (461).
- Frontend-Hotspots: `src/main/resources/static/js/` (Polling-Last pro Refresh).
- **Analyse abgeschlossen (2026-10-10):** Der konsolidierte Refactor-Plan mit
  Phasen 0–5, Zielarchitektur (`MinerEngineAdapter`, `MinerBinaryInstaller`,
  `ProxyFeeClient`, `AtomicFileStore`, `WalletRule`, Aggregat-`/status`) und
  allen file:line-Belegen liegt in
  `docs/agent-wiki/refactoring-adapter-layers.md` (neu, unverändert gültige
  Closed-Seam- und Wire-Format-Regeln sind dort mitgedacht). Noch KEIN Code
  verändert; nächste Phase wäre Phase 0 (reine Leaf-Extraktionen,
  verhaltensneutral). Offene Owner-Entscheidungen stehen am Ende des Plans.
- Wire-Format-Gesetz bleibt: Enum-Wire-Namen sind REST-Verträge für statisches UI
  UND Solar-Miner-Node; jede Konsolidierung braucht pinning-Tests bevor Endpoints
  bewegt werden (Vorbild: `ProxyOverviewWireFormatTest`).

### Teilfortschritt Phase 0 (2026-10-10, verhaltensneutral, unvercommitet)

- Neue Paket `pcagent/support/` mit drei Leaf-Helfern aus dem Plan:
  `AtomicFileStore` (temp→ATOMIC_MOVE→Fallback→Cleanup, fail-soft),
  `ProcessTermination` (destroy→Shared-Deadline→destroyForcibly inkl.
  Descendants), `LogTailer` (bounded FileChannel-Chunk-Read).
- Umgestellt: `ReferralConfigurationService.set` (Persistenzblock),
  `MinerProcessRegistry.stop` + `ManagedProxyService.terminateOrphanedProxyProcesses`
  + `ManagedProxyService.stopProcess` (Terminationsblöcke),
  `MinerConsoleService.read` + `ManagedProxyService.readLog` (Log-Chunk-Blöcke).
  Semantik 1:1 beibehalten (Graces 3 s/5 s wie vorher).
- Verifikation (Gradle-Distribution auf diesem Host nicht cached/network-blocked,
  daher direkter javac/JUnit-Launcher gegen Gradle-Classpath aus dem Cache):
  HEAD-Baum + diese Änderungen kompilieren vollständig (`-parameters`),
  JUnit-Konsolenlauf über `mining`+`controller`+`pearl`: **127/127 Tests grün**.
- Hinweis an den Parallel-Agenten: die untracked WIP-Dateien
  `AgentNetworkDiscoveryService.java`, `NetworkDashboardService.java`,
  `NetworkDashboardController.java` kompilieren derzeit NICHT
  (`Settings.networkSharingEnabled()` fehlt; `Enumeration`-Import fehlt in
  Zeile 213). Der Gesamt-Worktree-Build ist dadurch rot — nicht durch die
  Phase-0-Änderungen. Bitte in deren Session beheben.
- Übrige Phase-0-Ziele (11 weitere Persistenzblöcke: NodeAssessment,
  AgentIdentity, AgentControlSettings, MiningPerformanceProfileStore,
  GpuEfficiencyStore, BenchmarkSharing, PayoutDefaults, ProxyConfiguration ×3,
  MiningService, MinerCatalog, EnergyJournal) sind mechanisch nach demselben
  Muster umstellbar — nächster Andockpunkt.

### Phase 0 abgeschlossen (2026-10-10, Fortsetzung derselben Sitzung)

- Alle 12 Persistenz-Blöcke auf `AtomicFileStore.writeText`/`writeJson`
  umgestellt (Liste + Semantik-Details in
  `refactoring-adapter-layers.md` Phase-0-Checkliste); `writeJson` neu im Helfer.
- Verifikation: Compile HEAD+alle 15 eigenen Dateien + `support/`
  (`javac -parameters`, Gradle-Classpath aus Cache) OK; JUnit-Console
  `mining`+`controller`+`pearl`: **127/127 grün**. Unvercommitet.
- Nächster Andockpunkt: **Phase 1** (`support/ProxyFeeClient` + `FeeTarget`,
  `ProxyPorts`, Dev-Fee-Vereinheitlichung, Service-Merges) gemäß
  `refactoring-adapter-layers.md`.

### Phase 1 teilweise (2026-10-10, Fortsetzung)

- `support/ProxyFeeClient` + `FeeTarget` eingeführt; die 4 Kopien des
  `/api/v1/fees/{coin}/targets`-Parsings (`FeeTransparencyService` ×2,
  `PayoutDefaultsService`, `ProxyConfigurationService.feeReady`) und der
  `POST /fees/tier`-Client (`FeeTierService`) laufen jetzt durch den einen
  Client; `validPool`/`validPoolAddress` auf eine Stelle konsolidiert.
  Fail-closed-Semantik (leere Liste ⇒ nicht fee-ready) beibehalten;
  Konstruktor-Signaturen unverändert (Client wird intern erzeugt).
- Verifikation: Compile HEAD+17 Dateien + `support/`; JUnit-Console
  mining/controller/pearl **127/127 grün**. Unvercommitet.
- Phase 1 offen: `ProxyPorts`-Vereinheitlichung (3 Port-Quellen), Dev-Fee
  Doppelwahrheit (`FeeTransparencyService`-Switch vs. `MinerCatalogService`),
  Merges `ProxyStartupService`/`StandaloneFeeGuard`. Details in der
  Phasen-Checkliste von `refactoring-adapter-layers.md`.

## 2026-10-10 — NVIDIA-SRBMiner-Version und Worker-Konsolenpopup

- Die offizielle SRBMiner-MULTI-Matrix nennt NVIDIA Pascal, Turing, Ampere,
  Ada Lovelace, Hopper und Blackwell. Release 3.7.3 macht die neuen
  PearlHash-Kernel für SM86, SM89 und SM120 zum Standard und entfernt die
  vorübergehenden Kernel-Schalter. Der PC-Agent pinnt deshalb nun Windows und
  Linux auf den geprüften 3.7.3-Vertrag. Er übergibt weiterhin ausschließlich
  die per PCI-Adresse aus `--list-devices` ermittelte `--gpu-id`; SRBMiner wählt
  den Architektur-Kernel. Ein atomar geschriebenes Versionskennzeichen sorgt
  dafür, dass alte oder nicht versionierte Installationen erst nach einem
  ausdrücklichen, SHA-256-geprüften Update wieder als einsatzbereit gelten.
- Ein Klick auf eine Worker-Zeile öffnet nun die persistierte, pro Worker
  getrennte Miner-Konsole als Dialog. Der Client lädt binäre UTF-8-Blöcke
  inkrementell, verwirft bei einem neuen Miner-Lauf die alte Ansicht, folgt
  neuer Ausgabe am unteren Rand und bietet den vollständigen Log-Download.
  `Einrichten`/`Ändern` bleibt die separate Zuweisungsaktion. Sämtliche neue
  Dialog-, Status- und Accessibility-Texte sind im deutschen/englischen
  Frontend-Katalog geschlossen; Miner-Rohausgabe bleibt unverändert.

## 2026-10-10 — Release-owned one-command installers

- Added stable-release bootstrap assets for native Linux, Docker and Windows. Native Linux installs missing base tools through apt/dnf/yum/zypper/pacman/apk, provisions a private Adoptium x64 JRE 21, verifies its published SHA-256, then verifies and starts the PC-Agent JAR. Windows downloads the existing release launcher, which already provisions and verifies its private JRE and Agent. Docker installs Engine/Compose through the system package manager when absent, downloads release-owned base/NVIDIA/AMD Compose files, validates the selected stack and starts it.
- GPU/kernel drivers are an explicit safety boundary: the Docker bootstrap checks existing NVIDIA driver + Container Toolkit or AMD `/dev/kfd` + `/dev/dri`, but never installs or replaces host drivers. The new POSIX installer diagnostics are bilingual German/English. Package-manager/Docker output remains external verbatim text, and the delegated pre-existing Windows launcher startup checks remain English-only as already recorded in `standalone/README.md`. Starting the Agent still does not download or start a miner.
- The stable release workflow now publishes all three bootstrap scripts and all three Compose assets, and syntax-checks both POSIX shell installers before upload. Landing and documentation consumers use `Solarminer-app/pc-agent/releases/latest/download/**`; these URLs require the first stable release from this repository before public rollout.
- Stable/beta channel selection is explicit end to end. The beta branch bootstrap resolves the newest published `pc-agent-beta-*` prerelease, uses separate native/Docker data directories and selects `latest-beta`; the stable path continues to use GitHub's latest stable release and `latest`. The beta workflow now publishes the same installer and Compose asset set as the stable workflow.
- Verification: `sh -n` and `bash -n` for both shell scripts, release-asset staging checks, landing tests/typecheck/lint/build, Compose base/NVIDIA/AMD validation and `git diff --check`. PowerShell and Docusaurus build tools were unavailable locally; the Windows bootstrap, package-manager mutation, Docker daemon, UAC, GPU drivers and future stable-release downloads were not executed.

## 2026-10-10 — PC-Agent-Frontend DE/EN-Durchgang

- Alle neun HTML-Seiten sowie alle JavaScript-Dateien unter `src/main/resources/static/js/` auf sichtbare Texte, Statusmeldungen, Formulardetails und Accessibility-Labels geprüft. Fehlende statische und dynamische Katalogeinträge ergänzt; variable Sätze verwenden nun Platzhalter statt fertig zusammengesetzter, nicht auflösbarer Übersetzungsschlüssel.
- Benchmark-, Sweep-, Hardware-, Telemetrie-, Proxy-, Wallet-, Software- und Worker-Meldungen aus der Agent-API werden an den sichtbaren Stellen über `s()` lokalisiert. Bekannte deutsche und englische Agent-Status sowie variable Diagnosepräfixe sind im geschlossenen Katalog bzw. in `i18n.js` abgedeckt. Bereits übersetzte dynamische Meldungen werden beim Weiterreichen durch gemeinsame UI-Helfer nicht erneut als fehlende Übersetzung gemeldet.
- `scripts/check-i18n.cjs` prüft alle neun statischen Seiten, literale `t()`-Schlüssel, Platzhalter sowie die Umwandlung in beide Sprachrichtungen; Vertrag und Ausnahmen stehen in `docs/agent-wiki/frontend-i18n.md`. Externe Miner-Rohausgabe, Diagnoseausgabe fremder Tools sowie technische Hardware-/Pool-/Wallet-Kennungen bleiben unverändert.
- Verifikation: `node scripts/check-i18n.cjs`, `node --check` für alle Frontend-JavaScript-Dateien und `git diff --check` erfolgreich. Ein visueller Browserlauf mit Agent-API war auf diesem Host nicht verfügbar.

## 2026-10-10 — Key-basiertes Übersetzungswörterbuch (Frontend-Migration)

- Der Frontend-Katalog `js/core/i18n-catalog.js` wurde von deutschen Quelltexten als Schlüsseln auf ein geschlossenes key-basiertes Wörterbuch umgestellt: 1212 Einträge der Form `snake_case_key: {de: …, en: …}`. `t('key', {param})` löst nach Locale auf; unbekannte Keys warnen im Console und lassen `scripts/check-i18n.cjs` fehlschlagen.
- Alle 9 HTML-Seiten referenzieren Keys über `data-i18n` (Textinhalt) sowie `data-i18n-title`/`-placeholder`/`-aria-label`; der deutsche Text bleibt als No-JS-Fallback im Markup. Alle 456 dynamischen deutschen String-Literale in `js/pages`, `js/chrome` und `js/core` wurden auf Keys umgestellt (456 Ersetzungen, vollautomatisch, nachher `node --check`-geprüft).
- Agent-seitige Laufzeittexte können keine Keys tragen; sie bleiben text-keyed in den beiden Agent-Wörterbüchern (`agent`: EN→DE, `agentGerman`: DE→EN). `s()` löst zusätzlich Frontend-Keys auf, damit `Error(t('key'))`-Meldungen über `s(error.message)` übersetzbar bleiben. Die Präfixregeln für zusammengesetzte Agent-Diagnosen sind unverändert.
- Fehlerbehebungen beim Durchgang: Pool-Ortsnamen/Anbieternamen in `pool-catalog.js`/`wallets.js` liefen unübersetzt durch (`t(entry.name)` ergänzt); `coinTicker('none')` in `workers.js` konsolierte den rohen Wert statt „Frei"; Nav-Link „Overview" hatte einen identischen de/en-Eintrag (auf „Übersicht" korrigiert); ein hartkodierter deutscher Fallbacktext in `workers.js` erhielt einen Key.
- Verifikation: `node scripts/check-i18n.cjs` (Wörterbuchform, alle `data-i18n`-Referenzen, alle literalen `t()`/`s()`-Keys, Interpolation, beide Sprachrichtungen, Selector-Speichern/Reload), `node scripts/simulate-i18n-render.cjs` (beide Locales aller 9 Seiten, keine falsch-sprachigen Textknoten), `node scripts/scan-i18n-literals.cjs` (0 unkeyed deutsche Literale) und `node --check` für alle Frontend-JS-Dateien erfolgreich. Ein visueller Browserlauf mit Agent-API war auf diesem Host nicht verfügbar (Chromium startet hier nicht).

### Handover an Folgesitzung (2026-10-10)

- Auftrag an anderen Agenten: Phase 1 (Rest) fortsetzen. Diese Sitzung committet
  nichts — alle Phase-0/Phase-1-Änderungen liegen als unversionierte
  Arbeitsstands-Änderungen im Worktree (`git status` zeigt sie unter `M` plus
  das neue Paket `src/main/java/de/verdox/solarminer/pcagent/support/`).
  Nicht erneut implementieren, sondern auf dem Worktree aufsetzen.
- Achtung: Die WIP-Dateien des Network-Dashboard-Agenten
  (`AgentNetworkDiscoveryService`, `NetworkDashboardService`,
  `NetworkDashboardController`) kompilieren weiterhin nicht; Verifikation daher
  wie im Verifikationsabschnitt von `refactoring-adapter-layers.md` beschrieben
  gegen HEAD+eigene Dateien, nicht gegen den nackten Worktree.

## 2026-10-10 — Coin-Auswahl für Messläufe und Miner-Log-Diagnose

- Betreiberbefund: Eine GTX 1080 (Pascal/SM61) startet PearlHash nicht; SRBMiner meldet
  `Could not set up ctx -> algorithm`. Das ist ein Upstream-Fehler des Miners (Kernel kann für
  diese Architektur nicht erzeugt werden), kein Adapter-Aufruffehler: Der Agent übergibt korrekt
  `--algorithm pearlhash` plus PCI-verifizierte `--gpu-id`; SRBMiner 3.7.3 wählt den Kernel selbst.
- **Coin-Auswahl auf der Benchmark-Seite:** Die Benchmark-Maske zeigt jetzt eine Checkbox pro
  Mining-Coin (Quelle: `/api/agent/local/miner-catalog`; nicht installierte Coins sind gesperrt).
  Die Auswahl gilt für beide Messläufe: Benchmark (`POST /api/agent/local/benchmarks` mit
  optionalem `coins`-Feld im `StartRequest`) und Power-Limit-Sweep
  (`POST /api/agent/local/efficiency` mit optionalem JSON-Body `{coins:[...]}`). Die Auswahl wird
  im Browser unter `solarminer.pc-agent.benchmark-coins` persistiert; alle angehakt = uneingeschränkt
  (leere Liste). Unbekannte Coin-IDs lehnt `BenchmarkSessionService.validateCoinSelection` mit 400
  ab. `WorkerAssignmentService.prepareBenchmarkDefaults(Set)` und
  `EfficiencySweepService.targets(discovered, Set)` respektieren die Auswahl; im LIVE-Modus werden
  die Worker direkt über ihre Algorithmus-Zuordnung gefiltert.
- **Abstrahierte Miner-Log-Diagnose:** Neu `miner/diagnostics/MinerLogDiagnostics` — regelbasierte
  Klassifikation roher Minerausgabe nach Miner-Familie (`srbminer`, `xmrig`) in Kategorien
  `GPU_ALGORITHM_UNSUPPORTED`, `GPU_DRIVER_FAILURE`, `POOL_CONNECTION`, `BINARY_BLOCKED`.
  `PearlMinerService` und `GpuCoinMinerService` klassifizieren beim Log-Drain (redaktiert) und
  merken die erste Diagnose pro Run; `fail()` hängt den Detailtext an die Fehlermeldung und die
  Worker-Konsole. Der Seam-Zugang läuft über `GpuCoinMiner.diagnosis(vendor, index)` (default null),
  delegiert von `SrbGpuCoinView` bzw. `PearlMinerService`. Der Effizienz-Sweep ersetzt
  "Miner-Prozess ausgefallen" durch die Log-Diagnose, wenn eine vorliegt. Ein neuer Miner hängt sich
  an dieselbe Stelle an: eigene Regeln in `MinerLogDiagnostics` plus `classify(family, line)` im
  Output-Drain — Orchestrierung sieht nur Kategorie und Detailtext.
- Diagnose-Detailtexte sind agent-autoriertes Englisch und im Agent-Wörterbuch
  (`i18n-catalog.js`, EN→DE) geschlossen übersetzt; die neuen UI-Texte der Coin-Auswahl haben
  Frontend-Keys (`choose_the_coins_for_measurement_runs_this`, `coins_for_measurement_runs`,
  `installed_lowercase`).
- Verifikation: `sh gradlew test --offline` (140 Tests, inkl. neuer
  `MinerLogDiagnosticsTest`, Auswahl-Test in `BenchmarkSessionServiceTest` und
  `EfficiencySweepControllerTest` für Coin-Durchreichung/Ablehnung), `node scripts/check-i18n.cjs`,
  `node --check` für `benchmarks.js`/`i18n-catalog.js`, `git diff --check` erfolgreich. Ein realer
  Start auf der GTX 1080 (Diagnose-Text im Dashboard) und ein Sweep mit Auswahl auf echter GPU
  wurden auf diesem Host nicht ausgeführt.

## 2026-10-10 — LAN-Peer-Dashboard: PC-Agents finden sich im Netzwerk

- Neues Feature: Das Dashboard zeigt unter „GEMEINSAMES NETZWERK" alle PC-Agents im selben
  Netzwerk samt deren Workern und Erträgen. Discovery spiegelt das Proxy-Muster
  (`ProxyDiscoveryServer`): `AgentNetworkDiscoveryService` sendet alle 2 s einen Heartbeat
  (`SOLARMINER_AGENT_DISCOVER_V1`, UDP 8093, `SO_REUSEADDR`) und alle ~30 s eine Query auf
  denselben Port; eine Receive-Schleife unterscheidet Query (antwortet immer, Identität ist
  nie consent-pflichtig) und Heartbeat (füttert die Peer-Tabelle, TTL 20 s). Ein Agent schließt
  sich selbst per eigener `agentId` (UUID pro Start) aus.
- Datenweg: `NetworkDashboardService.cards()` aggregiert eigene Stats + jeden Peer über
  `GET /api/agent/external/network-snapshot` (nur MINING/ERROR-Worker, Coin-Earnings, Totals;
  keine Wallets/Config). Abrufe laufen parallel (Virtual Threads), 1,5 s Timeout, 4 s Cache;
  403 = „LAN-Sichtbarkeit deaktiviert" (Karte bleibt sichtbar), Timeout = „nicht erreichbar".
  SSE-Kanal `network-agents` (5 s) + `GET /api/agent/local/network/agents` als REST-Fallback.
- Consent: neues Feld `networkSharingEnabled` (Boolean, default aus) in
  `AgentControlSettingsService.Settings` (Persistenz wie bisher; alte Dateien → null → aus).
  Der Filter (`AgentWriteAccessFilter`) gate `GET /api/agent/external/network-snapshot` an
  diesen Consent, NICHT am Node-Gate; POSTs bleiben überall gesperrt. UI-Schalter
  „LAN-Sichtbarkeit" auf `automation.html`, Umschalten via `POST /api/agent/local/power-control/network-sharing`.
- Discovery ist bewusst NICHT consent-pflichtig: abgewählte Maschinen bleiben als Peers sichtbar
  (Name/IP/Dashboard-Link), nur Worker/Ertrag fehlen. Docker: UDP 8093 in `Dockerfile` EXPOSE
  und `docker-compose.pc-agent.yml` ports ergänzt.
- Verifikation: `sh gradlew test` 140 Tests grün (neu: `AgentNetworkDiscoveryServiceTest` 3,
  `AgentWriteAccessFilterTest` Consent-Test), `node scripts/check-i18n.cjs` (1215 Keys),
  `node --check` für `overview.js`/`automation.js`. Live-Boot mit zwei Instanzen
  (:18084 Sharing an, :18085 Sharing aus): beide sehen sich gegenseitig (peerCount 1),
  Instanz A zeigt B mit `reachable:true, error:"LAN visibility is disabled on this agent"`,
  direkter Snapshot-GET auf B liefert 403, auf A 200. Docker-Zwei-Host-Broadcast nicht getestet.

## 2026-10-11 — Frontend-i18n: ausschließlich Schlüssel für Browsertexte

- `SolarMinerI18n.t()` akzeptiert nur noch Einträge aus dem geschlossenen
  `frontend`-Katalog. Der frühere Rohtext-/Textknoten-Fallback wurde entfernt, sodass
  ein versehentlich in Browsercode eingebauter Text in beiden Sprachvarianten als
  fehlender Schlüssel auffällt. Agent-, Miner- und sonstige Laufzeittexte bleiben über
  `s()` im getrennten Agent-Katalog behandelt.
- Die verbliebene Dashboard-Meldung „Alles im erwarteten Bereich“ hat nun den Schlüssel
  `everything_is_within_expected_limits` mit deutschen und englischen Werten. Interne
  Katalog-, Freigabe- und Live-Ereignisfehler verwenden ebenfalls übersetzbare Schlüssel.
- Der Literal-Scanner erkennt jetzt auch Displaytext ohne deutsche Sonderzeichen; die
  einzigen expliziten Ausnahmen sind technische Daten (Eigennamen/Algorithmusnamen,
  CSS-Selektoren/-Klassen und SVG-Pfade).
- Verifikation: `node scripts/scan-i18n-literals.cjs` (0),
  `node scripts/check-i18n.cjs` (1.219 Schlüssel, neun Seiten, beide Locales),
  `node scripts/simulate-i18n-render.cjs` sowie `git diff --check` erfolgreich.
- Alle neun HTML-Seiten laden Katalog, i18n-Laufzeit und Seitenmodule mit derselben
  `v=20261011-i18n-keys`-Versionskennung. Das verhindert einen gemischten Browser-Cache
  (neue Schlüssel im HTML bei altem Katalog), der sonst sichtbare Schlüsselnamen erzeugt.
  `check-i18n.cjs` erzwingt diese Versionskennung für Katalog und Laufzeit.

## 2026-10-10 — PC-Agent-Refactoring Phase 1 (Rest): ProxyPorts, Fee-Einzelwahrheit, Service-Merges

- Fortsetzung des Auftrags „START: Mining/Controller-Abstraktion" (Sitzung 2).
  Auf dem bestehenden unversionierten Arbeitsstand (Phase 0 + ProxyFeeClient)
  aufgesetzt, nichts neu implementiert.
- **`ProxyPorts` als einzige Coin→Port-Wahrheit:** Neu `mining/ProxyPorts`
  (Record + @Component, eine @Value-Quelle, EnumMap). Vorher drei Quellen:
  `ProxyConfigurationService` (@Value je Coin + EnumMap), `ManagedProxyService`
  (8 @Value-Felder + 7 harte `--proxy.coins.<coin>.port=`-Zeilen),
  `ProxyDiscoveryService` (api/monero als @Value). Alle drei konsumieren jetzt
  `ProxyPorts`; die Proxy-Startbefehle erzeugen die Coin-Ports generisch über
  `Coin.miningCoins()` (neuer Coin = ein Coin-Eintrag + eine Property).
  Bequemlichkeits-Methoden `moneroUrl()`…`quantusUrl()` gelöscht; alle Nutzer
  umgestellt: `MiningController` (2), `XmrMinerService` (3),
  `MiningControllerValidationTest` (nutzt `coinUrl(Coin.MONERO)`).
  Wire-Format unverändert: AnyGetter-Flachfelder kommen aus
  `ProxyOverview.coinRoutes` (`ProxyOverviewWireFormatTest` grün),
  `ProxyCandidate`-Feldnamen unverändert.
- **Dev-Fee-Doppelwahrheit aufgelöst:** Der Coin-Switch in
  `FeeTransparencyService.forCoin` (hartkodierte Prozentzahlen) ist gelöscht.
  `MinerCatalogService.MinerOption` trägt zusätzlich `developerFeeLabel` und
  `developerFeeSource` (additive Record-Felder neben `developerFeePercent`) und
  ist damit die einzige Quelle für Zahl, Label und Quelle; DECRED bleibt
  `known=false` (Prozentzahl unverifiziert). FeeTransparency liest den
  ausgewählten Katalogeintrag (`selected(coin)`).
  Bewusste Verhaltensschärfe: Die MINER-Gebühr folgt jetzt dem ausgewählten
  Miner statt einer harten Coin-Zuordnung — identische Werte, solange
  xmrig/srbminer-multi ausgewählt ist (teamredminer ist nicht integrierbar).
  Neue deutsche Agent-Texte (TeamRedMiner/BLAKE3/QPoW-Labels,
  „Miner-Katalogeintrag fehlt") sind im `agentGerman`-Katalog DE→EN übersetzt.
- **Service-Merges:** `ProxyStartupService` gelöscht — sein @EventListener
  (`configureInitialProxy`) ist jetzt eine Methode von
  `ProxyConfigurationService`. `StandaloneFeeGuard` gelöscht — die Guard-Logik
  läuft als `FeeTierService.enforceFeeRoute()` (@Scheduled fixedDelay 10 s wie
  vorher). MinerFactory wird dort über `ObjectProvider` lazy geholt, sonst
  entsteht ein Bean-Zyklus (FeeTierService→MinerFactory→XmrMinerService→
  PayoutDefaultsService→FeeTierService).
- **Nicht durchgeführt:** Optionaler Merge FiatRateService+EarningsForecastService
  → MarketDataService (Checkliste offen gelassen).
- Verifikation: Der Network-Dashboard-WIP kompiliert inzwischen; daher direkt
  gegen den Worktree: `sh gradlew test --offline` — 140/140 Tests grün
  (36 Testklassen, inkl. ProxyConfigurationServiceTest, ManagedProxyHealthTest,
  FeeTransparencyServiceTest, FeeTierServiceTest, MiningControllerValidationTest,
  ProxyOverviewWireFormatTest). `node scripts/check-i18n.cjs` grün (1219 Keys),
  `git diff --check` sauber.
- Status: unvercommitet (Owner-Commit-Freigabe steht aus); Checkliste in
  `refactoring-adapter-layers.md` auf `[x]` gestellt.

## 2026-10-10 — PC-Agent-Refactoring Phase 1 Optional: MarketDataService

- Optionalen Phase-1-Punkt nachgezogen (Sitzung 3): `FiatRateService` und
  `EarningsForecastService` zu einem `mining/MarketDataService` verschmolzen —
  gleicher Upstream (currency.solarminer.app), gleiches TTL-Cache-/Stale-Muster
  jetzt an einer Stelle: exchange-rates (1 h TTL, 5-min-Retry bei Fehler),
  mining-networks (10 min, Virtual-Thread-Refresh), coin-prices (10 min,
  2-h-Abschaltfenster). Verhalten 1:1 übernommen, keine Wire-Änderung:
  `Forecast`/`Snapshot`-Feldnamen feldgleich, REST-Routen
  (`/local/fiat-rates`, `/local/earnings`, `/external/earnings`) unverändert.
- Konsumenten umgestellt: `FiatRateController`, `MiningController`,
  `ExternalAgentController`, `NetworkDashboardService`. Alte Services gelöscht;
  Tests umbenannt (`MarketDataServiceTest`, `MarketDataServiceFiatTest`),
  Fixture-Konstruktor (coin-prices aus mining-networks-URL abgeleitet) erhalten.
- Doku: `docs/agent-wiki/pc-agent.md` (Seam-Abschnitt) und
  `refactoring-adapter-layers.md` (Checkliste + Zielarchitektur-Kontext) aktualisiert.
- Verifikation: `sh gradlew test --offline` 140/140 Tests grün (36 Klassen),
  `git diff --check` sauber. Unvercommitet (Owner-Freigabe ausstehend).

## 2026-10-11 — PC-Agent-Refactoring Phase 2: MinerBinaryInstaller, RouteValidator/WalletRule, gemeinsame Proxy-Route-/Share-Pfade

- Fortsetzung des Auftrags „START: Mining/Controller-Abstraktion" (Sitzung 4).
  Auf dem bestehenden unversionierten Arbeitsstand (Phasen 0–1 inkl.
  MarketDataService) aufgesetzt, nichts neu implementiert.
- **`miner/engine/MinerBinaryInstaller` (neu):** gemeinsamer Zustandsblock
  (`DownloadState`/`detail`/`progress` — Wire-Felder `downloadStatus`,
  `downloadDetail`, `downloadProgress` unverändert), `retry()` mit
  `WindowsAntivirusBlock`-Klassifikation, `remove()` mit Downloading-Sperre,
  `downloadAndVerify()` (DigestInputStream + Größenkappung + exakter
  Größen-/SHA-256-Vergleich), `extractArchive()`/`copyEntry()` mit der
  SRB-Traversal/Symlink-Wall (die sicherere Implementierung gewinnt — jetzt
  auch für XMRig), `installBinary()` (Temp + setExecutable + atomarer Move
  mit Fallback) inkl. Leer-Binary-Wall. Hooks: `binaryAvailable()`,
  `install()` (Release-Query + Install-Layout), `removeInstalled()`,
  `installerName()`, `downloadThreadName()`.
  Umgestellt: `XmrDownloadService` (152→~100 Z.; Staging-Extraktion statt
  Einzelbinary-Suche — Verhalten gleich, Wall jetzt strenger) und
  `SrbDownloadService` (313→~180 Z.; Version/Manifest via `AtomicFileStore`).
  Bewusste Nebeneffekte: XMRig nutzt jetzt 1-GiB-Entpackkappung +
  Link-Wall (Sicherheit, kein normaler Releasefall); SRB-Version/Manifest
  schreiben bei AtomicMove-Fallback nicht mehr ohne REPLACE_EXISTING
  (identischer Zielinhalt-Fall).
- **`miner/engine/RouteValidator` + `WalletRule` (neu):** identische
  Pool-/Proxy-/Worker-Regex je einmal; Wallet-Strategie pro Coin
  (PEARL prl1, RAVENCOIN base58+Checksum, ETHEREUMCLASSIC 0x, DECRED
  base58+Checksum, QUANTUS qz) über `WalletRule.forCoin(Coin)` — die
  Coin-Verzweigung liegt jetzt adapter-seitig in der Closed-Seam-Tabelle,
  nicht mehr in der Engine-Validierung. `PearlMinerService.validate` und
  `GpuCoinMinerService.validate` leiten durch; die RVN/DCR-Base58-Helfer
  wanderten nach `WalletRule.RouteValidation` (Tests umgestellt).
  Fehlermeldungen wortgleich beibehalten (Wire-/UI-Texte, i18n-Katalog
  unverändert). winzigste Verhaltensschärfe: Pearl-Pool-Portfehler nutzt
  jetzt dieselbe Pool-Meldung wie die Regex-Verletzung (die alte
  „Pearl pool port must be 1-65535"-Meldung stand in keinem Katalog und
  war nie übersetzbar); identische Port-Regex macht den Fall ohnehin
  praktisch unerreichbar.
- **`updateProxyRoute`-Near-Copies** (Pearl ≡ GpuCoin) auf
  `RouteValidator.withProxyRoute()`; **`updateShares`** (Pearl ≡ GpuCoin,
  wortgleich) als `MinerShareTelemetry.applySrbShares(pool, ShareSink)` —
  `GpuRun`/`Run` implementieren `ShareSink` (Phase-3-Vorbereitung:
  `WorkerRunState` kann den Sink übernehmen).
- **`writeConfig`-Kopien → `AtomicFileStore`:** `PearlMinerService.writeConfig`,
  `GpuCoinMinerService.writeConfig` (werfen weiter `IOException`; neue
  EN-Meldungen im `agent`-Katalog DE-übersetzt) und
  `LocalGpuPowerService.save` (fail-soft wie vorher).
- Neue Pinning-/Verhaltenstests `miner/engine/RouteValidatorTest`
  (Regex-Grenzfälle, withProxyRoute-Feldtreue, Wallet-Regel-Tabelle).
- Verifikation: `sh gradlew test --offline` **144/144 Tests grün**
  (4 Testklassen mehr als Referenz 140: Network-Dashboard-WIP anderer
  Agenten + RouteValidatorTest), `node scripts/check-i18n.cjs` grün,
  `node scripts/scan-i18n-literals.cjs` 0, `git diff --check` sauber.
- Status: unvercommitet (Owner-Commit-Freigabe steht aus); Checkliste in
  `refactoring-adapter-layers.md` auf `[x]` gestellt. Phase 3
  (AbstractMinerEngine) wartet auf Owner-Entscheide O1/O2.

## 2026-10-11 — PC-Agent-Refactoring Phase 3 (Kern): AbstractMinerEngine + O1/O2-Drifts (Owner-freigegeben)

- Auftrag „START: Mining/Controller-Abstraktion" (Sitzung 5). Owner-Entscheide
  dieses Tages: **O1 JA** (SRB-Crash-Restart), **O2a JA** (Pearl-Watchdog-Grace),
  **O2b JA** (Payout-Refresh für GPU-Coins). Im Wiki-Dokument unter „Offene
  Owner-Entscheidungen" als erledigt vermerkt. Auf Phase-2-Arbeitsstand
  aufgesetzt (Phase 2 dieser Sitzungsreihe, siehe voriger Eintrag).
- **VERHALTENSÄNDERND (bewusst, freigegeben):**
  - O1: `PearlMinerService` und `GpuCoinMinerService` erhalten je ein
    `@Scheduled(fixedDelay=30_000) restartAfterUnexpectedExit()` analog XMRig:
    max 3 Versuche pro Worker (`AbstractMinerEngine.MAX_CRASH_RESTARTS`),
    übersprungen solange `LocalRunLock.busy()` (Benchmark/Sweep besitzen die
    Hardware), Budget-Auffüllung bei Operator-Start/Stop/Resume. Konsolenzeile
    „SRBMiner watchdog restart n/3 (…)".
  - O2a: Pearl-Monitor läuft jetzt über die gemeinsame `monitorLoop` mit
    `SrbMonitorPolicy.pearl()`: Usable-Pool-Job-Regel (Hashrate rettet stale
    Jobs — behebt KAWPOW-Falschalarme), `reportedHashrate`-Fallback
    gpu.total→1m→1min, 20-s-Hashrate-Grace. Pearl behält: 90-s-Startup,
    120-s-Stall auf frischen Jobs, Job-basierte Pool-Health, EN-Detailtexte,
    HTTP-Fehler als eigener Detailzustand, keinen Hashrate-Abort.
    Nebenfolge: `poolHealthy`/`pool_connected`-Wire-Feld für Pearl kann jetzt
    auch bei stalem `last_job_received` mit aktiver Hashrate true sein.
  - O2b: `refreshDefaultPayout(coin)` neu in `GpuCoinMinerService` (Start-Pfad
    aller GPU-Coins). Wirkt nur bei Fee-Backend-Standardziel
    (`usesDefault`), validiert die neue Route, schreibt atomar und loggt
    „Payout updated to the current SolarMiner default target" (bestehender
    i18n-Katalogeintrag). Operator-Eigenwallets bleiben unberührt.
- **Mechanik zentralisiert (`miner/engine/`):** `WorkerRunState` ersetzt
  `PearlMinerService.GpuRun` + `GpuCoinMinerService.Run` (ein Feldsatz,
  typisiertes `relay`-Slot, `restartAttempts`); `AbstractMinerEngine` trägt
  `startOutputDrain` (Redactor-Hook; Pearl weiter mit Roh-Konsolenzeilen,
  GpuCoins redigiert — historisches Verhalten pro Engine erhalten),
  `monitorLoop` (Policy-Record `SrbMonitorPolicy` hält alle historischen
  Zahlen/Texte je Engine fest), `stopProcess` (5 s/5 s, Pearl-Dual-Console
  per Flag), `unexpectedExit`, Hasrate-Helfer. Engine-intern belassen wie
  geplant: `buildCommand`, GPU-Id-/PCI-Mapping, Login-Encoding,
  Status-Aggregation, Pause-Buchführung, Relay nur GpuCoin.
- **Messdienste generisch:** `GpuCoinMiner.captureState()/restoreState()`
  (`WorkerPauseSnapshot` auf bestehenden `runningGpuDeviceIds`/
  `manuallyPausedGpuKeys`) — `BenchmarkSessionService` snapshottet/restored
  jetzt alle GPU-Adapter in einem Loop (kein Pearl-Zweig, kein separates
  `runningGpuCoins()`-Handling), `EfficiencySweepService` nutzt denselben
  Snapshot für Pearl; Referenz-Binary-/External-Gates laufen über
  `MinerFactory.srbBinaryAvailable()`/`srbExternalMinerRunning()`.
- **Nebenbefund gefixt:** `SrbGpuCoinView.runningGpuDeviceIds()` lieferte
  `vendor:index` statt Device-Ids (Interface-Vertrag verletzt;
  `restoreWorkerState` konnte laufende Worker nie wiederstarten). Liefert
  jetzt echte Device-Ids — damit ist der Sweep-/Benchmark-Restore-Pfad für
  GPU-Coins erstmals tatsächlich funktionsfähig.
- **Nicht erledigt (Phase-3-Rest):** XMR als dritter Engine-Adapter inkl.
  Watchdog-Hook, `MeasurementRunGuard`/`SampleCollector` (A5),
  Live-Rollout-Gate an echter Hardware. O1/O2 sind gebaut und unit-getestet,
  aber NICHT an echter Miner-Hardware abgenommen.
- Verifikation: `sh gradlew test --offline` **149/149 Tests grün** (38 Klassen;
  neu `MinerEngineDriftTest` mit O1-Budget/-Messlauf-Sperre, O2b-Refresh +
  Operator-Schutz, Policy-Zahlen-Pinning), `node scripts/check-i18n.cjs` grün
  (keine neuen UI-Texte nötig; Watchdog-/Payout-Zeilen nutzen bestehende
  Katalogeinträge bzw. das `[SolarMiner]`-Präfix), `scan-i18n-literals.cjs` 0,
  `git diff --check` sauber.
- Status: unvercommitet (Owner-Commit-Freigabe steht aus); Checkliste auf ◐
  (Kern `[x]`, XMR-Adapter + A5 + Live-Gate offen).

## 2026-10-11 — PC-Agent-Refactoring Phase 3 (A5): MeasurementRunGuard + SampleCollector

- Auftrag „START: Mining/Controller-Abstraktion" (Sitzung 6). Auf dem
  Phase-3-Kern-Arbeitsstand aufgesetzt (Sitzung 5); nichts neu implementiert.
- **`mining/MeasurementRunGuard` (neu, paket-privat):** die identische
  Session-Mechanik beider Messläufe je einmal — Single-Thread-Run-Executor
  (Thread-Namen `pc-agent-benchmark`/`pc-agent-efficiency-sweep` unverändert),
  Cancel-Flag (`resetCancel`/`requestCancel`/`cancelRequested`),
  `LocalRunLock`-Buchführung (`begin`/`end`/`lockBusy`) und
  `close()`/`awaitTermination` für den @PreDestroy-Pfad. Beide Dienste halten
  die Lock-Disziplin wie vorher: `end()` in jedem terminalen Pfad inkl.
  Early-Return-Skip (Benchmark-Außenminer-Pfad) und Submit-Fehlerpfad (Sweep).
  `BenchmarkSessionService.withExternalControl` gateftet jetzt über
  `guard.lockBusy()` — gleiche Semantik wie vorher `lock.busy()`.
- **`mining/SampleCollector` (neu, paket-privat):** der wörtlich duplizierte
  Median (Benchmark `summarize`/`aggregateWorkers`/`persistPerformanceProfiles`
  + Sweep `measureStep`) und die Valid-Hashrate-Regel (endlicher Wert > 0) —
  je einmal. Die Sammel-Loops selbst bleiben dienst-spezifisch (Benchmark
  sammelt Worker pro Phase, Sweep Rate/Watt-Paare pro Power-Schritt); nur die
  Arithmetik und die Gültigkeitsregel sind gemeinsam. Median sortiert eine Kopie
  (Eingabe wird nicht mehr mutiert — verhaltensneutral, da alle Aufrufer die
  Quelle ohnehin verwerfen).
- **Keine Wire-/UI-Texte geändert:** alle Meldungen blieben wortgleich;
  verhaltensneutral (Phase-3-Regel gilt nur für die O1/O2-Drifts).
- **Nebeneffekt Sweep-`close()`:** die Interrupt-Behandlung wandert in
  `MeasurementRunGuard.awaitTermination` (setzt das Interrupt-Flag und meldet
  Nicht-Terminierung wie vorher); die Fehler-Session wird identisch gesetzt.
- Neue Tests `MeasurementRunGuardTest`: Median (leer/gerade/ungerade,
  Nicht-Mutation), Valid-Hashrate-Grenzfälle (NaN/0/negativ),
  gegenseitige Guard-Blockade über eine gemeinsame `LocalRunLock`
  (Benchmark↔Sweep-Semantik) und Cancel-Beobachtung + Reset über eine echte
  Lauf-Loop.
- Verifikation: `sh gradlew test --offline` **153/153 Tests grün** (39 Klassen;
  +4 gegenüber Referenz 149 aus Sitzung 5), `node scripts/check-i18n.cjs` grün
  (1219 Keys), `scan-i18n-literals.cjs` 0, `git diff --check` sauber.
- Status: unvercommitet (Owner-Commit-Freigabe steht aus); A5-Checklistenpunkt
  in `refactoring-adapter-layers.md` auf `[x]`. Phase-3-Rest: XMR als dritter
  Engine-Adapter (inkl. Watchdog-Hook) + Live-Hardware-Gate.

## 2026-10-11 — PC-Agent-Refactoring Phase 3 (Rest): geteilte Engine-Basis + XmrMinerService als dritter Adapter

- Auftrag „START: Mining/Controller-Abstraktion" (Sitzung 7). Auf dem
  A5-Arbeitsstand (Sitzung 6) aufgesetzt. Die beiden offenen Code-Punkte der
  Phase-3-Checkliste abgeschlossen; verhaltensneutral für XMR (Phase-Regel:
  nur O1/O2 durften Verhalten ändern — keine neuen Drifts).
- **Engine-Basis zweistufig (`miner/engine/`):** `AbstractMinerEngine` trägt
  jetzt ausschließlich Miner-Unabhängiges — `console()/mapper()/logger()`
  (jetzt abstract), `restartBudgetLeft(attempts)` als gemeinsames O1-Budget
  (`MAX_CRASH_RESTARTS = 3`) und `drainProcessOutput(process, threadName,
  onLine, onError)` als einzige Prozess-Read-Schleife (virtual thread).
  Die SRB-Familien-Mechanik (`monitorLoop`, `stopProcess`, `unexpectedExit`,
  `reportedHashrate`/`displayedHashrate`, `startOutputDrain`, `fail`,
  `apiClient`, `HASHRATE_GRACE`, aggregate/drain-Tag-Hooks) wanderte wortgleich
  in das neue `AbstractSrbMinerEngine extends AbstractMinerEngine`.
  `PearlMinerService`/`GpuCoinMinerService` extenden die SRB-Stufe;
  `GpuCoinMinerServiceTest` zeigt jetzt auf `AbstractSrbMinerEngine`
  (Hasrate-Helfer).
- **`XmrMinerService extends AbstractMinerEngine`:** Output-Drain nutzt die
  gemeinsame Schleife statt des eigenen Cached-Pool-Tasks — Thread-Typ
  (virtual statt pooled) und onExit-Warten (`join(1000)` statt
  `future.get(1 s)`) sind beobachtungsneutral; die Konsole „Could not read
  miner output: …“ bleibt wortgleich. Der Watchdog prüft
  `restartBudgetLeft(crashRestartAttempts)` und schreibt „… n/3“ jetzt aus
  `MAX_CRASH_RESTARTS` (identischer Text). Bewusst NICHT migriert wie geplant:
  XMRig-JSON-Config, Thread-/TDP-Heuristik, HugePages, API-Polling
  (`fetchHashrateFromApi`/`handleApiError`) — Analyse C verbietet das.
- **XMR-Verhalten unverändert:** Start-/Stop-/Pause-Pfade, Statusmaschine,
  Wire-Felder und Scheduling (30 s) blieben; nur die zwei Mechaniken wurden
  auf die gemeinsame Basis gehoben.
- Neuer Test in `MinerEngineDriftTest`: `restartBudgetLeft`-Grenzen (0/2/3)
  plus echter Drain-Durchlauf (Prozess „echo first; echo second“ → beide
  Linien in der Callback-Liste, kein Fehler-Callback).
- Verifikation: `sh gradlew test standaloneJar --offline` **154/154 Tests
  grün** (39 Klassen; +1 ggü. 153 aus Sitzung 6), standaloneJar gebaut,
  `check-i18n.cjs` grün (1219 Keys), `scan-i18n-literals.cjs` 0,
  `git diff --check` sauber.
- Status: unvercommitet (Owner-Commit-Freigabe steht aus); Checkliste Phase 3
  auf „✔ Code erledigt“ gestellt. Offen bleibt ausschließlich das
  Live-Rollout-Gate: Miner-Start/Stop/XMRig-Restart an echter Hardware
  abnehmen — Build + Unit-Tests sind dafür kein Nachweis.

## 2026-10-11 — PC-Agent-Refactoring Phase 4: PearlChainBalanceProvider (Wallet-Balance-Adapter)

- Auftrag „START: Mining/Controller-Abstraktion" (Sitzung 8). Owner-Entscheide
  dieses Nachmittags im Wiki dokumentiert: **O3 = `status`-Kanal als neuer
  Standard, alte SSE-Kanäle und lokale Alt-Routen beim UI-Umstieg löschen,
  `/external/**` bleibt eingefroren; Commits weiterhin ausstehend.**
- **`mining/PearlChainBalanceProvider` (neu):** der PEARL-On-Chain-Sonderfall
  (`WalletBalanceService` 56–59/78–85) verlässt die Closed-Seam-Umgehung —
  Explorer-HTTP (pearlchain.live, 3 s Connect/5 s Request wie vorher) und
  `parsePearlBalance` (Grains→PRL, Integral-/Vorzeichen-Wall) wandern
  wortgleich in den Adapter; `id()="pearl-chain"`.
- **`PoolBalanceProvider` (Seam erweitert, additiv):** `supportsOnChain(coin)`
  + `onChainBalance(coin, wallet)` als default-Methoden (false bzw.
  UnsupportedOperation); `balance` ebenfalls default, damit reine
  Chain-Provider die Pool-Methode nicht implementieren müssen.
  `KryptexPoolBalanceProvider` bleibt unverändert.
- **`WalletBalanceService` vollständig generic:** pro Ziel werden je ein
  Pool-Provider (`supports(poolUrl, coin)`) und ein Chain-Provider
  (`supportsOnChain(coin)`) gesucht; Statuswerte (`UNSUPPORTED_POOL`,
  `UNAVAILABLE`, `AVAILABLE`, `NOT_SUPPORTED`) und Cache-Disziplin
  (1 min / 15 s bei Fehler) unverändert. Der `mapper`/`HttpClient`-Besitz und
  der Coin-Import sind aus dem Service verschwunden — Konstruktur
  `(List<PoolBalanceProvider>)`; keine Test-Fixtures nutzten die alte Signatur.
  Parser-Seams `parsePoolBalance`/`parsePearlBalance` bleiben als Delegaten
  (Test-Kompatibilität).
- Nebenbefund: Chain-Cache-Key enthält jetzt die Coin-ID
  (`chain:pearl-chain:<coin>:<wallet>` statt `chain:pearl:<wallet>`) —
  reiner Cache-Interner Schlüssel, kein Wire-Feld.
- Verifikation: `sh gradlew test --offline` **154/154 Tests grün** (39 Klassen,
  wie Referenz Sitzung 7; kein Test umgeschrieben nötig), `git diff --check`
  sauber. Neue Adapter-eigene Tests auf Wunsch des Owners auf „am Ende“
  vertagt.
- Status: unvercommitet (Owner: Commits noch nicht). Phase 5 kann beginnen
  (Pinning-Tests zuerst).

## 2026-10-11 — PC-Agent-Refactoring Phase 5a: Wire-Format-Pinning-Tests

- Auftrag „START: Mining/Controller-Abstraktion" (Sitzung 8, Fortsetzung).
  Owner-Entscheid O3 vorher im Dokument vermerkt (status-Kanal als Standard,
  Alt-Kanäle/-Routen beim UI-Umstieg löschen, /external/** eingefroren).
- Neu `controller/AgentWireFormatPinningTest` (5 Tests): exakte JSON-Feldmengen
  für `MinerStats` inkl. `Worker`/`PoolTelemetry`/`MinerIdentity`, die vier
  `MinerStatus`-Drahtnamen, `DownloadState.wireName()`-Tabelle,
  `PowerStatus`/`GpuStatus`/`Identity` (Node-Power-Control-Vertrag) und
  `AgentOverview` mit `moneroConfiguration`/`pearlConfiguration`-Sektionen
  (`/external/overview`-Vertrag). AnyGetter-Flachfelder bleiben durch
  `ProxyOverviewWireFormatTest` abgedeckt.
- Nebenbefund aus dem ersten Lauf: `MinerStats` serialisiert zusätzlich
  `singleAlgorithmMined` (Getter des Records) — im Repo ungenutzt, vom Node
  lenient parst; jetzt als De-facto-Wire-Feld gepinnt inkl. Kommentar, damit
  eine Entfernung bewusst entschieden werden muss.
- Verifikation: `sh gradlew test --offline` **159/159 Tests grün** (40 Klassen;
  +5 Pinning-Tests), `git diff --check` sauber.
- Status: unvercommitet. Weiter mit Phase 5b (Aggregat `GET /api/agent/status`
  + SSE-Kanal `status`).

## 2026-10-11 — PC-Agent-Refactoring Phase 5b+5c: Aggregat-Read-Model, SSE-Ausbau, Polling-Senkung, Frontend-Performance-Fix

- Auftrag „START: Mining/Controller-Abstraktion" (Sitzung 8, Fortsetzung).
  Owner-Auftrag: gesamten Rest abarbeiten. Owner-Fehlerbericht mitbehandlelt:
  Frontend lädt träge + Tomcat-„Encountered a non-recycled request"-Sturm.
- **Ursache Performance (diagnostiziert + behoben):**
  1. `spring.task.scheduling.pool.size` war Default 1 — `publishChanges()`
    wertet alle Kanäle sequentiell auf diesem einen Scheduler-Thread aus; ein
    langsamer overview-Tick (GPU-Discovery + Forecasts + Fee/Currency-HTTP)
    blockierte alle anderen Kanäle und Watchdog-Restarts. → Poolgröße 4 in
    `application.properties`.
  2. `live-data.js` tat pro Kanal-Registrierung `close(); open();` — eine
    Seite mit 5 Kanälen baute pro Laden 5 EventSources auf und warf sie weg;
    abgebrochene asynchrone Servlet-Requests erzeugen genau das gemeldete
    CoyoteAdapter-Rauschen. → Registrierungen coaleszieren jetzt in einen
    einzigen Reconnect pro Task (`rescheduleOpen`).
  3. overview-Kanal recomputierte 1×/s → auf 2 s gedrosselt (Checklisten-Punkt
    „overview-Kanal-Neuberechnung drosseln").
- **Phase 5b — `controller/AgentStatusService` (neu):** aggregiertes
  Read-Model; Sektionen overview/settings/telemetry/proxy-gate/fee-tier/
  energy/node-assessment/wallets/coin-catalog mit historischem Takt,
  generatedAt/stale je Sektion; Ausfall einer Sektion degradiert zu
  stale=true (2 s Fehler-Cache), Einzelkanäle lesen denselben Cache
  (`payload(name)`) — jede Sektion rechnet pro Tick höchstens einmal.
  NEU `AgentStatusController`: `GET /api/agent/local/status`,
  `/status/ui`, `/status/coin-catalog`. NEUE SSE-Kanäle `status`, `ui`,
  `efficiency`, `coin-catalog` (additiv; alte Kanäle bleiben bis 5d).
- **Phase 5c — Frontend:** `overview.js` rendert Backend-alerts (`ui`-Kanal)
  statt eigener JS-Regeln; `workers.js` + `wallets.js` löschen ihre
  hartkodierten Coin-/Algorithmus-Maps zugunsten `/status/coin-catalog`
  (abgeleitet aus Closed Enum `Coin`). Polling entfernt: benchmarks.js
  (1-s-Timer, 3 Fetches → Kanäle benchmarks/efficiency/benchmark-upload),
  telemetry.js (3 s → telemetry), hardware.js (5 s → power-control),
  mining/software.js (5 s → miner-options), workers.js (5 s → workers/
  energy). Alle Seiten nutzen damit die eine SSE-Verbindung + Watchdog.
  Mutationen refreshen gezielt die betroffene Sektion statt refreshAll.
- Wire-Disziplin: `/external/**` nicht angefasst; Pinning-Tests (5a) grün;
  neue Endpunkte sind additiv und nur lokal (Write-Access-Filter greift).
- Verifikation: `sh gradlew test standaloneJar --offline` **159/159 Tests
  grün** (40 Klassen), Standalone-JAR gebaut; alle geänderten JS-Dateien
  `node --check` sauber; `check-i18n.cjs` grün (1219 Keys),
  `scan-i18n-literals.cjs` 0, `git diff --check` sauber.
- Status: unvercommitet. Offen: 5d (Controller-Merges + Löschen alter
  lokale Routen/Kanäle), Live-Hardware-Gate.
  HINWEIS fürs Live-Testing: Die Performance-Fixes wirken erst im nächsten
  Boot; SSE-Verbindungs-Churn sollte danach aus den Logs verschwunden sein.

## 2026-10-11 — PC-Agent-Refactoring Phase 5d + Owner-Aufträge Docker/Paketordnung (Sitzung 9)

- Auftrag „START: Mining/Controller-Abstraktion" (Sitzung 9). Auf dem
  5a/5b/5c-Stand (Sitzung 8) aufgesetzt. Phase-5d-Checkliste vollständig
  abgearbeitet; zusätzlich Owner-Aufträge Docker-Verifikation und
  Paket-Finalpass. Owner-Commit-Freigabe steht weiterhin aus — alles
  unvercommitet.
- **Worker-Ressource gemergt:** `WorkerController` (`/api/agent/local/workers`)
  trägt jetzt Inventar + Zuweisung + Start/Pause + die aus
  `AgentPowerController` übernommene Route `/{workerId}/external-control`
  (Console-Echo wortgleich). Automation-Seite auf
  `/workers/{id}/external-control` umgestellt. Die Alt-Routen
  `power-control/workers/{id}/coin` und `/coin-policy` hatten nach dem
  5c-Umstieg auf `/workers/{id}/assignment` keinen Consumer (grep über JS,
  Node-Repo, Tests) und wurden gemäß O3 gelöscht — kein Forwarding.
  `/external/**` nicht angefasst.
- **Economics:** `EnergyController` + `FiatRateController` gelöscht, ein
  `EconomicsController` (`GET /local/economics`, `POST /local/economics/settings`,
  `GET /local/economics/rates`) mit feldgleichen Payloads. UI umgestellt:
  `energy`-Kanäle (overview.js, workers.js) → `/local/economics`,
  i18n-Fiat-Read → `/local/economics/rates`.
- **Proxy-Ressource:** `ProxyGateController` gelöscht; Gate-Endpunkte leben
  als `GET /local/proxy/gate`, `GET /local/proxy/gate/log`,
  `POST /local/proxy/gate/retry` im `MiningController` (derselbe
  `ProxyConfigurationService`). `proxy-gate.js` (Kanal-REST-Fallback, Log,
  Retry) umgezogen; `ProxyGateControllerTest` auf die neue Route gezogen
  (Payload-Form unverändert, Wire-Disziplin gewahrt).
- **MiningOptimizationController → Adapter-Capability:** neues Interface
  `miner/RandomXCapability` (Status-Record feldgleich zum alten
  `/optimizations/randomx`), implementiert von `XmrMinerService`
  (`WindowsHugePagesService`-Abhängigkeit dorthin verlagert). Generischer
  `MinerOptimizationController` (`/local/miners/{coin}/optimizations[/{setting}]`)
  löst die Capability ausschließlich über `MinerFactory` — keine
  Coin-Verzweigung im Controller; Prozess-Restart bleibt beim
  Orchestrierer (`restartRequiredAfterToggle`). Nebenbefund: die alte Route
  hatte KEINEN UI-Consumer im Repo (weder HEAD noch Worktree riefen sie;
  i18n-Keys vorhanden, aber keine aufrufende Seite) — Funktion ist bewusst
  erhalten geblieben, jetzt coin-agnostisch angebunden.
- **UI-Bereinigung (5d-Rest):** Proxy-Seite von 12-s-Poll des vollen
  `/overview` auf den geteilten `overview`-SSE-Kanal umgestellt (Payload
  identisch). Damit existiert im UI kein Seiten-Timer-Polling mehr und kein
  `refreshAll()`-Aufruf außer dem bewussten Übersichts-„Aktualisieren“-Button.
- **Paket-Finalpass (Owner-Auftrag):** `MeasurementRunGuard` +
  `SampleCollector` + Test nach `mining/measurement/` verschoben
  (Mess-Mechanik getrennt von den Mess-DIENSTEN; Sichtbarkeit package→public,
  Wire-/Thread-Namen unverändert). `AgentStatusService` bleibt bewusst im
  `controller`-Package (drahtformesenes UI-Read-Model, das die
  Controller-Produzenten zusammenführt — ein eigenes Read-Model-Package wäre
  heute eine Ein-Klassen-Hülle; bei wachsender Sektionszahl nachziehbar).
  `PearlChainBalanceProvider` bleibt in `mining/` (PoolBalanceProvider-Seam
  und Coordinator wohnen dort). Keine Wire-Klassen umbenannt.
- **Docker/LAN-Findung (Owner-Auftrag):** `Dockerfile` EXPOSE prüft:
  `8084 8092/udp 8093/udp` (der 8093-Zusatz ist bereits im Worktree
  enthalten; 8090/3334/3335 im Standalone-Overlay sind dokumentierte
  Loopback-only-Restbestände des gebündelten Proxys, kein LAN-Vertrag —
  siehe `docs/agent-wiki/docker-lan-discovery.md`). Compose nutzt
  `network_mode: host` (korrekte Wahl: UDP-Broadcast über Bridge geht
  nicht). Verifikation real statt Build: zwei Agent-Instanzen
  (Standalone-JAR, verschiedene Ports) parallel auf diesem Host gestartet —
  beide sahen einander binnen ~60 s über UDP 8093
  (`GET /local/network/agents` lieferte je den anderen Peer mit
  `reachable:true`, inkl. einem echten Dritt-Agenten auf 192.168.178.126:8084
  im LAN). Zwei echte Container/Hosts waren auf dieser Maschine nicht
  möglich (Docker-Socket ohne Berechtigung, `unshare -n` verboten) — die
  Instanz-Findung ist damit auf Prozess-Ebene bewiesen, die
  Container-Bridge-Grenze bleibt dokumentierte Einschränkung.
  Live-Boot bestätigte nebenbei: neue Routen grün
  (`/local/economics` 200, `/local/proxy/gate` 200,
  `/local/miners/monero/optimizations` 200,
  `POST /local/workers/cpu/external-control` 200), alle gelöschten Alt-Routen
  404, `/external/identity` offen, restliches `/external/**` hinter dem Gate.
- Verifikation: `JAVA_HOME=…graalvm-ce-21.0.2 sh gradlew test standaloneJar
  --offline` **159/159 Tests grün** (40 Klassen, Zähler aus
  `build/test-results/test/*.xml`), alle geänderten JS-Dateien `node --check`
  sauber, `check-i18n.cjs` grün (1219 Keys), `scan-i18n-literals.cjs` 0,
  `git diff --check` sauber.
- Status: unvercommitet (Owner-Commit-Freigabe steht aus). Refactoring
  Phase 5 damit vollständig; das einzige offene Gate des Programms bleibt
  die Live-Hardware-Abnahme der Phase 3 (Miner-Start/Stop/Crash-Restart O1
  + Pearl-Watchdog O2a an echter HW) — in dieser Sitzung erneut nicht
  möglich, kein Miner-Rechner verfügbar; bewusst NICHT abgehakt.

## 2026-10-11 — REST/SSE-Ladeplan P1–P6 (Sitzung 10, Auftrag „START: Mining/Controller-Abstraktion")

- Arbeitsdokument: `docs/agent-wiki/rest-sse-loading-plan.md`. Auf dem
  5d-Stand (Sitzung 9) aufgesetzt; alle Checklistenpunkte P1–P6 abgearbeitet.
  Owner-Commit-Freigabe steht weiterhin aus — alles unvercommitet.
- **P1 — tote Kanäle gelöscht (O3, Consumer-Grep vorher):** `coin-catalog`
  und `status`-Aggregat aus `AgentEventController` entfernt. Grep über
  `static/js`: beide wurden von keiner Seite abonniert (coins-Katalog liest
  REST `/status/coin-catalog` in workers.js:342/wallets.js:90; das Aggregat
  existierte nur als REST). Boot-Nachweis: SSE-Subscribe auf
  `channels=coin-catalog` bzw. `channels=status` liefert 0 Events auf einem
  offenen Stream. Sektionen bleiben im Service (REST `/local/status`).
- **P2 — Dirty-Flag-Infrastruktur:** `AgentStatusService.invalidate(String...)`
  (thread-sicher, Set) + `isDirty/clearDirty`; `payload()` und `aggregate()`
  ignorieren den Cache für gemeldete Sektionen; `publishChanges()` wertet
  dirty-Kanäle vorrangig aus (Wake pro Sweep genau einmal, Clear erst nach
  dem Snapshot — sonst schluckt der Cache den Wake). TTLs bleiben Netz für
  externe Änderungen (Proxy-Heartbeat, Node-Push, Downloads, Sensoren).
  Nicht-Sektions-Kanäle (workers, power-control, miner-options, benchmarks,
  efficiency, benchmark-upload, network-agents) werden über denselben
  Kanalnamen geweckt. NEU `AgentStatusServiceTest` (5 Tests): Cache bei
  sauberer Sektion, invalidate → sofortige Neubewertung, Isolation pro
  Sektion, Kanal-Wakes, Aggregat + Fehler-Zähler.
- **P3 — Mutationen verdrahten:** `invalidate(...)` in allen schreibenden
  `/local/**`-Endpunkten: `power-control/{settings,target,gpus/../limits,
  network-sharing,identity}` + Node-Pfade `externalTarget/Pause/Resume`
  (bewusst: Node-Steuerung soll die lokale UI sofort nachziehen),
  `workers/{id}/{assignment,start,pause,external-control}`,
  `economics/settings`, `telemetry/restart`, `proxy*` (host/mode/roll-mode/
  gate-retry), `miners/{coin}/**` (download/remove/select/gpu-pause/resume/
  power-target), `{coin}/configuration` (CPU/Pearl/GPU), `referral`,
  `setPoolConfiguration`, `coin`, `pause/resume`,
  `miners/{coin}/optimizations`, `node-assessment` (POST),
  `benchmarks*`, `efficiency*`. `MiningController`/`AgentPowerController`/
  `EconomicsController`/`TelemetryController`/`NodeAssessmentController`
  erhalten `AgentStatusService` lazy-injiziert (`@Lazy` — die Controller
  sind selbst Sektions-Produzenten); `MiningController` bekam einen
  test-kompatiblen 20-Arg-Konstruktor (null-Status toleriert) plus
  `@Autowired` am produktiven Konstruktor (Boot-Nachweis fand die
  Zwei-Konstruktor-Ambiguität: „No default constructor found").
- **P4 — UI übernimmt POST-Antworten:** `hardware.js` GPU-Limits: die POST-
  Antwort (GpuStatus) wird direkt aufs Kartenmodell gemappt; Settings-Toggle
  übernimmt das gesparte Settings-Objekt; beide ersetzen das alte
  `await refresh()` durch gezielten `SolarMinerLive.refresh(...)`-Rest
  (Watchdog-Regel bleibt). Die übrigen Seiten (workers/wallets/automation/
  benchmarks) riefen bereits gezielte Section-Refreshes — die werden durch
  P3 seltener wirksam nötig; kein `refreshDirty()` eingeführt (Plan).
- **P5 — First Paint über das Aggregat:** `live-data.js` `onopen` ruft
  `firstPaint()`: EIN `GET /local/status` pro Seite verteilt die Sektionen
  an alle noch leeren Sektions-Kanäle; Kanäle ohne Aggregat-Sektion
  (workers, power-control, benchmarks, …) behalten ihren Einzel-Fallback,
  und Seiten ohne Sektions-Kanal holen das Aggregat erst gar nicht.
  Spätere Reconnects heilen wie bisher über die Connect-Snapshots.
  Boot-Nachweis: `/local/status` liefert alle 10 Sektionen inkl. `ui`,
  `stale:false`, `generatedAt` je Sektion.
- **P6 — Takt/TTLs:** Sweep `solarminer.agent.events.interval-ms=500`
  (application.properties, begründet). Sektions-TTLs: settings 1 s→30 s und
  energy 5 s→30 s (nur noch Netz; Mutationen melden jetzt), overview bleibt
  2 s, proxy-gate 1 s (Download-Fortschritt extern), übrige unverändert.
  NEU Diagnose-Endpunkt `GET /local/status/stats` (TTL + Bewertungszähler
  pro Sektion; nicht UI-vertraglich).
- **Boot-Messung (P2/P6-Evidence, Standalone-JAR, Port 18084, headless):**
  SSE abonniert (settings,energy,node-assessment), Mutationen per POST:
  `POST /local/economics/settings` → energy-Push nach **208 ms**;
  `POST /local/power-control/settings` → settings-Push nach **193 ms**
  (beide < 1 s; power-control-Kanal sendet nicht, weil sich sein Payload
  inhaltlich nicht änderte — Emit nur bei Wertänderung, Verhalten unverändert).
  60 s Idle bei 500-ms-Sweep: overview +0 Bewertungen (vorher: 30/min bei
  2-s-Tick wäre unverändert; Zähler vergleicht pro Minute), settings +2,
  energy +2 (30-s-Netz), node-assessment +12 (5-s-Netz), telemetry/
  proxy-gate/fee-tier/wallets +0 weil unbelauscht — unabo = keine Arbeit.
  Vor P2 hätte derselbe Idle jede abonnierte Sektion jeden Tick neu
  berechnet. Vorher/Nachher identische Counter-Basis: `/status/stats`.
- Wire-Disziplin: `/external/**` unverändert; Pinning-Tests grün;
  Sektionsnamen unverändert (nur TTLs); abonnierte Kanalnamen/Payloads
  unverändert gelöscht nur die zwei Consumer-losen Kanäle.
- Verifikation: `JAVA_HOME=…graalvm-ce-21.0.2 sh gradlew test standaloneJar
  --offline` **164/164 Tests grün** (41 Klassen, Zähler aus
  `build/test-results/test/*.xml`; +5 neue AgentStatusServiceTest),
  Standalone-JAR gebaut und real gestartet; geänderte JS-Dateien
  (`live-data.js`, `hardware.js`) `node --check` sauber; `check-i18n.cjs`
  grün (1219 Keys), `scan-i18n-literals.cjs` 0, `git diff --check` sauber.
- BUG-002 (KNOWN_BUGS) dadurch bearbeitet — Status dort aktualisiert.
- Status: unvercommitet (Owner-Freigabe steht aus).

## 2026-10-12 — Ladeplan P8: REST-first, SSE nur für Live-Zustände

- Auftrag: Das Dashboard blieb beim Einstieg lange auf „Verbinde …“; SSE soll
  nur dort bestehen bleiben, wo laufende Änderungen sichtbar sein müssen.
- Ursache: Der Frontend-Loader behandelte SSE weiterhin als allgemeinen
  Primärpfad. Zudem malte er zwar einen `sessionStorage`-Snapshot sofort, ließ
  bei REST-Kanälen danach aber die initiale Revalidierung aus. Ein alter
  Dashboard-Snapshot wartete dadurch bis zum 2-s-Watchdog, bevor der Agent
  überhaupt angefragt wurde.
- Umsetzung: `live-data.js` ist REST-first. Jeder REST-Kanal wird pro
  Seiteneinstieg genau einmal sofort revalidiert, auch nach einem Cache-Paint.
  Der Server publiziert nur noch sechs echte Live-Kanäle: `telemetry`,
  `workers`, `benchmarks`, `efficiency`, `benchmark-upload`, `proxy-gate`.
  Übersicht, Konfiguration, Energie, Fee-Tier, Wallets, Hardware,
  Miner-Katalog, Node-Bewertung, UI-Ableitungen und LAN-Agenten bleiben REST.
  Der globale Proxy-Gate liest den üblichen `ready`-Fall einmal über REST und
  öffnet seine SSE-Verbindung erst, wenn Vorbereitung/Download läuft; dann
  bleiben Fortschritt und Ready-Übergang live. Cache-Buster aller betroffenen
  HTML-Seiten wurden angehoben.
- Während der Verifikation reproduziert und behoben: Der sofortige Cache-Paint
  konnte `dashboardWorkers()` ausführen, bevor das weiter unten deklarierte
  `let agentName` initialisiert war (`ReferenceError`, kompletter Render-Pfad
  abgebrochen). Der gesamte Identity-Zustand wird jetzt vor der ersten
  Channel-Registrierung initialisiert; `overview.js` erhielt ebenfalls einen
  neuen Cache-Buster.
- Vertrag/i18n: `/external/**`, REST-Pfade und Payloads unverändert. Keine neuen
  sichtbaren Texte; deutsche und englische Kataloge bleiben geschlossen.
- Verifikation: `AgentEventControllerTest` pinnt exakt die sechs Kanäle;
  `check-dashboard-startup.cjs` rendert den synchron gelieferten Cache-Snapshot
  ohne Initialisierungsfehler; ein separater Loader-Harness belegt Cache-Paint +
  genau eine sofortige REST-Revalidierung + keine EventSource für REST-Kanäle.
  `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test standaloneJar
  --offline` erfolgreich (181 Tests / 47 Klassen). `node --check` für alle
  geänderten JS-Dateien, `check-i18n.cjs` (1255 Keys, DE/EN),
  `scan-i18n-literals.cjs` (0), `simulate-i18n-render.cjs` (alle 9 Seiten in
  beiden Sprachen) und `git diff --check` sauber.

## 2026-10-12 — Remote-Worker: Earnings und Earnings/kWh

- Ursache: Der LAN-Snapshot entfernte beim Abbilden des internen Worker-/Forecast-
  Modells genau die beiden Felder, welche die Dashboard-Formel voraussetzt:
  `WorkerRow` hatte keine Coin-ID und `EarningsRow` kein `available`. Deshalb
  fand `overview.js` keinen Forecast; selbst ein Treffer wäre von
  `workerUsdPerDay()` als nicht verfügbar abgelehnt worden. Die übertragene
  Agent-Gesamtsumme wurde für einzelne Tabellenzeilen absichtlich nicht
  verwendet.
- Umsetzung: Der additive Snapshot-Vertrag veröffentlicht nun `worker.coin`
  (aus dem zentralen `Coin.byAlgorithm`, inklusive Aliase) und
  `earnings.available=true` für die bereits serverseitig gefilterten Forecasts.
  Das Dashboard unterstützt gemischte LAN-Versionen: Fehlt `coin`, wird es aus
  dem geschlossenen Algorithmus-Katalog abgeleitet; fehlt `available`, gilt der
  vom alten Agent bereits gefilterte Forecast als verfügbar. Die bestehende
  Berechnung und Währungsdarstellung bleiben unverändert.
- Verifikation: `NetworkDashboardServiceTest` prüft Coin-Auflösung inklusive
  `blake3`-Alias, den Availability-Vertrag und die Deserialisierung eines alten
  Snapshots ohne additive Felder. `check-dashboard-startup.cjs` rendert einen
  solchen Legacy-Peer und verlangt Tagesertrag sowie positiven Ertrag/kWh.
  `sh gradlew test standaloneJar --offline` erfolgreich (184 Tests / 48
  Klassen); `check-i18n.cjs` (1255 Keys, DE/EN),
  `scan-i18n-literals.cjs` (0), `simulate-i18n-render.cjs` (9 Seiten in beiden
  Sprachen), JS-Syntax und `git diff --check` sauber. Keine neuen UI-Texte.

## 2026-10-12 — SRBMiner-Migrationshinweis und Worker-Update

- Eine vorhandene, aber nicht mit der geprüften SRBMiner-Version markierte Binary wird im
  Miner-Katalog nun als `UPDATE_REQUIRED` ausgewiesen. Damit bleibt der Startschutz aktiv,
  unterscheidet den Migrationsfall aber von einer fehlenden Installation.
- Der Software-Katalog und betroffene Worker zeigen eine ausdrücklich benannte,
  lokalisierte Aktion zum Aktualisieren. Sie nutzt den vorhandenen checksum-geprüften
  SRBMiner-Installer; die gespeicherte Pearl-Konfiguration bleibt unverändert.
- Verifikation: `node scripts/check-i18n.cjs`, `node --check` für `software.js` und
  `workers.js`, sowie gezielt `MinerCatalogServiceTest` und `SrbMinerVersionTest` mit
  Java 21 / Gradle erfolgreich.

## 2026-10-11 — Wallet-Snapshot: Start-Übergang ohne Request-I/O

- Befund: Nach der Umstellung von Wallet-Daten auf einen reinen Read-Model-Cache konnte
  der erste Browser-Abruf direkt nach einem Agent-Neustart noch den absichtlich leeren
  Start-Snapshot sehen. Der Hintergrund-Refresh füllte ihn anschließend, aber der
  Wallet-Strip fragte wegen seines 90-Sekunden-Intervalls nicht zeitnah erneut ab.
- `wallet-strip.js` liest deshalb bei einem leeren, fehlerfreien Snapshot den lokalen
  REST-Cache bis zu fünfmal im Ein-Sekunden-Abstand erneut. Sobald Wallets vorliegen,
  werden ausstehende Wiederholungen beendet. Die Endpoint-Aufrufe bleiben cache-only:
  kein Pool-, Chain- oder sonstiges Upstream-I/O wird dadurch auf dem Request-Pfad
  ausgelöst.
- Verifikation: `node --check src/main/resources/static/js/chrome/wallet-strip.js`,
  `node scripts/check-i18n.cjs` (1219 Schlüssel, beide Locales),
  `node scripts/scan-i18n-literals.cjs` (0) und `git diff --check` erfolgreich.

## 2026-10-12 — Ladepfad P8: seitenbezogene, parallele Status-Aggregation

- Befund: `GET /api/agent/local/status` sammelte bislang immer alle Sektionen seriell. Beim
  Kaltstart konnten dadurch unabhängige, langsame Datenquellen (Wallet-/Marktdaten,
  Proxy- und Fee-Lookups) den ersten Dashboard-Paint ausbremsen, obwohl die aktuelle Seite
  diese Daten nicht benötigte.
- `AgentStatusController` akzeptiert nun optional `sections=a,b`; ohne Parameter bleibt der
  vollständige bisherige Response kompatibel. `AgentStatusService` ignoriert unbekannte Namen
  sicher, liefert nur die angeforderten Sektionen und führt unabhängige Cache-Misses auf
  virtuellen Threads parallel aus. Die Reihenfolge im Wire-Response bleibt stabil; `ui` wird
  erst nach seinen Abhängigkeiten berechnet und verwendet damit deren frische Caches.
- `live-data.js` übergibt beim gebündelten First Paint genau die von der Seite registrierten
  Aggregatkanäle. Dadurch blockieren nicht abonnierte Sektionen den sichtbaren Inhalt nicht.
  Es wurden keine UI-Texte, Übersetzungen oder Node-/externen Verträge verändert.
- Verifikation: zusätzlicher Unit-Test pinnt, dass ein selektives Aggregat keine Wallet-Abfrage
  auslöst; `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test --offline --no-daemon`,
  `node --check src/main/resources/static/js/core/live-data.js` und `git diff --check` grün.

## 2026-10-12 — LAN-Anzeige standardmäßig aktiv

- Neue PC-Agent-Installationen aktivieren die reine Leseansicht im lokalen Netzwerk jetzt
  standardmäßig. Ein explizit gespeichertes `networkSharingEnabled:false` bleibt unverändert
  wirksam und kann weiterhin in der Automation-Seite gesetzt werden.
- Verifikation: Unit-Test deckt sowohl den Default als auch den persistenten Opt-out ab.

## 2026-10-12 — Dashboard: parallele Proxy- und Auszahlungslayouts

- Befund: Beim kalten oder nicht erreichbaren Proxy führte die Dashboard-Übersicht REST-Aufrufe
  zu Fee-Targets und Standard-Auszahlungen pro Coin nacheinander aus. Jeder Aufruf hat bis zu
  drei Sekunden Timeout; dadurch konnte die Antwort über mehrere Coin-Timeouts hinweg blockieren
  und der Browser zeigte unnötig lange den Abgleich des letzten bekannten Stands.
- `ProxyConfigurationService` schützt Aktualisierungen nun je Coin statt mit einem globalen
  Monitor. `MiningController.proxy()` und `payoutDefaults()` starten die voneinander unabhängigen
  Coin-Reads gleichzeitig auf virtuellen Threads und behalten die stabile Response-Reihenfolge.
  Dadurch ist die Obergrenze ein einzelnes Timeout statt der Summe aller Coin-Timeouts.
- Verifikation: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test --offline --no-daemon`,
  JavaScript-Syntax, beide i18n-Checks und `git diff --check` grün.

## 2026-10-12 — Dashboard-Stale: konkurrierende Erstabrufe entfernt

- Tatsächliche Ursache für den lang sichtbaren Hinweis „Letzter bekannter Stand …“: Das
  Proxy-Gate startet den gemeinsamen Watchdog sofort. Dessen erster Zwei-Sekunden-Tick löste
  für jede noch leere Sektion, insbesondere `overview`, einen Einzel-REST-Abruf aus, obwohl
  der gebündelte `/status`-First-Paint noch lief. Beide kalten Abrufe konkurrierten um dieselben
  GPU-, Proxy- und Cache-Produzenten; ein verlorener Abruf endete nach acht Sekunden und setzte
  die Stale-Meldung.
- `live-data.js` unterdrückt diese Einzel-Reads für Aggregat-Sektionen, solange der gebündelte
  First-Paint aktiv ist. Der Fallback bleibt nach dessen Abschluss unverändert. Außerdem sendet
  der SSE-Endpunkt die Initial-Snapshots je Kanal parallel, so dass Einstellungen, Telemetrie
  und Node-Status nicht mehr hinter einer langsamen Overview-Produktion warten.
- Verifikation: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test --offline --no-daemon`,
  JavaScript-Syntax, beide i18n-Checks und `git diff --check` grün.

## 2026-10-12 — REST-first, SSE nur für echte Live-Zustände

- Owner-Entscheidung: SSE ist nicht der Standardtransport für jede Seiteninformation. Der
  Client unterstützt nun pro Kanal `transport: 'rest'` mit einem expliziten, bedarfsgerechten
  `pollMs`. Der direkte erste REST-Read wird pro Tick gebündelt gestartet; bei versteckter
  Seite findet kein Polling statt und beim Zurückkehren wird einmal aktualisiert.
- Dashboard und statische/selten wechselnde Seitenbereiche verwenden REST: Overview,
  Einstellungen, Node-Assessment, Energie, Netzwerk-Agents, Fee-Badge, Wallet-Streifen,
  Leistungsgrenzen, Miner-Optionen und Proxy-Übersicht. SSE bleibt für Telemetrie,
  Proxy-Download/Gate, Worker-Laufzustände sowie Benchmark-/Sweep-Fortschritt aktiv.
- Der teure abgeleitete Alert-Read wird erst nach dem ersten sichtbaren Overview-REST-Render
  gestartet. Damit konkurriert seine Overview-Abhängigkeit nicht mehr mit dem First Paint.
- Verifikation: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test --offline --no-daemon`,
  Syntaxcheck der geänderten JavaScript-Dateien, beide i18n-Checks und `git diff --check` grün.

## 2026-10-12 — Overview-GET: Setup-Nebenwirkung entfernt

- Messung gegen den laufenden lokalen Agenten mit Browser-Origin: Settings, Node-Assessment,
  Energie, Fee-Tier und Wallet-Snapshot lagen unter 10 ms, Telemetrie unter 200 ms und
  Netzwerk-Agents bei etwa 1,2 s; `GET /overview` blieb bei etwa 2,1 s.
- Befund im Read-Pfad: `MiningController.overview()` führte synchron
  `XmrMinerService.ensureDefaultConfiguration()` aus. Diese Operation darf eine
  Fee-Backend-Abfrage durchführen und XMRig-Dateien schreiben; sie gehört nicht in einen
  lesenden Dashboard-Request. Sie bleibt im Miner-Start-/Readiness-Pfad erhalten, ist aber
  aus `GET /overview` entfernt.
- Verifikation: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test --offline --no-daemon`,
  beide i18n-Checks und `git diff --check` grün.

## 2026-10-12 — Read-Model-Regel: keine I/O in GPU-, LAN- und Wallet-REST-Reads

- Architekturregel des Owners: Ein lokaler REST-GET serialisiert ausschließlich den letzten
  abgeschlossenen Read-Model-Snapshot. Treiber-, Prozess-, Datei- und Netzwerkarbeit läuft
  im Hintergrund oder auf expliziten Befehlswegen.
- `LocalGpuPowerService` stellt `snapshot()` bereit, wärmt die Treibererkennung nach dem
  Application-Start und aktualisiert sie periodisch im Hintergrund. Overview und die
  read-only Worker-Liste verwenden diesen Snapshot; Start-/Benchmark-/Power-Befehle behalten
  bewusst die direkte Hardware-Erkennung.
- `NetworkDashboardService` cached die komplette Kartenliste und aktualisiert eigene
  Worker-/Forecast-Erfassung sowie Peer-HTTP im Hintergrund. Der lokale Netzwerk-GET und die
  externe Peer-Snapshot-Antwort führen keine I/O mehr aus. `MiningController` cached auch
  Wallet-Ziele, Pool-/Chain-Balances und Marktpreise im Hintergrund; Wallet-REST-Reads geben
  nur diesen konsistenten Snapshot zurück.
- Verifikation: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test --offline --no-daemon`,
  beide i18n-Checks und `git diff --check` grün (166 Tests).

## 2026-10-12 — Gebündelter First Paint für sofortige Agent-Verbindung

- Ursache der langsamen Seitenstarts: Nach dem Öffnen von SSE startete `firstPaint()` den
  Aggregat-Read asynchron, anschließend löste derselbe `onopen`-Handler aber sofort für jeden
  noch leeren Kanal einen eigenen REST-Read aus. Langsame Sensor-, Wallet- oder Proxy-Sektionen
  erzeugten so einen parallelen Kaltstart-Burst und hielten den Header auf „Verbinde …“.
- `live-data.js` bündelt Kanalregistrierungen nun für einen Event-Loop und führt genau einen
  First-Paint-Read aus. Einzel-REST bleibt nur der gezielte Fallback für Sektionen, die das
  Aggregat nicht enthält. Ein erfolgreich geöffnetes lokales SSE setzt den Header sofort auf
  verbunden; fachliche Datenfehler können ihn weiterhin wie bisher auf offline setzen.
- Verifikation: `node --check src/main/resources/static/js/core/live-data.js`,
  `node scripts/check-i18n.cjs` (1219 Schlüssel, beide Locales) und `git diff --check` erfolgreich.

## 2026-10-12 — SSE-Handshake vom ersten Daten-Snapshot entkoppelt

- `AgentEventController.subscribe()` berechnete vor der Rückgabe des `SseEmitter` jeden
  angeforderten Kanal synchron. Dadurch wartete der EventSource-Handshake auf den langsamsten
  Wallet-, Proxy- oder Sensor-Producer und jede Seite blieb unnötig lange im Verbindungszustand.
- Der Emitter registriert die Kanäle nun sofort und liefert die unveränderten initialen
  Kanal-Snapshots auf virtuellen Threads nach. Der Handshake ist damit nicht mehr an
  Producer-Latenz gekoppelt; der Frontend-First-Paint bleibt ein einzelner, gebündelter Read.
- Verifikation: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test --offline --no-daemon`
  erfolgreich; `node --check src/main/resources/static/js/core/live-data.js`,
  `node scripts/check-i18n.cjs` und `git diff --check` erfolgreich.

## 2026-10-12 — Dashboard-Snapshot nicht als sofortiger Ausfall markieren

- Ein beim Seitenwechsel rehydrierter Overview-Snapshot ist nach dem kurzen 6-s-Freshnessfenster
  oft formal stale, obwohl der gebündelte Live-Read bereits läuft. Das Dashboard stellte ihn
  deshalb kurz als Agent-Ausfall dar, bevor der aktuelle Wert eintraf.
- `overview.js` behandelt ausschließlich einen fehlerfreien stale `snapshot` als
  Abgleich-Übergang: vorhandene Daten bleiben sichtbar und der Header zeigt Verbindung wird
  aufgebaut. Ein REST-/SSE-Fehler oder fehlende Daten nutzt weiter den bestehenden Offline- und
  Stale-Pfad.
- Verifikation: `node --check src/main/resources/static/js/pages/overview.js`,
  `node scripts/check-i18n.cjs`, `node scripts/scan-i18n-literals.cjs` und
  `git diff --check` erfolgreich.

## 2026-10-11, Sitzung „Package-Zyklen 1“ — Package-Struktur: Zyklus-Auflösung und Cluster-Bildung
- Auftrag: Package-Struktur gegen Software-Engineering-Standards prüfen und Korrekturen umsetzen.
- Befund (Import-Graph, programmatisch extrahiert): 10 Package-Zyklen; `mining/` war ein
  God-Package (38 Klassen); `miner/engine/` schaute nach oben in `mining/` (MinerConsoleService,
  MinerShareTelemetry, WindowsAntivirusBlock); `lowlevel.sensor` und `xmr` hingen an
  `pearl/LocalGpuPowerService` (Wiki hatte das bereits als Verschiebe-Kandidat markiert).
- Umgesetzt (nur `package`-Deklarationen + Imports + `git mv`; keine Klassen-/Wire-Namen geändert):
  - `mining/MinerConsoleService|MinerShareTelemetry|MinerStopContext|MinerProcessRegistry` → `miner/`
    (Prozess-/Konsolen-Plumbing, das alle Miner-Adapter teilen).
  - `mining/WindowsAntivirusBlock` → `miner/engine/` (einziger Nutzer ist `MinerBinaryInstaller`;
    package-private Test-Overload bleibt erreichbar, Test mitgezogen).
  - `pearl/LocalGpuPowerService` → `lowlevel/` (Wiki-Vorschlag umgesetzt; bricht die Zyklen
    `lowlevel.sensor↔pearl`, `xmr↔pearl`, `miner.engine↔pearl`, `miner↔pearl`-Ast).
  - Proxy-Cluster `mining/ManagedProxyService|ProxyConfigurationService|ProxyDiscoveryService|
    ProxyReleaseService|ProxyPorts|ProxyLifecycle|GpuStratumRelay` + `support/ProxyFeeClient`
    → `mining/proxy/`; zugehörige Tests (`ProxyConfigurationServiceTest`, `GpuStratumRelayTest`,
    `ManagedProxyHealthTest`) mitgezogen.
  - `mining/LocalRunLock` → `mining/measurement/` (Guard und Lock gehören zusammen; macht
    `mining/measurement` bis auf eine Ausnahme zum Leaf).
- Bewusst NICHT verschoben: `PayoutDefaultsService` (Versuch erzeugt `miner↔mining`-Zyklus über
  FeeTierService/ReferralConfigurationService — revertiert), `dto/`-Umzug (Wire-Pinning-Risiko,
  kein Nutzen), `AgentStatusService` aus `controller/` (läuft gerade in Phase-5-Arbeit mit).
- Verbleibende Zyklen sind Adapter-Register-Zyklen (Factory→Adapter, Adapter→Orchestrierung)
  und dokumentierte Fachschleifen (mining↔pearl/xmr über PayoutDefaults/ProxyConfiguration);
  Auflösung erfordert Interface-Extraktion, nicht nur Paketsuchen — als Follow-up notiert.
- Verifikation: `JAVA_HOME=…graalvm-ce-21.0.2 sh gradlew test standaloneJar --offline`
  **164/164 Tests grün** (41 Klassen, Zähler aus `build/test-results/test/*.xml`), identisch
  zum Stand vor dem Eingriff; Import-Graph neu gezählt: 10 → 8 Zyklen, davon 2 komplett
  beseitigt (`lowlevel.sensor↔pearl`, `miner.engine↔mining`) und `support` wieder zyklusfrei;
  `git diff --check` sauber. Keine REST-/Wire-Namen berührt (Pinning-Tests grün).
- Status: unvercommitet (Owner-Freigabe steht aus).

## 2026-10-11 — Ladeplan P7: Snapshot-Cache gegen Seitenwechsel-Trägheit (Sitzung 11)

- Auftrag (Owner): Wallets/Worker/Dashboard laden nach dem Refactoring weiterhin
  spürbar; beim Seitenwechsel wird alles kalt nachgeladen — Caching fehlt.
  Framework-Wechsel (Next.js/React) ausdrücklich geprüft und VERWORFEN: die
  Latenz steckt im Daten-Layer (Kaltstart pro Seite + Server-TTL), nicht im
  Rendering; ein zweiter Node-Prozess wäre für das Ein-JAR-Deployment ein
  Rückschritt. Stattdessen P7 im bestehenden Vanilla/SSE-Design.
- `live-data.js`: jedes `emit()` schreibt `{receivedAt, value}` nach
  `sessionStorage` (`solarminer.channel.v1.<name>`; 30-min-Netz,
  128-KB-Deckel, Quota-Fehler sind rein optinal). Der Channel-Konstruktor
  rehydriert VOR dem ersten `subscribe()` (source `snapshot`), damit der
  erste Handler sofort malt. Bestehende Alter-Regel unverändert: älter als
  `maxAgeMs` ⇒ `meta.stale:true`, Watchdog (2 s) ersetzt; jünger ⇒ gilt als
  frisch, SSE/Push überschreibt bei Änderung. `persist:false` für
  Run-State-Kanäle (benchmarks, efficiency in `benchmarks.js` gesetzt).
  NEU `SolarMinerLive.snapshot(name)` — Lesezugriff ohne Kanal-Registrierung.
  NEBENBEFUND: der dokumentierte P5-`firstPaint()`-Aufruf in `onopen` fehlte
  im Code (work-log P5 behauptete ihn) — wieder eingebaut.
- `components.js`: `cachedCoinCatalog()` — der closed-Enum-Katalog
  (`/status/coin-catalog`, Server-TTL 1 h) wird pro Tab gecacht;
  `workers.js`/`wallets.js` nutzen ihn statt Frischfetch pro Seitenaufruf.
- `wallets.js` (bewusst kanalloser manueller Pfad): seedet beim Load aus dem
  `overview`-Snapshot des Tabs und rendert sofort; der REST-`/overview`-Read
  bleibt Source of Truth und re-rendert. Fehlerpfad zeigt die Offline-Meldung
  nur noch, wenn gar kein Seed vorhanden ist (sonst weiter aus dem Snapshot
  arbeiten lassen).
- Verifikation: Node-Harness (Stub sessionStorage/EventSource) 8/8 —
  Snapshot geschrieben, Rehydrierung vor subscribe, sofortige Lieferung mit
  stale:false (frisch) / stale:true (alt), >30 min verworfen, persist:false
  schreibt nie, snapshot()-API. `node --check` je geänderter Datei sauber;
  `check-i18n.cjs` grün (1219 Keys, keine neuen UI-Texte),
  `scan-i18n-literals.cjs` 0; `sh gradlew test --offline` 164/164 (41
  Klassen, Zähler aus `build/test-results/test/*.xml`); Standalone-JAR neu
  gebaut und auf Port 18085 gebootet: Aggregat liefert alle 10 Sektionen,
  SSE-Kanäle senden Events (5 Events in 6 s), danach beendet.
  `git diff --check` sauber. Wire-Disziplin: keine REST-Pfade/Payloads
  geändert, nur Client-Caching; `/external/**` unberührt.
- Status: unvercommitet (Owner-Freigabe steht aus).

## 2026-10-11 (Sitzung 11): REST-Controller Architektur-Audit + Refactoring (I/O aus Request-Pfaden)
- Auftrag: Vollständiges Audit aller HTTP-Endpunkte gegen das Prinzip „REST-Controller lesen nur
  In-Memory-Snapshots / übergeben Commands; keine direkte oder indirekte I/O in der transitiven
  Request-Kette“. Bericht: `docs/architecture/pc-agent-rest-controller-audit.md` (Inventar 15
  Controller / 106 Endpunkte, Klassifizierung, Zielarchitektur, Plan R1–R11, Re-Audit J).
- UMGESETZT (alle Planpunkte):
  - R1 NEU `mining/proxy/ProxyHealthService`: Proxy-Reachability + Fee-Ready je Coin als
    `AtomicReference<Snapshot>`, @Scheduled 2 s Single-Flight auf Virtual Thread;
    `GET /proxy`, `/overview`, `/external/capabilities` lesen nur noch den Store. Live-Checks
    bleiben in `ProxyConfigurationService` für Miner-Start-Gates (`miningReady` = kein REST-Pfad).
  - R2 `FeeTransparencyService`: volatile `overviewSnapshot`/`devFeeSummarySnapshot` +
    @Scheduled-Collector (5 s) + Warm; `GET /fees`, `/fee-tier` snapshot-basiert.
  - R3 `PayoutDefaultsService`: `resolveCached()` (rein) + @Scheduled-Prewarm (15 s) + Warm;
    REST `view()` und alle Command-Pfade (Controller, WorkerAssignment) cache-only.
  - R4 `MarketDataService`: `prices()`/`forecasts()` rein lesend; Erfassung nur noch @Scheduled
    (`refreshPricesIfNeeded`, `refreshNetworksIfNeeded`) + `warmCaches()`. Kein Request triggert
    mehr eine Erfassung.
  - R5 `MinerCatalogService`: Installations-Flags volatile; Probe bei Startup/@Scheduled 10 s/
    nach download+remove; Katalog-Reads dateifrei.
  - R6 REST-Reads `LocalGpuPowerService.snapshot()` statt `discover()`: AgentPower-Status,
    Worker-Validierung, Capabilities, MiningService-Stats, selectedGpus(true).
  - R7 `XmrConfigService.readUserPoolFromConfig()` MTime-Cache (Invalidation an allen 5
    Schreibpfaden); `XmrMinerService.readCPUTemperature()` 1-s-Cache; `randomXStatus()` 5-s-Cache.
  - R8 Benchmark-Sharing `retry-manual`/`upload-manual`: Auftragsannahme (`UPLOADING`,
    Single-Flight), Upload auf Virtual Thread; UI-Zweig in `benchmarks.js` (bestehender
    i18n-Key `uploading_benchmark_data`).
- NEUE TESTS: `architecture/RestControllerArchitectureTest` (ArchUnit 1.3.0 neu in
  build.gradle.kts: keine java.net.http/ProcessBuilder/CompletableFuture/InetAddress in
  Controllern außer dokumentierter Allowlist) und
  `controller/RestReadModelNonBlockingTest` (Nachweis: proxy-Endpunkt ruft nie isReachable/
  feeReady; forecasts/prices/payout lösen bei leerem Cache keinen Upstream-Call aus;
  Health-Snapshot atomar). Angepasst: NodeMiningProfileTest, WorkerAssignmentServiceTest,
  MiningControllerValidationTest, MinerCatalogServiceTest, MarketDataServiceTest.
- Verifikation: `JAVA_HOME=~/.jdks/graalvm-ce-21.0.2 sh gradlew test --no-daemon` BUILD
  SUCCESSFUL, 175 Tests / 43 Klassen, 0 Fehler (Zähler aus build/test-results/test/*.xml);
  `sh gradlew standaloneJar` erfolgreich; `check-i18n.cjs` grün (1221 Keys, beide Locale).
- API: keine Drahtformate geändert. Verhaltensänderungen dokumentiert im Bericht (§J):
  Upload-Endpunkte liefern zuerst UPLOADING; Lese-Endpunkte zeigen nach Mutation bis zu einem
  Kollektor-Tick den alten Stand.
- Status: unvercommitet (Owner-Freigabe steht aus).
## 2026-10-13 — Node earnings monitoring and known-fee forecast

- `GET /api/agent/external/earnings` is now part of the read-only monitoring allowlist, so disabling remote miner control no longer removes Node forecasts while status/power telemetry remain visible.
- The external response keeps the existing gross fields and additively exposes gross and known-fee-net daily coin/USD values. Net values are withheld unless the proxy fee route and all pool/miner fee parts are resolved; rejected/stale shares, taxes and power cost remain outside the estimate.
- Verification: JDK 21 `sh gradlew test --no-daemon` and `sh gradlew standaloneJar --no-daemon` passed, including the access-filter regression and known-fee mapping tests. The closed frontend i18n catalog, both-locale render simulation and unkeyed-literal scan passed. No live Kryptex credit or payout was created by this change.
## 2026-10-13 — QuanPool Miner 6.4.0 für Quantus

- Offizielle QuanPool-Seite und den real ausgelieferten Linux-x86-64-Build geprüft. Live-Header:
  15.054.128 Byte; SHA-256 `b3663cc6e7acc0a006e5d3d8c03dbce85e7c6a67cb922412a32703c18a5c4c20`
  stimmt mit der Seite überein. CLI, Non-TTY-Logs und `/hive-stats` wurden direkt erfasst; der
  auf der Website genannte `gpu-list`-Befehl existiert in 6.4.0 nicht.
- Eigenständigen, atomaren und größen-/hashgeprüften Installer sowie Quantus-only-Adapter mit
  Proxy-Relay, Fee-/Versionsprüfung, Prozess-Exklusivität, Stop/Restart, redigierter Konsole und
  Hive-Stats-Telemetrie ergänzt. QuanPool-Dateien und SRBMiner-Paket bleiben vollständig getrennt.
- Geräteauswahl (später in derselben Beta-Arbeit korrigiert): Der erste Entwurf begrenzte den
  Adapter wegen `--gpu-devices N` auf `NVIDIA:0`. Die nachfolgende UUID-Isolation hebt diese
  unnötige Beschränkung auf; maßgeblich ist der Eintrag vom 2026-10-14 unten.
- Katalogvertrag additiv um `installable` erweitert: Paket kann auf Linux x86-64 installiert
  werden, bleibt aber `selectable=false`, bis NVIDIA-Start/Stop, SolarMiner-Proxy-Protokoll und
  echte User-/House-/Referral-Share-Abrechnung belegt sind. Software- und Worker-UI werten diese
  Zustände getrennt aus. Neue UI-/Status-Texte sind im geschlossenen DE/EN-Katalog enthalten;
  externe Miner-Ausgabe bleibt bewusst unverändert.
- Dauerhafte Verträge und offene Gates: `quantus-integration.md`; Vorschlag für schnelle neue
  Miner über signierte Manifest-/Fixture-Pakete: `miner-onboarding-architecture.md`.
- Verifikation: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test standaloneJar
  --offline --no-daemon` grün (196 Tests), beide i18n-Audits und `git diff --check` grün.
  Das Standalone-JAR initialisierte den vollständigen Spring-Kontext und Tomcat; der anschließende
  Tray-Start scheiterte erwartungsgemäß auf dem headless Prüfhost an fehlendem X11. Reale
  GPU-/Pool-Zertifizierung ist ausdrücklich noch offen und wird nicht als Support behauptet.

## 2026-10-13 — QuanPool Pool-/Payout-Read für QTC

- Offizielle Live-Verträge geprüft: `/api/chains` veröffentlicht die mainnet-Stratumziele
  Global, US und Asia; `/api/terms` meldet aktuell 1% Poolgebühr und die planck-basierten
  Auszahlungsschwellen. Der vom Betreiber ausgelieferte Frontendcode belegt `10^12` planck
  pro QTC und liest `/api/miner/mainnet/{address}` ohne Login/API-Key.
- QTC-Poolkatalog um `global.quanpool.com:9854`, `us.quanpool.com:9854` und
  `asia.quanpool.com:9854` ergänzt. Neuer `QuanPoolBalanceProvider` erkennt ausschließlich
  Quantus und echte QuanPool-Hosts, URL-kodiert die Adresse und liefert pending sowie
  kumuliert paid in QTC. HTTP-/Schemafehler bleiben `UNAVAILABLE`; nur ein explizites
  `seen=false` bedeutet bekannten Nullstand.
- Generischen Pool-Account-Vertrag additiv um `paidTotal`/`paidTotalStatus` erweitert. Der
  Wallet-Streifen zeigt QuanPools bestätigte Gesamtauszahlung separat und zählt historische
  Auszahlungen nicht als aktuellen Vermögenswert. DE/EN-Texte wurden gemeinsam ergänzt.
- Die Antwort enthält außerdem ein walletbezogenes Ledger mit `payout_debit`, Betrag,
  Payout-ID, Zeit und Status. Eine chronologische History ist noch nicht im PC-Agent-UI-Vertrag;
  die API ist vom offiziellen Frontend genutzt, aber nicht separat versioniert dokumentiert.
- Verifikation: vollständige Java-21-Suite und `standaloneJar` grün (200 Tests, 0 Fehler;
  darin `WalletBalanceServiceTest`: 7), beide i18n-Audits, JavaScript-Syntaxprüfung und
  `git diff --check` grün. Keine fremde Wallet wurde abgefragt und kein echter Share/Credit/
  Payout erzeugt; die Miner-Zuweisung bleibt deshalb gemäß Integrationsleitfaden gesperrt.

## 2026-10-14 — QuanPool-Workerzuweisung auf dem Beta-Branch

- Der installierte QuanPool Miner ist im Katalog direkt auswählbar und kann einem Quantus-Worker
  zugewiesen werden. Ein zunächst ergänzter separater Laufzeitschalter wurde auf Owner-Hinweis
  vollständig entfernt: Der `beta`-Branch ist bereits das Freigabe- und Test-Gate; erst nach
  erfolgreicher Zertifizierung wird der Code auf den Release-Pfad übernommen.
- Keine channel-spezifischen Argumente, Docker-Variablen oder Launcher-Sonderfälle. Die
  Plattformgrenze Linux x86-64/NVIDIA bleibt; eine feste GPU-Indexgrenze gibt es nicht.
- Der Pfad bleibt experimentell und ist keine Produktionsfreigabe. Die echten NVIDIA-, Proxy-,
  Share- und Payout-Nachweise aus `quantus-integration.md` bleiben vor Release-Promotion offen.
- Verifikation nach Entfernung des Gates: Java-21-Suite und `standaloneJar` erfolgreich
  (200 Tests, 0 Fehler), beide DE/EN-i18n-Audits, JavaScript-Syntax, Installer-Syntax und
  `git diff --check` grün.

## 2026-10-14 — Beliebige NVIDIA-GPUs mit QuanPool Miner

- Ursache der falschen `NVIDIA:0`-Sperre: QuanPools CLI kann mit `--gpu-devices N` nur die
  ersten N für den Prozess sichtbaren Karten wählen. Das bedeutet nicht, dass SolarMiner auf
  den globalen Index 0 beschränkt sein muss.
- Der Adapter startet nun pro ausgewählter Karte einen eigenen Prozess und setzt dessen
  `CUDA_VISIBLE_DEVICES` auf die stabile, durch `nvidia-smi` erkannte GPU-UUID. Der Miner sieht
  dadurch genau diese Karte als sein internes Device 0. Beliebige NVIDIA-Indizes sowie mehrere
  gleichzeitige Zuweisungen sind möglich, ohne von der CUDA-Reihenfolge abzuhängen.
- Prozess, Relay, Metrikport, Temperatur, Shares, Konsole, Pause und Crash-Restart werden pro
  GPU getrennt geführt. Physische Exklusivität verwendet weiterhin dieselbe UUID wie die
  anderen Mineradapter.
- Tests belegen UUID-Umgebungsisolation, beliebige/nicht fortlaufende Indizes, Mehrfachauswahl,
  Duplikat- und AMD-Ablehnung. Ein echter Multi-GPU-Hosttest bleibt Teil der Beta-Zertifizierung.
- Verifikation: Java-21-Suite und `standaloneJar` erfolgreich (204 Tests, 0 Fehler), beide
  DE/EN-i18n-Audits, JavaScript-Syntax und `git diff --check` grün.

## 2026-10-14 — Miner-Auswahl pro physischem Worker

- Die Miner-Auswahl ist nicht länger nur ein Coin-Default. `AgentControlSettingsService`
  persistiert additiv `workerMiners` neben `workerCoins`; ein explizit eingerichteter Worker
  behält damit seine konkrete Software-ID. Alte Profile ohne dieses Feld fallen weiterhin auf
  die bisherige Coin-Auswahl zurück und werden nicht ungültig.
- `MinerFactory`, `MiningService`, `WorkerAssignmentService` und die alten GPU-Aktionsrouten
  lösen Start, Pause, Node-Dispatch und Telemetrie jetzt über Gerät + Coin + Software-ID auf.
  Zwei Quantus-GPUs können dadurch gleichzeitig QuanPool Miner beziehungsweise
  SRBMiner-MULTI verwenden. Eine Zuweisung ändert nur den ausgewählten physischen Worker;
  der andere Worker bleibt unverändert. Die Prozessregistrierung verhindert weiterhin, dass
  zwei Adapter dieselbe GPU-UUID gleichzeitig beanspruchen.
- QuanPool besitzt nun eine eigene Konfigurationsdatei unter
  `solarminer-agent/quanpool/solarminer-quantus.json`; SRBMiner behält seine Quantus-Datei.
  Ein Beta-Profil aus dem vorigen gemeinsamen Speicherformat wird übernommen, sofern seine
  Geräteauswahl QuanPool-kompatibel ist. Beim Speichern einer Quantus-Wallet wird dieselbe
  Route auf alle tatsächlich zugewiesenen Adapter verteilt, aber jeweils nur mit deren eigener
  GPU-Teilmenge.
- Energ Sessions zeichnen die wirkliche Worker-Miner-ID auf. Verwendet ein Coin gleichzeitig
  Miner mit verschiedenen Entwicklergebühren, bleibt die coinweite Netto-Schätzung bewusst
  unbekannt; die Gebühren werden nicht fälschlich addiert oder über alle Worker vereinheitlicht.
  Die neue Diagnose ist im geschlossenen DE/EN-Katalog enthalten; sonst entstand keine neue
  Bedienkopie. Externe Miner-Ausgabe bleibt wie dokumentiert unverändert.
- Regressionstests belegen unabhängige Persistenz zweier Quantus-Worker, Adapter-Routing beim
  Start, unveränderte Nachbar-GPUs, Legacy-Settings-Migration und korrekte Miner-ID im
  Energiejournal. Verifikation: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test
  standaloneJar --offline --no-daemon` erfolgreich (209 Tests, 0 Fehler/Skips),
  `check-i18n.cjs` (1.269 Schlüssel, beide Locales), `scan-i18n-literals.cjs` (0) und
  `git diff --check` grün. Reale gemischte Multi-GPU-Ausführung bleibt Teil der Beta-
  Hardwarezertifizierung; es gibt weiterhin keinen zusätzlichen Runtime-Beta-Schalter.
## 2026-10-15 — Efficiency sweep tolerates delayed worker registration

- Root cause of the reported immediate "no stable profile" result: `measureStep`
  treated `findWorker(...) == null` as an instant miner failure, even though the
  configured startup grace only covered a present worker reporting zero hashrate.
  A freshly restarted SRBMiner can briefly have no worker row in the adapter while
  its process/API state is still initializing, so the sweep stopped before using
  the four-minute grace period.
- Missing worker rows now remain in the normal sample loop (temperature safety
  checks continue); expiry of the existing startup grace reports a specific,
  localized diagnosis. An explicit `ERROR` worker still fails immediately.
- German and English text were added to the closed catalog and dynamic status
  translations. Both i18n checks passed (1,270 keys; zero unkeyed display
  literals). The Java suite could not start: Gradle attempted to create its
  wrapper lock under the read-only `/home/lukas/.gradle` directory. `git
  diff --check` passed after recording this entry.
## 2026-10-15 — Expose live power-limit sample progress

- The sweep API already publishes the active GPU, power cap, sample count,
  required sample count and current phase, but the active card rendered only a
  compact text counter. It now includes an accessible progress bar and explicit
  stage guidance: waiting for the first valid sample (miner startup can take
  up to four minutes) or collecting samples at five-second intervals.
- Added both German and English catalog entries. No API contract changed.
- Verification: `node --check src/main/resources/static/js/pages/benchmarks.js`,
  `check-i18n.cjs` (1,273 keys, both locales), `scan-i18n-literals.cjs` (0)
  and `git diff --check` passed.
## 2026-10-15 — Quantus sweep matched the display algorithm, not telemetry ID

- Diagnosis: sweep targets used the adapter display label `QPoW (Poseidon2)`,
  while Quantus worker telemetry reports the canonical algorithm ID `quantus`.
  `findWorker` required an exact string match, so it never found a live Quantus
  worker and the startup grace eventually produced an unstable step despite the
  normal worker view showing hashrate. Lookup now validates the device ID and
  resolves the worker algorithm through the closed `Coin` identity mapping.
- A regression test covers the display-label/canonical-ID mismatch and rejects
  a different device or coin algorithm.
- Windows power-control path review: NVIDIA uses `nvidia-smi -i <GPU UUID> -pl
  <watts>`, then reads `power.limit` back and refuses a target differing by
  more than 1 W. The Windows launcher starts the Agent only with an
  Administrator token. Existing simulated power-service tests exercise the
  write/readback path, but this Linux workspace cannot establish whether the
  user's Windows driver accepted this run's changed targets. A native Windows
  changed-value write/readback remains required for live confirmation.
- Verification: focused Gradle tests could not start because Gradle cannot
  determine a usable wildcard IP in this execution environment (also with
  IPv4-preference enabled); source-level test added, native Windows probe
  unavailable.
## 2026-10-15 — Apply saved GPU bounds before manual miner start

- Diagnosis from the RTX 2080 report: the Hardware page saved per-GPU minimum
  and maximum bounds, but manual `resumeMining` started the adapter without
  applying those saved bounds. A previous 220 W driver cap therefore remained
  active after saving a 180 W maximum. Applying new bounds now immediately
  clamps the live driver limit; the page says the limits are checked again at
  the next miner start.
- Every GPU adapter now asks `LocalGpuPowerService` to clamp the live driver cap
  into that GPU's saved [minimum, maximum] range before starting. The service
  reads fresh driver state and verifies any changed cap through readback; it
  leaves already-in-range targets untouched. This covers worker, coin, local
  and Node start paths, and preserves a global budget that is already in range.
- Regression coverage verifies a 220 W NVIDIA mock cap is written and read back
  as 180 W when the bounds are applied, and the start path rechecks them. The
  Gradle test run could not initialize in
  this Linux execution environment because it cannot determine a usable
  wildcard IP. Native RTX 2080 Windows verification remains outstanding.
## 2026-10-15 — Persist no-stable efficiency sweep results

- The sweep built a profile with completed step results and failure notes when
  no stable limit was found, but only called `GpuEfficiencyStore.save` in the
  stable-result branch. The results page reads that store, so failed curves
  disappeared after the run.
- Completed no-stable runs now persist their full profile and step history;
  cancelled partial runs remain session-local and do not replace a prior
  completed profile. Failed attempts use history keys separate from the
  canonical stable-profile key, so economic planning can still find the last
  usable profile while the profile UI shows each attempt and its failure note.
  Completed-run rows also render the step's failure note; previously they only
  showed a cross and omitted the reason. Existing localized backend-note
  mappings are reused. Added a store regression test for retaining both
  records and preserving the stable lookup.
- Verification: `git diff --check` passed; focused Gradle execution remains
  unavailable in this environment because Gradle cannot determine a usable
  wildcard IP. Both i18n scripts passed (1,274 keys, 9 pages, German and
  English; no unkeyed browser literals).

## 2026-10-15 — Surface Linux GPU power-limit rejection details

- The Hardware UI replaced every failed limits response with the same generic
  driver-rejection text. The backend's `setUserLimits` also returned only a
  boolean, discarding the write/readback error from `nvidia-smi` or Linux AMD
  sysfs.
- Added a structured limits-update result with localized reason keys and the
  underlying driver detail. The UI now shows the specific reason while keeping
  the draft in place; failed writes still roll back the saved user range and
  restore the original live cap.
- Added a focused regression test for an NVIDIA driver write failure after the
  capability probe succeeds. Execution is not recorded yet.

## 2026-10-15 — Keep efficiency sweep progress visible between runs

- The benchmarks page rendered full cards only for `RUNNING` sweep runs. When the
  queue temporarily contained only dependency-blocked `QUEUED` work (for example,
  sibling GPUs waiting for a reference curve), its waiting group was collapsed by
  default and the active sweep appeared to have lost its progress display.
- The waiting group now opens automatically whenever there is no currently running
  run, including when an operator had previously collapsed it. This leaves queued
  work visible during transitions without changing the sweep API or adding UI copy.
- Verification: `node --check src/main/resources/static/js/pages/benchmarks.js`,
  both i18n checks, and `git diff --check` passed.
