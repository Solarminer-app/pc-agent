# Local worker operating modes (2026-10-16)

The Worker dialog stores `workerPowerModes` in `control-settings.json`, keyed by physical worker ID. Missing entries mean `MANUAL`. `POST /api/agent/local/workers/{deviceId}/power-mode` validates a requested mode against local evidence before saving it and uses the local run lock to exclude concurrent measurements/control plans; `GET /api/agent/local/workers` reports each worker's mode.

| Mode | Local decision |
| --- | --- |
| `MANUAL` | GPU starts with the saved user power cap. CPU retains its existing manual thread target. |
| `EFFICIENCY` | GPU: choose the best hashrate per measured watt from sustained-temperature-validated sweep points for the assigned coin. CPU: retain the ordinary measured-thread-target path. |
| `MAX_HASHRATE` | GPU only. Choose the highest hashrate from stable, temperature-measured sweep steps for the assigned coin within the configured GPU watt bounds. It does not choose a different coin. |
| `REVENUE_PER_KWH` | Keep the assigned coin and choose its highest measured gross USD/kWh coin/miner/power point from fresh cached market data. The selected miner software and cap are applied before a local Worker-page start. |
| `COIN_REVENUE_PER_KWH` | GPU only. Select the highest measured gross USD/kWh coin/miner/power point before a local Worker-page start, updating the worker's coin and miner assignment as needed. While mining, re-evaluate measured route-ready pairs every minute and switch only with at least 5% forecast improvement and a 30-minute in-process cooldown. The existing single-GPU Coin Hopping automation yields that GPU while a Worker mode is active. |
| `PROFIT_PER_DAY` | GPU only. Keep the assigned coin and miner; select its measured power point with the highest estimated gross USD/day minus measured 24-hour electricity cost. Fees are excluded. |
| `COIN_PROFIT_PER_DAY` | GPU only. Select the measured route-ready coin/miner/power point with the highest estimated gross USD/day minus measured 24-hour electricity cost. Fees are excluded. Re-evaluate every minute; switch only for a higher estimate, using the shared 5% positive-profit improvement gate and 30-minute in-process cooldown. |

Automatic modes require a writable GPU cap (for GPUs), a known target and positive measured hashrate/power. Revenue and daily-profit GPU modes require a temperature-validated power-sweep point or a confirmed Auto Tuning result; ordinary LIVE and benchmark observations remain in history but cannot supply these operating points. Other modes retain their own evidence rules below. Unsupported or unmeasured modes reject activation and retain the old setting. CPU automatic modes need benchmark/ordinary measurements at positive thread targets; CPU coin switching is unavailable because the agent has only one CPU coin. The daily-profit modes are GPU-only because their selection currently consumes measured GPU power candidates.

### Thermal failure of a measured point (2026-10-21)

The revenue/profit candidate comparison excludes observational LIVE and ordinary
benchmark rows: they do not store a full clock configuration or thermal evidence.
Sweep candidates must include sustained-load thermal evidence as specified below,
not just a cold peak below the worker ceiling (85 °C fallback). Confirmed tuning
points additionally retain their 32-sample and exact device/software/settings
evidence. A first baseline start after unknown
clock state stops the managed worker and resets native settings and driver clocks
before applying its cap. New power-only curves also reset before measurement,
even when no tuning phase follows. Explicit defaults include a queried NVIDIA
memory offset, including TITAN RTX, with write/readback verification; interrupted
tuning recovery still restores the saved original offset through its separate path.

If a running worker reaches its configured thermal ceiling, the guard pauses it
and records the measured temperature and limit in the miner console. Missing
temperature has a separate stop reason and does not indict the profile. A known
hot point is recorded per physical GPU and exact coin/miner/cap/clock identity in
`operating-point-safety.json`. Old evidence for that point stays excluded after
an Agent restart; a new validated measurement taken after the failure can make
it eligible again; a raised temperature policy can also release the thermal quarantine as described below. Other GPUs and other points remain independent. Unreadable
or unwritable rejection history blocks revenue/profit selection with
`power_mode_point_safety_unavailable`; a persistence failure cannot prevent the
emergency pause. The cooldown restart contract below applies after successful thermal stops. No valid candidate
reports `power_mode_no_validated_point`, distinct from an unavailable tariff.
Console stop reasons are deliberately bilingual; `s()` resolves these messages
to DE/EN when rendered as agent diagnostics.

Deutsch: GPU-Ertrags- und Tagesprofit-Modi verwenden nur temperaturgeprüfte
Sweep-Punkte oder bestätigte Tuning-Ergebnisse. LIVE-/Benchmark-Beobachtungen
bleiben Historie. Vor dem ersten Basisstart und vor neuen Kurven werden alte
native Einstellungen und Treibertakte zurückgesetzt. Ein Temperaturfehler sperrt
den konkreten Betriebspunkt dauerhaft nur für diese GPU, bis eine neue geprüfte
Messung nach dem Fehler vorliegt oder eine ausreichend erhöhte Temperaturgrenze die Sperre aufhebt. Ein fehlender Sensorwert stoppt ebenfalls,
sperrt aber kein Profil. Messwert und Grenze stehen im Konsolenprotokoll;
Diagnosen sind DE/EN übersetzt. Abkühlen startet den Miner nicht automatisch.

