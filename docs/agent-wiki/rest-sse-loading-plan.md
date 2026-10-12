# Lade-Strategie REST vs. SSE — Analyse und Umsetzungsplan

**Zweck:** Antwort auf die Owner-Frage (2026-10-11): „Wo sollten wir im
PC-Agent-Frontend auf REST umsteigen? Statische Informationen sollten möglicherweise
über REST kommen — über das volle SSE-Design ist alles sehr träge.“ Dieses Dokument
IST der Arbeitsplan für einen folgenden Agenten; Fortschritt in die Checkliste unten
eintragen (`[x]` = erledigt + verifiziert), Worklog-Tail vorher lesen.

Stand: nach Phase 5d (Sitzung 9). `AgentEventController` betreibt 18 Kanäle,
`AgentStatusService` hält 9 Sektionen mit TTL-Cache, `live-data.js` führt EINE
EventSource pro Seite + Watchdog (holt REST nur bei Push-Stille).

## Befund (Ist, file:line)

1. **Trägheit nach Mutationen ist das eigentliche Problem, nicht SSE an sich.**
   Ein Kanal sendet erst, wenn seine Bewertung beim nächsten Tick einen geänderten
   Wert sieht: `fee-tier` 5 s, `power-control` 5 s, `energy` 5 s, `node-assessment`
   5 s, `wallets` 15 s (`AgentEventController` Konstruktur). Eine lokale Änderung
   (Tarif speichern, Worker freigeben, Proxy umschalten) ist also bis zu
   Tick-interval lang unsichtbar, wenn der Aufrufer nicht selbst `refresh(section)`
   aufruft. Beispiel: `overview.js:186` refreshed `energy` gezielt —
   `hardware.js` (GPU-Limits, Settings-POST) verlässt sich auf den 5-s-Tick.
2. **Statische Daten hängen an Push-Ticks.** `coin-catalog` ist ein Closed-Enum
   (`Coin.miningCoins()`, ändert sich NUR beim Neustart) und wird trotzdem als
   SSE-Kanal mit 60-s-Tick geführt (`AgentEventController:98`) — ihn abonniert
   KEINZIGE Seite; `workers.js:342` und `wallets.js:90` lesen bereits REST
   `/status/coin-catalog`. Der Kanal ist tobes Gewicht.
   `settings` (reine Konfiguration, nur durch eigene POSTs geändert) läuft mit
   1-s-Tick (`AgentEventController:76`).
3. **Doppelte Buchführung.** Der Aggregat-Kanal `status` (1 s,
   `AgentEventController:93`) wird von KEINER Seite abonniert; alle Seiten nutzen
   die Einzelkanäle. Das Aggregat existiert bisher nur als REST
   `GET /local/status` — und das auch nur als Reserve; der erste Seitenaufbau
   macht pro Kanal einen Einzel-REST-Fallback (`live-data.js:113`).
4. **Serverseitige Neubewertung ist teurer als der Netzwerk-Kanal.**
   `overview` (GPU-Discovery + Forecasts + Fee-HTTP) wird alle 2 s neu
   zusammengerechnet, `fee-tier`/`wallets` HTTP gegen Proxy/Upstream,
   `miner-options` Dateisystem-Checks — unabhängig davon, ob sich etwas geändert hat.

## Soll-Architektur: drei Ladeklassen

| Klasse | Daten | Weg | Invalidierung |
|---|---|---|---|
| **Sitzung-statisch** | coin-catalog, identity, miner-catalog (benchmarks.js) | REST 1× pro Page-Load, Speicher-Cache (`window.SolarMinerCoinCatalog` existiert schon) | Page-Load / Agent-Reconnect |
| **Konfiguration** | power-control settings, energy settings, worker external-control, GPU-Limits | REST GET bei Load; POST-Antwort enthält bereits den neuen Zustand → direkt anwenden | eigene Mutation (Client) + dirty-Flag (Server-Sicherheit) |
| **Live** | overview, telemetry, workers, benchmarks, efficiency, energy-Journal, proxy-gate, fee-tier, wallets, node-assessment, network-agents, miner-options (Download-Fortschritt!) | SSE, aber **ereignisgesteuert**: Services melden `invalidate(section)`, Publisher wertet gemeldete Sektionen sofort aus | dirty-Flag + historischer TTL als Sicherheitsnetz |

