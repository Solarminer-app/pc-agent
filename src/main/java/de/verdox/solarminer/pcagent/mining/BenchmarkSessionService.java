package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.xmr.XmrMinerService;
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
    private final MiningService mining;
    private final XmrMinerService xmr;
    private final PearlMinerService pearl;
    private final BenchmarkSharingService sharing;
    private final MinerConsoleService consoles;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("pc-agent-benchmark").daemon(true).unstarted(r));
    private volatile Session session = Session.idle();
    private volatile boolean cancel;

    public BenchmarkSessionService(MiningService mining, XmrMinerService xmr, PearlMinerService pearl,
                                   BenchmarkSharingService sharing, MinerConsoleService consoles) {
        this.mining = mining; this.xmr = xmr; this.pearl = pearl; this.sharing = sharing; this.consoles = consoles;
    }

    public synchronized Session start(String mode) {
        if (!"LIVE".equals(mode) && !"INSTALLED".equals(mode)) throw new IllegalArgumentException("Unknown benchmark mode");
        if (session.running()) throw new IllegalStateException("A benchmark is already running");
        List<String> phases = "INSTALLED".equals(mode) ? installedPhases() : List.of("live");
        if (phases.isEmpty()) throw new IllegalStateException("Install and configure at least one miner before running this benchmark");
        cancel = false;
        Instant start = Instant.now();
        int phaseCount = phases.size();
        session = new Session(true, mode, "Preparing", start, null, null, 0, phaseCount, List.of(), null);
        executor.submit(() -> run(mode, phases));
        return session;
    }

    public Session status() {
        Session value = session;
        return value;
    }
    public synchronized Session cancel() { cancel = true; return status(); }

    /** Serializes Node controls with benchmark admission so a checked command cannot race a new run. */
    public synchronized <T> T withExternalControl(Supplier<T> command) {
        if (session.running()) throw new BenchmarkRunningException();
        return command.get();
    }

    private List<String> installedPhases() {
        List<String> phases = new ArrayList<>();
        if (xmr.readyForStart()) phases.add("monero");
        if (pearl.binaryAvailable() && pearl.configuration() != null) phases.add("pearl");
        return List.copyOf(phases);
    }

    private void run(String mode, List<String> phases) {
        if ("INSTALLED".equals(mode) && (xmr.hasExternalMinerProcess() || pearl.hasExternalMinerProcess())) {
            Session old = session;
            session = new Session(false, mode, "Sequential benchmark skipped: externally started miners cannot be safely paused and restored",
                    old.startedAt(), Instant.now(), null, 0, old.phaseCount(), List.of(), null);
            return;
        }
        List<MinerStats.Worker> before = mining.getWorkerStats();
        boolean cpuPausedBefore = mining.cpuManuallyPaused();
        boolean xmrWasMining = before.stream().anyMatch(w -> isMining(w) && "CPU".equalsIgnoreCase(w.hardwareType()));
        // Do not derive this snapshot from visible worker status: SRBMiner deliberately reports PAUSED
        // while it is connecting to the pool, although its managed process is already running.
        Set<String> pearlWasMining = pearl.runningGpuDeviceIds();
        Set<String> pearlPausedBefore = pearl.manuallyPausedGpuKeys();
        Map<String, List<MinerStats.Worker>> observations = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        int index = 0;
        String error = null;
        try {
            if ("INSTALLED".equals(mode)) {
                update(mode, "Pausing current miners", 0, phases.size(), observations);
                if (xmrWasMining) pauseForBenchmark("monero", "capturing the pre-benchmark state");
                if (!pearlWasMining.isEmpty()) pauseForBenchmark("pearl", "capturing the pre-benchmark state");
            }
            for (String phase : phases) {
                if (cancel) break;
                index++;
                if ("INSTALLED".equals(mode)) {
                    if (!mining.resumeMining(phase)) {
                        skipped.add(phase);
                        benchmarkLog(phase, "Benchmark start failed; phase is skipped.");
                        continue;
                    }
                }
                // In INSTALLED mode a just-started miner must be a candidate before its first API/pool
                // health sample. LIVE intentionally keeps its existing "only currently mining" semantics.
                List<MinerStats.Worker> candidates = phaseWorkers(phase, "LIVE".equals(mode));
                Set<String> expected = candidates.stream().map(BenchmarkSessionService::key).collect(java.util.stream.Collectors.toSet());
                if (expected.isEmpty()) {
                    String reason = "LIVE".equals(mode)
                            ? " (no active workers)" : " (no configured workers detected)";
                    skipped.add(phase + reason);
                    benchmarkLog(phase, "Phase skipped:" + reason);
                    if ("INSTALLED".equals(mode)) {
                        benchmarkLog(phase, "Benchmark pauses miner after skipped phase.");
                        pauseForBenchmark(phase, "skipped phase");
                    }
                    continue;
                }
                benchmarkLog(phase, "Measurement phase started; expected workers: " + String.join(", ", expected)
                        + ("INSTALLED".equals(mode) ? "; waiting up to 95 s for the first active status." : "."));
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
                if ("INSTALLED".equals(mode)) {
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
                if ("INSTALLED".equals(mode)) {
                    boolean restored = true;
                    if (xmrWasMining) restored = mining.resumeMining("monero");
                    else mining.restoreCpuPauseState(cpuPausedBefore);
                    restored = pearl.restoreWorkerState(pearlPausedBefore, pearlWasMining) && restored;
                    if (!restored) error = "Could not fully restore miner state";
                    benchmarkLog("monero", restored ? "Previous miner state restored."
                            : "Previous miner state could not be fully restored.");
                    benchmarkLog("pearl", restored ? "Previous miner state restored."
                            : "Previous miner state could not be fully restored.");
                }
            } catch (RuntimeException restoreFailure) {
                error = "Could not fully restore miner state: " + Objects.toString(restoreFailure.getMessage(), "unknown error");
            }
            List<MinerStats.Worker> captured = aggregateWorkers(observations);
            sharing.reportManualResults(captured);
            Session old = session;
            String result = error != null ? "Error: " + error : cancel ? "Cancelled; previous miner state restored"
                    : "Complete; previous miner state restored" + (skipped.isEmpty() ? "" : "; skipped: " + String.join(", ", skipped));
            session = new Session(false, mode, result, old.startedAt(), Instant.now(), null, phases.size(), phases.size(), summarize(observations), null);
        }
    }

    private List<MinerStats.Worker> phaseWorkers(String phase, boolean miningOnly) {
        return phaseWorkers(mining.getWorkerStats(), phase, miningOnly);
    }
    private void benchmarkLog(String phase, String message) {
        String entry = "[Benchmark] " + message;
        if ("monero".equals(phase)) consoles.append("monero", entry);
        else if ("pearl".equals(phase)) pearl.appendBenchmarkEvent(entry);
        else {
            consoles.append("monero", entry);
            pearl.appendBenchmarkEvent(entry);
        }
    }
    private boolean pauseForBenchmark(String phase, String reason) {
        return MinerStopContext.with("Benchmark: " + reason, () -> mining.pauseMining(phase));
    }

    static List<MinerStats.Worker> phaseWorkers(List<MinerStats.Worker> workers, String phase, boolean miningOnly) {
        return workers.stream()
                .filter(w -> "live".equals(phase) || ("monero".equals(phase)
                        ? "CPU".equalsIgnoreCase(w.hardwareType()) : "GPU".equalsIgnoreCase(w.hardwareType())))
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
    private static String phaseLabel(String phase) { return "live".equals(phase) ? "Measuring active miners" : "Benchmarking " + phase; }

    private void update(String mode, String phase, int index, int count, Map<String, List<MinerStats.Worker>> observations) {
        Session old = session;
        session = new Session(true, mode, phase, old.startedAt(), null, null, index, count, summarize(observations), null);
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
