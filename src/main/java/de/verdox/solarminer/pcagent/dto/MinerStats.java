package de.verdox.solarminer.pcagent.dto;

import java.util.List;
import java.util.stream.Collectors;

public record MinerStats(
        MinerIdentity minerIdentity, String name, MinerStatus miningStatus,
        long powerTargetWatts,
        long minPowerTarget,
        long defaultPowerTarget,
        long maxPowerTarget,
        long approximatedPowerUsageWatts,
        double terahashPerSecond, double temperatureCelsius,
        List<Pools> pools,
        List<Worker> workers
) {

    public MinerStats withName(String name) {
        return new MinerStats(minerIdentity, name, miningStatus, powerTargetWatts, minPowerTarget, defaultPowerTarget, maxPowerTarget, approximatedPowerUsageWatts, terahashPerSecond, temperatureCelsius, pools, workers);
    }

    public static final MinerStats DEFAULT = new MinerStats(
            new MinerIdentity("", "", ""),
            "-",
            MinerStatus.ERROR,
            0, 0, 0, 0, 0, 0.0, 0.0,
            List.of(),
            List.of()
    );

    public boolean minesOnlyOneAlgorithm() {
        return workers.isEmpty() || workers.stream().collect(Collectors.groupingBy(worker -> worker.currentAlgorithm)).size() <= 1;
    }

    public String getSingleAlgorithmMined() {
        return workers.stream().map(worker -> worker.currentAlgorithm).findAny().orElse("-");
    }

    public record Worker(
            MinerStatus miningStatus,
            String workerDisplayName,
            String currentAlgorithm,
            double terahashPerSecond,
            double temperatureCelsius,
            long powerTargetWatts,
            long minPowerTarget,
            long defaultPowerTarget,
            long maxPowerTarget,
            long approximatedPowerUsageWatts,
            List<Pools> pools,
            String hardwareType,
            String hardwareModel,
            String deviceId,
            Long acceptedShares,
            Long rejectedShares,
            PoolTelemetry pool
    ) {
    }

    /** Pool-side quality readings straight from the miner API; a null member means "not reported", never zero. */
    public record PoolTelemetry(Double difficulty, Double bestShareDifficulty, Long staleShares, Long latencyMs) {
        public static PoolTelemetry unavailable() { return new PoolTelemetry(null, null, null, null); }
    }

    public enum MinerStatus {
        MINING,
        STOPPED,
        PAUSED,
        ERROR
    }

    public record MinerIdentity(String minerUID, String macAddress, String minerModel) {

    }
}
