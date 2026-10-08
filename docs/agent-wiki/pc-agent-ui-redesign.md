# PC-Agent: grundlegendes UI/UX-Redesign

Stand: 5. Oktober 2026. Umsetzung im Modul [`pc-agent`](../../src/main/resources/static). Dieses Dokument ist die verbindliche Spezifikation zum Umbau von Dashboard und Mining-Bereich; die Vorgängerversion ([UI/UX-Bewertung und Gesamtkonzept](pc-agent-design-concept.md), Stand 3.10.) bleibt als Begründung der Grundentscheidungen erhalten.

> **Fortgeschrieben am 6. Oktober:** Für Dashboard, Miner, Worker und Pools gilt nun der
> [Operations-UI- und Energiejournal-Vertrag](pc-agent-operations-ui.md). Insbesondere ist Miner keine
> Coin×Build-Betriebsansicht mehr, sondern reine paketbezogene Softwareverwaltung; die vollständige
> Zuweisung und Prozesssteuerung liegt bei Worker. Dieses Dokument bleibt als Entstehungs- und
> Telemetriebegründung erhalten.

## Ausgangslage und warum kein Farbanstrich reicht

Der Agent beantwortete bereits „was läuft“, aber nicht zuverlässig „wo läuft es, zu welchem Preis, und mit welchem Fehlerbild“. Konkret:

| Befund im Bestand | Folge | Entscheidung |
| --- | --- | --- |
| Dashboard summiert Leistung, zeigt aber keine Effizienz, keine Difficulty, keine Pool-Verbindung | Der wichtigste Wirtschaftlichkeits- und Verbindungsnachweis fehlt auf der Hauptseite | KPI-Leiste mit Effizienz, Shares inkl. Stale/Ablehnungsquote, Difficulty und eigenem Pool-Verbindungspanel |
| Worker-Tabelle zeigt GPU-Temperatur als `—`, obwohl Hostsensoren vorliegen, ohne zu sagen, woher ein Wert kommt | Messwert und Sensorquelle sind nicht unterscheidbar | Temperatur mit Quellenkennzeichnung (Worker-Miner-API, GPU-Sensor, nicht gemeldet) |
| Mining-Seite: Coin-Leiste setzt Coin gleich mit Miner; pro Coin ist genau ein Build aktiv, die Bibliothek zeigt Pakete, die Einrichtung vier hart verdrahtete Formulare | Installation mehrerer Miner für denselben Coin ist nicht sichtbar, Konfiguration ist eine Formularwand | Eigenes Modell **Miner-Instanz = Coin × Miner-Build**, Bibliothek nach Coins gruppiert, Einrichtung als ein geführter Ablauf mit Standardwerten plus Bereich „Erweitert“ |
| GPU-Worker leben in einem Nebenpanel „Parallele GPU-Prozesse“ pro Coin; es gibt keine Sicht über alle Worker hinweg | Bei mehreren Coins/Minern verliert man die Gesamtzahl der Worker und ihre Fehler | Eigene Seite **Worker** mit Tabelle über alle Miner, Filter, Aktionen und Effizienzwertung |
| Pool-Auswahl ist in `mining.html` als `<option>`-Liste hartkodiert, Verbindungszustand des Pools nirgends | Poolwechsel ist undurchsichtig, Ausfall unsichtbar | Eigene Seite **Pools** mit zentralem Pool-Katalog (`pool-catalog.js`), Verbindungsstatus, Latenz und Difficulty je Coin |
| Aktionen sind über die ganze Seite verteilt, ohne gemeinsamen Kontext | Schneller Wechsel zwischen Coins/Minern/Workern/Pools fehlt | Gemeinsame **Scope-Leiste** auf Miner, Worker und Pools: Coin-Chips, Suche, Tastaturkürzel `/` |
| Zustände erscheinen als Freitext aus dem Backend (z. B. „2 von 3 GPU-Minern mit Pool verbunden“) | Nicht übersetzbar, nicht maschinenlesbar | Zählbare Zustände im Frontend aus `poolHealthy`/`running`/`selected` ableiten; Freitext bleibt als Detail |

## Datenlage (Warum das Design so gebaut werden konnte)

