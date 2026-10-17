# Worker modes that maximize daily profit

**Status: implemented in the Beta PC-Agent.** This document describes two
additional goals alongside the existing revenue-per-kWh modes. Both currently
require a GPU; the fixed-coin mode retains its coin and miner, while the second
may choose a different GPU route.

## Review of commit `c8bd75f` / Prüfung des Commits `c8bd75f` (2026-10-17)

**Deutsch:** Die Grundauswahl ist eingebaut: GPU-Messpunkte werden mit eigener
Leistung, frischen Prognosen, bekannten Gebühren und dem gespeicherten Tarif
nach geschätztem Nettogewinn/Tag sortiert. Die Umsetzung erfüllt das Konzept
noch nicht vollständig:

- `EnergyJournalService.loadSettings()` setzt bei fehlender Tarifdatei 0,30 EUR/kWh.
  `CoinHoppingService.addCandidate()` behandelt diesen Vorgabewert als bekannten
  Tarif; die Aktivierung meldet keinen fehlenden oder unbestätigten Tarif.
- `workers.js:31-40` zeigt als „geschätzten Gewinn“ Bruttoertrag minus Stromkosten
  ohne Gebühren. Das widerspricht der Auswahlformel; Coin, Messpunkt, Gebühren,
  Tagesenergiekosten und Auswahlgrund sind dort auch nicht einsehbar.
- Die vorhandenen `WorkerCoinRevenueModeServiceTest`-Fälle prüfen nur die alten
  kWh-Modi. Die im Abschnitt „Verifikation“ genannten Rechnungs-, Wechsel-,
  Negativgewinn- und Wiederherstellungsfälle bleiben ungetestet. Die
  Wiederherstellung bei Hardware- oder Minerfehlern braucht zusätzlich einen
  realen GPU-/Pool-Lauf.
- `WorkerCoinRevenueModeService.evaluateWorker()` wechselt nur, wenn der gerade
  laufende Coin-/Miner-/Power-Punkt noch in der aktuellen Kandidatenliste steht.
  Läuft dessen bis zu 30 Tage altes Profil ab oder fehlen dafür aktuelle
  Eingaben, bleibt ein vorhandener besserer Kandidat ungenutzt; das ist aus
  dem Codepfad abgeleitet und noch nicht als Laufzeittest reproduziert.

**English:** The core selector is wired: GPU measurements use their own power,
fresh forecasts, known fees and the stored tariff to rank estimated net
profit/day. The implementation does not yet meet the whole contract:

- `EnergyJournalService.loadSettings()` supplies 0.30 EUR/kWh when no tariff file
  exists. `CoinHoppingService.addCandidate()` treats that default as a known
  tariff, so activation does not identify an unset or unconfirmed rate.
- `workers.js:31-40` displays gross revenue minus electricity cost as
  “estimated profit,” omitting fees. The selected coin, measurement, fee
  assumption, daily energy cost and selection reason are not inspectable there.
- Existing `WorkerCoinRevenueModeServiceTest` cases cover only the older kWh
  modes. The calculation, switching, negative-profit and rollback scenarios
  listed below remain untested. Recovery after a hardware or miner failure
  also needs a live GPU/pool run.
- `WorkerCoinRevenueModeService.evaluateWorker()` switches only if the running
  coin/miner/power point remains in the current candidate list. When its
  profile ages out or required data disappears, another better candidate is
  ignored. This follows from the code path and has not yet been reproduced in
  a runtime test.

Review evidence: focused `WorkerCoinRevenueModeServiceTest` passed on JDK 21;
both i18n catalog/literal checks passed. No live GPU or pool evidence was
available. No source code was changed in this review.

### Follow-up / Nacharbeit (2026-10-17)

**Deutsch:** Der Dashboard-Energiebereich zeigt den gespeicherten Tarif nun
als hervorgehobenen Wert mit direkter Schaltfläche zum Ändern. Die Worker-
und Dashboard-Gewinnanzeigen kennzeichnen ihre Rechnung aus Ertrag minus
Stromkosten mit `≈`; die Modus-Auswahl berücksichtigt bekannte Gebühren
weiterhin. Wenn das Profil des laufenden Punkts überholt ist, wählt die
regelmäßige Neubewertung einen anderen gültigen Messpunkt, auch wenn nur
dieser eine Kandidat übrig ist. Der alte GPU-Grenzwert und die Zuweisung
werden bei fehlgeschlagenem Wechsel wiederhergestellt. Drei gezielte
Regressionstests für diese Fälle sowie Katalog-, Literal- und Renderprüfungen
für Deutsch/Englisch bestanden. Der Standardtarif bleibt ein Vorgabewert;
eine Bestätigung oder dynamische PV-Bewertung ist nicht implementiert.

