package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkSessionServiceTest {
    @Test
    void installedRunKeepsAStartingGpuAsExpectedWorkerUntilStartupGraceExpires() {
        MinerStats.Worker startingGpu = worker(MinerStats.MinerStatus.PAUSED, "GPU", "gpu-1");

        assertEquals(List.of(startingGpu), BenchmarkSessionService.phaseWorkers(List.of(startingGpu), "pearl", false));
        assertTrue(BenchmarkSessionService.phaseWorkers(List.of(startingGpu), "pearl", true).isEmpty());
        assertFalse(BenchmarkSessionService.workerUnavailable("gpu-1|PearlHash", startingGpu, Set.of(), Instant.now().plusSeconds(1)));
        assertTrue(BenchmarkSessionService.workerUnavailable("gpu-1|PearlHash", startingGpu, Set.of(), Instant.now().minusSeconds(1)));
    }

    @Test
    void startupAndLossRulesApplyEquallyToCpuAndGpuWorkers() {
        MinerStats.Worker startingCpu = worker(MinerStats.MinerStatus.PAUSED, "CPU", "cpu");
        MinerStats.Worker runningGpu = worker(MinerStats.MinerStatus.MINING, "GPU", "gpu-1");
        MinerStats.Worker failedCpu = worker(MinerStats.MinerStatus.ERROR, "CPU", "cpu");

        assertEquals(List.of(startingCpu), BenchmarkSessionService.phaseWorkers(List.of(startingCpu, runningGpu), "monero", false));
        assertEquals(List.of(runningGpu), BenchmarkSessionService.phaseWorkers(List.of(startingCpu, runningGpu), "pearl", true));
        assertFalse(BenchmarkSessionService.workerUnavailable("cpu|RandomX", startingCpu, Set.of(), Instant.now().plusSeconds(1)));
        assertTrue(BenchmarkSessionService.workerUnavailable("cpu|RandomX", failedCpu, Set.of(), Instant.now().plusSeconds(1)));
        assertTrue(BenchmarkSessionService.workerUnavailable("gpu-1|PearlHash", worker(MinerStats.MinerStatus.PAUSED, "GPU", "gpu-1"), Set.of("gpu-1|PearlHash"), Instant.now().plusSeconds(1)));
    }

    private static MinerStats.Worker worker(MinerStats.MinerStatus status, String hardwareType, String deviceId) {
        String algorithm = "CPU".equals(hardwareType) ? "RandomX" : "PearlHash";
        return new MinerStats.Worker(status, deviceId, algorithm, 0, 0,
                0, 0, 0, 0, 0, List.of(), hardwareType, "test", deviceId, null, null, MinerStats.PoolTelemetry.unavailable());
    }
}