Grundlage ist die Auswertung von `pc-agent/src/main/java`. Wichtig für alles Weitere:

- `GET /api/agent/local/overview` liefert die komplette Betriebsdaten-Instanz; `GET /api/agent/local/events` (SSE) schiebt dieselbe Instanz bei Änderung. Der Dashboard-Aufbau nutzt SSE als Primärquelle und behält den 5-Sekunden-Poll als Fallback.
- `/api/agent/local/telemetry` liefert Hostsensoren (`cpu.temperature`, `cpu.package_power`, `system.total_power`, `gpu.nvidia.<i>.temperature`, `gpu.amd.<card>.<label>.temperature`, `hardware.*` nur Windows) mit `available`/`value`/`source`/`directMeasurement`.
- Pro GPU existieren Modell, Vendor, Index, `deviceId`, Treiber- und Nutzer-Wattfenster, aktueller Limit und **gemessene Watt (nur NVIDIA)**; AMD hat nie eine Leistungsmessung.
- `MinerStats.Worker.temperatureCelsius` war für GPU-Worker hart auf `0.0` gesetzt — ein Platzhalter, der wie ein Messwert aussah. Das Dashboard fragt deshalb die Sensorquelle ab und behauptet keine Temperatur.
- **Fehlten vorher komplett:** Pool-/Job-Difficulty, Pool-Latenz, Stale-Shares, beste Share. Beide Miner-APIs liefern sie, der Agent hat sie nie gelesen. Diese Lücke ist mit additive Java-Feldern geschlossen (unten, „Backend-Ergänzung“).
- Es gibt keine Zeitreihen im Backend. Alle Verläufe sind Sitzungsverläufe; das UI sagt das ausdrücklich.

## Informationsarchitektur

| Arbeitsbereich | Seite | Kernfrage |
| --- | --- | --- |
| Betrieb | **Dashboard** `/` | Läuft alles, was laufen soll — und was ist der nächste Schritt? |
| Betrieb | **Miner** `/mining.html` | Welcher Miner läuft auf welchem Gerät, wie installiere und konfiguriere ich ihn? |
| Betrieb | **Worker** `/workers.html` | Alle CPU-/GPU-Worker über alle Miner hinweg: Status, Leistung, Fehler |
| Betrieb | **Pools** `/pools.html` | Welcher Pool pro Coin, ist er verbunden, wie schnell, welche Gebühr? |
| Optimierung | **Leistungsgrenzen** `/hardware.html` | Innerhalb welches Wattbereichs darf geregelt werden? |
| Optimierung | **Benchmarks** `/benchmarks.html` | Wie misst meine Konfiguration, und was macht der Messlauf mit laufenden Workern? |
| System | **Sensoren** `/telemetry.html` | Welche Messwerte gibt es, und woher stammen sie? |
| System | **Verbindung** `/proxy.html` | Welcher Proxy verbindet Miner und Pools? |

Bestehende URLs bleiben erhalten; `workers.html` und `pools.html` kommen neu hinzu. Die Navigation bleibt in drei Gruppen (Betrieb / Optimierung / System); die Coin-Leiste auf der Mining-Seite ist der Instanz-Umschalter, nicht mehr der Coin-Umschalter.

## Gemeinsame Bausteine

`components.js` (neu) ist die einzige Quelle für Formatierung und Zustände, damit Dashboard, Miner, Worker und Pools dieselbe Sprache sprechen:

- `hashrate()`, `power()`, `temperature()`, `efficiency()`, `shares()`, `difficulty()` — Einheiten und Dezimalstellen identisch, `—` für „kein Wert“, niemals `0` als Platzhalter.
- `statusPill(status)` mit Text **und** Punkt; Farben zusätzlich, nie ausschließlich.
- `sparkline(values)` mit Lücken bei fehlenden Messpunkten.
- `dataTable(config)` mit sortierbarer Kopfzeile, rechtsbündigen Zahlen, `aria-sort` und horizontaler Scrollzone.
- `scopeBar()` — Coin-Chips, Suche, Ergebniszähler; auf Miner, Worker und Pools identisch bedienbar (`/` fokussiert die Suche).
- `panel(head, body)` erzwingt überall dieselbe Kopfstruktur: Kicker, Überschrift, rechts eine Aktion.
- `operatingRows(rows)` und `idleDeviceNotice(el, overview)` — „was läuft wirklich“ ist an einer Stelle definiert (`MINING`, `ERROR`), damit Dashboard und Worker-Seite nie unterschiedliche Mengen zeigen; der Hinweis nennt Hardware, die in keinem Miner läuft.