Kernstück: `AgentStatusService.invalidate(name)` + `AgentEventController` wertet
pro Sweep (Tick auf 500 ms senkbar, weil dann nur noch dirty/abgelaufene Sektionen
rechnen) zuerst dirty Sektionen aus. Damit: Push < 1 s nach jeder Mutation
(Trägheit weg) UND weniger CPU/HTTP-Last (Grund: tick-getriebene Neuberechnung
fällt für saubere Sektionen bis zum TTL weg).

## Umsetzungs-Checkliste

- [x] **P1 — tote Kanäle löschen (O3-gedeckt, Consumer vorher grep-pen):**
      `coin-catalog`-Kanal aus `AgentEventController` entfernen (UI liest REST);
      prüfen, ob `status`-Aggregat-Kanal abonniert wird (Ist: nein) — Kanal
      entfernen ODER Seite auf Aggregat umstellen; Empfehlung: Kanal entfernen,
      REST-Aggregat bleibt. Sektionen bleiben im Service (REST `/local/status`).
      ERLEDIGT 2026-10-11 (Sitzung 10): beide Kanäle entfernt; Consumer-Grep
      über static/js ergab null Abonnenten; Boot-Nachweis: Subscribe auf
      `channels=coin-catalog|status` liefert 0 Events. Evidence im Worklog.
- [x] **P2 — Dirty-Flag-Infrastruktur:** `AgentStatusService.invalidate(String...)`
      (Thread-sicher, Set) + `AgentEventController.publishChanges()` wertet
      dirty-Sektionen vorrangig aus; TTL bleibt als Fallback für externe Änderungen
      (Proxy-Heartbeat, Node-Push, Download-Fortschritt, Sensorwerte).
      NEUER Test `AgentStatusServiceTest`: invalidate → nächste Bewertung neu,
      saubere Sektion bleibt bis TTL cached.
      ERLEDIGT 2026-10-11 (Sitzung 10): 5 Tests grün; Boot-Push < 1 s gemessen
      (208 ms / 193 ms). Evidence im Worklog.
- [x] **P3 — Mutationen verdrahten:** `invalidate(...)` in allen schreibenden
      Endpunkten/Services, die eine Sektion ändern: `power-control/settings`,
      `network-sharing`, `workers/*/external-control`, `economics/settings`,
      `proxy*` (mode/host/roll/gate-retry), `miners/{coin}/**` (download,
      remove, select → `miner-options`+`overview`), `wallets`-Saves
      (`{coin}/configuration` → `wallets`+`overview`), `node-assessment` POST,
      `mining pause/resume/target`. Grep-Liste: alle `@PostMapping` unter
      `/local/**`.
      ERLEDIGT 2026-10-11 (Sitzung 10): alle Treffer der Grep-Liste verdrahtet
      (inkl. benchmarks/efficiency/sharing und Telemetrie-Restart);
      `/external/**`-Code unverändert, nur die dort auflaufenden internen
      Methoden melden jetzt mit. Evidence im Worklog.
- [x] **P4 — UI: POST-Antwort direkt anwenden:** `hardware.js` (settings,
      GPU-Limits) und weitere Stellen übernehmen den POST-Response statt
      auf den nächsten Push zu warten; `live-data.js` bekommt
      `refreshDirty()` nicht — gezielte `refresh(section)`-Aufrufe bleiben,
      werden aber seltener (Server pusht ja jetzt sofort).
      ERLEDIGT 2026-10-11 (Sitzung 10): hardware.js übernahm vorher ein
      undefiniertes `refresh()` (Latent-Bug) — jetzt POST-Response-Mapping +
      gezielte Kanal-Refreshes; kein refreshDirty eingeführt.
- [x] **P5 — First Paint:** erste Seite(n) laden das REST-Aggregat
      `GET /local/status` (1 Request) statt N Einzel-Fallbacks;
      `live-data.js` `onopen`-Refresh bleibt als Lücken-Heilung.
      ERLEDIGT 2026-10-11 (Sitzung 10): firstPaint() im onopen; Aggregate nur
      für Sektions-Kanäle, Rest behält Einzel-Fallback; Reconnect-Heilung
      unverändert. `/local/status` Boot-geprüft (10 Sektionen inkl. ui).
