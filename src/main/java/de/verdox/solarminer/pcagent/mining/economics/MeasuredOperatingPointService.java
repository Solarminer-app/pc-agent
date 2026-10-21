package de.verdox.solarminer.pcagent.mining.economics;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.lowlevel.GpuTuningService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Applies the exact measured settings, not merely their watt cap. Caller owns stop/start and rollback. */
@Service
public class MeasuredOperatingPointService {
    private final MinerFactory miners;
    private final LocalGpuPowerService power;
    private final GpuTuningService clocks;
    private final Map<String, CoinHoppingService.Candidate> applied = new ConcurrentHashMap<>();
    public MeasuredOperatingPointService(MinerFactory miners, LocalGpuPowerService power, GpuTuningService clocks) {
        this.miners = miners; this.power = power; this.clocks = clocks;
    }
    public void invalidate(String device) { applied.remove(device); }
    public CoinHoppingService.Candidate current(String device) { return applied.get(device); }
    public boolean matches(String device, CoinHoppingService.Candidate candidate) {
        var current = applied.get(device);
        return current == null ? candidate.tunedProfile() == null : current.pointKey().equals(candidate.pointKey());
    }
    public boolean needsRestart(String device, CoinHoppingService.Candidate candidate) {
        var previous = applied.get(device);
        return !matches(device, candidate) && (candidate.tunedProfile() != null || previous != null && previous.tunedProfile() != null);
    }
    public boolean apply(CoinHoppingService.Candidate candidate, LocalGpuPowerService.Gpu gpu) {
        var profile = candidate.tunedProfile();
        var previous = current(gpu.deviceId());
        if (profile == null) {
            if (previous != null && previous.tunedProfile() != null && !resetClocks(gpu)) return false;
        } else {
            if (!gpu.deviceId().equals(profile.deviceId()) || !candidate.coin().equals(profile.coinId())
                    || !Objects.equals(candidate.minerSoftwareId(), profile.softwareId())
                    || !Objects.equals(MinerFactory.minerVersionForSoftware(profile.softwareId()), profile.softwareVersion())
                    || !Objects.equals(gpu.model(), profile.model()) || !Objects.equals(gpu.vendor(), profile.vendor())
                    || candidate.limitWatts() != profile.powerLimitWatts()) return false;
            if (previous != null && previous.pointKey().equals(candidate.pointKey()))
                return power.setTotalPowerTarget(candidate.limitWatts(), List.of(gpu));
            if (!resetClocks(gpu)) return false;
            var miner = miners.gpuMiner(Coin.byIdOrNull(candidate.coin()), candidate.minerSoftwareId());
            boolean written;
            if ("NVIDIA".equalsIgnoreCase(gpu.vendor()) && miner instanceof MinerNativeTuning nativeTuning)
                written = nativeTuning.applyNativeTuning(gpu, profile);
            else written = (profile.memoryOffsetMhz() == null
                    ? clocks.apply(gpu.deviceId(), profile.coreMhz(), profile.coreMhz(), profile.memoryMhz(), profile.memoryMhz())
                    : clocks.apply(gpu.deviceId(), profile.coreMhz(), profile.coreMhz(), null, null,
                            profile.memoryOffsetMhz(), profile.memoryOffsetPstate())).applied();
            if (!written) return false;
        }
        if (!power.setTotalPowerTarget(candidate.limitWatts(), List.of(gpu))) return false;
        applied.put(gpu.deviceId(), candidate); return true;
    }
    public boolean restore(CoinHoppingService.Candidate previous, LocalGpuPowerService.Gpu gpu, int oldLimit) {
        if (previous != null && previous.tunedProfile() != null) {
            applied.remove(gpu.deviceId());
            return apply(previous, gpu);
        }
        // A failed write can leave clocks changed even though the applied cache still holds a raw point.
        boolean clocksRestored = resetClocks(gpu);
        boolean powerRestored = power.setTotalPowerTarget(oldLimit, List.of(gpu));
        if (clocksRestored && powerRestored && previous != null) applied.put(gpu.deviceId(), previous);
        else applied.remove(gpu.deviceId());
        return clocksRestored && powerRestored;
    }
    /** Only call after the physical worker is stopped: native clear may otherwise restart it. */
    private boolean resetClocks(LocalGpuPowerService.Gpu gpu) {
        boolean restored = true;
        for (Coin coin : Coin.gpuCoins()) for (var adapter : miners.gpuMinerAdapters(coin))
            if (adapter.miner() instanceof MinerNativeTuning nativeTuning)
                restored = nativeTuning.clearNativeTuning(gpu) && restored;
        return clocks.restoreDefaults(gpu.deviceId()).applied() && restored;
    }
}
