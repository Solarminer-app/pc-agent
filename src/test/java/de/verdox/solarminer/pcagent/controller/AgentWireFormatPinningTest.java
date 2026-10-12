package de.verdox.solarminer.pcagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.DownloadState;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.dto.Pools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refactoring Phase 5, step 5a — wire-format pinning (PFLICHT before any controller move).
 * These DTO shapes are consumed three times over: the bundled operator UI (static/js),
 * Solar-Miner-Node's Java client (core/.../MinerAgentController.java, AgentEarningsSource.java,
 * MinerDiscoveryService.java) and the SSE channel payloads. Renaming or dropping a field here
 * breaks the Node build silently, because the Node defines its own copy of MinerStats.
 * Any intentional wire change must be additive and update this test deliberately.
 */
class AgentWireFormatPinningTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void minerStatsMatchesTheNodeSideRecordFieldForField() throws Exception {
        MinerStats stats = new MinerStats(new MinerStats.MinerIdentity("uid-1", "aa:bb:cc:dd:ee:ff", "pc-agent"),
                "PC-Agent", MinerStats.MinerStatus.MINING, 300, 60, 300, 600, 280, 12.5, 61.0,
                List.of(new Pools("stratum+tcp://pool:3333", "user", "x")),
                List.of(new MinerStats.Worker(MinerStats.MinerStatus.MINING, "RTX 3070", "kawpow", 4.2, 60.0,
                        120, 60, 120, 170, 110, List.of(), "GPU", "NVIDIA GeForce RTX 3070", "gpu-0",
                        42L, 3L, new MinerStats.PoolTelemetry(8.0, 12.0, 1L, 45L))));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(stats));
        // NOTE: `singleAlgorithmMined` is a de-facto wire field: the record's
        // getSingleAlgorithmMined() getter is serialized by Jackson. Nothing in this repo
        // consumes it; removal would be a deliberate wire change (Node tolerates it today
        // via lenient parsing). Pinned here so any removal is a conscious decision.
        assertFieldNames(json, Set.of("minerIdentity", "name", "miningStatus", "powerTargetWatts",
                "minPowerTarget", "defaultPowerTarget", "maxPowerTarget", "approximatedPowerUsageWatts",
                "terahashPerSecond", "temperatureCelsius", "pools", "workers", "singleAlgorithmMined"));
        assertFieldNames(json.path("minerIdentity"), Set.of("minerUID", "macAddress", "minerModel"));
        assertFieldNames(json.path("workers").get(0), Set.of("miningStatus", "workerDisplayName",
                "currentAlgorithm", "terahashPerSecond", "temperatureCelsius", "powerTargetWatts",
                "minPowerTarget", "defaultPowerTarget", "maxPowerTarget", "approximatedPowerUsageWatts",
                "pools", "hardwareType", "hardwareModel", "deviceId", "acceptedShares", "rejectedShares", "pool"));
        assertFieldNames(json.path("workers").get(0).path("pool"),
                Set.of("difficulty", "bestShareDifficulty", "staleShares", "latencyMs"));
        // The Node parses miningStatus as its own MinerStatus enum: exactly these four names.
        assertEquals("MINING", json.path("miningStatus").asText());
    }

    @Test
    void minerStatusEnumKeepsItsFourWireNames() {
        assertEquals(List.of("MINING", "STOPPED", "PAUSED", "ERROR"),
                List.of(MinerStats.MinerStatus.values()).stream().map(Enum::name).toList());
    }

    @Test
    void downloadStateWireNamesAreFrozen() {
        // The UI compares these strings; they are NOT the same set as enum name() forever —
        // pin wireName() explicitly so a future rename of the Java constant stays safe.
        Map<String, String> expected = Map.of(
                "PENDING", "PENDING", "CHECKING", "CHECKING", "DOWNLOADING", "DOWNLOADING",
                "READY", "READY", "FAILED", "FAILED", "UNSUPPORTED", "UNSUPPORTED",
                "BLOCKED_BY_ANTIVIRUS", "BLOCKED_BY_ANTIVIRUS");
        for (DownloadState state : DownloadState.values()) {
            assertEquals(expected.get(state.name()), state.wireName(), "wireName of " + state);
        }
        assertEquals(expected.keySet(), java.util.Arrays.stream(DownloadState.values())
                .map(DownloadState::name).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void powerStatusMatchesTheNodePowerControlContract() throws Exception {
        AgentPowerController.PowerStatus status = new AgentPowerController.PowerStatus(
                true, true, false, 60, 600, 300, 300, 280L, "measured", false,
                Map.of("cpu", true), List.of(), 0, 0, 0, 0, "applied", null, Map.of("*", "monero"));
        JsonNode json = mapper.readTree(mapper.writeValueAsString(status));
        assertFieldNames(json, Set.of("supportsDynamicPowerScaling", "dynamicPowerScalingEnabled",
                "externalControlEnabled", "minPowerWatts", "maxPowerWatts", "defaultPowerWatts",
                "currentTargetWatts", "currentUsageWatts", "usageMeasurement", "miningPaused",
                "workerExternalControl", "gpus", "requestedTargetWatts", "appliedTargetWatts",
                "appliedCpuTargetWatts", "appliedGpuTargetWatts", "targetApplicationStatus",
                "targetApplicationFailureReason", "workerCoins"));

        AgentPowerController.GpuStatus gpu = new AgentPowerController.GpuStatus("gpu-0", "NVIDIA", 0,
                "RTX 3070", 0, 350, 60, 140, 120, 110.0, "measured", true, null, false);
        JsonNode gpuJson = mapper.readTree(mapper.writeValueAsString(gpu));
        assertFieldNames(gpuJson, Set.of("deviceId", "vendor", "index", "model", "driverMinPowerLimitWatts",
                "driverMaxPowerLimitWatts", "userMinPowerLimitWatts", "userMaxPowerLimitWatts",
                "currentPowerLimitWatts", "currentUsageWatts", "usageMeasurement",
                "supportsDynamicPowerScaling", "regulationError", "externalControlEnabled"));

        AgentPowerController.Identity identity = new AgentPowerController.Identity("pc-agent", 2, "PC", null);
        assertFieldNames(mapper.readTree(mapper.writeValueAsString(identity)),
                Set.of("kind", "powerControlProtocolVersion", "model", "name"));
    }

    @Test
    void externalOverviewKeepsThePerCoinConfigurationSections() throws Exception {
        // Node MinerAgentController reads moneroConfiguration/pearlConfiguration with
        // poolUrl/wallet/worker (+devices on Pearl) — the /external/overview contract.
        MiningController.AgentOverview overview = new MiningController.AgentOverview(
                MinerStats.DEFAULT, "monero", "linux", "amd64", List.of(), List.of(), List.of(),
                new MiningController.ProxyOverview(null, false, "external", "random", "external", "", "v0", List.of()),
                new MiningController.MoneroConfiguration("stratum+tcp://proxy:3335;w;x", "wallet", "worker"),
                new de.verdox.solarminer.pcagent.miner.MinerConfig("pool", "proxy", "wallet", "worker", "NVIDIA:0"),
                new MiningController.DownloadReadiness("READY", "", 100, null, "/dir"),
                new MiningController.PearlReadiness(true, true, "READY", "", 100, null, "", false, true, List.of(), "/dir"),
                List.of(), new MiningController.ReferralOverview(null), List.of(), Map.of());

        JsonNode json = mapper.readTree(mapper.writeValueAsString(overview));
        assertFieldNames(json, Set.of("stats", "activeCoin", "platform", "architecture", "coins",
                "earnings", "gpus", "proxy", "moneroConfiguration", "pearlConfiguration", "monero",
                "pearl", "payoutDefaults", "referral", "fees", "gpuCoins"));
        assertFieldNames(json.path("moneroConfiguration"), Set.of("poolUrl", "wallet", "worker"));
        assertFieldNames(json.path("pearlConfiguration"), Set.of("poolUrl", "proxyUrl", "wallet", "worker", "devices"));
        assertFieldNames(json.path("monero"), Set.of("downloadStatus", "downloadDetail", "downloadProgress",
                "minerError", "installDirectory"));
    }

    private static void assertFieldNames(JsonNode object, Set<String> expected) {
        assertTrue(object.isObject(), "expected a JSON object, got " + object.getNodeType());
        Set<String> actual = new java.util.HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        assertEquals(expected, actual, "wire field set drifted");
    }
}
