# Independent console tools / Eigenständige Konsolenwerkzeuge

Implemented 2026-10-20. New entry: `/console/tools.html`, linked from the new
operations sidebar and local worker details. `/console/index.html` remains the
fleet overview. Both use only the new console's assets and
`/api/agent/local/operations/**`. No existing controller is called or inherited.
Old frontend files/controllers remain separately available for comparison.

Umgesetzt am 20.10.2026. Neuer Einstieg: `/console/tools.html`, über die neue
Navigation und lokale Worker-Details erreichbar. Die Flottenübersicht bleibt
unter `/console/index.html`. Beide Oberflächen verwenden ausschließlich neue
Konsolen-Dateien und Operations-APIs. Alte Controller werden weder aufgerufen
noch geerbt; die bisherige Oberfläche bleibt separat zum Vergleich erhalten.

## Delivered workflows / Umgesetzte Abläufe

| Tool / Werkzeug | Behavior / Verhalten |
| --- | --- |
| Wallets / Pools | Coin-specific custom routes, authoritative complete default route, saved-target display and available pool/on-chain balances. / Eigene coinabhängige Routen, vollständiges Standardziel, gespeicherte Ziele und verfügbare Pool-/On-Chain-Guthaben. |
| Benchmarks | LIVE/INSTALLED, explicit worker/coin selection, 1–64 GPU concurrency, optional mining after run, cancellation, saved profiles and full JSON export. / LIVE/INSTALLED, ausdrückliche Worker-/Coin-Auswahl, 1–64 parallele GPUs, optionaler Start nach dem Lauf, Abbruch, gespeicherte Profile und vollständiger JSON-Export. |
| Efficiency sweep / Effizienz-Sweep | Restart/resume/cancel for selected GPUs and coins, concurrency and profile results. / Neu starten, fortsetzen und abbrechen für ausgewählte GPUs/Coins, Parallelität und Profilergebnisse. |
| Power / Leistung | Driver/user/current/default GPU limits; validated manual cap, worker operating mode, per-worker remote/boot permissions. A stopped manual CPU can set a session-local estimated thread budget without starting mining. / Treiber-/Nutzer-/Ist-/Standardgrenzen, validierte manuelle GPU-Grenze, Betriebsmodus und Worker-Fernsteuerung/Autostart. Eine pausierte CPU im manuellen Modus erhält ein geschätztes Thread-Budget für diese Agent-Sitzung, ohne Mining zu starten. |
| Electricity / Strom | EUR/USD/CHF tariff editor and today's recorded energy/cost. / Tarifeditor für EUR/USD/CHF und erfasste heutige Energie/Kosten. |
| Auto Tuning | Device-scoped dimensions/efficiency-or-profit goal, global concurrency, start/per-device cancel, explicit calibration-data removal, current session progress and confirmed profiles/history. / Gerätebezogene Dimensionen und Effizienz-/Gewinnziel, globale Parallelität, Start/gerätebezogener Abbruch, ausdrückliches Löschen der Kalibrierdaten, Fortschritt und bestätigte Profile/Messverlauf. |
| Clocks / Taktraten | Driver capabilities, current clocks, memory-offset range, explicit core/memory/offset writes and default restoration through the domain service. / Treiberfähigkeiten, aktuelle Takte, Speicher-Offset-Bereich, ausdrückliche Kern-/Speicher-/Offset-Schreibaktionen und Wiederherstellung über den Fachdienst. |
| Updates | Channel/version/status, explicit release check, supervised-native installation with restart/version confirmation, channel-correct Docker host instructions. / Kanal/Version/Status, ausdrückliche Release-Prüfung, überwachtes natives Update mit Neustart-/Versionsprüfung und passende Docker-Host-Befehle. |
| Logs / Miner-Ausgabe | Source selector, incremental UTF-8-safe tail, follow/pause-scroll, bounded display, inert raw text and complete download. / Quellenwahl, inkrementelle UTF-8-sichere Ausgabe, automatisches Nachführen optional, begrenzte Anzeige, unveränderter Text und vollständiger Download. |