**English:** The dashboard energy section now shows the stored rate prominently
with a direct edit button. Worker and dashboard profit figures use `≈` for
their revenue-minus-electricity estimate; mode selection still includes
known fees. If the running point's profile expires, periodic reevaluation
can choose another valid measured point even when it is the only remaining
candidate. A failed switch restores the previous GPU cap and assignment.
Three focused regression tests and both-language catalog, literal and render
checks passed. The default tariff remains a preset; confirmation and dynamic
solar valuation are not implemented.

### Results comparison / Ergebnisvergleich (2026-10-17)

**Deutsch:** `/benchmark-results.html` vergleicht für eine gewählte GPU einen
festen Coin und Miner anhand derselben aktuell nutzbaren Kandidaten. Der
Effizienzpunkt maximiert gemessene Hashes/Watt, der Tagesgewinnpunkt den vom
Backend geschätzten Nettogewinn/24 h. Die Ansicht nennt Hashrate, GPU-Limit,
gemessene Watt, kWh und Stromkosten je 24 h, Bruttoertrag/kWh und /24 h sowie
die Gebührenannahme und den geschätzten Nettogewinn. Der Tarif stammt aus dem lokalen Economics-Read-
Model; fehlt er oder ein Gewinnkandidat, erscheinen keine erfundenen Nullwerte.
Die Ansicht ist eine 24-Stunden-Hochrechnung und keine PV-Zeitplanung.

**English:** `/benchmark-results.html` compares one selected GPU, fixed coin
and miner over the same currently eligible candidates. The efficiency point
maximizes measured hashes/watt; the daily-profit point maximizes backend
estimated net profit/24 h. It displays hashrate, GPU limit, measured watts,
kWh and electricity cost per 24 h, gross revenue/kWh and /24 h, and estimated
net profit with the fee assumption. The tariff comes from the local economics read model; missing
tariff or profit inputs remain unavailable rather than becoming zero. This is
a 24-hour projection, not a solar schedule.

### Manual baseline / Manueller Vergleichspunkt (2026-10-17)

**Deutsch:** Die Ergebnisansicht zeigt zusätzlich `MANUAL`. Das Power-Target
kommt aus `GET /api/agent/local/power-control`, Feld
`gpus[].userMaxPowerLimitWatts` für die ausgewählte GPU. Dieses Limit wird
auch beim manuellen Worker-Start verwendet. Hashrate, gemessene Watt und
Ertragswerte erscheinen nur, wenn für exakt dieses Limit und denselben Coin
und Miner ein aktuell nutzbarer Messpunkt existiert. Ein nächstgelegener
Messpunkt wird nicht als manuelles Ergebnis ausgegeben.

**English:** Results also show `MANUAL`. The power target comes from
`GET /api/agent/local/power-control`, field `gpus[].userMaxPowerLimitWatts`
for the selected GPU. Manual worker startup uses this limit too. Hashrate,
measured watts and revenue appear only when a currently eligible measurement
matches that exact limit, coin and miner. A nearby point is never presented
as the manual result.

## Deutsch

### Modi

| Proposed ID | UI label | Auswahlziel |
| --- | --- | --- |
| `PROFIT_PER_DAY` | Gewinn pro Tag · fester Coin | GPU-only. Wählt für den zugewiesenen Coin und Miner den gemessenen Power-Punkt mit dem höchsten geschätzten Nettogewinn pro 24 Stunden. |
| `COIN_PROFIT_PER_DAY` | Coin & Gewinn pro Tag | GPU-only. Wählt aus den verfügbaren Coin-, Miner- und Power-Kombinationen den Betriebspunkt mit dem höchsten geschätzten Nettogewinn pro 24 Stunden. |

Die bestehenden Modi `REVENUE_PER_KWH` und `COIN_REVENUE_PER_KWH` bleiben Effizienzziele. Die neuen Modi maximieren den Geldbetrag pro Tag. Bei gleichem Coin und identischem Stromverbrauch führen beide Ziele in der Regel zur gleichen Auswahl; bei unterschiedlichen Power-Limits können sie andere Betriebspunkte wählen.

### Berechnung

