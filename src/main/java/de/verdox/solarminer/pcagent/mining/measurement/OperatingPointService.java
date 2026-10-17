package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.MiningService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Learns local CPU and GPU operating points during ordinary mining, without starting a benchmark. */
@Service
public class OperatingPointService {
    private static final int SAMPLES = 12;
    private static final Duration MAX_GAP = Duration.ofSeconds(15);
    private final MiningService mining;
    private final LocalRunLock runLock;
    private final MiningPerformanceProfileStore profiles;
    private de.verdox.solarminer.pcagent.miner.MinerFactory miners;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMinerFactory(de.verdox.solarminer.pcagent.miner.MinerFactory miners) { this.miners = miners; }
    private final Map<String, Window> windows = new HashMap<>();

    public OperatingPointService(MiningService mining, LocalRunLock runLock, MiningPerformanceProfileStore profiles) {
        this.mining = mining;
        this.runLock = runLock;
        this.profiles = profiles;
    }

    @Scheduled(fixedDelayString = "${solarminer.agent.operating-point-sample-ms:5000}",
            initialDelayString = "${solarminer.agent.operating-point-sample-ms:5000}")
    public void collect() {
        if (runLock.busy()) {
            synchronized (this) { windows.clear(); }
            return;
        }
        try { accept(mining.getWorkerStats(), Instant.now()); }
        catch (RuntimeException ignored) { /* A failed sample never disturbs active mining. */ }
    }

    synchronized void accept(List<MinerStats.Worker> workers, Instant now) {
        Set<String> seen = new HashSet<>();
        for (MinerStats.Worker worker : workers) {
            Coin coin = Coin.byAlgorithm(worker.currentAlgorithm()).orElse(null);
            if (coin == null || !(coin.isGpu() && "GPU".equalsIgnoreCase(worker.hardwareType())
                    || coin.isCpu() && "CPU".equalsIgnoreCase(worker.hardwareType()))
                    || worker.deviceId() == null || worker.deviceId().isBlank()
                    || worker.powerTargetWatts() <= 0 || worker.powerTargetWatts() > Integer.MAX_VALUE
                    || worker.miningStatus() != MinerStats.MinerStatus.MINING
                    || !SampleCollector.hasValidHashrate(worker)
                    || worker.approximatedPowerUsageWatts() <= 0
                    || (Double.isFinite(worker.temperatureCelsius()) && worker.temperatureCelsius() > 90)) continue;
            Long accepted = worker.acceptedShares(), rejected = worker.rejectedShares();
            if (accepted != null && rejected != null && rejected > 0
                    && rejected / (double) Math.max(1, accepted + rejected) > 0.02) continue;
            String software = miners == null ? null : miners.minerSoftwareForWorker(coin, worker.deviceId());
            String key = worker.deviceId() + "|" + coin.id() + "|" + worker.powerTargetWatts() + "|" + software;
            seen.add(key);
            Window window = windows.computeIfAbsent(key, ignored -> new Window());
            if (window.lastAt != null && Duration.between(window.lastAt, now).compareTo(MAX_GAP) > 0) window.samples.clear();
            window.lastAt = now;
            window.samples.add(worker);
            if (window.samples.size() < SAMPLES) continue;
            double[] rates = window.samples.stream().mapToDouble(value -> value.terahashPerSecond() * 1_000_000_000_000d).toArray();
            double median = SampleCollector.median(rates);
            long watts = Math.round(window.samples.stream().mapToLong(MinerStats.Worker::approximatedPowerUsageWatts).average().orElse(0));
            if (watts > 0 && median > 0 && java.util.Arrays.stream(rates).allMatch(rate -> rate >= median * 0.8 && rate <= median * 1.2)) {
                profiles.save(new MiningPerformanceProfileStore.Profile(worker.deviceId(), worker.hardwareType(),
                        worker.hardwareModel(), coin.id(), coin.algorithm(), median, watts, SAMPLES, now, "LIVE",
                        (int) worker.powerTargetWatts(), software));
            }
            window.samples.clear();
        }
        windows.keySet().removeIf(key -> !seen.contains(key));
    }

    private static final class Window {
        final List<MinerStats.Worker> samples = new ArrayList<>(SAMPLES);
        Instant lastAt;
    }
}