## Dashboard

1. **Statuszeile** (geleimt unter der Kopfzeile): Agent-Verbindung, Proxy-Modus und Erreichbarkeit, Node-Verbindung und Wirtschaftlichkeitsentscheidung, Meldungs-zähler. Jeder Eintrag ist eine Aktion auf die passende Seite.
2. **KPI-Leiste**, sechs Kacheln, umschaltbar nach Algorithmus, wo es fachlich sinnvoll ist: Hashrate, Leistung (Ist aus Hostsensoren), **Effizienz** (Hashrate pro Watt), Worker aktiv/gesamt, **Shares** (akzeptiert · abgelehnt · veraltet plus Ablehnungsquote), **Difficulty** (Job-Difficulty des Miners, Netzwerk-Difficulty als Kontext).
3. **Verlauf**: Hashrate, Leistung, Temperatur, Effizienz. Beschriftung: „Verlauf seit Seitenaufruf“.
4. **Pool-Verbindungen** pro Coin: Proxy-Route, Ziel-Pool, Erreichbarkeit, Fee-Route, Latenz, Difficulty, verbundene Worker n/m.
5. **Worker-Tabelle** der laufenden Miner — dieselbe Regel wie auf der Worker-Seite: angehaltene Miner tragen keine Zeile, damit keine GPU doppelt auftaucht; eine Karte, die in keinem Miner läuft, wird als Hinweis über der Tabelle genannt. Spalten: Worker, Coin/Miner, Algorithmus, Status, Hashrate, Temperatur mit Quelle, Leistung, Effizienz, Shares, Difficulty. Zeile führt auf die Miner-Detailseite.
6. **Bereitschaft und nächster Schritt** kompakt; Ertragsprognose und Kontostände nachrangig aufklappbar.

## Mining-Bereich

**Modell.** Eine Miner-Instanz ist die Paarung *Coin × Miner-Build* (`MinerCatalogService` modelliert das bereits; `selectedMiner` bestimmt, welcher Build startet). Die UI macht das sichtbar: pro Coin können mehrere Builds installiert sein, genau einer ist als aktiver Build markiert, Wechsel ist eine explizite Aktion ohne Verlust der Konfiguration.

**Instanzkopf.** Emblem, Coin · Build · Algorithmus · Hardware, Status-Pill, Start/Pause, darunter sechs Mini-KPIs (Hashrate, Leistung Ist/Ziel, Temperatur, Shares, Difficulty/Latenz, Prognose).

**Fünf Bereiche statt Dreier-Tabs:**

| Bereich | Inhalt |
| --- | --- |
| Status | Telemetrie-Details, Voraussetzungen und Plattformtipps mit Risiko und Quelle |
| Geräte | Ein Eintrag pro zugewiesener GPU/CPU: Status, Hashrate, Temperatur, gemessene Watt, Wattfenster, Fehler, Start/Pause; oben „Alle starten/pausieren“ |
| Konfiguration | Geführter Ablauf in drei Schritten mit Fortschritt: Auszahlung (Pool als Karten, nicht als Auswahlliste), Wallet, Workername + Geräte. Einsteiger sehen nur diese drei Schritte; der SolarMiner-Standardpfad bleibt eine bewusste, bestätigungspflichtige Option |
| Erweitert | Eigene Pool-Adresse, Referral-Key, vollständige Gebührenaufschlüsselung, Download-/Installationsverwaltung, Windows-Ausnahme, Installation entfernen |
| Konsole | Live-Log des gewählten Builds bzw. einer GPU, Verfolgung, Download |

