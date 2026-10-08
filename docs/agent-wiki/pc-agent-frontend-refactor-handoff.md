# PC-Agent Frontend: Aufräumen, Übersetzungspflicht, Responsiveness — Übergabe

**Status: NICHT UMGESETZT.** Dies ist ein Plan mit Mess- und Code-Evidenz, kein Nachweis gelieferter
Funktion. Zwei Dateien liegen bereits im Working Tree (uncommitted, siehe §1). Behandle jeden
Punkt unten als zu verifizieren, bevor du ihn änderst.

Auftrag (2026-10-07): statisches PC-Agent-Frontend grundlegend aufräumen, Duplikate entfernen, das
Übersetzungsschema fest verdrahten und KI-Agenten auf die Übersetzungspflicht hinweisen; danach eine
tiefgreifende Performance-Analyse mit Fokus auf Responsiveness, Polling zugunsten von Events
zurückdrängen und ausschließen, dass alte Daten im Frontend verbleiben. Funktionalität und Design
dürfen sich nicht ändern.

## 0. Arbeitsbedingungen — zuerst lesen

- `git status` war zu Beginn der Analyse clean; während der Arbeit haben **andere Akteure parallel**
  in `pc-agent/src/main/resources/static/` und `pc-agent/src/main/java/.../mining/` geschrieben
  (`proxy-gate.js`, `ProxyGateController`, `ProxyReleaseService`, `AgentIdentityService`, alle
  `*.html`, `i18n.js`, `agent.css`, `overview.js`, `workspace.js`). **Vor jedem Write erneut
  `git status --short` prüfen** und Datei-Mtimes mit Sitzungsbeginn vergleichen.
- Alle Zeilennummern unten wurden gegen `HEAD = 52652f9` (Branch `beta`) erhoben und am 2026-10-07
  gegen den Working Tree nachgeprüft. Sie **verschieben sich**, weil parallel editiert wird.
  `overview.js` wuchs z. B. von 425 auf ~466 Zeilen.
- Vor der ersten Änderung wurde ein Text-/Struktur-Baseline-Snapshot aufgenommen. Er ist durch die
  Fremdänderungen **veraltet** und muss neu aufgenommen werden.

## 1. Was bereits im Working Tree liegt (von dieser Aufgabe)

| Datei | Zustand | Verifikation |
| --- | --- | --- |
| `pc-agent/src/main/resources/static/components.js` | neu geschrieben: gemeinsame Sichtschicht. Neu exportiert: `$`, `s`, `kilowattHours`, `duration`, `tariff`, `money`, `cellLines`, `statusStrip`, `notice`, `clearNotice`, `setConnection`, `setUpdated`, `filterButtonGroup`, `debounce`, `getJson`, `postJson`, `readError`, `coinCatalog`, `coinName`, `coinTicker`, `gpuCoinIds`, `configurationFor`, `matchesAlgorithm`, `temperatureOf`, `sensorKey`. Entfernt (nach Tot-Code-Analyse ohne Abnehmer): `panel`, `tabs`, `scopeBar`, `installSearchShortcut`, `coinBlockers`, `workerUsdPerDay`-Nutzung, `instances`, `gpuKey`, `idleDeviceNotice`, `sparkline`, `shareLines`, `percent`, `shares`, `tag`. `t()` hat jetzt optionale `params`. | `node --check` ok; alle 8 Seiten laden ohne neue Fehler |
| `pc-agent/src/main/resources/static/live-data.js` | neu, **von keiner Seite eingebunden**: ein SSE-Client pro Seite. Push ist Primärpfad; REST nur für Erstanzeige, nach Mutationen und als Watchdog. Jeder Channel hat `age()`, `fresh()`, `meta().stale`, Reconnect mit Backoff und `visibilitychange`-Pause. | `node --check` ok, nie ausgeführt |

Keine andere Datei wurde von dieser Aufgabe angefasst. `components.js` ist abwärtskompatibel zu den
lebenden Aufrufstellen (`overview.js`, `workers.js`) und zu `proxy-gate.js`, das nur
`SolarMinerI18n.t` benutzt.

## 2. Gemessene Baseline (Responsiveness)

Gemessen mit `.codex-qa/pc-agent-perf.cjs` (Fixture-Server, CDP-`Performance.getMetrics`,
`PerformanceObserver` für `longtask`, `window.fetch`-Wrapper), 15 s Fenster pro Seite, Locale `en`:

