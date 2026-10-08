# PC-Agent: PV-gesteuerte Leistungsregelung

## Ziel

Der Solar-Miner-Node soll einen PV-Leistungswert an den PC-Agent übergeben können, ohne die interne CPU-/GPU-Regelung des PCs kennen zu müssen. Der PC-Agent ist für die Übersetzung dieses Wattziels auf die lokal vorhandene Hardware und Miner verantwortlich.

## Zuständigkeiten

- **Solar-Miner-Node:** Ermittelt das verfügbare Mining-Leistungsbudget aus PV-/Anlagenzustand, fragt den unterstützten Leistungsbereich des Agents ab und übergibt ein Wattziel.
- **PC-Agent:** Ermittelt CPU, GPUs, Treiber- und Minerfähigkeiten, verteilt das Ziel lokal und steuert Mining sowie GPU-Power-Limits.
- **GPU-Einstellungen des Nutzers:** Beschränken die von SolarMiner genutzte GPU-Leistung innerhalb der von Karte und Treiber gemeldeten Grenzen.

Der Node soll die CPU-/GPU-Aufteilung nicht nachbilden. Bestehende Unterworker-/Worker-Daten bleiben für Status und Telemetrie nützlich, sind aber keine Voraussetzung dafür, dass der Node selbst jede PC-Komponente einzeln regelt.

## Wattziel-Verhalten

Der PC-Agent bietet einen Endpunkt, der seinen regelbaren Wattbereich und die aktuellen Werte meldet. Der Node sendet danach ein Wattziel. Der Agent setzt es nach folgenden Regeln um:

| Angefordertes Ziel | Verhalten des PC-Agents |
| --- | --- |
| Unterhalb der minimal nötigen Mining-Leistung | Mining pausieren |
| Innerhalb des unterstützten Bereichs | Ziel lokal umsetzen und auf zulässige Hardware-/Treiber-Schritte runden |
| Oberhalb der unterstützten Maximalleistung | Auf das Maximum begrenzen |
| Nicht zuverlässig regelbare Hardware | Nur die sicher unterstützte Regelung anbieten, beispielsweise Start/Stop; andernfalls als nicht regelbar melden |

Der Agent meldet mindestens das angeforderte Ziel, das tatsächlich angewendete Ziel, den aktuellen Mining-/Pausezustand und den gemessenen oder geschätzten Verbrauch zurück. Messwerte müssen als gemessen oder geschätzt gekennzeichnet sein; nicht verfügbare Werte sind nicht als null Watt zu interpretieren.

## GPU-Sicherheit und Nutzergrenzen

CPU-Undervolting und CPU-Übertakten sind nicht Teil dieses Features. Diese Einstellungen verbleiben beim Nutzer und BIOS/UEFI. Der PC-Agent darf CPU-Mining nur über vom Miner unterstützte Mittel wie Start/Stop und gegebenenfalls Threadzahl oder Mining-Intensität beeinflussen.

Bei GPUs steuert der Agent ausschließlich unterstützte Power-Limits. Er verändert weder Spannung noch GPU-Takt. Vor dem Anwenden eines Limits muss er für jede GPU die aktuellen Fähigkeiten und zulässigen Grenzen über den passenden Treiber-/Herstellerpfad ermitteln. Es werden keine universellen Watt-Schrittweiten angenommen.

Der Nutzer erhält im PC-Agent eine eigene GPU-Einstellung mit Minimum und Maximum pro Karte. Diese Grenzen sind Schutzkorridore für den SolarMiner-Betrieb und dürfen die aktuellen Treiber-/Hardwaregrenzen nur weiter einschränken:

```text
effektives Minimum = max(Treiber-Minimum, Nutzer-Minimum)
effektives Maximum = min(Treiber-Maximum, Nutzer-Maximum)
```

Der Nutzer kann damit zum Beispiel verhindern, dass eine Karte oberhalb eines persönlich gewählten Power-Limits betrieben wird. Werte außerhalb der vom Treiber gemeldeten Spezifikationen dürfen weder im UI auswählbar sein noch durch API-Aufrufe angewendet werden.

Der Agent muss:

1. die zulässigen GPU-Grenzen beim Erkennen der Karte und vor dem Anwenden erneut abfragen;
2. UI-Werte und eingehende PV-Ziele gegen die effektiven Grenzen validieren und begrenzen;
3. die vom Treiber akzeptierte Schrittweite bzw. den tatsächlich gesetzten Wert verifizieren, statt eine Genauigkeit zu versprechen, die der Treiber nicht bietet;
4. bei fehlenden Grenzen, fehlenden Rechten oder unklarer Treiberantwort keine dynamische GPU-Power-Regelung anbieten;
5. bei einem abgelehnten oder nicht verifizierbaren Wert den letzten bekannten sicheren Zustand wiederherstellen oder die betroffene GPU pausieren und den Fehler melden;
6. GPU-Einstellungen anhand einer stabilen Gerätekennung speichern, nicht ausschließlich anhand eines veränderlichen Index wie „GPU 0“;
7. beim Start und nach Änderungen an Hardware oder Treiber gespeicherte Werte erneut validieren.