Für jeden Kandidaten wird dieselbe 24-Stunden-Periode verwendet:

```text
Netto-Gewinn/Tag = Brutto-Ertrag/Tag × (1 − bekannte Gebühren/100)
                  − (gemessene Watt × 24 / 1000 × Strompreis pro kWh)
```

Der Brutto-Ertrag wird aus dem gemessenen Kandidaten-Hashrate-Profil und den aktuellen Netzwerk-, Blockreward- und Kursdaten geschätzt. Der Vergleich nutzt den Kandidaten-eigenen gemessenen Verbrauch, nicht einen gemeinsamen Wattwert. Währung und Umrechnung müssen für alle Kandidaten gleich sein.

Wenn Gebühren nicht zuverlässig bekannt sind, darf die Automatik sie nicht als null ausgeben. Die Implementierung vergleicht Kandidaten nur, wenn die anwendbaren Gebühren bekannt sind; andernfalls ist die Auswahl nicht verfügbar.

### Kandidatenauswahl und Laufzeit

- Kandidaten stammen aus stabilen Sweep-, Benchmark- oder normalen Mining-Messpunkten mit gültiger Hashrate und gemessener Leistung.
- Jeder Kandidat muss für Gerät, Coin, Miner-Version und Power-Limit passen. Veraltete Netzwerk-/Kursdaten und nicht mehr startbare Miner-Routen werden ausgeschlossen.
- Beide Modi sind zunächst GPU-only, da ihre Kandidaten gemessene GPU-Power-Punkte mit verifizierbarer Geräte-Leistungsregelung benötigen.
- `PROFIT_PER_DAY` darf Coin und Miner-Zuweisung nicht ändern. Es kann den besten gemessenen Power-Punkt für den fest zugewiesenen Coin und Miner anwenden.
- `COIN_PROFIT_PER_DAY` ist zunächst GPU-only und darf Coin, Miner und Power-Punkt gemeinsam wählen. CPU-Coinwechsel bleiben ausgeschlossen.
- Beim Start wird der Kandidaten-Coin (falls erlaubt) und das Power-Limit vor dem Minerstart angewendet und verifiziert.
- Im laufenden Betrieb darf nur nach einer vollständigen Neuberechnung gewechselt werden. Die vorhandene Hysterese (Mindestverbesserung und Cooldown) soll gegen häufiges Umschalten schützen; konkrete Werte müssen vor Implementierung festgelegt werden.
- Ein explizites Pause-Signal, ein laufender Benchmark/Sweep oder ein nicht verifizierbares Power-Limit verhindern den Wechsel. Tarif-, Gebühren- oder Marktfeed-Ausfall darf keinen Nullpreis und damit keinen scheinbar maximalen Profit erzeugen.

### Energieannahme

Diese Modi passen zur Auswahl des höchsten täglichen Nettogewinns, wenn die Energie aus dem Netz kommt und der Tarif für die betrachteten 24 Stunden gilt. Bei PV, Batterie, Einspeisevergütung, zeitvariablen Tarifen oder Node-Dispatch kann der wirtschaftliche Wert einer kWh variieren. In diesem Fall soll der PC-Agent weiterhin lokale Kandidatenwerte melden; eine zentrale Planung muss den tatsächlichen marginalen Energiewert berücksichtigen.

### UI und Nachvollziehbarkeit

Die Auswahl muss als **geschätzter** Gewinn erkennbar sein. Für den aktiven Kandidaten sollten Coin, Hashrate, Watt, Tarif, tägliche Energiekosten, Gebührenannahme, geschätzter Brutto-Ertrag und geschätzter Nettogewinn einsehbar sein. Fehlende Eingaben zeigen „nicht verfügbar“ mit Ursache und dürfen nicht durch null ersetzt werden.

### Verifikation und verbleibende Rollout-Nachweise

1. Eine Rechnung mit zwei Power-Punkten belegt, dass der gewählte Punkt den höheren Nettogewinn pro Tag hat, auch wenn er nicht den höchsten Gewinn pro kWh hat.
2. Ein fester-Coin-Modus ändert keine Coin-Zuweisung; der Coin-Modus kann zwischen zulässigen GPU-Coin-Routen wechseln.
3. Tarif, Gebühren, Fiat-Umrechnung, stale Marktdaten, fehlende Messwerte und negative Gewinne sind definiert und sichtbar.
4. Start, Umschaltung, Abbruch und Wiederherstellung von Power-Limit und Miner-Zustand sind serialisiert und fehlersicher.
5. Deutsche und englische Modusnamen, Erklärungen, Fehler und Barrierefreiheitstexte sind katalogisiert.
6. Simulator-/Unit-Verifikation und anschließend echte GPU-, Pool-Share- und Stromverbrauchsvergleiche dokumentieren, dass die Auswahl dem beobachteten Ergebnis nahekommt. Ein erfolgreicher Build allein ist kein Profitabilitätsnachweis.

