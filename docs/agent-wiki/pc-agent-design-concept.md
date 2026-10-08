# PC-Agent: UI/UX-Bewertung und Gesamtkonzept

Stand: 3. Oktober 2026. Geltungsbereich: alle sechs Seiten des PC-Agent. Diese Bewertung basiert auf Quellcode, kontrollierten Browser-Szenarien und visueller Prüfung. Sie ist keine Nutzungsstudie und keine Bestätigung realer Hardware- oder Pool-Kompatibilität.

## Leitentscheidung

Der PC-Agent ist eine lokale Mining-Zentrale. Seine primäre Aufgabe ist, Betrieb und Automatisierung verständlich und kontrollierbar zu machen. Die Oberfläche muss zuerst beantworten: Was läuft? Welche Geräte darf der Node verwenden? Ist etwas zu tun? Messwerte, Optimierung und Finanzinformationen folgen dieser Reihenfolge.

Die bisherige Gestaltung hatte ein brauchbares dunkles Grundbild und eine gute Miner-Leiste mit Plus. Ihr Hauptproblem lag in der Informationsarchitektur und im Verhalten der Bedienelemente. Ein bloßer Austausch von Farben hätte das nicht gelöst.

## Bewertung des vorherigen Gesamtzustands

| Befund | Auswirkung | Entscheidung |
| --- | --- | --- |
| Sechs gleichrangige Navigationspunkte und eine auf Mobilgeräten horizontal abgeschnittene Kopfzeile | Alltag, Einrichtung und Diagnose sind schwer auseinanderzuhalten; Sprachwahl und Aktionen werden verdeckt | Navigation nach Betrieb, Optimierung und System gruppieren; Desktop-Seitenleiste und mobiles Menü |
| Kontostände auf fast jeder Seite, große doppelte Schnellzugriffe auf der Startseite | Finanzinformationen und Navigation verdrängen den Betriebsstatus | Status zuerst; Kontostände nur auf der Übersicht als aufklappbarer Finanzbereich |
| Startseite summiert Hashraten verschiedener Algorithmen | Ein gemeinsamer Zahlenwert suggeriert fachlich vergleichbare Leistung | Verlauf je ausgewähltem Algorithmus und separate Worker |
| Angezeigte Worker-Temperatur von 0 °C trotz fehlender Mining-Telemetrie | Ein Platzhalter wirkt wie ein echter Messwert | Temperatur bei den verfügbaren Hostsensoren darstellen; Worker-Zeile zeigt Algorithmus, Hashrate und Zustand |
| Hardware-Slider speichern nach kurzem Ziehen, Polling ersetzt Eingaben | Unabsichtliche Treiberänderungen und verlorene Entwürfe | Explizites Anwenden/Verwerfen und geschützte Entwürfe |
| Proxy-Wechsel übernimmt den ersten Suchtreffer | Suche, Auswahl und wirksame Änderung werden vermischt | Alle Kandidaten zeigen; Host explizit auswählen; Aktivieren ist eine eigene Aktion |
| Benchmark-Startarten stehen nebeneinander ohne klare Konsequenzhierarchie | Ein Test kann den laufenden Betrieb überraschend pausieren | Zwei verständliche Messarten mit Auswirkungen vor dem Start; Teilen ist eine getrennte freiwillige Entscheidung |
| Windows-Sensorfehler erscheint als modaler Vollbildblock | Diagnose und Navigation werden unnötig verdeckt | Inline-Hinweis mit Wiederholungsaktion; Backend-Sensoranforderungen bleiben wirksam |
| Unregelmäßige Seitentitel, gemischte Begriffe und unterschiedliche Karten-/Formstile | Höherer Lernaufwand, schlechtere Orientierung | Gemeinsames Raster, Begriffe, Farben, Abstände, Fokusmarkierungen und deutsche/englische Haupttexte |

Quellbelege: static/overview.js (ursprüngliche algorithmusübergreifende Summe), hardware.js (ursprünglicher 350-ms-Speicher-Timer), proxy.js (ursprünglich candidates[0]), telemetry.html (ursprünglicher modaler Sensor-Gate), Kopfzeilen und Wallet-Bereiche in den sechs HTML-Seiten. Ausgangs-Screenshots liegen im Workspace unter .codex-qa/screenshots/pc-agent-design/before-*.

## Informationsarchitektur

| Arbeitsbereich | Seite | Kernfrage |
| --- | --- | --- |
| Betrieb | Übersicht | Läuft mein PC, und was muss ich als Nächstes tun? |
| Betrieb | Miner | Was möchte ich manuell starten, und welche Geräte darf der Node automatisch verwenden? |
| Optimierung | Leistungsgrenzen | Innerhalb welchen GPU-Wattbereichs darf geregelt werden? |
| Optimierung | Benchmarks | Wie misst sich meine Konfiguration, und wie wirkt der Messlauf auf laufende Worker? |
| System | Sensoren | Welche Messwerte sind verfügbar und woher stammen sie? |
| System | Verbindung | Welcher lokale oder externe Proxy verbindet die Miner? |