**Bibliothek.** Nach Coins gruppiert, pro Build: Gerät, Algorithmus, Entwicklergebühr, Vor-/Nachteile, Installationsstatus, Mehrfachauswahl und gemeinsamer Installation, Fortschritt live. Ist ein Build installiert, erscheint „Als aktiven Build wählen“ statt eines zweiten Startknopfs.

## Worker-Seite

Aggregat oben (Hashrate mit GPU-Kapazität, Leistung, Effizienz, höchste Temperatur, Shares inkl. abgelehnt/veraltet, Ablehnungsquote), darunter die Tabelle der laufenden Worker mit Filtern nach Coin (Scope-Leiste) und Hardware sowie Sortierung nach jedem Messwert. Aktionen pro Worker: ein umschaltender Button Starten/Pausieren und ein Link auf den zugehörigen Miner; die Zeile selbst öffnet denselben Miner. Die Tabelle nutzt `table-layout:fixed` mit festem Spaltenraster; Worker- und Aktionsspalte sind innerhalb der horizontalen Scrollzone festgeleimt, damit Identität und Aktion auch bei vielen Spalten sichtbar bleiben. Latenz ist der Difficulty-Zelle als Unterzeile zugeordnet, weil beide Werte immer gemeinsam aus der Miner-API kommen.

Umgesetzt, aber anders als ursprünglich geplant: keine eigene Spalte „Node-Zuordnung“ — die Zuordnung gehört auf die Seite Leistungsgrenzen, von der die Worker-Seite per Link erreichbar ist.

**Die Liste ist die laufende Menge — Status ist keine Filterachse.** Ein angehaltener Miner meldet seine Geräte weiter, sodass derselbe GPU ein zweites Mal unter einem anderen Coin auftaucht (laufend unter Pearl, angehalten unter Ravencoin). Das suggeriert, es könnten mehr Miner gleichzeitig laufen als Hardware vorhanden ist. Angehaltene Miner tragen deshalb **keine** Zeile bei: gelistet werden nur Worker eines laufenden Miner-Prozesses (`MINING`, `ERROR`). Es gibt weder einen Statusfilter noch eine Mischansicht — dazwischen zu wechseln wäre eine Ansicht, die es nicht geben darf.

Damit die weggelassenen Geräte nicht als verlorene Hardware wirken, wird die Kapazität aktiv benannt: die Hashrate-Kachel trägt „2/2 GPUs im Einsatz“, und sobald eine Karte in keinem Miner läuft, steht über der Tabelle „1 GPU läuft gerade in keinem Miner · Geräte zuteilen →“ (Link auf Leistungsgrenzen). Coin, Hardware und Suche bleiben eine eigene Scope-Achse mit „n ausgeblendet · Filter zurücksetzen“; diese Grenze überschreitet nie ein Status. Ist nichts am Laufen, sagt die leere Tabelle „Kein Miner läuft gerade. Angehaltene Miner zeigt der Bereich Miner.“

## Pools-Seite

Pro Coin ein Board: sechs Fakten (Proxy-Ziel des Miners, aktuelles Pool-Ziel, Latenz, Difficulty, Pool-Kontakt auf die Geräteauswahl **dieses** Coins, Auszahlungsziel) und darunter die Katalogkandidaten als Karten mit Region und Poolgebühr. „Ziel übernehmen“ ist eine bestätigte Aktion, weil der Miner des Coins dabei angehalten und neu gestartet wird; Wallet, Worker und Geräte werden aus der bestehenden Konfiguration übernommen und mitgeschickt, damit ein Poolwechsel die Auszahlung nicht löscht. Eigene Adressen laufen über ein aufklappbares Feld mit demselben Format-Validator wie die Miner-Einrichtung. Der Pool-Katalog liegt in `pool-catalog.js` und nicht mehr in HTML-`<option>`-Elementen.

Der Sammelzustand der Fee-Routen steht bewusst in der Aggregatleiste („Fee-Ziele geladen n/m“) und nicht auf jeder Karte, weil eine fehlende Fee-Route den Start des Miners verhindert und damit eine Betriebsbedingung des gesamten Agents ist. Die Pools-Seite nutzt ein einfaches Suchfeld statt der Coin-Chips der Scope-Leiste, weil sie immer alle Coins zeigt und kein Auswahlzustand nötig ist.