Der Java-Code wurde kompiliert, und die deutsche/englische Katalog- und Renderprüfung lief durch. Unit-Tests und Vergleiche mit realer GPU, Pool-Shares und Stromverbrauch wurden in diesem Änderungsschritt nicht ausgeführt. Die Auswahl bleibt eine Schätzung, bis diese Nachweise vorliegen.

## English

### Modes

| Proposed ID | UI label | Selection goal |
| --- | --- | --- |
| `PROFIT_PER_DAY` | Profit per day · fixed coin | GPU-only. Selects the measured power point with the highest estimated net profit per 24 hours for the assigned coin and miner. |
| `COIN_PROFIT_PER_DAY` | Coin & profit per day | GPU-only. Selects the coin, miner and power combination with the highest estimated net profit per 24 hours. |

The existing `REVENUE_PER_KWH` and `COIN_REVENUE_PER_KWH` modes remain efficiency goals. The new modes maximize money per day. For the same coin and identical power use, both goals usually select the same point; when power limits differ, they can choose different points.

### Calculation

Use the same 24-hour period for every candidate:

```text
Net profit/day = gross revenue/day × (1 − known fees/100)
                 − (measured watts × 24 / 1000 × electricity rate per kWh)
```

Estimate gross revenue from the candidate's measured hashrate profile and current network, block reward and market data. Use each candidate's own measured consumption rather than a shared watt value. Currency and conversion must be consistent across candidates.

If applicable fees are not known reliably, the selector does not silently treat them as zero. It compares candidates only when their applicable fee parts are known; otherwise, selection is unavailable and the UI explains that fees, tariff or current data are missing.

### Candidate selection and runtime

- Candidates come from stable sweep, benchmark or ordinary mining measurements with valid hashrate and measured power.
- Each candidate must match the device, coin, miner version and power limit. Exclude stale network/market data and miner routes that cannot start.
- `PROFIT_PER_DAY` must not change the assigned coin or miner assignment. It may apply the best measured power point for the fixed coin and miner.
- Both modes are GPU-only because their candidates require measured GPU power points and verified device power control.
- `COIN_PROFIT_PER_DAY` is GPU-only initially and may choose coin, miner and power point together. CPU coin switching remains out of scope.
- At start, apply and verify the candidate coin (where allowed) and power limit before starting the miner.
- During a run, recalculate regularly and use a minimum improvement threshold and cooldown to prevent oscillation.
- An explicit pause, active benchmark/sweep or unverifiable power limit prevents switching. A tariff, fee or market-feed failure must not become zero cost and a falsely optimal profit.

### Energy assumption

These modes target the highest daily net profit when energy comes from the grid and the tariff applies across the evaluated 24 hours. With PV, batteries, export compensation, time-of-use rates or Node dispatch, the economic value of energy can vary. In those cases, the PC-Agent should continue reporting local candidate data while central planning accounts for the actual marginal energy value.

### UI and auditability

Clearly label the result as **estimated** profit. Let the operator inspect the active candidate's coin, hashrate, watts, tariff, daily energy cost, fee assumption, estimated gross revenue and estimated net profit. Missing inputs show “unavailable” with the reason and must not be replaced with zero.

### Verification and remaining rollout evidence

1. A calculation with two power points proves that the selected point has higher net profit per day, even if it does not have the highest profit per kWh.
2. The fixed-coin mode never changes coin assignment; the coin mode can switch among allowed GPU routes.
3. Tariff, fees, fiat conversion, stale market data, missing measurements and negative profit are defined and visible.
4. Start, switch, cancel and restoration of power limit and miner state are serialized and fail-safe.
5. German and English mode names, explanations, errors and accessibility text are catalogued.
6. Simulator/unit verification followed by real GPU, pool-share and power-use comparisons documents how closely selection matches observed results. A successful build alone does not prove profitability.

The Java code compiled, and German/English catalog and render checks passed. Unit tests and comparisons against real GPU, pool-share and power-use results were not run in this change. Selection remains an estimate until that evidence is available.
