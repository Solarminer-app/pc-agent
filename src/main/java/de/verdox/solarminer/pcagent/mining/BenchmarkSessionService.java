package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.coin.BenchmarkMode;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.WorkerIds;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.miner.CoinMiner;
import de.verdox.solarminer.pcagent.miner.GpuCoinMiner;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Sample-driven local benchmark; full runs restore the miners that were active beforehand. */
@Service
public class BenchmarkSessionService {
    /** Samples are two seconds apart; a median of twelve points ignores startup noise. */
    public static final int REQUIRED_SAMPLES_PER_WORKER = 12;
    private static final long SAMPLE_INTERVAL_MILLIS = 2_000;
    /** Pearl's health monitor permits 90 seconds for its first pool job; leave a small scheduling margin. */
    static final Duration WORKER_STARTUP_GRACE = Duration.ofSeconds(95);
    /** LIVE phase label: measures whatever is currently mining, without a coin identity. */
    static final String PHASE_LIVE = "live";
    private final MiningService mining;
    private final MinerFactory miners;
    private final BenchmarkSharingService sharing;
    private final MinerConsoleService consoles;
    private final LocalRunLock lock;
    private final MiningPerformanceProfileStore performanceProfiles;
    private final WorkerAssignmentService assignments;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("pc-agent-benchmark").daemon(true).unstarted(r));
    private volatile Session session = Session.idle();
    private volatile boolean cancel;

    public BenchmarkSessionService(MiningService mining, MinerFactory miners,
                                   BenchmarkSharingService sharing, MinerConsoleService consoles, LocalRunLock lock,
                                   MiningPerformanceProfileStore performanceProfiles, WorkerAssignmentService assignments) {
        this.mining = mining; this.miners = miners; this.sharing = sharing; this.consoles = consoles; this.lock = lock;
        this.performanceProfiles = performanceProfiles;
        this.assignments = assignments;
    }

    public synchronized Session start(String mode) {
        BenchmarkMode parsed = BenchmarkMode.from(mode).orElseThrow(() -> new IllegalArgumentException("Unknown benchmark mode"));
        if (session.running()) throw new IllegalStateException("A benchmark is already running");
        List<String> setupSkipped = new ArrayList<>();
        if (parsed == BenchmarkMode.INSTALLED) {
            assignments.prepareBenchmarkDefaults().forEach((coin, reason) -> {
                if (reason != null && !reason.isBlank()) setupSkipped.add(coin + " (" + reason + ")");
            });
        }
        List<String> phases = parsed == BenchmarkMode.INSTALLED ? installedPhases() : List.of(PHASE_LIVE);
        if (phases.isEmpty()) {
            String detail = setupSkipped.isEmpty()
                    ? "Install and configure at least one miner before running this benchmark"
                    : "Benchmark cannot start: " + String.join("; ", setupSkipped);
            throw new IllegalStateException(detail);
        }
        if (!lock.tryBegin("benchmark")) throw new IllegalStateException("Ein anderer Messlauf (Effizienz-Sweep) läuft gerade; der Benchmark wartet, bis er beendet ist");
        cancel = false;
        Instant start = Instant.now();
        int phaseCount = phases.size();
        session = new Session(true, parsed.wireName(), "Preparing", start, null, null, 0, phaseCount, List.of(), null);
        executor.submit(() -> run(parsed, phases, setupSkipped));
        return session;
    }

    public Session status() {
        Session value = session;
        return value;
    }
    public synchronized Session cancel() { cancel = true; return status(); }

    /** Serializes Node controls with measurement runs so a checked command cannot race a new run. */
    public synchronized <T> T withExternalControl(Supplier<T> command) {
        if (session.running() || lock.busy()) throw new BenchmarkRunningException();
        return command.get();
    }

    private List<String> installedPhases() {
        List<String> phases = new ArrayList<>();
        if (miners.miner(Coin.MONERO) instanceof de.verdox.solarminer.pcagent.miner.CpuMiner cpu && cpu.readyForStart())
            phases.add(Coin.MONERO.id());
        phases.addAll(assignments.benchmarkGpuPhases());
        return List.copyOf(phases);
    }

    private void run(BenchmarkMode mode, List<String> phases, List<String> setupSkipped) {
        boolean installed = mode == BenchmarkMode.INSTALLED;
        boolean externalMiner = Coin.miningCoins().stream().anyMatch(coin -> miners.miner(coin).hasExternalMinerProcess());
        if (installed && externalMiner) {
            Session old = session;
            session = new Session(false, mode.wireName(), "Sequential benchmark skipped: externally started miners cannot be safely paused and restored",
                    old.startedAt(), Instant.now(), null, 0, old.phaseCount(), List.of(), null);
            lock.end();
            return;
        }
        List<MinerStats.Worker> before = mining.getWorkerStats();
        boolean cpuPausedBefore = mining.cpuManuallyPaused();
        boolean xmrWasMining = before.stream().anyMatch(w -> isMining(w) && "CPU".equalsIgnoreCase(w.hardwareType()));
        // Do not derive this snapshot from visible worker status: SRBMiner deliberately reports PAUSED
        // while it is connecting to the pool, although its managed process is already running.
        GpuCoinMiner pearl = miners.gpuMiner(Coin.PEARL);
        Set<String> pearlWasMining = pearl.runningGpuDeviceIds();
        Set<String> pearlPausedBefore = pearl.manuallyPausedGpuKeys();
        List<String> gpuCoinsWasMining = mining.runningGpuCoins();
        Map<String, List<MinerStats.Worker>> observations = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>(setupSkipped);
        int index = 0;
        String error = null;
        try {
            for (String skippedSetup : setupSkipped)
                benchmarkLog(Coin.PEARL.id(), "Benchmark setup skipped: " + skippedSetup);
            if (installed) {
                update(mode, "Pausing current miners", 0, phases.size(), observations);
                if (xmrWasMining) pauseForBenchmark(Coin.MONERO.id(), "capturing the pre-benchmark state");
                if (!pearlWasMining.isEmpty()) pauseForBenchmark(Coin.PEARL.id(), "capturing the pre-benchmark state");
                for (String coin : gpuCoinsWasMining) pauseForBenchmark(coin, "capturing the pre-benchmark state");
            }
            for (String phase : phases) {
                if (cancel) break;
                index++;
                if (installed) {
                    if (!mining.resumeMining(phase)) {
                        String detail = mining.lastStartError(phase);
                        String reason = detail == null || detail.isBlank() ? "Miner did not start; check its console" : detail;
                        skipped.add(phase + " (" + reason + ")");
                        benchmarkLog(phase, "Benchmark start failed: " + reason);
                        continue;
                    }
                }
                // In INSTALLED mode a just-started miner must be a candidate before its first API/pool
                // health sample. LIVE intentionally keeps its existing "only currently mining" semantics.
                List<MinerStats.Worker> candidates = phaseWorkers(phase, mode == BenchmarkMode.LIVE);
                Set<String> expected = candidates.stream().map(BenchmarkSessionService::key).collect(java.util.stream.Collectors.toSet());
                if (expected.isEmpty()) {
                    String reason = mode == BenchmarkMode.LIVE
                            ? " (no active workers)" : " (no configured workers detected)";
                    skipped.add(phase + reason);
                    benchmarkLog(phase, "Phase skipped:" + reason);
                    if (installed) {
                        benchmarkLog(phase, "Benchmark pauses miner after skipped phase.");
                        pauseForBenchmark(phase, "skipped phase");
                    }
                    continue;
                }
                benchmarkLog(phase, "Measurement phase started; expected workers: " + String.join(", ", expected)
                        + (installed ? "; waiting up to 95 s for the first active status." : "."));
                Set<String> unavailable = new HashSet<>();
                Set<String> observedMining = new HashSet<>();
                Instant startupDeadline = Instant.now().plus(WORKER_STARTUP_GRACE);
                update(mode, phaseLabel(phase) + " · 0/" + (expected.size() * REQUIRED_SAMPLES_PER_WORKER) + " Messpunkte",
                        index, phases.size(), observations);
                while (!cancel) {
                    List<MinerStats.Worker> current = phaseWorkers(phase, false);
                    Map<String, MinerStats.Worker> currentByKey = current.stream()
                            .collect(java.util.stream.Collectors.toMap(BenchmarkSessionService::key, worker -> worker, (first, ignored) -> first));
                    for (MinerStats.Worker worker : current.stream().filter(BenchmarkSessionService::isMining).toList()) {
                        String workerKey = key(worker);
                        if (!expected.contains(workerKey)) continue;
                        observedMining.add(workerKey);
                        if (!hasValidHashrate(worker)) continue;
                        List<MinerStats.Worker> samples = observations.computeIfAbsent(workerKey, ignored -> new ArrayList<>());
                        if (samples.size() < REQUIRED_SAMPLES_PER_WORKER) {
                            samples.add(worker);
                        }
                    }
                    List<String> lost = expected.stream()
                            .filter(k -> observations.getOrDefault(k, List.of()).size() < REQUIRED_SAMPLES_PER_WORKER)
                            .filter(k -> !unavailable.contains(k))
                            .filter(k -> workerUnavailable(k, currentByKey.get(k), observedMining, startupDeadline))
                            .toList();
                    unavailable.addAll(lost);
                    if (!lost.isEmpty()) {
                        String reason = "Worker did not start or stopped: " + String.join(", ", lost);
                        skipped.add(phase + " (" + reason + ")");
                        benchmarkLog(phase, reason);
                    }
                    boolean complete = expected.stream().allMatch(k -> unavailable.contains(k)
                            || observations.getOrDefault(k, List.of()).size() >= REQUIRED_SAMPLES_PER_WORKER);
                    if (complete) break;
                    int collected = expected.stream().mapToInt(k -> observations.getOrDefault(k, List.of()).size()).sum();
                    update(mode, phaseLabel(phase) + " · " + collected + "/" + (expected.size() * REQUIRED_SAMPLES_PER_WORKER) + " Messpunkte",
                            index, phases.size(), observations);
                    Thread.sleep(SAMPLE_INTERVAL_MILLIS);
                }
                int collected = expected.stream().mapToInt(k -> observations.getOrDefault(k, List.of()).size()).sum();
                benchmarkLog(phase, "Measurement phase complete: " + collected + "/" + (expected.size() * REQUIRED_SAMPLES_PER_WORKER)
                        + " valid samples." + (unavailable.isEmpty() ? "" : " Unavailable workers: " + String.join(", ", unavailable)));
                if (installed) {
                    benchmarkLog(phase, "Benchmark pauses miner after measurement to restore the previous state.");
                    pauseForBenchmark(phase, "measurement phase complete");
                }
                update(mode, "Summarizing", index, phases.size(), observations);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); error = "Benchmark interrupted";
        } catch (RuntimeException e) {
            error = Objects.toString(e.getMessage(), "Benchmark failed");
        } finally {
            try {
                if (installed) {
                    boolean restored = true;
                    if (xmrWasMining) restored = mining.resumeMining(Coin.MONERO.id());
                    else mining.restoreCpuPauseState(cpuPausedBefore);
                    restored = pearl.restoreWorkerState(pearlPausedBefore, pearlWasMining) && restored;
                    for (String coin : gpuCoinsWasMining) restored = mining.resumeMining(coin) && restored;
                    if (!restored) error = "Could not fully restore miner state";
                    benchmarkLog(Coin.MONERO.id(), restored ? "Previous miner state restored."
                            : "Previous miner state could not be fully restored.");
                    benchmarkLog(Coin.PEARL.id(), restored ? "Previous miner state restored."
                            : "Previous miner state could not be fully restored.");
                }
            } catch (RuntimeException restoreFailure) {
                error = "Could not fully restore miner state: " + Objects.toString(restoreFailure.getMessage(), "unknown error");
            }
            List<MinerStats.Worker> captured = aggregateWorkers(observations);
            persistPerformanceProfiles(observations);
            sharing.reportManualResults(captured);
            lock.end();
            Session old = session;
            String result = error != null ? "Error: " + error : cancel ? "Cancelled; previous miner state restored"
                    : "Complete; previous miner state restored" + (skipped.isEmpty() ? "" : "; skipped: " + String.join(", ", skipped));
            session = new Session(false, mode.wireName(), result, old.startedAt(), Instant.now(), null, phases.size(), phases.size(), summarize(observations), null);
        }
    }

    private List<MinerStats.Worker> phaseWorkers(String phase, boolean miningOnly) {
        return phaseWorkers(mining.getWorkerStats(), phase, miningOnly);
    }
    private void benchmarkLog(String phase, String message) {
        String entry = "[Benchmark] " + message;
        Coin coin = Coin.byIdOrNull(phase);
        if (coin != null) miners.miner(coin).appendBenchmarkEvent(entry);
        else consoles.append(phase, entry);
    }
    private boolean pauseForBenchmark(String phase, String reason) {
        return MinerStopContext.with("Benchmark: " + reason, () -> mining.pauseMining(phase));
    }

    static List<MinerStats.Worker> phaseWorkers(List<MinerStats.Worker> workers, String phase, boolean miningOnly) {
        return workers.stream()
                .filter(w -> PHASE_LIVE.equals(phase) || (Coin.MONERO.id().equals(phase)
                        ? "CPU".equalsIgnoreCase(w.hardwareType())
                        : phase.equals(coinForAlgorithm(w.currentAlgorithm()))))
                .filter(w -> !miningOnly || isMining(w))
                .toList();
    }
    static boolean workerUnavailable(String workerKey, MinerStats.Worker current, Set<String> observedMining, Instant startupDeadline) {
        if (current != null && current.miningStatus() == MinerStats.MinerStatus.ERROR) return true;
        if (current != null && isMining(current)) return false;
        if (observedMining.contains(workerKey)) return current == null || !isMining(current);
        return !Instant.now().isBefore(startupDeadline);
    }
    private static boolean hasValidHashrate(MinerStats.Worker w) { return Double.isFinite(w.terahashPerSecond()) && w.terahashPerSecond() > 0; }
    private static String phaseLabel(String phase) { return PHASE_LIVE.equals(phase) ? "Measuring active miners" : "Benchmarking " + phase; }

    private void update(BenchmarkMode mode, String phase, int index, int count, Map<String, List<MinerStats.Worker>> observations) {
        Session old = session;
        session = new Session(true, mode.wireName(), phase, old.startedAt(), null, null, index, count, summarize(observations), null);
    }
    private static boolean isMining(MinerStats.Worker w) { return w.miningStatus() == MinerStats.MinerStatus.MINING; }
    private static String key(MinerStats.Worker w) { return String.join("|", Objects.toString(w.deviceId(), ""), Objects.toString(w.currentAlgorithm(), "")); }
    private static List<Result> summarize(Map<String, List<MinerStats.Worker>> observations) {
        return observations.values().stream().map(values -> {
            MinerStats.Worker w = values.getFirst();
            double[] rates = values.stream().mapToDouble(v -> v.terahashPerSecond() * 1_000_000_000_000d).sorted().toArray();
            double median = rates.length % 2 == 1 ? rates[rates.length / 2] : (rates[rates.length / 2 - 1] + rates[rates.length / 2]) / 2;
            double watts = values.stream().mapToLong(MinerStats.Worker::approximatedPowerUsageWatts).filter(v -> v > 0).average().orElse(0);
            return new Result(w.hardwareType(), w.hardwareModel(), w.currentAlgorithm(), median, watts, watts > 0 ? median / watts : 0, values.size());
        }).toList();
    }
    private static List<MinerStats.Worker> aggregateWorkers(Map<String, List<MinerStats.Worker>> observations) {
        return observations.values().stream().map(values -> {
            MinerStats.Worker w = values.getFirst();
            double[] rates = values.stream().mapToDouble(MinerStats.Worker::terahashPerSecond).sorted().toArray();
            double medianRate = rates.length % 2 == 1 ? rates[rates.length / 2] : (rates[rates.length / 2 - 1] + rates[rates.length / 2]) / 2;
            long watts = Math.round(values.stream().mapToLong(MinerStats.Worker::approximatedPowerUsageWatts).filter(v -> v > 0).average().orElse(0));
            return new MinerStats.Worker(w.miningStatus(), w.workerDisplayName(), w.currentAlgorithm(), medianRate,
                    w.temperatureCelsius(), w.powerTargetWatts(), w.minPowerTarget(), w.defaultPowerTarget(),
                    w.maxPowerTarget(), watts, w.pools(), w.hardwareType(), w.hardwareModel(), w.deviceId(),
                    w.acceptedShares(), w.rejectedShares(), w.pool());
        }).toList();
    }

    private void persistPerformanceProfiles(Map<String, List<MinerStats.Worker>> observations) {
        Instant measuredAt = Instant.now();
        for (List<MinerStats.Worker> values : observations.values()) {
            if (values.size() < REQUIRED_SAMPLES_PER_WORKER) continue;
            MinerStats.Worker worker = values.getFirst();
            String coin = coinForAlgorithm(worker.currentAlgorithm());
            if (coin == null || worker.deviceId() == null || worker.deviceId().isBlank()) continue;
            double[] rates = values.stream().mapToDouble(value -> value.terahashPerSecond() * 1_000_000_000_000d).sorted().toArray();
            double hashrate = rates.length % 2 == 1 ? rates[rates.length / 2] : (rates[rates.length / 2 - 1] + rates[rates.length / 2]) / 2;
            long watts = Math.round(values.stream().mapToLong(MinerStats.Worker::approximatedPowerUsageWatts)
                    .filter(value -> value > 0).average().orElse(0));
            if (!(hashrate > 0) || watts <= 0) continue;
            performanceProfiles.save(new MiningPerformanceProfileStore.Profile(worker.deviceId(), worker.hardwareType(),
                    worker.hardwareModel(), coin, worker.currentAlgorithm(), hashrate, watts, values.size(), measuredAt, "BENCHMARK"));
        }
    }

    private static String coinForAlgorithm(String algorithm) {
        Coin coin = Coin.byAlgorithm(algorithm).orElse(null);
        return coin == null || coin == Coin.NONE ? null : coin.id();
    }
    @PreDestroy public void close() { cancel = true; executor.shutdownNow(); }

    public record Result(String hardwareType, String hardwareModel, String algorithm, double hashrateHs, double powerWatts, double hashesPerWatt, int observations) { }
    public record Session(boolean running, String mode, String phase, Instant startedAt, Instant phaseEndsAt, Instant totalEndsAt,
                          int phaseIndex, int phaseCount, List<Result> results, Long secondsRemaining) {
        static Session idle() { return new Session(false, "", "Idle", null, null, null, 0, 0, List.of(), 0L); }
    }

    public static final class BenchmarkRunningException extends RuntimeException {
        public BenchmarkRunningException() { super("Node controls are temporarily locked while a benchmark is running"); }
    }
}
