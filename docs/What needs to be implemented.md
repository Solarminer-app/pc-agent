1. In the pc-agent power limit page we have a slider to toggle the power target.
Since the gpu driver tells us the standard power target of a card we want to make that standard target visual in the slider.
The slider bar will be red for values above this standard target and green below that standard target

2. Wichtig ist, dass sich das pc-agent power target tuning system immer selbst verbessert, wenn man zB einen Operating mode ausgewählt hat der selber tuned:
Das bedeutet. Während ein miner läuft sammelt er weiterhin Daten.
Wenn er merkt, dass zB die power target konfiguration sich stark verändert hat und durch gelegentlich messungen sich die
power profile verändern, dann muss er logischerweise das berücksichtigen. Das sollte aber soweit ich weiß sowieso schon passieren.
Wichtig ist nur dass er immer wieder lokal misst und speichert.

3. Im dashboard kann man andere worker sehen.
Wir wollen durch einen klick auf den namen des workers zur webpage des entsprechenden pc-agents weitergeleitet werden

4. Wenn im pc-agent measurements collected werden sieht das ungefähr so aus:
   290 W ✓ · 83.42 TH/s · 286 W · 275 W ✓ · 82.3 TH/s · 274 W · 260 W ✓ · 81.19 TH/s · 259 W · 245 W ✓ · 79.67 TH/s · 244 W · 230 W ✓ · 78.41 TH/s · 230 W · 215 W ✓ · 76.74 TH/s · 215 W · 200 W ✓ · 74.01 TH/s · 200 W · 185 W ✓ · 71.95 TH/s · 185 W · 170 W ✓ · 69.29 TH/s · 170 W · 155 W ✓ · 63.26 TH/s · 155 W · 140 W ○ · 125 W ○ · 110 W ○ · 100 W ○
   Könnte man das nicht ein wenig schöner grafisch aufzeigen? Aneinander gereihte Zeichenketten ind nicht sehr schön. Vielleicht lieber untereinander zum ausklappen?
