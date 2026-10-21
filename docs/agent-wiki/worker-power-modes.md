# Local worker operating modes (2026-10-16)

The Worker dialog stores `workerPowerModes` in `control-settings.json`, keyed by physical worker ID. Missing entries mean `MANUAL`. `POST /api/agent/local/workers/{deviceId}/power-mode` validates a requested mode against local evidence before saving it and uses the local run lock to exclude concurrent measurements/control plans; `GET /api/agent/local/workers` reports each worker's mode.

| Mode | Local decision |
| --- | --- |
| `MANUAL` | GPU starts with the saved user power cap. CPU retains its existing manual thread target. |
| `EFFICIENCY` | Among stable sweep steps and benchmark/ordinary measurements with at least 12 samples, choose the best hashrate per measured watt for the assigned coin. |
| `MAX_HASHRATE` | GPU only. Choose the highest hashrate from stable, temperature-measured sweep steps for the assigned coin within the configured GPU watt bounds. It does not choose a different coin. |
| `REVENUE_PER_KWH` | Keep the assigned coin and choose its highest measured gross USD/kWh coin/miner/power point from fresh cached market data. The selected miner software and cap are applied before a local Worker-page start. |
| `COIN_REVENUE_PER_KWH` | GPU only. Select the highest measured gross USD/kWh coin/miner/power point before a local Worker-page start, updating the worker's coin and miner assignment as needed. While mining, re-evaluate measured route-ready pairs every minute and switch only with at least 5% forecast improvement and a 30-minute in-process cooldown. The existing single-GPU Coin Hopping automation yields that GPU while a Worker mode is active. |
| `PROFIT_PER_DAY` | GPU only. Keep the assigned coin and miner; select its measured power point with the highest estimated gross USD/day minus measured 24-hour electricity cost. Fees are excluded. |
| `COIN_PROFIT_PER_DAY` | GPU only. Select the measured route-ready coin/miner/power point with the highest estimated gross USD/day minus measured 24-hour electricity cost. Fees are excluded. Re-evaluate every minute; switch only for a higher estimate, using the shared 5% positive-profit improvement gate and 30-minute in-process cooldown. |

Automatic modes require a writable GPU cap (for GPUs), a known target and positive measured hashrate/power. A complete performance sweep is recommended because it provides multiple stable points; one ordinary benchmark or mining measurement may be sufficient when it includes a target. Unsupported or unmeasured modes reject activation and retain the old setting. CPU automatic modes need benchmark/ordinary measurements at positive thread targets; CPU coin switching is unavailable because the agent has only one CPU coin. The daily-profit modes are GPU-only because their selection currently consumes measured GPU power candidates.

Ordinary CPU and GPU mining now adds a local profile after twelve valid five-second readings at the same device/coin/target. Changing the target starts a new sample window; the profile store keeps one latest measured point per target and persists it under `./solarminer-agent/`. A running GPU in `EFFICIENCY` or fixed-coin `REVENUE_PER_KWH` mode checks those updated points every minute. It changes the cap only when the selected target has measured at least 5% better hashes per watt than the current measured target, with a 30-minute cooldown and no active benchmark/sweep. The current coin and live GPU occupancy are checked again before writing. `COIN_REVENUE_PER_KWH` retains its separate coin-switch policy. `MAX_HASHRATE` still requires temperature-validated sweep steps, because ordinary mining profiles do not prove the peak temperature of a new target. CPU automatic modes use newly collected points on the next start; live CPU thread retuning is not claimed.

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

For a locally started `COIN_REVENUE_PER_KWH` worker, the one-minute scheduler now retries the best measured start if the worker unexpectedly stops, including during the 30-minute switch cooldown. Explicit Worker, coin, global, LAN peer and Node pause actions clear that in-memory start intent. It is deliberately volatile across Agent restarts. Every automatic coin stop now carries a bilingual coin-switch reason into the miner console, and switch, failed start and failed restoration details are logged by the Agent. A failed start or missing candidate still leaves the worker stopped and requires investigation of the reported error; no claim of continuous mining is made without live miner, pool and driver verification.


## 2026-10-21 selection and temperature update / Auswahl und Temperatur

New GPU profiles use the two economic goals plus independent coin/software
permissions. Legacy API mode names remain compatible. See the current
[benchmark workflow](benchmark-workflow.md) for scope, safety and verification.

Neue GPU-Profile nutzen die zwei wirtschaftlichen Ziele und getrennte Rechte
für Coin-/Softwarewechsel. Alte API-Modi bleiben kompatibel. Der aktuelle
[Benchmark-Ablauf](benchmark-workflow.md) beschreibt Umfang, Sicherheit und Nachweise.