Bestehende URLs bleiben erhalten. Die verständlichen Navigationsnamen ersetzen interne Begriffe: Hardware wird Leistungsgrenzen, Telemetrie wird Sensoren, Proxy wird Verbindung. Das vermeidet neue Einstellungsseiten, ohne Aufgaben zusammenzuwerfen. Die Miner-Leiste bleibt innerhalb des Mining-Arbeitsbereichs erhalten.

## Umgesetztes Gesamtbild

Die gemeinsame Anwendungshülle in workspace.js und workspace.css erzeugt eine gruppierte Seitennavigation, aktuelle Seitenmarkierung, Kopfzeile, Sprunglink und gemeinsamen Verbindungshinweis. Auf Mobilgeräten öffnet ein Menüknopf die Navigation mit Hintergrundabdeckung, Schließen-Schaltfläche, Escape und begrenzter Tastatur-Fokusfolge. Die darunterliegende Seite wird währenddessen inert.

Das Erscheinungsbild verwendet dunkle neutrale Flächen, zurückhaltende Linien, klare Karten und größere lesbare Inhalte. Grün markiert Freigabe/erfolgreiche Bereitschaft, warme Akzentfarbe die Hauptaktion und Hinweise; fehlende Daten bleiben neutral. Zustände tragen Text und werden nicht ausschließlich durch Farben vermittelt. Bewegung respektiert prefers-reduced-motion.

### Übersicht

Die erste Ebene zeigt laufende Worker und die gespeicherte Node-Gerätezuordnung. Darunter stehen Mining-Verbindung, Automatisierungsfreigabe und Node-Verfügbarkeit. Ein eigener Handlungshinweis führt bei Erststart, fehlender Einrichtung, Verbindungsproblemen oder Worker-Fehlern zur passenden Seite.

Messverläufe sind lokale Sitzungsverläufe; es wird keine historische Datenhaltung vorgetäuscht. Die Hashrate kann nach Algorithmus gewählt werden. Fehlende Chart-Messpunkte werden nicht als Nullwerte verbunden. Finanzprognosen erscheinen für eingerichtete reguläre Coins und erklären Bruttocharakter, fehlende Kosten/Gebühren und den Unterschied zur Pool-Gutschrift. Kontostände sind nachrangig aufklappbar.

### Miner

Die bereits umgesetzte dauerhaft gespeicherte Zuordnung pro physischem Gerät bleibt die Autorität für Node-Steuerung. Sie ist von manuellen Starts und vom betrachteten Miner getrennt. Das Node-Profil ist bei gespeicherter Einrichtung kompakt aufklappbar; bei einer neuen leeren Zuordnung öffnet es sich. Der Direktlink #profile öffnet die Geräteauswahl.

Die Coin-Leiste mit +, eindeutigen Kürzeln und Laufzuständen bleibt bestehen. Status/Geräte, Einrichtung und Konsole/Diagnose bilden getrennte Bereiche. Eine Navigation zwischen Coins verändert keine Automatisierungsentscheidung.

Der Mining-Inhalt wurde anschließend vollständig an die gemeinsame Gestaltung angeglichen (`mining.css`, `mining.html`, `mining-workspace.js`). Eine einzige Seitenüberschrift führt in den Bereich; der ausgewählte Miner erhält eine eigene Betriebsfläche mit Start/Pause, gefolgt von Status, Hashrate, Leistungsziel und nachrangiger Ertragsprognose. Gerätedetails und GPU-Prozesse stehen auf breiten Bildschirmen nebeneinander. Der Katalog verwendet die gemeinsamen neutralen Karten, Filter und Abstände; der große Schnellstart erscheint nur ohne installierten Miner. Das separat aufklappbare Node-Profil folgt dem lokalen Arbeitsbereich und ist direkt über den Kopfbutton erreichbar, einschließlich Tastaturfokus und Direktlink. Entfernen liegt in der Einrichtung unter „Installation verwalten“. Auf schmalen Bildschirmen läuft die Coin-Leiste horizontal; die Inhalte nutzen die volle Breite. Fehlende Verbindungsdetails eines laufenden, poolgesunden Miners werden nicht mehr als „Noch nicht gestartet“ dargestellt; ein Wattziel ohne gemeldete Obergrenze bleibt als solches erkennbar.

Ergänzende Chrome-Fixtures prüfen diese Hierarchie, genau eine Hauptüberschrift, Profilzugriff/Fokus ohne Konfigurationsänderung, Katalogfilter, Status-/Einrichtungs-/Diagnosebereiche, den eingeklappten Entfernen-Bereich sowie Erststart ohne installierte Binaries. Desktop- und Mobilansichten von Betrieb, Katalog, Einrichtung, Diagnose und geöffnetem Profil wurden visuell geprüft. Der verzögerte Initialabruf bleibt durch die bestehenden Latenzprüfungen abgesichert.

### Leistungsgrenzen

