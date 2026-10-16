# Local worker operating modes (2026-10-16)

The Worker dialog stores `workerPowerModes` in `control-settings.json`, keyed by physical worker ID. Missing entries mean `MANUAL`. `POST /api/agent/local/workers/{deviceId}/power-mode` validates a requested mode against local evidence before saving it and uses the local run lock to exclude concurrent measurements/control plans; `GET /api/agent/local/workers` reports each worker's mode.

| Mode | Local decision |
| --- | --- |
| `MANUAL` | GPU starts with the saved user power cap. CPU retains its existing manual thread target. |
| `EFFICIENCY` | Among stable sweep steps and benchmark/ordinary measurements with at least 12 samples, choose the best hashrate per measured watt for the assigned coin. |
| `REVENUE_PER_KWH` | Choose the same point for the assigned coin. For a fixed coin, multiplying each hashrate by the same current fiat quote leaves the ranking unchanged. The configured grid tariff does not affect this gross revenue/kWh ranking; it is a separate break-even threshold. |
| `COIN_REVENUE_PER_KWH` | GPU only. Start at a measured point for the assigned coin, then evaluate measured, route-ready coin/power pairs using fresh cached market data. Switch only while mining, with at least 5% forecast USD/kWh improvement and a 30-minute in-process cooldown. The existing single-GPU Coin Hopping automation yields that GPU while a Worker mode is active. |

Automatic modes require a writable GPU cap (for GPUs), a known target and positive measured hashrate/power. A complete performance sweep is recommended because it provides multiple stable points; one ordinary benchmark or mining measurement may be sufficient when it includes a target. Unsupported or unmeasured modes reject activation and retain the old setting. CPU automatic modes need benchmark/ordinary measurements at positive thread targets; CPU coin switching is unavailable because the agent has only one CPU coin.

Before a GPU miner starts, `WorkerPowerModeService` writes and verifies the selected cap. `LocalGpuPowerService.enforceConfiguredPowerLimit` leaves that cap in place for automatic workers; switching back to manual reapplies the saved user cap. CPU starts set the measured thread target before XMRig starts. GPU writes and miner behavior still require live driver/OS validation.

`GET /api/agent/external/power-control` reports dynamic scaling off while any assigned worker has an automatic mode. External positive/zero targets become start/pause requests; increment/decrement are rejected. C10 capabilities add `powerMode`; assignments and target changes in Node economic plans are rejected for locally managed workers, while `activeWorkerIds` remains available for the existing locally consented `AUTO` workers. The Node consumes `powerMode` to omit those assignments. Existing clients without this additive field see `MANUAL`.

The per-worker coin/revenue scheduler currently keeps its 30-minute cooldown in process memory. It does not add an independent payout ledger; forecast USD/kWh is gross and excludes pool fees, rejected shares and actual rewards. A restart may reset its cooldown. Physical switching, thermal stability and pool credit verification remain rollout gates.
