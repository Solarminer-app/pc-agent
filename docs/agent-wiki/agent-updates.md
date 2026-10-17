# PC-Agent update contract

The dashboard update panel uses local `GET /api/agent/local/update`,
`POST /api/agent/local/update/check`, and `POST /api/agent/local/update/start`.
The local write filter still requires a same-origin browser request. Starting
an executable update additionally requires the existing same-origin dashboard
write check, an explicitly supervised native launcher, and a newer release in
the install's own channel. This permits an operator using the dashboard over
the trusted LAN to start the update; the request still comes from the agent's
own UI and the executable replacement still runs on the agent host.
The frontend translates phase/error keys through the closed DE/EN catalog.

Native release launchers set `SOLARMINER_AGENT_CHANNEL`,
`SOLARMINER_AGENT_RELEASE_TAG`, and `SOLARMINER_AGENT_UPDATE_SUPERVISED=1`.
The Agent queries GitHub stable `/releases/latest` or published prereleases
whose tags match `pc-agent-beta-<12 hex>`. The selected release supplies both
the standalone JAR and its SHA-256 asset. The Agent stages the bounded
download under `.update/`, verifies the hash, writes an update request, then
shuts down Spring and exits with code 42. Windows PowerShell and the Linux
installer supervise that process, recheck the staged hash and channel,
replace the JAR, and relaunch. An immediate nonzero exit within 60 seconds
restores the previous JAR. The browser polls across the restart and confirms
the running release tag. A later failure is an ordinary runtime failure, not
an automatic rollback trigger.

The Dockerfile embeds `SOLARMINER_AGENT_DEPLOYMENT=docker`, channel, and release
tag from the corresponding workflow. Docker images are never modified from
inside the container. Compose uses a persistent `/data` bind mount, and the
dashboard gives the operator `pull` and `up -d` commands for the host. The
installer writes `.env` with `PC_AGENT_IMAGE` and `PC_AGENT_GPU`; the latter
passes the selected overlay to the Agent for the instruction text. The command
also sets `PC_AGENT_IMAGE` explicitly, so older Beta installations without
`.env` cannot silently pull the Stable image. Custom Compose installations
must adapt the file names and overlays.

Verification on 2026-10-17: `compileJava`, `standaloneJar`, focused
`AgentUpdateServiceTest` (Beta selection) and `WindowsLauncherLifecycleTest`
plus `RestControllerArchitectureTest` passed on JDK 21. The full suite ran
241 tests with one previously documented, unrelated `QuanPoolStatsTest`
failure (`resolvesEveryConfiguredNvidiaWorkerWithoutAssumingContiguousIndices`).
`check-i18n.cjs` checked both locales; literal scan reported
zero browser literals. Both shell installers passed `sh -n`, and the Beta/NVIDIA
Compose combination passed `docker compose config --quiet`. Live Windows,
Linux, Docker-pull and browser restart trials have not been run on target hosts.