Eine globale Freigabe für dynamische Regelung erklärt die Wirkung auf Wattziele. Die Gerätezuordnung hat einen einzigen Bearbeitungsort im Mining-Profil. Je regelbarer GPU bleiben Treiberbereich, aktuelles Limit und verfügbare Aufnahme sichtbar.

Slider bearbeiten einen Entwurf. Erst Grenzen anwenden sendet die Änderung; Verwerfen kehrt zu gespeicherten Werten zurück. Polling überschreibt weder Entwürfe noch gerade fokussierte Eingaben. Fehler behalten den Entwurf; gespeicherte und ungespeicherte Zustände sind beschriftet. Nicht regelbare GPUs erhalten eine verständliche Einschränkung. Vor Verlassen mit ungespeichertem Entwurf greift die normale Browser-Schutzabfrage.

### Benchmarks

Die Messarten sind Karten: laufenden Betrieb beobachten oder konfigurierte Miner nacheinander testen. Auswirkungen auf Pausen, Wiederherstellung und Node-Steuerung stehen vor der Startaktion. Aktionen sind gegen doppelte Eingaben gesperrt und zeigen Netzwerkfehler. Ergebnisse und Abbruch sind vom freiwilligen Teilen getrennt. Die Freigabe ist vor erfolgreichem Lesen des tatsächlichen Zustands nicht bearbeitbar.

### Sensoren

Überblick und Gerätemesswerte bleiben direkt sichtbar; technische Rohwerte liegen in den Details. Suche nach Sensor/Quelle/Einheit und ein Verfügbarkeitsfilter erleichtern Diagnose. Unverfügbare Balken werden anders dargestellt, ohne eine Nullmessung zu behaupten. Ein Windows-Sensorfehler erscheint als Inline-Hinweis. Die im Backend bestehenden Sensoranforderungen werden dadurch nicht umgangen.

### Verbindung

Lokaler und externer Proxy stehen als klare Optionen nebeneinander. Ein aktiver Modus ist markiert; Erreichbarkeit bleibt eine eigene Aussage. Netzwerk-Suche listet Kandidaten und verändert keinen Host oder Modus. Auswählen füllt den Entwurf; erst Aktivieren speichert und wechselt die Verbindung. Dass dieser Wechsel Miner pausiert, steht vor der Aktion.

Routen und Fee-Zustände liegen in aufklappbaren Coin-Details. Vorbereitete RVN/ETC-Pfade bleiben als vorbereitet gekennzeichnet und erhalten keine neue Mining-Freigabe.

## Verifikation und Grenzen

- Alle sechs Seiten wurden in Chrome mit API-Fixtures bei 1440 px und 390 px geprüft und visuell kontrolliert; englische Navigation und Darstellung zusätzlich bei 1280 px und 320 px.
- .codex-qa/pc-agent-design.cjs prüft Seitennavigation, mobile Navigation/Schließen, Erststart, Agent-Ausfall, erhaltene Slider-Entwürfe über Polling, bewusstes Anwenden, Proxy-Suche ohne Wechsel, explizite Auswahl/Aktivierung, getrennte Algorithmus-Hashrate, Sensorfilter, nichtmodale Sensorfehler, Benchmark-Start/Abbruch und Ein-/Ausschalten der freiwilligen Freigabe.
- Die bestehende Geräteprofil-Prüfung .codex-qa/pc-agent-workspace.cjs bleibt grün. JavaScript-Syntax und git diff --check werden geprüft. :pc-agent:test ist mit JDK 21 erfolgreich (41 Tests).
- Die neue Gestaltung benutzt die vorhandenen lokalen APIs. Dieser zweite Arbeitsschritt ändert keine Pool-, Fee-, Geräte-, Leistungsplanungs- oder öffentlichen Telemetrieverträge. Reale GPU-Treiberwirkung, Mining, Pool-Gutschriften und Node-Orchestrierung wurden hier nicht auf Hardware geprüft.
- Für neue Coins ist dieser Schritt nicht anwendbar: kein neuer Coin/Algorithmus und kein zusätzlicher Mining-Pfad. Bestehende Pearl- und RVN/ETC-Verifikationsgrenzen bleiben bestehen. 21energy/Heizungssteuerung ist nicht betroffen.

## Bewusste verbleibende Grenzen

Ein durchgehender Onboarding-Assistent, gespeicherte langfristige Messverläufe und ein neuer zentraler Fehlerdiagnose-Dienst wurden nicht erfunden. Die Übersicht führt auf die vorhandenen, funktionsfähigen Einrichtungswege. Ob Anfänger diese Wege tatsächlich schneller bewältigen, muss mit echten Nutzern geprüft werden. Sinnvolle Studienaufgaben: erste GPU einrichten, CPU ausschließlich für Benchmarks behalten, Node-Profil erklären, verlorene Proxy-Verbindung beheben und einen Benchmark samt Auswirkungen starten.

Das gemeinsame Stylesheet überlagert die vorhandene Komponentenbasis, damit bestehende Frontend-Arbeit erhalten bleibt. Eine spätere CSS-Konsolidierung wäre Wartungsarbeit, kein Nachweis zusätzlicher UX-Verbesserung.