**Die Kopfzahl zählt Hardware, nicht Auswahl.** „Verbundene Worker 0/13“ war dieselbe Täuschung in anderer Form: der Nenner war die Summe der Geräteauswahlen über alle Coins, und eine Karte, die für drei Coins ausgewählt ist, zählte dreimal — eine Zahl, die dieser Agent nie erreichen kann. Die erste Kachel heißt „Laufende Geräte n/m“ mit m = tatsächlich verbaute Geräte (GPUs + CPU) und n = davon laufend; der Untertitel nennt, wie viele davon Pool-Kontakt haben. Pro Coin bleibt „Pool-Kontakt n/m“ auf die Auswahl **eines** Coins bezogen und ist damit eine wahre Aussage über einen einzelnen Miner.

## Visuelles System

- **Farben:** `--canvas #0d1118`, `--surface #151c26`, `--raised #1b2532`, `--line #2a3544`, `--text #eff3f8`, `--muted #9eacbd`, `--accent #8bddbc` (aktiv/freigegeben), `--warm #f4c47b` (Handlung/Aufforderung), `--danger #ef8f8a`, `--info #83bfff`. Bedeutung immer zusätzlich durch Text und Form.
- **Zahlen:** `font-variant-numeric: tabular-nums` in allen Tabellen und KPIs, damit sich Werte beim Pollen nicht verschieben.
- **Hierarchie:** Kicker 10 px / 1,4 Zeichenabstand, H1 32 px, H2 20 px, KPI-Wert 26–30 px, Fließtext 13–14 px, Zeilenhöhe 1,6.
- **Raster:** 8-px-Skala, Kartenradius 12 px, identische Panel-Köpfe, Tabellen mit geleimtem Kopf und horizontaler Scrollzone unter 900 px.
- **Zustände:** Geladen als Skelett mit fester Mindesthöhe (kein Layout-Sprung), veraltete Daten abgedunkelt mit Text „Letzter Stand …“, Fehler inline mit Wiederholungsaktion statt Modal.
- **Barrierefreiheit:** Sprunglink, `aria-live` für Status, `aria-sort` in Tabellen, Pfeiltasten in Tabs, Fokusfolgen in der mobilen Navigation, `prefers-reduced-motion`, Kontrast nach AA.
- **Responsiv:** ≥1400 px sechs KPIs und zwei Chart-Spalten; 900–1400 px drei KPIs; <900 px Navigation als Drawer; <620 px zwei KPIs und Tabellen als Kartenstapel.

## Backend-Ergänzung (additiv)

Damit Difficulty, Latenz, Stale-Shares und beste Share überhaupt darstellbar sind:

- `mining/MinerShareTelemetry.java`: `Counters` um `stale`, `difficulty`, `bestShare`, `latencyMs` erweitert; die Parser akzeptieren jetzt zusätzlich **Array-Hüllen** (`results[]`, `connection[]`), weil beide Miner-APIs diese Knoten je nach Version als Objekt oder Array liefern. Bisher führte ein Array stillschweigend zu `null`.
- `dto/MinerStats.Worker`: neues Feld `PoolTelemetry(Double difficulty, Double bestShareDifficulty, Long staleShares, Long latencyMs)`. Bestehende Felder bleiben unverändert; `null` bedeutet weiterhin „nicht gemeldet“, nie `0`.
- `xmr/XmrMinerService`, `pearl/PearlMinerService`, `pearl/GpuCoinMinerService`: Zähler und Poolwerte werden gespeichert und an den Worker übergeben.

Bewusst **nicht** geändert: `MinerStats.Worker.temperatureCelsius` für GPU-Worker bleibt `0.0` in den Miner-Services, weil die Sensorzuordnung im Agenten schon existiert und eine Änderung an zwei Stellen die Vertragspartner in `core` und Node berühren würde. Das Frontend löst die Anzeige über die Sensorquelle und beschriftet sie korrekt.

## Umsetzung und Verifikation

Stand 5.10.2026, geprüft mit Headless-Chromium (Playwright) gegen API-Fixtures, JDK `graalvm-ce-21.0.2`.

