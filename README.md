# SolarMiner PC-Agent

Standalone CPU/GPU mining agent for SolarMiner. Runs the local mining stack on a
PC: manages the Stratum proxy as a child process (downloaded from
[Solarminer-app/solarminer-stratum-proxy](https://github.com/Solarminer-app/solarminer-stratum-proxy)
releases at runtime), installs and supervises miners (XMRig, SRBMiner-MULTI),
reads hardware sensors, and serves a local operator UI at `http://127.0.0.1:8084/`.

This repository was split out of `Solar-Miner-Node/pc-agent` on 2026-10-08. The
SolarMiner Node discovers and controls the agent purely over its LAN HTTP API
(`:8084`, identity route `GET /api/agent/external/identity`); there is no
compile-time coupling in either direction.

## Build

Requires JDK 21.

```bash
./gradlew test standaloneJar
```

Output: `build/distributions/solarminer-pc-agent-standalone.jar`. Run it with:

```bash
java -jar build/distributions/solarminer-pc-agent-standalone.jar --solarminer.agent.standalone=true
```

For development, `./gradlew bootRun` starts Spring Boot directly.

See [standalone/README.md](standalone/README.md) for the Windows launcher and
release packaging, and [standalone/DOCKER.md](standalone/DOCKER.md) for the
Docker image (`verdox/solar-miner-pc-agent`).

## Releases

Push a `pc-agent-v<version>` tag matching `pcAgentVersion` in
`gradle.properties`. The `release.yml` workflow runs tests, verifies that no
Stratum proxy classes are bundled, publishes the Docker image, and creates the
GitHub release with the standalone JAR, SHA-256 and Windows launchers.

## Working here

- [Wiki README](docs/agent-wiki/README.md) — start with
  [pc-agent.md](docs/agent-wiki/pc-agent.md) (ownership map).
- Adding a miner to an existing coin: [MINER-INTEGRATION-GUIDE.md](MINER-INTEGRATION-GUIDE.md).
- Cross-repo contracts (fee routing, proxy discovery, currency service) live in
  the workspace docs: `../AGENTS.md`, `../admin-portal/docs/encyclopedia/`,
  `../NEW-MINING-COIN-GUIDE.md`, `../PEARL-INTEGRATION.md`.