## Application boundary / Anwendungsgrenze

`ConsoleToolsController` is a new HTTP adapter. `ConsolePayoutService` owns the
new route orchestration; `ConsoleToolsService` dispatches commands to existing
domain services. `ConsoleToolJobs` bounds writes; `ConsoleToolsReadService`
collects requested sections off the request thread.

`ConsoleToolsController` ist ein neuer HTTP-Adapter. `ConsolePayoutService`
koordiniert die neuen Routenänderungen, `ConsoleToolsService` delegiert an die
bestehenden Fachdienste. `ConsoleToolJobs` begrenzt Schreibaufträge;
`ConsoleToolsReadService` sammelt angeforderte Ansichten im Hintergrund.

- `GET /tools/state/{wallets|measurements|hardware|tuning|updates|logs}?device=…`
  under `/api/agent/local/operations` returns `{collectedAt,payload,error}`.
  Initial data may be null while the background collector runs. Failed refreshes
  retain the original timestamp and last payload. Tuning is collected per device
  before serialization, with at most 200 history readings. Other tables are
  filtered/bounded in the read model; the UI shows at most 50 result rows.
- `POST /tools/tasks` takes `{id,action,arguments}`; HTTP 202 means accepted,
  not a successful measurement, confirmed mining or a completed update.
  `GET /tools/tasks/{id}` returns queued/running/succeeded/failed. Same ID and
  same request returns the existing task without a second write. Another body
  with that ID is rejected. Two execution threads, queue 16, at most 128 retained
  tasks and a 30-minute completed-task admission expiry bound memory.
- IDs are process-local. After restart or expiration, a missing task requires
  checking current device state. The browser stores IDs and reads them back
  after an ambiguous response; it never automatically resends a mutation.
  Native update completion is checked against the target running release tag.
- Collectors run only for recently requested sections (30 seconds after the
  last read), coalesce concurrent collection, retain at most 128 watched keys,
  and prune inactive cached keys. No driver/network probe runs in a status GET.
  Explicit JSON export reads in-memory domain history on demand and can be
  larger than the normal paginated view.
- Same-origin protection remains `AgentWriteAccessFilter`. Tool APIs are local
  to the agent. No remote driver/configuration permission or external LAN wire
  contract is added. Per-worker remote/boot settings preserve other settings.

Die Status-GETs liefern Zeitpunkt, Daten und Fehler getrennt. Ein kalter Abruf
wartet nicht auf Hardware oder Netzwerke. Fehler verjüngen keine alten Daten.
Die normalen Tabellen und Kalibrierungsverläufe bleiben begrenzt; vollständige
Daten werden nur ausdrücklich exportiert. Schreibaufträge besitzen feste IDs
und Einzelergebnisse. Dieselbe ID wiederholt keinen Schreibvorgang innerhalb
der laufenden Aufbewahrung. Nach einem Neustart ist der Verlauf nicht dauerhaft
vorhanden; die Oberfläche prüft den Ist-Zustand und wiederholt keine Mutation.
Die vorhandene Same-Origin-Prüfung gilt auch hier. Die LAN-Fernsteuerung wird
nicht um Treiber- oder Konfigurationsschreibrechte erweitert.

## Mutation semantics / Bedeutung von Änderungen

- Routes are validated before stopping miners. GPU adapter routes are all
  validated before the first stop/write. Proxy endpoints are derived from the
  configured agent proxy; a caller cannot supply a bypass route. Empty-wallet
  saves use the default target's pool/login together, including the exact CPU
  default login. Saving never changes the active coin or starts a stopped miner.
- Writing several miner configuration files cannot be atomic. A partial write
  reports `tool_route_partial`; affected workers remain stopped and must be
  checked before restart. A failed write does not change the default-target flag.
