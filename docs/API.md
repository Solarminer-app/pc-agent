# PC-Agent API

The PC-Agent lives in its own Git repository (`https://github.com/Solarminer-app/pc-agent`),
split out of `Solar-Miner-Node/pc-agent` on 2026-10-08. The Node consumes the
agent purely over its LAN HTTP API (`:8084`); the contract below is the
Node-relevant record and is maintained here alongside the agent's own docs.

## PC Agent API (experimental)

The PC Agent is not part of the default Compose deployment. Its API and DTOs may change while the component remains work in progress.

The agent serves its local dashboard at `http://<agent-host>:8084/`; mining and hardware telemetry have separate pages. The telemetry page shows the CPU and detected GPU names, all reported sensor values and a component power sum. Total watts adds CPU package power to available GPU board readings; it is marked as partial when devices have no power reading. The active coin and external proxy host are stored under `./solarminer-agent/`. XMRig and SRBMiner-MULTI start only when their configured route matches the SolarMiner Stratum proxy and its API responds. Monero uses proxy port `3335`; Pearl uses `3334`. Existing XMRig configs with a direct pool are rejected on start. The former XMRig process-switching developer fee has been removed; fee routing belongs to the proxy. The SRBMiner JSON API supplies Pearl hashrate per managed GPU; GPU temperature remains in the separate hardware telemetry path. Like the rest of the local Node stack, the agent currently relies on the trusted LAN; do not expose port 8084 to the internet.

The API has two disjoint namespaces. `/api/agent/local/**` belongs exclusively to the same-origin PC-Agent dashboard. `/api/agent/external/**` belongs exclusively to the SolarMiner Node and returns HTTP 403 for every read and write while Node control is disabled. The former shared `/api/agent/**` routes are not compatibility aliases and cannot control the agent.

The Overview and each installed miner view show a gross daily earnings estimate from the current local hashrate. The PC-Agent obtains all price and network inputs for Monero, Pearl, Ravencoin and Ethereum Classic from `https://currency.solarminer.app/api/v1/public/mining-networks`; it no longer contacts individual explorers or price providers. The agent caches successful central snapshots for ten minutes, keeps the last successful values marked as stale when the central request fails and never treats missing hashrate or market data as zero earnings. Estimates are probabilistic and do not deduct pool fees, miner fees, stale shares or the SolarMiner developer fee.

The Mining page has an independent console for each agent-managed miner. `GET /api/agent/local/console/{monero|pearl}?offset=<byte-offset>` returns `{nextOffset,data,hasMore}`, where `data` is base64-encoded UTF-8 console bytes in chunks of at most 64 KiB. Its `/download` endpoint downloads the entire log. Output is appended without truncation to `./solarminer-agent/logs/` across miner and agent restarts; operators should manage disk usage and access to pool login details in those files.

The PC-Agent supports XMRig and SRBMiner concurrently. Local coin/GPU controls exist only below `/api/agent/local/**`; Node-wide pause, resume and power targets exist only below `/api/agent/external/**`. The persisted legacy `activeCoin` selection no longer stops another miner.

For an independent installation, build `./gradlew standaloneJar` in the PC-Agent repository. This builds the sibling `solarminer-stratum-proxy` repository and packages both runnable JARs plus Windows/Linux launchers; see `standalone/README.md`. Java 21 is required on the target PC. The launcher starts the proxy locally on loopback and fixes the agent's proxy host to `127.0.0.1`. Standalone Monero and Pearl mining are gated on an active, positive fee target from the fee backend. The bundled proxy refuses mining connections without a usable fee target, and the agent stops a running miner if the proxy or fee route disappears. The proxy's job routing performs the fee split.

The PC-Agent also exposes a host hardware snapshot for local integrations. It is sampled on request with a one-second cache and does not upload or persist readings. Linux reads available kernel `hwmon`, thermal and powercap/RAPL sensors. On Windows, the agent downloads LibreHardwareMonitor from its official upstream release and verifies the published SHA-256 digest. It configures the LHM JSON server on loopback (`127.0.0.1:8085`) and asks Windows for elevation when starting LHM, because its sensor driver requires administrator rights. The UI stays blocked until LHM responds; if it does not start, the user can retry from the UAC prompt flow. Missing or inaccessible sensor values are returned with `available: false` and `value: null`; JVM memory is labelled separately from physical host memory. Close a separately running LHM instance if it has no local JSON server enabled.

Example: `GET /api/agent/local/telemetry` returns `{"collectedAt":"2026-09-28T12:00:00Z","platform":"Linux","architecture":"amd64","metrics":{"cpu.temperature":{"value":54.2,"unit":"°C","source":"Linux hwmon/thermal","available":true,"directMeasurement":true},"cpu.package_power":{"value":72.1,"unit":"W","source":"Linux powercap/RAPL","available":true,"directMeasurement":true}},"sources":{"linux-kernel-sensors":"active; reads hwmon, thermal and powercap on demand"}}`. Dynamic hardware metrics use keys below `hwmon.*` on Linux and `hardware.*` on Windows. Power is in watts, temperatures in Celsius, memory/data in bytes, energy in joules, voltage in volts, current in amperes and fan speed in RPM.

Controller source: [`MiningController`](../src/main/java/de/verdox/solarminer/pcagent/controller/MiningController.java)

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/agent/external/identity` | Read-only Node discovery; remains available when external Node control is disabled. Every other external route is gated. |
| `GET` | `/api/agent/external/status` | Node-visible CPU/GPU worker statistics. |
| `GET` | `/api/agent/external/power-control` | Node-visible power range and applied target. |
| `GET` | `/api/agent/external/{telemetry|overview|proxy|earnings}` | Node read contracts. |
| `POST` | `/api/agent/external/{pause|resume}` | Pause or resume externally assigned workers. |
| `POST` | `/api/agent/external/power-target?watts=<watts>` | Apply the Node's PV-wide target. |
| `POST` | `/api/agent/external/{proxy|referral|pool-configuration}` | Node-owned routing configuration. |
| `POST` | `/api/agent/external/{monero|pearl}/configuration` | Node-owned miner configuration. |
| `GET` / `POST` | `/api/agent/local/**` | Same-origin dashboard APIs for local status, settings, mining, telemetry, benchmarks and diagnostics. |

Dynamic GPU-Power-Regelung wird nur angeboten, nachdem ein Same-Value-Schreibtest und der anschließende Readback erfolgreich waren. NVIDIA verwendet die stabile GPU-UUID und `nvidia-smi -pl`. AMD verwendet bevorzugt AMD-SMI mit UUID/BDF; unter Linux steht zusätzlich der `amdgpu`-hwmon-Power-Cap über den stabilen PCI-BDF zur Verfügung. Jeder Zielwert bleibt innerhalb der Treiber- und Nutzergrenzen. Der Agent ändert weder Spannung noch Takt. Vor dem ersten Eingriff persistiert er die aktuellen Limits für Rollback, reguläres Beenden und Wiederherstellung beim nächsten Start nach einem Prozessabbruch.
