# Overclocking center / Overclocking-Zentrale

2026-10-21. The PC-Agent's `/benchmarks.html` is the unified entry point for
power sweeps, optional chained tuning, short benchmarks and individual GPU
calibration. `/autotuning.html` redirects to `#oc-expert`; the shared sidebar
has one entry. The results archive remains at `/benchmark-results.html`.

Deutsch: `/benchmarks.html` bündelt Power Sweeps, anschließendes Tuning, kurze
Benchmarks und einzelne GPU-Kalibrierungen. Die alte Autotuning-Adresse öffnet
den Expertenbereich. Das Ergebnisarchiv bleibt separat erreichbar.

## UI and data ownership / Darstellung und Datenhoheit

- `js/pages/overclocking.js` owns the fleet read model: search, filters, twelve
  workers per page, live readings, terminal test counts and stored best points.
  It shares the existing benchmark/efficiency channels and measurement snapshot.
  `workers` uses the supported SSE topic; tuning profiles/sessions use REST
  channels, because the server does not publish those SSE topics.
- `benchmarks.js` continues to own policy saves, run admission, starts, resume,
  cancellation, progress and curves. No driver or admission rule moves into
  the new read model. A known active individual calibration locks run controls.
- `autotuning.js` loads only when the expert disclosure first opens. Device
  controls are collapsed individually; clicking a fleet GPU opens that GPU.
  Its notices have a separate host. Completed session observations remain
  available, and missing chart sensors remain missing rather than becoming zero.
- Stable sweep points and confirmed tuning profiles have separate totals.
  Saved/live duplicate sweep points count once. Per-worker bests compare
  hashrate/watt only within the same coin, algorithm and miner software.
  An improvement percentage compares against the highest-limit stable stored
  sweep point of that same combination. This is not a cross-algorithm ranking,
  hardware certification, or a promise of pool income.
- Profile refresh failures retain previously loaded data and show a partial-data
  notice. Driver messages, hardware identifiers and external miner output retain
  the existing i18n exception; all authored copy is catalogued in DE and EN.

Deutsch: Die Flottenansicht liest vorhandene Daten und führt keine Miner- oder
Treiberbefehle aus. Bestwerte werden nur innerhalb derselben Coin-/Algorithmus-/
Software-Kombination verglichen. Effizienzgewinne beziehen sich auf den stabilen
gespeicherten Sweep-Punkt mit dem höchsten Power-Limit dieser Kombination.
Bestätigte Tuning-Profile werden gesondert gekennzeichnet. Fehler beim Nachladen
löschen keine bereits sichtbaren Ergebnisse. Worker-Details und GPU-Einstellungen
öffnen sich gezielt; fehlende Sensorwerte erscheinen als fehlend. Alle neuen
Texte unterstützen Deutsch und Englisch.

## Verification / Verifikation

- `scripts/check-overclocking-browser.cjs`: real assets, mocked local APIs,
  28-worker pagination/search/filter, live telemetry/progress, stable-point
  deduplication, confirmed bests, completion, retained data after HTTP 503,
  lazy expert controls, language selector, legacy URL and 390px viewport.
  Asserts that browsing issues no mining/driver writes.
- `scripts/check-benchmark-workflow-browser.cjs`: existing policy save/start,
  resume/cancel admission controls, failure to save, CPU-only sweep guard,
  LIVE-mode behavior, results archive and mobile regression.
- `check-i18n.cjs`, `simulate-i18n-render.cjs`, `scan-i18n-literals.cjs` and
  JavaScript syntax checks. Tests use browser fixtures; no live-hardware or
  installed-release acceptance is claimed.

Deutsch: Browserprüfungen mit simulierten APIs sowie Sprach-, Syntax- und
Darstellungsprüfungen. Keine echte Hardware-, Pool- oder Release-Abnahme.