- Power, clock, route, mode and worker-policy changes respect `LocalRunLock`.
  Mode changes require a stopped worker and validate the selected mode's
  actual profiles, hardware and market prerequisites. Auto Tuning's profit goal
  remains a preference with the existing efficiency fallback.
- CPU targets are explicitly estimates translated into thread counts. They
  require a stopped manual CPU, stay within the domain's min/max, and are not
  persisted across agent restarts. They are not a measured wall-power cap.
- Calibration/profile clearing is an explicit destructive UI action. Arbitrary
  voltage controls and unsupported GPU clock writes are not introduced.
- Update install is an explicit UI action and still requires the existing
  supervised launcher. Docker gets instructions; the agent does not mutate its
  container. A task being applied is not itself proof of a successful restart.

Routen werden vor einem Miner-Stopp geprüft. Alle betroffenen GPU-Adapter müssen
die Route validieren, bevor die erste Konfiguration geschrieben wird. Proxy-Ziele
werden vom Agent übernommen. Leere Wallets übernehmen Pool und Login zusammen
vom verfügbaren Standardziel. Mehrere Konfigurationsdateien sind keine atomare
Transaktion: Teilfehler werden ausdrücklich gemeldet, betroffene Miner bleiben
gestoppt. Speichern wählt keinen anderen Coin aus und startet keine Miner.

Leistungs-, Takt-, Routen-, Modus- und Worker-Einstellungen beachten die Messsperre.
Modusänderungen verlangen einen pausierten Worker. CPU-Ziele sind ausschließlich
ein geschätztes, sitzungslokales Thread-Budget. Das Löschen von Kalibrierdaten und
ein Agent-Update sind ausdrückliche Bedienaktionen. Updates behalten die
Launcher-/Kanal-/Prüfsummenregeln; der tatsächlich laufende neue Release-Tag
bestätigt den erfolgreichen Neustart.

## Language and verification / Sprache und Prüfung

All new controls, labels, notices and known domain errors use the independent
DE/EN `console/tools-i18n.js` catalog. Static and dynamic keys, action labels and
Auto Tuning/clock error codes are checked by `check-operations-i18n.cjs`.
Hardware/software names, IDs, URLs and raw external miner/driver output remain
verbatim; diagnostic disclosures are labelled and raw output is rendered as text.

Alle neuen Bedienelemente, Hinweise und bekannten Fehler sind auf Deutsch und
Englisch verfügbar. Geräte-/Softwarenamen, IDs, URLs und externe Miner-/Treiber-
Originalausgaben bleiben unverändert; ihr Umfeld ist übersetzt. Die Ausgabe wird
als Text dargestellt. Die Sprachprüfung deckt auch dynamische Aktionsnamen und
die Fehlercodes der Auto-Tuning-/Takt-Fachdienste ab.

Verification includes targeted domain/HTTP/job tests, complete Java suite and
standalone packaging, both locale checks, the existing 2,000-worker overview
browser fixture, and `scripts/check-operations-tools-browser.cjs`. The tool
fixture covers 1,000 workers with a 50-row picker, explicit selection, wallet
save, benchmark commands, power/mode/permission writes, tuning, clocks, lost
acceptance-response recovery, update restart/version checks, split UTF-8 log
chunks, inert HTML-looking output, both languages and 390 px mobile layout.
Every API request is intercepted and rejects a legacy endpoint.

Geprüft werden Fachdienst-/HTTP-/Auftragsverträge, Java-Gesamtsuite und
Standalone-Paket, beide Sprachen, die bestehende Übersicht mit 2.000 simulierten
Workern und die neuen Werkzeuge mit 1.000 simulierten Workern. Die Browserprüfung
umfasst auch unklare Annahmeantworten, Update-Neustart/Versionsabgleich und
aufgeteilte UTF-8-Ausgaben. Sie greift auf keine reale Mining-Hardware zu.
Echte GPU-/Pool-/Treiber- und Launcher-Update-Abnahmen bleiben erforderlich;
ein erfolgreicher Build oder ein Fixture bestätigt diese nicht.