Ordinary CPU and GPU mining adds a historical profile after twelve valid five-second readings at the same device/coin/target. Changing the target starts a new sample window; the profile store keeps one latest measured point per target and persists it under `./solarminer-agent/`. Ordinary GPU observations are not candidates for automatic operating points. The active-mode retuning gain/cooldown and local-run-lock rules still apply to eligible points. CPU automatic modes use newly collected points on the next start; live CPU thread retuning is not claimed.

`MAX_HASHRATE` has a stricter evidence gate: only sweep steps with a measured peak temperature below `solarminer.agent.max-hashrate.max-temperature-c` qualify. The default ceiling is 80 °C, clamped to 60–90 °C. Start is rejected if the current GPU temperature is missing or at/above the ceiling minus 10 °C. A scheduled check (default every five seconds) pauses an active maximum-hashrate worker if its GPU temperature is missing or reaches the ceiling. If worker telemetry fails entirely, it attempts to pause every configured maximum-hashrate worker. The same 10 °C hysteresis gates a later manual or Node start, avoiding immediate hot restarts. The PC-Agent never increases the driver/user watt bound, and switching modes does not activate this mode by default. The sensor is the vendor API's available GPU or hotspot temperature; an NVIDIA memory junction sensor is not included. The ceiling is a conservative generic policy, not a board-specific hardware certification, and hardware verification remains required.

The Node still receives only on/off control for this mode. Whether a run is economically sensible with a battery, export revenue, PV forecast and grid tariff remains a Node scheduling decision; a full battery by itself does not prove that maximum-hashrate mining is the most profitable dispatch.

The Worker setup dialog puts operating mode before coin/miner selection. Its revenue modes validate that usable measured candidates exist. Before a GPU miner starts through the local Workers page, the revenue mode writes and verifies the chosen target, then starts with that already selected cap; a coin-mode start first assigns the best candidate coin. CPU starts set the measured thread target before XMRig starts. GPU writes and miner behavior still require live driver/OS validation.

`GET /api/agent/external/power-control` reports dynamic scaling off while any assigned worker has an automatic mode. External positive/zero targets become start/pause requests; increment/decrement are rejected. C10 capabilities add `powerMode`; assignments and target changes in Node economic plans are rejected for locally managed workers, while `activeWorkerIds` remains available for the existing locally consented `AUTO` workers. The Node consumes `powerMode` to omit those assignments. Existing clients without this additive field see `MANUAL`.

The per-worker coin/revenue scheduler currently keeps its 30-minute cooldown in process memory. The kWh modes remain gross and exclude pool fees, rejected shares and actual rewards. Daily-profit modes calculate gross forecast minus electricity cost when the local tariff can be converted to USD and market data is fresh; fee parts are informational and do not gate this estimate. Estimates still do not include rejected shares or actual pool accounting. A restart may reset the cooldown. Physical switching, thermal stability and pool credit verification remain rollout gates.

When an automatically managed GPU worker is paused from the Workers page (including a local pause request), the Agent first stops the miner and then restores the GPU's saved Power Limits target. If no user target was saved, the driver's maximum target is used, matching normal manual-mode startup. A failed stop leaves the currently applied cap in place; a failed restoration is reported as `power_mode_apply_failed`.

Worker assignment changes update the device list shared by GPUs on the same
coin/miner pair. Since applying that list restarts the miner process, the
assignment service skips identical lists and restarts the remaining assigned
workers that were mining before a changed list was written. A GPU moved to a
different coin/miner is not restored through the previous pair. This recovery
path has not yet been validated with live miners.

## Start and recovery review (2026-10-17)

The mode-change endpoint holds the local run lock while saving and applying a mode. Its revenue selection now uses that existing lock instead of trying to acquire it again. A running GPU can therefore change to `REVENUE_PER_KWH` or `COIN_REVENUE_PER_KWH` without a spurious `power_mode_busy` rejection. Local Worker, LAN peer and Node on/off starts use the same measured revenue-point selection; a coin-mode start can reassign a deliberately suboptimal saved coin before starting its miner. Economic plans pass their already held lock into this start path.

When a running worker's assignment changes in the Worker dialog, the browser saves the assignment and mode, then sends one start request for the new target. A failed start is shown as an action error; saving no longer silently leaves a successfully reconfigured worker paused. This browser sequence is not an atomic server transaction, so a failed mode save or start may still leave the new assignment saved and the worker stopped.

