package de.verdox.solarminer.pcagent.mining.measurement;

/** Explicit evidence in fixtures that represent newly validated results; legacy fixtures omit it. */
public final class ThermalTestEvidence {
    private ThermalTestEvidence() { }
    public static ThermalStabilityTracker.Evidence settled(double temperature) {
        return new ThermalStabilityTracker.Evidence(1, 180, 90, temperature, temperature, 0);
    }
    public static java.util.function.LongSupplier clock() {
        var now = new java.util.concurrent.atomic.AtomicLong();
        return () -> now.getAndAdd(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
    }
    public static GpuEfficiencyStore.StepResult step(int limit, Double rate, Double watts, Double temperature,
                                                      boolean stable, String note) {
        return step(limit, rate, watts, temperature, stable, note, null, null);
    }
    public static GpuEfficiencyStore.StepResult step(int limit, Double rate, Double watts, Double temperature,
                                                      boolean stable, String note, Integer core, Integer memory) {
        return new GpuEfficiencyStore.StepResult(limit, rate, watts, temperature, stable, note, core, memory,
                stable && temperature != null ? settled(temperature) : null);
    }
    public static AutoTuningProfileStore.Profile profile(AutoTuningProfileStore.Profile row) {
        return new AutoTuningProfileStore.Profile(row.deviceId(), row.model(), row.vendor(), row.coinId(), row.algorithm(),
                row.softwareId(), row.softwareVersion(), row.coreMhz(), row.memoryMhz(), row.powerLimitWatts(),
                row.hashrateHs(), row.measuredWatts(), row.stableSamples(), row.efficiencyHashesPerWatt(), row.calibratedAt(),
                row.memoryOffsetMhz(), row.memoryOffsetPstate(), settled(60));
    }
    public static AutoTuningProfileStore.Measurement measurement(AutoTuningProfileStore.Measurement row) {
        return new AutoTuningProfileStore.Measurement(row.runId(), row.at(), row.deviceId(), row.model(), row.vendor(), row.coinId(),
                row.algorithm(), row.softwareId(), row.softwareVersion(), row.coreMhz(), row.memoryMhz(), row.powerLimitWatts(), row.type(),
                row.hashrateHs(), row.measuredWatts(), row.temperatureC(), row.observedCoreMhz(), row.observedMemoryMhz(),
                row.validSamples(), row.acceptedShares(), row.rejectedShares(), row.stable(), row.reason(), row.memoryOffsetMhz(),
                row.memoryOffsetPstate(), settled(row.temperatureC() == null ? 60 : row.temperatureC()));
    }
}
