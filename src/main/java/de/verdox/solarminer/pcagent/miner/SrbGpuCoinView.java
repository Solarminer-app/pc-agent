package de.verdox.solarminer.pcagent.miner;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.pearl.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Coin-independent adapter view of one SRBMiner GPU coin. It only delegates to the public
 * per-coin API of {@link GpuCoinMinerService}; every coin-specific parameter, command and
 * wallet rule stays inside that service. The MinerFactory hands these views to orchestration.
 */
public final class SrbGpuCoinView implements GpuCoinMiner {
    private final GpuCoinMinerService service;
    private final LocalGpuPowerService power;
    private final Coin coin;

    public SrbGpuCoinView(GpuCoinMinerService service, LocalGpuPowerService power, Coin coin) {
        this.service = service;
        this.power = power;
        this.coin = coin;
    }

    private String key() { return coin.id(); }

    @Override public Coin coin() { return coin; }
    @Override public boolean configured() { return service.configuration(key()) != null; }
    @Override public boolean routeConfigured() {
        MinerConfig current = service.configuration(key());
        return current != null && service.routeMatches(key(), current.proxyUrl());
    }
    /** The overview counts a saved SRB configuration as configured, matching the shipped UI semantics. */
    @Override public boolean setupComplete() { return configured(); }
    @Override public boolean binaryAvailable() { return service.binaryAvailable(); }
    @Override public boolean running() { return service.running(key()); }
    @Override public String lastError() { return service.lastError(); }
    @Override public MinerStats.MinerStatus status() { return service.status(key()); }
    @Override public List<MinerStats.Worker> workerStats(List<LocalGpuPowerService.Gpu> cards) { return service.workerStats(key(), cards); }
    @Override public boolean startAll() { return service.start(key()); }
    @Override public boolean stopAll() { return service.stop(key()); }
    @Override public boolean pauseAll() { return service.pause(key()); }
    @Override public boolean setPowerCap(long watts) { return power.setTotalPowerTarget(watts, selectedGpus()); }
    @Override public boolean updateProxyRoute(String proxyUrl) throws IOException { return service.updateProxyRoute(key(), proxyUrl); }
    @Override public void appendBenchmarkEvent(String message) { service.appendConsoleEvent(key(), message); }
    @Override public List<LocalGpuPowerService.Gpu> selectedGpus() { return service.selected(key()); }
    @Override public List<LocalGpuPowerService.Gpu> eligibleGpus() { return service.eligible(key()); }
    @Override public List<GpuState> gpuStates(List<LocalGpuPowerService.Gpu> cards) { return service.gpuStates(key(), cards); }
    @Override public boolean startGpu(String vendor, int index) { return service.startGpu(key(), vendor, index); }
    @Override public boolean stopGpu(String vendor, int index) { return service.stopGpu(key(), vendor, index); }
    @Override public boolean pauseGpu(String vendor, int index) { return service.pauseGpu(key(), vendor, index); }
    @Override public boolean resumeGpu(String vendor, int index) { return service.resumeGpu(key(), vendor, index); }
    @Override public boolean startForBudget() { return service.startForBudget(key()); }
    @Override public boolean hasExternalMinerProcess() { return service.hasExternalSrbMiner(); }

    @Override public Set<String> runningGpuDeviceIds() {
        return gpuStates(power.discover()).stream().filter(GpuState::running)
                .map(state -> state.vendor() + ":" + state.index()).collect(Collectors.toUnmodifiableSet());
    }

    @Override public Set<String> manuallyPausedGpuKeys() {
        return gpuStates(power.discover()).stream().filter(GpuState::manuallyPaused)
                .map(state -> state.vendor() + ":" + state.index()).collect(Collectors.toUnmodifiableSet());
    }

    @Override public boolean restoreWorkerState(Set<String> pausedBefore, Set<String> runningBefore) {
        boolean success = service.stop(key());
        if (pausedBefore != null) for (String gpuKey : pausedBefore) {
            String[] parts = gpuKey.split(":");
            success = service.pauseGpu(key(), parts[0], Integer.parseInt(parts[1])) && success;
        }
        if (runningBefore != null) for (String deviceId : runningBefore) {
            power.discover().stream().filter(gpu -> gpu.deviceId().equals(deviceId)).findFirst().ifPresent(gpu ->
                    service.resumeGpu(key(), gpu.vendor(), gpu.index()));
        }
        return success;
    }

    @Override public void setSweepOverride(MinerConfig config) { service.setSweepOverride(key(), config); }
    @Override public void clearSweepOverride() { service.clearSweepOverride(key()); }
    @Override public MinerConfig configuration() { return service.configuration(key()); }
    @Override public void applyConfig(MinerConfig config) throws IOException { service.configure(key(), config); }
    @Override public void validateConfig(MinerConfig config) { service.validate(key(), config); }
    @Override public String reportedAlgorithm() { return coin.displayAlgorithm(); }
}