| Seite | Requests/min | Long Task | Scripting | Layout |
| --- | --- | --- | --- | --- |
| Benchmarks | **136** | 110 ms | 0,054 s | 0,114 s / 18 |
| Dashboard | **96** | 103 ms | 0,048 s | 0,108 s / 11 |
| Sensoren | **72** | 82 ms | 0,061 s | 0,091 s / 8 |
| Worker | 48 | – | 0,035 s | 0,050 s / 6 |
| Miner-Software | 32 | 51 ms | 0,051 s | 0,076 s / 6 |
| Wallets | 20 | 56 ms | 0,027 s | 0,051 s / 4 |
| Leistungsgrenzen | 20 | 67 ms | 0,041 s | 0,100 s / 5 |
| Verbindung | 12 | 54 ms | 0,020 s | 0,046 s / 4 |

Endpunkt-Aufschlüsselung (15 s):
`benchmarks`: `benchmarks`×16, `benchmarks/sharing/upload-status`×16 ·
`dashboard`: `overview`×5, `energy`×4, `node-assessment`×4, `power-control/settings`×4,
`telemetry`×4, `wallet-balances`×1, `market-prices`×1, `fiat-rates`×1 ·
`sensoren`: `telemetry`×17 · `miner`: `miner-options`×4.

Mutationen pro 15 s: Dashboard 331, Miner 270, Worker 305, Sensoren **465**, Benchmarks 355.

## 3. Befunde mit Belegen

### 3.1 Tot-Code

- `static/agent.js` (1600 Zeilen, 78 315 B) und `static/mining-workspace.js` (128 Zeilen) werden von
  **keiner** HTML-Seite geladen. `git show 52652f9 -- .../mining.html` zeigt, dass dieses Commit
  `agent.js`/`mining-workspace.js` durch `software.js` ersetzte und `pools.html`/`pools.js` löschte.
  Repoweiter grep nach `agent.js`/`mining-workspace` trifft nur Doku, keinen Code. Ihre Funktionen
  (Start/Pause, Konsole, Downloads, Poolwechsel) leben in `workers.js`, `wallets.js`, `software.js`
  weiter; die Konsole und `/{coin}/gpus/{vendor}/{index}/pause|resume` haben aktuell keinen Frontend-Aufrufer.
- `static/overview.js`: `render()` ruft nur `renderStatusStrip`, `renderAlerts`, `renderLiveWorkers`,
  `renderEnergy`. Nie aufgerufen: `renderKpis`, `renderCharts`, `track`, `renderPools`,
  `renderWorkers`, `renderAlgorithmPicker`, `workerColumns`, `charts`, `history`, `algorithms()`,
  `selectedAlgorithm`, `sort`, `workerFilter` — rund 190 Zeilen. Diese Funktionen referenzieren IDs
  (`kpi-strip`, `earnings-grid`, `charts`, `pool-list`, `worker-table`, `kpi-algorithm`), die in
  `index.html` nicht existieren → sie würden werfen, wären sie erreichbar.
- `pool-catalog.js` wird von `index.html` geladen, ist dort aber nutzlos (einziger Abnehmer war das
  tote `renderPools`).
- `hardware.html` und `telemetry.html` enthalten `<section id="wallet-strip">`, laden aber kein
  `wallet-strip.js` → leerer Bereich.
- `mining.html` hat `<a id="api-docs" hidden>`; einziger Abnehmer war `agent.js` (tot) → bleibt
  dauerhaft verborgen. `benchmarks.html` hat `<strong id="remaining">`, das kein Skript anfasst.

### 3.2 Duplikate (je Fundstelle: Datei:Zeile)

- `const $ = id => document.getElementById(id)` — 9 identische Kopien: `benchmarks.js:1`,
  `hardware.js:1`, `overview.js:4`, `proxy.js:1`, `software.js:2`, `telemetry.js:1`, `wallets.js:3`,
  `workers.js:4`, `agent.js:3`.
- DOM-Builder 9 Kopien von `ui.element`, davon **ohne** `t()`: `benchmarks.js:2` (`make`),
  `wallets.js:19` (`element`), `telemetry.js:7` (`el`).
- `notice()` 7 Kopien mit drei inkompatiblen Verträgen; `wallets.js:9` und `benchmarks.js:16`
  übersetzen ihre eigene Meldung nicht. Nur `hardware.js:7`/`proxy.js:5` kennen das
  `dataset.kind === 'connection'`-Protokoll.