Wo die Plattform das zuverlässig unterstützt, stellt der Agent beim Beenden oder Herunterfahren den ursprünglichen Power-Limit-Zustand wieder her. Die Oberfläche zeigt den vom Treiber tatsächlich gesetzten Wert an.

Software kann keine absolute Schadensfreiheit bei Defekten, Kühlungsproblemen oder fehlerhaften Treibern garantieren. Der Schutz dieses Features beruht daher auf enger Begrenzung auf gemeldete Treiberwerte, Nutzerlimits, Verifikation und einem sicheren Fehlerzustand; Spannung und Takt bleiben unangetastet.

## GPU-UI

Für jede erkannte und unterstützte GPU zeigt die Mining-Ansicht:

- Modellname und stabile Gerätekennung (mit Index nur als ergänzender Anzeige),
- vom Treiber gemeldete minimale und maximale Leistungsgrenze,
- einstellbare Nutzer-Minimum- und Nutzer-Maximum-Schieberegler innerhalb dieser Grenzen,
- aktuelles Power-Limit und aktuelle Leistungsaufnahme, jeweils soweit verfügbar,
- ob Werte direkt gemessen oder geschätzt werden,
- Regelungsstatus und verständliche Fehlermeldung, wenn die GPU nur Start/Stop unterstützt.

Die UI darf nicht den Eindruck erwecken, dass ein gesetztes Power-Limit eine exakte Leistungsaufnahme garantiert. Die Leistungsaufnahme kann je nach Last unter dem Limit liegen.

## Bereichs-/Status-API

Der Agent-Endpunkt sollte einen zusammengefassten Bereich für den Node sowie die Details je GPU liefern. Ein mögliches Schema:

```json
{
  "supportsDynamicPowerScaling": true,
  "minPowerWatts": 180,
  "defaultPowerWatts": 420,
  "maxPowerWatts": 700,
  "currentTargetWatts": 420,
  "currentUsageWatts": 397,
  "usageMeasurement": "measured",
  "miningPaused": false,
  "gpus": [
    {
      "deviceId": "stable-device-id",
      "model": "GPU model from driver",
      "driverMinPowerLimitWatts": 100,
      "driverMaxPowerLimitWatts": 350,
      "userMinPowerLimitWatts": 140,
      "userMaxPowerLimitWatts": 280,
      "currentPowerLimitWatts": 220,
      "currentUsageWatts": 211,
      "supportsDynamicPowerScaling": true
    }
  ]
}
```

Die Feldnamen sind ein Vorschlag und müssen mit den bestehenden Node-/Agent-DTOs abgestimmt werden. Nicht unterstützte Werte werden explizit als nicht verfügbar dargestellt. Der zusammengefasste Bereich berücksichtigt nur aktuell verfügbare und regelbare Ressourcen samt ihren effektiven Nutzer-/Treibergrenzen.

## Bestehende Systeme und Rückwärtskompatibilität

Der PC-Agent besitzt bereits interne CPU-/GPU-Zielwerte und liefert Worker-Statistiken. Diese interne Verteilung kann schrittweise hinter dem einfachen Wattziel-Vertrag gekapselt werden. Der Node nutzt weiterhin seine vorhandenen Miner-Power-Target-Mechanismen und muss für CPU/GPU-Details keine neue Regelungslogik erhalten.

Andere Miner behalten ihr jeweiliges Regelungsverhalten. Ein Agent ohne Range-Endpunkt oder ohne dynamische Leistungsregelung darf nicht so behandelt werden, als könne er jedes Wattziel exakt umsetzen; der Node kann dann auf vorhandene Start/Stop- oder feste Leistungsprofile zurückfallen.

## Umsetzungsschritte

1. PC-Agent-Vertrag für unterstützte Range, Wattziel und angewendeten Zustand festlegen.
2. GPU-Treiberadapter ergänzen, die Fähigkeiten und akzeptierte Power-Limits pro Plattform verlässlich abfragen und setzen.
3. Nutzergrenzen pro stabiler GPU-Kennung persistieren und im Agent-UI editierbar machen.
4. PV-Wattziel im Agenten auf CPU-Miner und GPUs verteilen; Unterschreiten pausiert, Überschreiten wird begrenzt.
5. Node nur um Abfrage des Agent-Bereichs und Nutzung des bestehenden Wattziel-Pfads ergänzen.
6. Fehlerfälle, Treiber-/Hardwarewechsel, ungültige gespeicherte Grenzen und Agent-Neustart abdecken.

## Verifikation vor Freigabe

- Treibergrenzen und echte Schrittweiten für jede unterstützte GPU-/OS-/Treiber-Kombination nachweisen.
- Ziele unter Minimum, innerhalb der Range und oberhalb Maximum prüfen.
- Sicherstellen, dass Werte außerhalb der Treibergrenzen weder über UI noch API angewendet werden.
- Ablehnung und Nichterreichbarkeit der Treiber-API, fehlende Rechte, Agent-Neustart und Gerätewechsel prüfen.
- Tatsächlichen Power-Limit-Sollwert getrennt von realer Leistungsaufnahme darstellen und verifizieren.
- CPU-Pfad prüfen, damit keine BIOS-, Spannungs- oder Taktänderungen vorgenommen werden.
- PC-Agent-Standalonebetrieb und Node-verbundenen PV-Betrieb berücksichtigen.