Historical note: the initial `COIN_REVENUE_PER_KWH` implementation retried unexpected stops through the revenue scheduler. This was removed: miner engines own bounded crash recovery; only the explicit thermal cooldown contract below handles thermal restarts. Explicit Worker, coin, global, LAN peer and Node pause actions clear that in-memory start intent. It is deliberately volatile across Agent restarts. Every automatic coin stop now carries a bilingual coin-switch reason into the miner console, and switch, failed start and failed restoration details are logged by the Agent. A failed start or missing candidate still leaves the worker stopped and requires investigation of the reported error; no claim of continuous mining is made without live miner, pool and driver verification.


## 2026-10-21 selection and temperature update / Auswahl und Temperatur

New GPU profiles use the two economic goals plus independent coin/software
permissions. Legacy API mode names remain compatible. See the current
[benchmark workflow](benchmark-workflow.md) for scope, safety and verification.

Neue GPU-Profile nutzen die zwei wirtschaftlichen Ziele und getrennte Rechte
für Coin-/Softwarewechsel. Alte API-Modi bleiben kompatibel. Der aktuelle
[Benchmark-Ablauf](benchmark-workflow.md) beschreibt Umfang, Sicherheit und Nachweise.


## 2026-10-22 thermal cooldown and policy changes / Abkühlung und Grenzwerte

EN: After a successful regular-mining thermal pause, the Agent keeps a volatile,
per-GPU restart intent. It waits for at least 60 seconds of observed cooling at
or below the current ceiling minus 5 °C; a missing, invalid or warmer reading
resets this cooling period. A measured-mode restart reselects an eligible point
using the normal mode and held run lock. A rejected point is excluded from GPU
efficiency, maximum-hashrate, tuned-tier and revenue/profit choices; MANUAL does
not silently change the operator target and waits if that target is blocked.
If no target is eligible, selection retries no more than once per minute.
The maximum-hashrate guard hands successful thermal stops to the same cooldown
owner and retains its stricter start and temperature limits. Missing sensors
stop the worker and require cooling evidence before restart, but do not
quarantine an unproven profile.

A later local, coin/global or direct Node pause, a newer operator stop (including
stop followed by clearing that stop), changed mode/coin/miner, or an active Node
economic plan cancels this intent. Measurement locks defer it. Intent is not
restored across Agent restarts; it is not an unbounded crash-recovery budget.

New entries in `operating-point-safety.json` persist failure time, observed
GPU temperature and the then-active ceiling. An old measurement becomes eligible
again only when fresh evidence supersedes the failure or the effective current
ceiling is **strictly above both** the old ceiling and observed temperature.
Thus 84/84 → 86 releases the thermal quarantine; observed 87 → ceiling 86 does
not. Lowering the ceiling restores exclusion. Other stability/evidence, hardware,
assignment and economics prerequisites still apply. Legacy timestamp-only files
remain readable: an explicit increase of an existing saved worker policy annotates
these entries with that previous policy boundary, acknowledging the old thermal
failure whose observation was not retained. Unrelated GPUs stay unchanged, and
lowering the policy reinstates those legacy quarantines too. Corrupt/unwritable
history fails closed. Automatic restart and pending-retry log messages are
bilingual DE/EN; external miner text remains verbatim.

DE: Nach erfolgreichem Temperaturstopp merkt sich der Agent pro GPU einen
Wiederanlauf. Er wartet mindestens 60 Sekunden bei beobachteter Temperatur von
höchstens aktueller Grenze minus 5 °C. Ungültige, fehlende oder wärmere Messwerte
setzen diese Abkühlphase zurück. Danach wählt der gespeicherte Modus erneut einen
zulässigen Betriebspunkt. Gesperrte Punkte werden auch bei Effizienz, maximaler
Hashrate und Tuning-Profilen ausgeschlossen. MANUAL verändert das vom Nutzer
gewählte Target nicht. Ohne zulässigen Punkt bleibt die GPU pausiert; die Auswahl
wird höchstens einmal pro Minute erneut versucht. Der strengere MAX_HASHRATE-
Startschutz bleibt bestehen. Messungen haben weiterhin die alleinige Kontrolle.

Ein späterer lokaler oder Node-Stopp, ein neuer manueller Stopp, ein Wechsel von
Modus/Coin/Miner oder ein aktiver Node-Plan hebt den Wiederanlauf auf. Die Absicht
ist flüchtig und wird bei einem Agent-Neustart nicht wiederhergestellt. Neue
Sperren enthalten Fehlerzeit, gemessene Temperatur und damalige Grenze. Bei
84/84 → 86 wird der Punkt wieder zulässig; bei gemessenen 87 °C und neuer Grenze
86 °C bleibt er gesperrt. Ein Absenken aktiviert die Sperre erneut. Alte Sperren
mit ausschließlich Zeitstempel werden bei ausdrücklicher Erhöhung einer bereits
gespeicherten GPU-Grenze mit dieser vorherigen Grenze ergänzt. Alle anderen
Stabilitäts- und Betriebsprüfungen gelten weiterhin. Laufzeittests auf echter
Hardware und Veröffentlichung stehen aus.