- Fetch-Fehlerbehandlung: `if (!response.ok) throw new Error(\`HTTP ${status}\`)` an 10 Stellen;
  `body?.message || body?.detail || …` viermal (`proxy.js:7`, `workers.js:206`, `workers.js:225`,
  `benchmarks.js:142`).
- Formatierung 8 unabhängig: `components.js:5`, `measurement-format.js:2`
  (`maximumSignificantDigits: 4` — andere Rundung), `hardware.js:3`, `telemetry.js:3`,
  `wallet-strip.js:9` (`maximumFractionDigits: 8`), `benchmarks.js:51`, `benchmarks.js:55`,
  `agent.js:11`. **Hashrate zweimal** mit unterschiedlichen Einheitslisten und Rundungen:
  `components.js:8-14` (`H/s … EH/s`) vs `measurement-format.js:5-11` (`H … EH` + `/s`).
  **Effizienz zweimal**: `components.js:26-31` und `measurement-format.js:12-18`.
- kWh-Formatierung 5× identisch inline: `overview.js:159`, `overview.js:181`, `overview.js:182`,
  `workers.js:70`, `workers.js:93`; `overview.js:183` mit 2 statt 3 Nachkommastellen.
- Tarif-Formatierung doppelt (`overview.js:186`, `workers.js:71`), `money()` doppelt
  (`overview.js:176`, `workers.js:25`), `duration()` doppelt und **abweichend** (`overview.js:179`
  immer „H h M min", `workers.js:26-29` ohne Stunden).
- „Aktualisiert …" 5 divergente Stellen: `overview.js:351`, `hardware.js:86`, `proxy.js:49`,
  `telemetry.js:98`, `telemetry.js:69` („Stand …").
- Verbindungs-Badge an 16 Stellen in 4 Dialekten; `benchmarks.js:73` setzt
  `'Agent verbunden'` **ohne** `t()`.
- Coin-Identitätskarten 5 divergent: `components.js:297` (`ravencoin: 'kawpow'`),
  `workers.js:172` (`ravencoin: 'KAWPOW'`), `workers.js:30` (Ticker), `software.js:26` (nur 4 Coins,
  ohne `decred`/`quantus`), `proxy.js:38` (nur 4 Coins, ohne `decred`/`quantus`),
  `components.js:392` (`['pearl','ravencoin','ethereumclassic']`, ohne `decred`/`quantus`).
- `configurationFor(coin)`-Ternär 3× lebend: `wallets.js:12-16`, `wallet-strip.js:99-101`,
  `components.js:344-345`.
- Temperatur-Lookup **dreimal divergent**: `components.js:273-296` (AMD-Fuzzy-Match über Modellname),
  `workers.js:18-24` (nur `gpu.<vendor>.<index>.temperature`, kein AMD-Fallback),
  `telemetry.js:43-56` (eigene AMD-Variante mit `gpu.amd.card{index}.`).
- Filter-Button-Gruppen identisch verdrahtet in `software.js:98-100` und `workers.js:268-270`;
  `workers.js:75-87` baut `components.js:223-256` `scopeBar()` komplett neu und verliert dabei
  `aria-pressed`, `role="group"` und den `/`-Shortcut.
- `setInterval`-Refresh-Schleifen 9× mit 5 Intervallen und 3 Guards (siehe §3.4).
- `escapeHtml` existiert nirgends; genau ein `innerHTML` im ganzen Baum (`i18n.js:863`, statisch).
  Debounce/Throttle existieren nicht — Suchfelder lösen bei jedem `input` ein Voll-Rendering aus
  (`software.js:97`, `workers.js:84`, `telemetry.js:127-128`).

### 3.3 Übersetzungsschema (der Kern der Übersetzungspflicht)

`static/i18n.js` (881 Zeilen) ist **kein Key-Katalog**, sondern deutsche Quelle → Englisch:

- Locale: `i18n.js:3-6`, `supported = ['de','en']`, **Default `en`** wenn nichts gespeichert ist
  (Key `solarminer.pc-agent.language`). Die UI läuft also standardmäßig auf dem teuren Pfad.
- `translations` (`i18n.js:71-710`): 873 eindeutige Keys, **99 doppelt geschriebene Keys**, davon 11
  mit widersprüchlichem Wert (z. B. `'Daten veraltet'` → `Stale data` vs `Data stale`;
  `'Poolgebühr'` → `pool fee` vs `Pool fee`).
- `backendGerman` (`i18n.js:711-729`): 21 Einträge Englisch → Deutsch für Agent-Meldungen.
- `translate()` (`i18n.js:730-834`): Wörterbuch, dann **84 Regex-Fallbacks** in Deklarationsreihenfolge.
  Catch-alls `/^(.+): (.+)$/` kommen zweimal vor (`i18n.js:789`, `i18n.js:830`),
  `/^(.+) · (.+)$/` einmal (`i18n.js:828`) — sie schlucken unbekannte Texte still.
- **Globaler `MutationObserver`** auf `document.body` mit `childList + characterData + attributes +
  subtree` (`i18n.js:866-879`) plus einmaliger Pass `i18n.js:854-860`. Dadurch wird jede
  Zeichenkette doppelt übersetzt (`components.js:73` ruft `t()` schon beim Bauen) und jede deutsche
  Literal-Notiz wird trotzdem übersetzt — **die Übersetzungspflicht ist technisch nicht erzwingbar**.
- `data-no-i18n` (`i18n.js:838`) wird nirgends benutzt.
- Textstellen, die **kein** `t()` erreichen (vom Observer gerettet): Tabellen-`<th>`
  (`components.js:146`) und Tabellen-`<td>` (`components.js:178`); `overview.js:279,280,283,291`;
  `workers.js:99,103,108,109,114,115,116`; `benchmarks.js:44,57,73,106,143,161,168,178,179`;
  `telemetry.js:63,67,77,93,94,98,102,116,120,125`; `wallets.js:47,71,73,78,80,93`;
  `wallet-strip.js:24,25,27,52,68`; `telemetry-gate.js:16,17,34`; `components.js:56-58`.
  Zusätzlich hartcodierte Zweisprachigkeit in `benchmarks.js:51,55`
  (`window.SolarMinerI18n.language === 'de' ? 'Vergleich' : 'Comparison'` u. a.).

### 3.4 Polling statt Events

Nur `index.html` nutzt SSE (`overview.js:399`). Alle anderen Seiten pollen:

| Stelle | Intervall | Guard |
| --- | --- | --- |
| `benchmarks.js:194` | 1000 ms | `!document.hidden` |
| `proxy-gate.js:86` | 1200 ms | keiner |
| `telemetry-gate.js:40` | 1500 ms | keiner |
| `telemetry.js:109` | 3000 ms | `!document.hidden` |
| `overview.js:460`, `workers.js:293`, `hardware.js:91`, `software.js:102` | 5000 ms | `!document.hidden` (+ `workers`: Editor zu) |
| `proxy.js:68` | 12000 ms | `!document.hidden && !busy` |
| `wallet-strip.js:116` | 60000 ms | `!document.hidden` |

Doppelt und dreifach geladene Endpunkte pro Seite:
`/telemetry` von `telemetry.js` **und** `telemetry-gate.js` auf fünf Seiten;
`/overview` von `overview.js` **und** `wallet-strip.js` auf dem Dashboard — `wallet-strip.js:86` lädt
den schwersten Endpunkt nur, um Wallet-Adressen abzuleiten; `hardware.js` und `proxy.js` laden ihn
ebenfalls vollständig.

**Alte Daten bleiben sichtbar:** `workers.js:255-258` behält `energy`/`controlSettings` vom letzten
Erfolg, wenn der optionale Abriss fehlschlägt; `wallet-strip.js:83-101` behält `balances`, `prices`,
`wallets` nach Fehlern; `overview.js:373-376` setzt optionale Werte zwar auf `null`, rendert aber
weiter. Kein Frontend außer dem toten `agent.js` (`overviewFresh`) kennt einen
Veraltet-Zustand mit Konsequenz für Schreibaktionen.

### 3.5 Backend kann bereits pushen

`controller/AgentEventController.java`: `GET /api/agent/local/events` liefert `SseEmitter`
(`:31-40`), `@Scheduled(fixedDelayString = "${solarminer.agent.events.interval-ms:1000}")`
(`:43`) vergleicht eine String-Snapshot-Kopie und sendet den Event-Namen `overview` an alle
Subscribers. `snapshot()` (`:52-58`) baut `MiningController.overview()` **pro Tick**, unabhängig
davon, wie viele Seiten welche Daten brauchen.

Kosten der beteiligten Dienste:
- `MiningController.overview()` (`:383-432`): `gpuPowerService.discover()` plus
  Virtual-Thread-Fan-out für `proxy()`, `payoutDefaults`, `fees`, dazu `miningService.getStats`,
  `earningsForecastService.forecasts`, sechs `CoinOverview` und vier `gpuOverview`. Schwerstes Payload
  im Vertrag (`AgentOverview`, 14 Top-Level-Felder).
- `pearl/LocalGpuPowerService.discover()` (`:49-56`) cached 2 s; `nvidia-smi`-Prozessstart in `:63`.
- `lowlevel/sensor/HardwareTelemetryService.snapshot()` (`:40-51`) cached 1 s.
- `mining/EnergyJournalService.overview()` (`:115`) ist `synchronized` und liest Journal-Dateien
  (`Files.list`, `Files.readAllLines`) → nicht mit 1 Hz takten.
- `mining/WalletBalanceService.balances()` (`:33`) trifft HTTP auf `pool.kryptex.com` und
  `pearlchain.live`, cache 60 s bzw. 15 s bei Fehler (`:22`, `:92`, `:96`).
- `mining/WorkerAssignmentService.workers()` (`:39`) ist `synchronized` und ruft `mining.getStats`.

## 4. Mit dem Auftraggeber abgestimmte Entscheidungen

1. **Konflikt:** Es wird nur `pc-agent/src/main/resources/static/`, die SSE-Backend-Klasse und die
   Doku angefasst. `build.gradle.kts`, `Dockerfile` und `.github/workflows/*` bleiben unberührt.
2. **Tot-Code:** `agent.js` und `mining-workspace.js` werden gelöscht; Vorher muss der
   Browser-Harness zeigen, dass keine Funktion fehlt.
3. **i18n:** Zwei getrennte Kanäle. `t(key, params)` für vom Frontend verfassten Text — exakter
   Katalog, Platzhalter wie `{time}`, **keine Regex**. `s(text)` nur für Agent-seitige Texte
   (Java-Meldungen, `connectionDetail`, `downloadDetail`, `managedDetail`, `regulationError`,
   `unavailableReason`, Benchmark-`phase`), weil diese nicht umgeschrieben werden können.
   `MutationObserver` entfällt; statisches HTML bleibt deutsch und wird einmal bei
   `DOMContentLoaded` übersetzt. Audit-Skript plus Regel in `AGENTS.md`/Wiki kommen dazu.
4. **Events:** Backend-SSE wird zu Kanälen ausgebaut; jeder Channel wird nur berechnet, wenn ein
   Subscriber ihn hört. Polling sinkt auf einen Notfallsicherheitsabstand.

## 5. Arbeitsplan

1. `git status --short` prüfen; Text-Baseline **neu** aufnehmen (§6).
2. `agent.js` und `mining-workspace.js` löschen; `pool-catalog.js` aus `index.html` entfernen;
   tote Funktionen in `overview.js` entfernen; verwaiste Exporte in `components.js` streichen;
   leere `wallet-strip`-Sektionen in `hardware.html`/`telemetry.html` entfernen oder Skript laden.
3. Seiten auf `components.js` migrieren: `$`, `notice`/`clearNotice`, `setConnection`/`setUpdated`,
   `getJson`/`postJson`, `statusStrip`, `cellLines`, `filterButtonGroup`, `debounce`,
   `kilowattHours`/`duration`/`tariff`/`money`, `coinCatalog`, `configurationFor`, `temperatureOf`.
   `measurement-format.js` auflösen, aber die 4-signifikante-Stellen-Rundung der Benchmark-Seite
   beibehalten (sonst ändern sich angezeigte Zahlen).
4. `i18n.js` auf `t(text, params)` + `s(text)` umstellen, `MutationObserver` löschen, interpolierte
   deutsche Texte in `t('… {time}', {time})` überführen, gemischte Stellen als
   `t('… {detail}', {detail: s(fehler)})` schreiben. Katalog **aus dem Code generieren**
   (alle `t('…')`-Literale einsammeln, englische Werte aus dem alten Katalog übernehmen,
   fehlende melden) und die 84 Regex nur für Agent-Texte behalten. Die 10 neuen Einträge
   (`MINING-PROXY`, `Mining-Proxy wird vorbereitet`, `Erneut versuchen`, …) mitnehmen.
5. `AgentEventController` auf Kanäle umstellen: `overview` 1000 ms, `telemetry` 1000 ms,
   `workers` 1500 ms, `power` 1500 ms, `benchmark` 1000 ms, `assessment` 2000 ms, `energy` 5000 ms,
   `wallets` 15000 ms. `?channels=` steuert, was berechnet wird; ohne Parameter alle (Abwärtskompatibilität).
   `wallets` braucht einen leichten Endpunkt für die konfigurierten Wallet-Ziele, damit
   `wallet-strip.js` nicht mehr `/overview` lädt.
6. Alle Seiten auf `live-data.js` umstellen, `setInterval`-Schleifen bis auf einen Watchdog löschen.
   Stale-Regel: Channel-Alter überschritten ⇒ Abschnitt als veraltet kennzeichnen und
   Schreibaktionen sperren; abgeleitete Zustände (`energy`, `controlSettings`, `balances`, `prices`)
   werden beim Fehler explizit verworfen statt weitergereicht.
7. Übersetzungspflicht festhalten: neue `pc-agent/FRONTEND-I18N.md`, Verweis in
   `Solar-Miner-Node/AGENTS.md` und in `docs/agent-wiki/pc-agent.md` (Zeile „Operator UI").
   Regel: keine deutsche Literal-Kette in eine Seiten-Skript-Datei ohne `t()`; Agent-Text nur über
   `s()`; keine Top-Level-`const`/`let` in Seiten-Skripten (siehe §7).
8. Datums-Eintrag in `docs/agent-wiki/work-log.md` mit Verträgen, Evidenz und offenen Gates.

## 6. Verifikation

```bash
# Java
cd Solar-Miner-Node && JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew :pc-agent:test --offline

# Text + DOM-Struktur in de UND en, vor und nach dem Umbau vergleichen
cd ../.codex-qa
LD_LIBRARY_PATH=$HOME/.local/lib/asound/usr/lib/x86_64-linux-gnu node pc-agent-text.cjs pc-agent-text-before.json
LD_LIBRARY_PATH=$HOME/.local/lib/asound/usr/lib/x86_64-linux-gnu node pc-agent-text.cjs pc-agent-text-after.json
node pc-agent-text-diff.cjs pc-agent-text-before.json pc-agent-text-after.json

# Requests, Long Tasks, Scripting pro Seite
LD_LIBRARY_PATH=$HOME/.local/lib/asound/usr/lib/x86_64-linux-gnu node pc-agent-perf.cjs --seconds 15
```

Erwartung: `pc-agent-text-diff.cjs` meldet **0 unterschiedliche Seiten** (Uhrzeiten sind
normalisiert). Zulässige Ausnahme: zusätzliches `@data-kind` am `#notice`-Element — es ist rein
intern, kein CSS in `agent.css`/`design.css`/`workspace.css`/`mining.css` verwendet `[data-kind]`.
Bewusste Verhaltensänderung, die dokumentiert werden muss: der vereinheitlichte Temperatur-Lookup
zeigt auf AMD-Hosts auf der Worker-Seite einen echten Sensorwert statt `Temperatur —`.

Die Harness-Dateien liegen in `.codex-qa/` (Workspace-Wurzel, **kein Git-Repo**, also nicht
versioniert). `pc-agent-redesign.cjs` ist veraltet: es erwartet das entfernte `pools.html` und
deutsche Tab-Beschriftungen, obwohl `i18n.js` standardmäßig `en` liefert.

## 7. Aktueller Defekt im Working Tree (fremd, nicht von dieser Aufgabe)

`static/proxy-gate.js` deklariert Top-Level `const gate` (`:3`), `const t` (`:31`),
`let retrying` (`:32`), `function close()` (`:34`). Klassische Skripte teilen den globalen
lexikalischen Scope, und `telemetry-gate.js:1-3` deklariert ebenfalls `const gate`/`let retrying`,
`benchmarks.js:10` und `proxy.js:2` deklarieren `const t`. Ergebnis gemessen mit
`pc-agent-perf.cjs`:

```
dashboard | mining | workers | telemetry | wallets → SyntaxError: Identifier 'gate' has already been declared
benchmarks | proxy                                  → SyntaxError: Identifier 't' has already been declared
hardware                                            → fehlerfrei
```

Ein SyntaxError bricht das jeweilige Skript vollständig, auf sieben von acht Seiten läuft also eines
der beiden Gate-Skripte nicht. Behebung: jedes Seiten-Skript in eine IIFE packen — was der
vorgesehene Umbau ohnehin durchgängig macht.