- [x] **P6 — Takt neu setzen:** Sweep-Intervall und Sektions-TTLs dokumentiert
      neu festlegen (Vorschlag: Sweep 500 ms; overview-TTL bleibt 2 s nur wenn
      dirty, sonst 5 s Sicherheitsnetz; settings/energy nur dirty + 30 s Netz).
      Messen: Anzahl Bewertungen pro Minute vor/nach P2 (Log-Zähler oder
      Test-Counter) als Evidence in den Worklog.
      ERLEDIGT 2026-10-11 (Sitzung 10): Sweep 500 ms (application.properties);
      settings/energy-TTL 30 s, overview 2 s belassen (Primärboard, Begründung
      im Code), proxy-gate 1 s (externer Download-Fortschritt). Zähler via
      `GET /local/status/stats`; 60-s-Idle-Messung im Worklog.
- [x] **P7 — Tab-übergreifender Snapshot-Cache (Seitenwechsel-Trägheit):**
      Jedes `emit()` spiegelt den Kanalwert nach `sessionStorage`
      (`solarminer.channel.v1.<name>`, 30-min-Netz, 128-KB-Deckel pro
      Kanal); der Konstruktor rehydriert VOR dem ersten `subscribe()`,
      damit der erste Handler sofort echte Daten malt. Das Alter regelt
      wie bisher die Frische: `meta.stale` markiert, der Watchdog ersetzt
      innerhalb seines 2-s-Ticks. Run-State-Kanäle (benchmarks,
      efficiency) opt-outen via `persist:false` — eine veraltete
      "läuft"-Karte ist schlimmer als ein leerer Moment. `wallets.js`
      (kein Kanal) seedet über `SolarMinerLive.snapshot('overview')`,
      der Coin-Katalog (closed Enum) wird pro Tab in
      `components.js cachedCoinCatalog()` gecacht (1 h). NEBENBEFUND:
      der P5-`firstPaint()`-Aufruf in `onopen` war im Code verloren —
      wieder eingebaut.
      ERLEDIGT 2026-10-11 (Sitzung 11): Node-Harness 8/8 grün
      (Rehydrierung vor subscribe, stale-Regel, 30-min-Verfall,
      persist:false, snapshot()-API); Boot-Probe JAR Port 18085:
      Aggregat + SSE-Events ok, `node --check` je Datei, 164/164 Tests,
      check-i18n grün. Evidence im Worklog.
- [x] **P8 — SSE auf echte Live-Daten begrenzen + REST sofort revalidieren:**
      Der Server bietet nur noch `telemetry`, `workers`, `benchmarks`,
      `efficiency`, `benchmark-upload` und `proxy-gate` als SSE-Kanäle an.
      Übersicht, Settings, Energie, Fee-Tier, Wallets, Hardware,
      Miner-Katalog, Node-Bewertung, UI-Ableitungen und LAN-Agenten sind
      REST-Snapshots. `proxy-gate` liest den Normalfall einmal per REST und
      öffnet SSE erst, wenn Vorbereitung/Download wirklich läuft.
      REST-Kanäle revalidieren beim Seiteneinstieg nun auch dann sofort, wenn
      bereits ein Session-Snapshot gerendert wurde; zuvor wartete ein alter
      Snapshot bis zum 2-s-Watchdog und hielt die Anzeige auf „Verbinde …“.
      ERLEDIGT 2026-10-12: `AgentEventControllerTest` pinnt die sechs
      Live-Kanäle; kompletter Gradle-Testlauf und Frontend-Prüfungen siehe
      Worklog.

## Harte Regeln (gelten weiter)

- `/external/**` eingefroren; Kanalnamen und Payloads der ABO-Kanäle sind
  UI-Vertrag (O3: löschen nur, wenn kein Consumer — vorher grep).
- Pinning-Tests (`AgentWireFormatPinningTest`, `ProxyOverviewWireFormatTest`)
  müssen grün bleiben; Sektions-Namen des Aggregats sind Wire-Keys.
- i18n-Pflicht für neue UI-Texte; `check-i18n.cjs` + `scan-i18n-literals.cjs` grün.
- Verifikation pro Schritt: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh
  gradlew test standaloneJar --offline` (Referenz 159/159, 40 Klassen, Zähler aus
  `build/test-results/test/*.xml`), `node --check` je JS-Datei, `git diff --check`.
- NEVER `git add -A`; Commits nur auf ausdrückliche Owner-Freigabe.
- Kein schnelles Seiten-Timer-Polling einführen; REST-Snapshots nutzen nur ihre
  langsamen Sicherheitsintervalle, explizite Aktualisierung und Mutationsergebnisse.