- Frontend neu: `components.js`, `design.css`, `pool-catalog.js`, `workers.html`/`workers.js`, `pools.html`/`pools.js`. Überarbeitet: `index.html`, `overview.js`, `mining.html`, `mining.css`, `agent.js`, `mining-workspace.js`, `workspace.js`, `workspace.css`, `i18n.js`.
- Browserprüfung `.codex-qa/pc-agent-redesign.cjs` — alle Prüfungen grün:
  - acht Seiten (Dashboard, Miner, Worker, Pools, Leistungsgrenzen, Benchmarks, Sensoren, Verbindung) ohne Page-/Console-Fehler und ohne horizontale Überläufe bei 1440 px und 390 px, jeweils mit Vollseiten-Screenshot,
  - Mining: alle fünf Bereiche klickbar, GPU-Pause erreicht `POST /api/agent/local/pearl/gpus/NVIDIA/0/pause`, der geführte Ablauf speichert Pool + Proxy-Route + Geräte nach `POST /api/agent/local/pearl/configuration`, Bibliothek öffnet/schließt und installiert pro Build über `POST /{coin}/miners/{id}/download`,
  - Worker: die Tabelle enthält ausschließlich laufende Worker — der angehaltene Zwilling derselben GPU (`GPU-demo-0`, KAWPOW) erzeugt weder eine Zeile noch einen laufenden Coin-Chip, und `#worker-table` nennt keinen zweiten `GPU-demo-0`; es existieren keine Status-Filterbuttons; die Hashrate-Kachel sagt „2/2 GPUs im Einsatz“, nach der Pause über `POST /api/agent/local/pearl/gpus/NVIDIA/0/pause` erscheinen „1 GPU läuft gerade in keinem Miner“ und „1/2 GPUs im Einsatz“; der Hardwarefilter verbirgt den CPU-Worker und „1 ausgeblendet · Filter zurücksetzen“ stellt ihn wieder her; das Panel überläuft bei 390 px nicht,
  - Dashboard: die Worker-Tabelle nutzt dieselbe laufende Menge — der angehaltene Zwilling fehlt, und der Hinweis auf freie GPUs bleibt verborgen, solange alle Karten laufen,
  - Pools: Poolwechsel sendet das neue Ziel inklusive Wallet und Worker an `POST /api/agent/local/monero/configuration` (CPU-Coin ohne `proxyUrl`/`devices`), die Suche reduziert die Boards, und der Nenner der Geräte-Kachel bleibt die verbaute Hardware (2 GPUs + CPU) obwohl beide Karten für drei Coins ausgewählt sind,
  - Übersetzung: Worker, Pools und Miner werden bei `language=en` vollständig englisch gerendert, inklusive dynamischer Zeilen wie „1 running worker“, „1/2 GPUs in use“, „No miner is using 1 GPU.“, „1 hidden · Reset filters“, „Pool fee 1%“, „Best share difficulty“; auf der englischen Worker-Seite kommen weder `KAWPOW` noch deutsche Reste (`läuft gerade`, `ausgeblendet`) vor.
- Java: `sh gradlew :pc-agent:test --offline` → BUILD SUCCESSFUL.
- Nicht geprüft: echtes Mining gegen XMRig/SRBMiner, echten Pool-Verbindungen und echten GPUs. Die Fixture-Antworten bilden die verifizierten API-Formen ab, nicht das Verhalten einer bestimmten Miner-Version.

## Grenzen

- Verläufe bleiben Sitzungsverläufe; es gibt keine historische Datenhaltung.
- Pool-Latenz und Difficulty hängen davon ab, was die eingesetzte Miner-Version meldet; fehlt sie, bleibt das Feld leer und beschriftet.
- AMD-GPUs liefern weiterhin keine Leistungsmessung; die Effizienzkachel zeigt dann für diese Worker „—“.
- Die Worker-Seite zeigt nur Worker, die der Agent kennt; extern gestartete Miner bleiben als solche markiert.
- Ob Einsteiger den geführten Ablauf wirklich schneller abschließen, ist eine Nutzerstudienfrage, keine Code-Tatsache.
