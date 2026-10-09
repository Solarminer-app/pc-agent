package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.pearl.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;

/**
 * Power-cap efficiency sweep: for every configured GPU miner (coin × GPU) the agent walks the
 * power limit downward and keeps only steps that prove stable under real mining load.
 *
 * <p>Hardware protection is the hard constraint of every step:</p>
 * <ul>
 *   <li>Only driver-verified power limits are written (read-back check inside
 *       {@link LocalGpuPowerService}); driver and persisted user limits are never undercut.</li>
 *   <li>Every sample checks the GPU temperature; exceeding the configured ceiling aborts the
 *       step immediately and keeps the last stable limit.</li>
 *   <li>A step counts as stable only while the miner process stays alive, keeps hashing with a
 *       valid pool connection and does not exceed the rejected-share ratio.</li>
 *   <li>After the run — including cancellation or failure — the previous power limits and the
 *       previous miner state are always restored.</li>
 * </ul>
 */
@Service
public class EfficiencySweepService {
    /** Five-second samples; twelve points cover at least one minute of steady-state mining per step. */
    static final int SAMPLES_PER_STEP = 12;
    static final long SAMPLE_INTERVAL_MILLIS = 5_000;
    private static final long ESTIMATED_STARTUP_SECONDS = 45;
    private static final List<String> GPU_COINS = List.of("ravencoin", "ethereumclassic", "decred", "quantus");

    private final PearlMinerService pearl;
    private final GpuCoinMinerService gpuCoins;
    private final LocalGpuPowerService power;
    private final MinerConsoleService consoles;
    private final PayoutDefaultsService payoutDefaults;
    private final ProxyConfigurationService proxyConfiguration;
    private final GpuEfficiencyStore store;
    private final BenchmarkSharingService sharing;
    private final LocalRunLock lock;
    private final int stepWatts;
    private final double maxTemperatureC;
    private final double maxRejectedRatio;
    /**
     * Time a step waits for the first valid hashrate sample. The sweep restarts the miner at
     * every power limit, and a cold restart costs real time before SRBMiner reports anything:
     * DAG load for kawpow/etchash-class algorithms plus SRBMiner's one-minute average window
     * can keep the reported hashrate at zero for close to three minutes while the miner is
     * perfectly healthy. This grace must outlive the miner monitor's own hashrate watchdog,
     * otherwise the sweep declares a working card unstable.
     */
    private final Duration startupGrace;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("pc-agent-efficiency-sweep").daemon(true).unstarted(r));
    private volatile Session session = Session.idle();
    private volatile boolean cancel;

    public EfficiencySweepService(PearlMinerService pearl, GpuCoinMinerService gpuCoins, LocalGpuPowerService power,
                                  MinerConsoleService consoles, PayoutDefaultsService payoutDefaults,
                                  ProxyConfigurationService proxyConfiguration,
                                  GpuEfficiencyStore store, BenchmarkSharingService sharing, LocalRunLock lock,
                                  @Value("${solarminer.agent.sweep.step-watts:15}") int stepWatts,
                                  @Value("${solarminer.agent.sweep.max-temperature-c:85}") double maxTemperatureC,
                                  @Value("${solarminer.agent.sweep.max-rejected-ratio:0.10}") double maxRejectedRatio,
                                  @Value("${solarminer.agent.sweep.startup-grace-seconds:240}") long startupGraceSeconds) {
        this.pearl = pearl;
        this.gpuCoins = gpuCoins;
        this.power = power;
        this.consoles = consoles;
        this.payoutDefaults = payoutDefaults;
        this.proxyConfiguration = proxyConfiguration;
        this.store = store;
        this.sharing = sharing;
        this.lock = lock;
        this.stepWatts = Math.max(5, stepWatts);
        this.maxTemperatureC = maxTemperatureC;
        this.maxRejectedRatio = maxRejectedRatio;
        this.startupGrace = Duration.ofSeconds(Math.max(60, startupGraceSeconds));
    }

    public synchronized Session start() {
        return start(false);
    }

    public synchronized Session resume() {
        if (session.running()) throw new IllegalStateException("Ein Effizienz-Sweep läuft bereits");
        if (session.runs().isEmpty() || session.runs().stream().allMatch(RunStatus::terminalComplete))
            throw new IllegalStateException("Es gibt keinen unterbrochenen Effizienz-Sweep zum Fortsetzen");
        return start(true);
    }

    private Session start(boolean resume) {
        if (session.running()) throw new IllegalStateException("Ein Effizienz-Sweep läuft bereits");
        Session previous = session;
        if (pearl.hasExternalMinerProcess())
            throw new IllegalStateException("Effizienz-Sweep abgebrochen: SRBMiner läuft außerhalb des PC-Agent; Leistungsgrenzen können nicht sicher überwacht werden");
        List<LocalGpuPowerService.Gpu> discovered = power.discover();
        List<Target> targets = targets(discovered);
        if (targets.isEmpty())
            throw new IllegalStateException(unavailableReason(discovered, pearl.binaryAvailable()));
        if (!lock.tryBegin("efficiency-sweep"))
            throw new IllegalStateException("Ein anderer Messlauf (Benchmark) läuft gerade; der Sweep wartet, bis er beendet ist");
        cancel = false;
        Instant startedAt = Instant.now();
        List<List<List<Target>>> referenceBatches = referenceBatches(targetGroups(targets));
        List<List<Target>> cohorts = referenceBatches.stream().flatMap(List::stream).toList();
        List<RunStatus> runs = initialRuns(referenceBatches);
        Map<String, GpuEfficiencyStore.Profile> checkpoint = resume ? reusableProfiles(previous) : Map.of();
        if (resume) {
            runs = applyCheckpoint(runs, previous.runs(), checkpoint);
            Set<String> compatible = runs.stream().filter(RunStatus::terminalComplete)
                    .map(RunStatus::id).collect(java.util.stream.Collectors.toSet());
            Map<String, GpuEfficiencyStore.Profile> filtered = new LinkedHashMap<>();
            for (Map.Entry<String, GpuEfficiencyStore.Profile> entry : checkpoint.entrySet())
                if (compatible.contains(entry.getKey())) filtered.put(entry.getKey(), entry.getValue());
            checkpoint = Map.copyOf(filtered);
        }
        List<GpuEfficiencyStore.Profile> reused = List.copyOf(checkpoint.values());
        int completed = (int) runs.stream().filter(RunStatus::terminalComplete).count();
        session = new Session(true, resume ? "Fortsetzen wird vorbereitet" : "Vorbereitung", startedAt,
                completed, targets.size(), reused, runs,
                estimateRemainingSeconds(runs, startedAt));
        try {
            Map<String, GpuEfficiencyStore.Profile> resumeCheckpoint = checkpoint;
            executor.submit(() -> run(targets, referenceBatches, cohorts, resumeCheckpoint));
        } catch (RuntimeException e) {
            session = Session.idle();
            lock.end();
            throw new IllegalStateException("Effizienz-Sweep konnte nicht gestartet werden", e);
        }
        return session;
    }

    public Session status() {
        return session;
    }

    public synchronized Session cancel() {
        cancel = true;
        return status();
    }

    public List<GpuEfficiencyStore.Profile> profiles() {
        return store.all();
    }

    /**
     * Coins with a saved configuration and a usable SRBMiner, restricted to driver-verified GPUs.
     * A coin the operator never configured is still measured against the fee-backend house
     * (dev-fee) payout through an ephemeral in-memory override, exactly like an empty wallet in
     * the operator UI. The override never touches disk and is cleared when the sweep ends.
     */
    private List<Target> targets(List<LocalGpuPowerService.Gpu> discovered) {
        List<Target> targets = new ArrayList<>();
        Map<String, LocalGpuPowerService.Gpu> verified = new LinkedHashMap<>();
        discovered.stream().filter(LocalGpuPowerService.Gpu::supportsDynamicPowerScaling)
                .forEach(gpu -> verified.put(gpu.deviceId(), gpu));
        if (verified.isEmpty() || !pearl.binaryAvailable()) return List.of();
        String devices = String.join(",", verified.values().stream()
                .map(gpu -> gpu.vendor() + ":" + gpu.index()).toList());
        if (pearl.configuration() != null)
            for (LocalGpuPowerService.Gpu gpu : pearl.selectedGpus())
                if (verified.containsKey(gpu.deviceId())) targets.add(new Target("pearl", "PearlHash", gpu, null));
        else {
            PearlMinerService.Config fallback = pearlFeeFallback(devices);
            if (fallback != null)
                for (LocalGpuPowerService.Gpu card : verified.values())
                    targets.add(new Target("pearl", "PearlHash", card, new ConfigOverride(fallback, null)));
        }
        for (String coin : GPU_COINS) {
            if (gpuCoins.configuration(coin) != null) {
                for (LocalGpuPowerService.Gpu gpu : gpuCoins.selected(coin))
                    if (verified.containsKey(gpu.deviceId()))
                        targets.add(new Target(coin, GpuCoinMinerService.algorithm(coin), gpu, null));
                continue;
            }
            GpuCoinMinerService.Config fallback = gpuCoinFeeFallback(coin, devices);
            if (fallback == null) continue;
            for (LocalGpuPowerService.Gpu gpu : verified.values())
                targets.add(new Target(coin, GpuCoinMinerService.algorithm(coin), gpu, new ConfigOverride(null, fallback)));
        }
        return List.copyOf(targets);
    }

    static String unavailableReason(List<LocalGpuPowerService.Gpu> discovered, boolean binaryAvailable) {
        if (discovered.isEmpty())
            return "Power-Limit-Test kann nicht gestartet werden: Keine GPU wurde erkannt";
        List<LocalGpuPowerService.Gpu> writable = discovered.stream()
                .filter(LocalGpuPowerService.Gpu::supportsDynamicPowerScaling).toList();
        if (writable.isEmpty()) {
            String driverStatus = discovered.stream()
                    .map(gpu -> gpu.model() + ": " + firstLine(gpu.regulationError()))
                    .distinct().limit(3).reduce((left, right) -> left + "; " + right).orElse("Treiberzugriff nicht verfügbar");
            return "Power-Limit-Test kann nicht gestartet werden: Keine GPU-Leistungsgrenze ist schreibbar. "
                    + "Starte den PC-Agent mit den für den GPU-Treiber nötigen Administrator-/root-Rechten. "
                    + "Treiberstatus: " + driverStatus;
        }
        if (!binaryAvailable)
            return "Power-Limit-Test kann nicht gestartet werden: SRBMiner-MULTI ist nicht installiert";
        return "Power-Limit-Test kann nicht gestartet werden: Für keine regelbare GPU ist ein startbereiter "
                + "GPU-Miner mit gültiger Pool-/Fee-Route verfügbar";
    }

    private static String firstLine(String value) {
        if (value == null || value.isBlank()) return "Power-Cap nicht verfügbar";
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    /** Ephemeral Pearl config against the fee-backend house target, or null when unreachable/invalid. */
    private PearlMinerService.Config pearlFeeFallback(String devices) {
        PayoutDefaultsService.DefaultPayout payout = payoutDefaults.resolve("pearl").orElse(null);
        if (payout == null || proxyConfiguration.host() == null) return null;
        String worker = payout.workerPart() != null ? payout.workerPart() : "solarminer";
        PearlMinerService.Config fallback = new PearlMinerService.Config(payout.poolUrl(),
                proxyConfiguration.pearlUrl(), payout.walletPart(), worker, devices);
        try {
            PearlMinerService.validate(fallback);
            return fallback;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Ephemeral GPU-coin config against the fee-backend house target, or null when unreachable/invalid. */
    private GpuCoinMinerService.Config gpuCoinFeeFallback(String coin, String devices) {
        PayoutDefaultsService.DefaultPayout payout = payoutDefaults.resolve(coin).orElse(null);
        if (payout == null || proxyConfiguration.host() == null) return null;
        String worker = payout.workerPart() != null && !payout.workerPart().isBlank() ? payout.workerPart() : "solarminer";
        String proxyUrl = switch (coin) {
            case "ravencoin" -> proxyConfiguration.ravencoinUrl();
            case "ethereumclassic" -> proxyConfiguration.ethereumclassicUrl();
            case "decred" -> proxyConfiguration.decredUrl();
            default -> proxyConfiguration.quantusUrl();
        };
        GpuCoinMinerService.Config fallback = new GpuCoinMinerService.Config(payout.poolUrl(),
                proxyUrl, payout.walletPart(), worker, devices);
        try {
            GpuCoinMinerService.validate(coin, fallback);
            return fallback;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void run(List<Target> targets, List<List<List<Target>>> referenceBatches,
                     List<List<Target>> cohorts, Map<String, GpuEfficiencyStore.Profile> checkpoint) {
        try {
            execute(targets, referenceBatches, cohorts, checkpoint);
        } catch (RuntimeException e) {
            Session old = session;
            session = new Session(false, "Fehler: " + Objects.toString(e.getMessage(), "Sweep fehlgeschlagen"),
                    old.startedAt(), old.phaseIndex(), old.phaseCount(), old.results(), old.runs(), 0L);
        } finally {
            lock.end();
        }
    }

    private void execute(List<Target> targets, List<List<List<Target>>> referenceBatches,
                         List<List<Target>> cohorts, Map<String, GpuEfficiencyStore.Profile> checkpoint) {
        // Safety snapshot: everything the sweep may touch is captured before the first write.
        Set<String> pearlWasMining = pearl.runningGpuDeviceIds();
        Set<String> pearlPausedBefore = pearl.manuallyPausedGpuKeys();
        Map<String, Set<String>> coinWasMining = new LinkedHashMap<>();
        Map<String, Set<String>> coinWasPaused = new LinkedHashMap<>();
        Map<String, Integer> limitsBefore = new LinkedHashMap<>();
        for (String coin : GPU_COINS) {
            if (gpuCoins.configuration(coin) == null) continue;
            for (GpuCoinMinerService.GpuState state : gpuCoins.gpuStates(coin, power.discover())) {
                String key = state.vendor() + ":" + state.index();
                if (state.running()) coinWasMining.computeIfAbsent(coin, ignored -> new java.util.HashSet<>()).add(key);
                if (state.manuallyPaused()) coinWasPaused.computeIfAbsent(coin, ignored -> new java.util.HashSet<>()).add(key);
            }
        }
        Set<String> targetDeviceIds = targets.stream().map(target -> target.gpu().deviceId()).collect(java.util.stream.Collectors.toSet());
        for (LocalGpuPowerService.Gpu gpu : power.refresh())
            if (targetDeviceIds.contains(gpu.deviceId()) && gpu.currentPowerLimitWatts() != null)
                limitsBefore.put(gpu.deviceId(), gpu.currentPowerLimitWatts());
        if (limitsBefore.size() != targetDeviceIds.size())
            throw new IllegalStateException("Nicht alle GPU-Power-Caps konnten vor dem Test sicher gelesen werden");

        List<GpuEfficiencyStore.Profile> produced = new ArrayList<>(checkpoint.values());
        String error = null;
        try {
            // Overrides are session-scoped. Keeping them until restoration avoids one parallel
            // run clearing a shared coin override while a sibling still needs to restart.
            for (Target target : targets) applyOverride(target);
            executeTasks(referenceBatches, cohorts, produced, checkpoint);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            error = Objects.toString(e.getMessage(), "Sweep failed");
        } finally {
            // Safety net: every ephemeral fee-backend override is removed even when a
            // target aborted early, so no dev-fee route can outlive the sweep.
            for (Target target : targets) clearOverride(target);
            boolean minersStopped = true;
            for (Target target : targets) minersStopped = stopMiner(target) && minersStopped;
            if (!minersStopped) error = appendError(error, "Konnte nicht alle Sweep-Miner anhalten");
            if (!power.restorePowerLimits(limitsBefore))
                error = appendError(error, "Konnte Leistungsgrenzen nicht vollständig wiederherstellen");
            try {
                boolean restored = pearl.restoreWorkerState(pearlPausedBefore, pearlWasMining);
                for (Map.Entry<String, Set<String>> entry : coinWasMining.entrySet())
                    for (String key : entry.getValue()) {
                        String[] parts = key.split(":");
                        restored = gpuCoins.resumeGpu(entry.getKey(), parts[0], Integer.parseInt(parts[1])) && restored;
                    }
                for (Map.Entry<String, Set<String>> entry : coinWasPaused.entrySet())
                    for (String key : entry.getValue()) {
                        String[] parts = key.split(":");
                        restored = gpuCoins.pauseGpu(entry.getKey(), parts[0], Integer.parseInt(parts[1])) && restored;
                    }
                if (!restored) error = appendError(error, "Konnte Miner-Zustand nicht vollständig wiederherstellen");
            } catch (RuntimeException e) {
                error = appendError(error, "Konnte Miner-Zustand nicht vollständig wiederherstellen: " + e.getMessage());
            }
            sharing.reportEfficiencyResults(List.copyOf(produced));
            Session old = session;
            String result = error != null ? "Fehler: " + error
                    : cancel ? "Abgebrochen; Leistungsgrenzen und Miner-Zustand wiederhergestellt"
                    : "Fertig; Leistungsgrenzen und Miner-Zustand wiederhergestellt";
            List<RunStatus> finalRuns = finalizePendingRuns(old.runs(), error != null ? "FAILED" : "CANCELLED",
                    error != null ? error : "Abgebrochen");
            int finalCompleted = (int) finalRuns.stream().filter(RunStatus::terminal).count();
            session = new Session(false, result, old.startedAt(), finalCompleted, targets.size(), List.copyOf(produced),
                    finalRuns, 0L);
        }
    }

    /**
     * Runs a dependency-aware queue. References of other coins fill GPUs whose same-coin
     * validation is still blocked; as soon as a reference completes, its sibling validations
     * become the preferred work for every matching device that becomes free.
     */
    private void executeTasks(List<List<List<Target>>> referenceBatches, List<List<Target>> cohorts,
                              List<GpuEfficiencyStore.Profile> produced,
                              Map<String, GpuEfficiencyStore.Profile> checkpoint) throws Exception {
        Map<String, List<Target>> siblingsByCohort = new LinkedHashMap<>();
        for (List<Target> cohort : cohorts)
            siblingsByCohort.put(cohortKey(cohort.getFirst()), List.copyOf(cohort.subList(1, cohort.size())));
        List<SweepTask> pendingReferences = new ArrayList<>();
        List<SweepTask> readyValidations = new ArrayList<>();
        for (List<Target> group : referenceBatches.stream().flatMap(List::stream).toList()) {
            Target reference = group.getFirst();
            String cohort = cohortKey(reference);
            GpuEfficiencyStore.Profile referenceProfile = checkpoint.get(runId(reference));
            if (referenceProfile == null) {
                pendingReferences.add(new SweepTask(reference, cohort, "FULL", null));
                continue;
            }
            unlockValidations(siblingsByCohort.getOrDefault(cohort, List.of()), cohort,
                    referenceProfile, checkpoint, readyValidations);
        }
        Set<String> busyDevices = new HashSet<>();
        int active = 0;
        try (ExecutorService parallel = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletionService<SweepTaskResult> completed = new ExecutorCompletionService<>(parallel);
            while (active > 0 || (!cancel && (!pendingReferences.isEmpty() || !readyValidations.isEmpty()))) {
                if (!cancel) {
                    SweepTask task;
                    while ((task = takeRunnable(readyValidations, pendingReferences, busyDevices)) != null) {
                        SweepTask selected = task;
                        busyDevices.add(selected.target().gpu().deviceId());
                        completed.submit(() -> new SweepTaskResult(selected,
                                sweepTarget(selected.target(), selected.limits(), selected.mode())));
                        active++;
                    }
                }
                if (active == 0) break;
                SweepTaskResult result;
                try {
                    result = completed.take().get();
                } catch (Exception e) {
                    cancel = true;
                    throw e;
                }
                active--;
                busyDevices.remove(result.task().target().gpu().deviceId());
                if (result.profile() != null) produced.add(result.profile());
                if (!result.task().mode().equals("FULL")) continue;
                List<Target> siblings = siblingsByCohort.getOrDefault(result.task().cohort(), List.of());
                if (cancel) {
                    for (Target sibling : siblings) finishRun(sibling, "CANCELLED", "Abgebrochen");
                } else if (result.profile() == null || result.profile().bestStableWatts() == null) {
                    for (Target sibling : siblings)
                        finishRun(sibling, "SKIPPED", "Referenzkarte lieferte keinen stabilen Ausgangswert");
                } else {
                    unlockValidations(siblings, result.task().cohort(), result.profile(), Map.of(), readyValidations);
                }
            }
        }
    }

    private void unlockValidations(List<Target> siblings, String cohort,
                                   GpuEfficiencyStore.Profile referenceProfile,
                                   Map<String, GpuEfficiencyStore.Profile> checkpoint,
                                   List<SweepTask> readyValidations) {
        for (Target sibling : siblings) {
            if (checkpoint.containsKey(runId(sibling))) continue;
            List<Integer> candidates = validationLimits(referenceProfile.bestStableWatts(),
                    sibling.gpu().minWatts(), activeLimit(sibling.gpu()), stepWatts);
            setPlannedLimits(sibling, candidates, "VALIDATION", "Referenz fertig; wartet auf freie GPU");
            readyValidations.add(new SweepTask(sibling, cohort, "VALIDATION", candidates));
        }
    }

    /** Prefer unlocked same-cohort validation work, then use an idle card for another coin. */
    static SweepTask takeRunnable(List<SweepTask> readyValidations, List<SweepTask> pendingReferences,
                                  Set<String> busyDevices) {
        SweepTask task = removeFirstRunnable(readyValidations, busyDevices);
        return task != null ? task : removeFirstRunnable(pendingReferences, busyDevices);
    }

    private static SweepTask removeFirstRunnable(List<SweepTask> tasks, Set<String> busyDevices) {
        for (Iterator<SweepTask> iterator = tasks.iterator(); iterator.hasNext(); ) {
            SweepTask candidate = iterator.next();
            if (busyDevices.contains(candidate.target().gpu().deviceId())) continue;
            iterator.remove();
            return candidate;
        }
        return null;
    }

    private GpuEfficiencyStore.Profile sweepTarget(Target target, List<Integer> requestedLimits,
                                                   String mode) {
        String label = target.gpu().model() + " · " + target.algorithm();
        // Only one miner per physical GPU: stop every other coin on this card first.
        if (!stopAllOn(target.gpu())) throw new IllegalStateException("Andere Miner auf " + target.gpu().model() + " konnten nicht angehalten werden");
        LocalGpuPowerService.Gpu fresh = power.refresh().stream()
                .filter(gpu -> gpu.deviceId().equals(target.gpu().deviceId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("GPU wurde während des Tests getrennt"));
        int current = activeLimit(fresh);
        List<Integer> limits = requestedLimits == null
                ? candidateLimits(fresh.minWatts(), current, stepWatts) : requestedLimits;
        setPlannedLimits(target, limits, mode, mode.equals("FULL") ? "Vollständige Referenzkurve" : "Parallele Gerätevalidierung");
        List<GpuEfficiencyStore.StepResult> steps = new ArrayList<>();
        GpuEfficiencyStore.StepResult best = null;
        for (int limit : limits) {
            if (cancel) break;
            updateRun(target, "RUNNING", limit, steps, 0, label + " · " + limit + " W");
            if (!power.setTotalPowerTarget(limit, List.of(target.gpu()))) {
                steps.add(new GpuEfficiencyStore.StepResult(limit, null, null, null, false,
                        "Leistungsgrenze wurde nicht vom Treiber bestätigt; Schritt übersprungen"));
                updateRun(target, "RUNNING", limit, steps, SAMPLES_PER_STEP,
                        "Treiber hat die Leistungsgrenze nicht bestätigt");
                break;
            }
            GpuEfficiencyStore.StepResult step = measureStep(target, limit, steps);
            steps.add(step);
            updateRun(target, "RUNNING", limit, steps, SAMPLES_PER_STEP,
                    step.stable() ? limit + " W stabil" : limit + " W instabil: " + step.note());
            if (!stopMiner(target)) throw new IllegalStateException("Sweep-Miner konnte nicht angehalten werden");
            sweepLog(target, label + " @ " + limit + " W: " + (step.stable()
                    ? "stabil, " + formatRate(step.medianHashrateHs()) + " · " + Math.round(step.avgPowerWatts()) + " W"
                    : "instabil (" + step.note() + ")"));
            if (!step.stable() && mode.equals("FULL")) break;
            if (step.stable() && (best == null || step.medianHashrateHs() / step.avgPowerWatts() > best.medianHashrateHs() / best.avgPowerWatts()))
                best = step;
            if (step.stable() && mode.equals("VALIDATION")) break;
        }
        GpuEfficiencyStore.Profile profile = null;
        if (best != null) {
            profile = new GpuEfficiencyStore.Profile(
                    target.gpu().deviceId(), target.gpu().model(), target.coin(), target.algorithm(),
                    best.limitWatts(), best.medianHashrateHs(), best.avgPowerWatts(),
                    best.medianHashrateHs() / best.avgPowerWatts(), Instant.now(), List.copyOf(steps));
            if (!cancel) store.save(profile);
            sweepLog(target, label + (cancel ? ": bisher bester Messwert " : ": bester stabiler Wert ")
                    + best.limitWatts() + " W ("
                    + formatRate(best.medianHashrateHs()) + ", " + Math.round(best.medianHashrateHs() / best.avgPowerWatts()) + " H/J)");
            finishRun(target, cancel ? "CANCELLED" : "COMPLETE", cancel
                    ? "Abgebrochen; bisher bester Messwert: " + best.limitWatts() + " W"
                    : "Bester stabiler Wert: " + best.limitWatts() + " W");
        } else {
            sweepLog(target, label + ": kein stabiler Undervolt-Wert gefunden");
            if (!steps.isEmpty())
                profile = new GpuEfficiencyStore.Profile(target.gpu().deviceId(), target.gpu().model(),
                        target.coin(), target.algorithm(), null, null, null, null, Instant.now(), List.copyOf(steps));
            finishRun(target, cancel ? "CANCELLED" : "FAILED", cancel ? "Abgebrochen" : "Kein stabiler Wert gefunden");
        }
        return profile;
    }

    private void applyOverride(Target target) {
        if (target.override() == null) return;
        if (target.override().pearl() != null) pearl.setSweepOverride(target.override().pearl());
        if (target.override().gpuCoin() != null) gpuCoins.setSweepOverride(target.coin(), target.override().gpuCoin());
    }

    private void clearOverride(Target target) {
        if (target.override() == null) return;
        if (target.override().pearl() != null) pearl.clearSweepOverride();
        if (target.override().gpuCoin() != null) gpuCoins.clearSweepOverride(target.coin());
    }

    /** Descending candidates never exceed the limit that was active when the sweep began. */
    static List<Integer> candidateLimits(int minWatts, int currentWatts, int stepWatts) {
        List<Integer> limits = new ArrayList<>();
        for (int limit = Math.max(minWatts, currentWatts); limit > minWatts; limit -= stepWatts) limits.add(limit);
        limits.add(Math.max(minWatts, 0));
        return List.copyOf(limits);
    }

    static List<Integer> validationLimits(int referenceWatts, int minWatts, int currentWatts, int stepWatts) {
        int start = Math.max(minWatts, Math.min(referenceWatts, currentWatts));
        List<Integer> limits = new ArrayList<>();
        for (int limit = start; limit < currentWatts; limit += stepWatts) limits.add(limit);
        if (limits.isEmpty() || limits.getLast() != currentWatts) limits.add(currentWatts);
        return List.copyOf(limits);
    }

    private static int activeLimit(LocalGpuPowerService.Gpu gpu) {
        return gpu.currentPowerLimitWatts() == null ? gpu.minWatts()
                : Math.min(gpu.maxWatts(), gpu.currentPowerLimitWatts());
    }

    private GpuEfficiencyStore.StepResult measureStep(Target target, int limit,
                                                       List<GpuEfficiencyStore.StepResult> completedSteps) {
        if (!startMiner(target))
            return new GpuEfficiencyStore.StepResult(limit, null, null, null, false, "Miner konnte nicht gestartet werden");
        List<Double> rates = new ArrayList<>();
        List<Double> watts = new ArrayList<>();
        double maxTemp = Double.NEGATIVE_INFINITY;
        Instant startupDeadline = Instant.now().plus(startupGrace);
        MinerStats.Worker worker = null;
        while (rates.size() < SAMPLES_PER_STEP) {
            if (cancel) return new GpuEfficiencyStore.StepResult(limit, null, null, null, false, "Abgebrochen");
            try {
                Thread.sleep(SAMPLE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new GpuEfficiencyStore.StepResult(limit, null, null, null, false, "Unterbrochen");
            }
            worker = findWorker(target);
            if (worker == null || worker.miningStatus() == MinerStats.MinerStatus.ERROR)
                return new GpuEfficiencyStore.StepResult(limit, null, null, null, false, "Miner-Prozess ausgefallen");
            Double temperature = power.readTemperatureC(target.gpu());
            if (temperature == null)
                return new GpuEfficiencyStore.StepResult(limit, null, null, null, false, "GPU-Temperatur nicht lesbar; Sicherheitshalber abgebrochen");
            maxTemp = Math.max(maxTemp, temperature);
            if (maxTemp > maxTemperatureC)
                return new GpuEfficiencyStore.StepResult(limit, null, null, maxTemp, false,
                        "GPU wurde " + Math.round(maxTemp) + " °C heiß; Grenze über dem Schwellwert von " + Math.round(maxTemperatureC) + " °C");
            if (worker.miningStatus() == MinerStats.MinerStatus.MINING
                    && Double.isFinite(worker.terahashPerSecond()) && worker.terahashPerSecond() > 0
                    && Double.isFinite(worker.approximatedPowerUsageWatts()) && worker.approximatedPowerUsageWatts() > 0) {
                rates.add(worker.terahashPerSecond() * 1_000_000_000_000d);
                watts.add((double) worker.approximatedPowerUsageWatts());
            } else if (!Instant.now().isBefore(startupDeadline)) {
                return new GpuEfficiencyStore.StepResult(limit, null, null, maxTemp == Double.NEGATIVE_INFINITY ? null : maxTemp,
                        false, "Miner liefert trotz Pool-Jobs keine Hashrate");
            }
            updateRun(target, "RUNNING", limit, completedSteps, rates.size(),
                    target.gpu().model() + " · " + target.algorithm() + " · " + limit + " W");
        }
        double median = median(rates);
        double avgWatts = watts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (median <= 0 || avgWatts <= 0)
            return new GpuEfficiencyStore.StepResult(limit, null, null, maxTemp, false, "Keine gültigen Messpunkte");
        long accepted = worker.acceptedShares() == null ? 0 : worker.acceptedShares();
        long rejected = worker.rejectedShares() == null ? 0 : worker.rejectedShares();
        if (accepted + rejected > 0 && (double) rejected / (accepted + rejected) > maxRejectedRatio)
            return new GpuEfficiencyStore.StepResult(limit, median, avgWatts, maxTemp, false,
                    rejected + " von " + (accepted + rejected) + " Shares verworfen (Power-Cap nicht stabil)");
        return new GpuEfficiencyStore.StepResult(limit, median, avgWatts, maxTemp, true, null);
    }

    private MinerStats.Worker findWorker(Target target) {
        // Re-discover so the worker row carries the live power draw instead of the
        // snapshot captured when the sweep started.
        LocalGpuPowerService.Gpu fresh = power.discover().stream()
                .filter(gpu -> gpu.deviceId().equals(target.gpu().deviceId())).findFirst().orElse(target.gpu());
        List<MinerStats.Worker> workers = "pearl".equals(target.coin())
                ? pearl.workerStats(List.of(fresh))
                : gpuCoins.workerStats(target.coin(), List.of(fresh));
        return workers.stream()
                .filter(w -> Objects.equals(w.deviceId(), target.gpu().deviceId())
                        && Objects.equals(w.currentAlgorithm(), target.algorithm()))
                .findFirst().orElse(null);
    }

    private boolean startMiner(Target target) {
        return "pearl".equals(target.coin())
                ? pearl.startGpu(target.gpu().vendor(), target.gpu().index())
                : gpuCoins.resumeGpu(target.coin(), target.gpu().vendor(), target.gpu().index());
    }

    private boolean stopMiner(Target target) {
        return MinerStopContext.with("Efficiency sweep", () -> "pearl".equals(target.coin())
                ? pearl.stopGpu(target.gpu().vendor(), target.gpu().index())
                : gpuCoins.stopGpu(target.coin(), target.gpu().vendor(), target.gpu().index()));
    }

    private boolean stopAllOn(LocalGpuPowerService.Gpu gpu) {
        return MinerStopContext.with("Efficiency sweep", () -> {
            boolean success = pearl.stopGpu(gpu.vendor(), gpu.index());
            for (String coin : GPU_COINS) success = gpuCoins.stopGpu(coin, gpu.vendor(), gpu.index()) && success;
            return success;
        });
    }

    private void sweepLog(Target target, String message) {
        String entry = "[Efficiency-Sweep] " + message;
        consoles.append("pearl", entry);
        consoles.append(target.coin(), entry);
    }

    static String cohortKey(Target target) {
        LocalGpuPowerService.Gpu gpu = target.gpu();
        return String.join("|", target.coin(), target.algorithm(), gpu.vendor(), gpu.model(),
                Integer.toString(gpu.driverMinWatts()), Integer.toString(gpu.driverMaxWatts()),
                Integer.toString(gpu.minWatts()), Integer.toString(gpu.maxWatts()));
    }

    static List<List<Target>> targetGroups(List<Target> targets) {
        Map<String, List<Target>> groups = new LinkedHashMap<>();
        for (Target target : targets)
            groups.computeIfAbsent(cohortKey(target), ignored -> new ArrayList<>()).add(target);
        return groups.values().stream().map(List::copyOf).toList();
    }

    /**
     * Assigns one cohort reference per physical GPU to each breadth-first batch. A cohort is
     * reordered so its selected reference is always first; all remaining cards stay available
     * for the later sibling validation. Batch barriers make it impossible for two coin runs to
     * use the same physical GPU concurrently.
     */
    static List<List<List<Target>>> referenceBatches(List<List<Target>> cohorts) {
        List<List<Target>> pending = new ArrayList<>(cohorts);
        List<List<List<Target>>> batches = new ArrayList<>();
        while (!pending.isEmpty()) {
            Set<String> usedDevices = new HashSet<>();
            List<List<Target>> batch = new ArrayList<>();
            for (Iterator<List<Target>> iterator = pending.iterator(); iterator.hasNext(); ) {
                List<Target> cohort = iterator.next();
                Target reference = cohort.stream()
                        .filter(candidate -> !usedDevices.contains(candidate.gpu().deviceId()))
                        .findFirst().orElse(null);
                if (reference == null) continue;
                List<Target> reordered = new ArrayList<>(cohort.size());
                reordered.add(reference);
                cohort.stream().filter(candidate -> candidate != reference).forEach(reordered::add);
                batch.add(List.copyOf(reordered));
                usedDevices.add(reference.gpu().deviceId());
                iterator.remove();
            }
            if (batch.isEmpty()) throw new IllegalStateException("Keine konfliktfreie GPU-Referenzplanung möglich");
            batches.add(List.copyOf(batch));
        }
        return List.copyOf(batches);
    }

    private List<RunStatus> initialRuns(List<List<List<Target>>> referenceBatches) {
        List<RunStatus> runs = new ArrayList<>();
        for (int batchIndex = 0; batchIndex < referenceBatches.size(); batchIndex++) {
            for (List<Target> group : referenceBatches.get(batchIndex)) {
                for (int index = 0; index < group.size(); index++) {
                    Target target = group.get(index);
                    boolean reference = index == 0;
                    List<Integer> limits = reference
                            ? candidateLimits(target.gpu().minWatts(), activeLimit(target.gpu()), stepWatts) : List.of();
                    runs.add(new RunStatus(runId(target), target.gpu().deviceId(), target.gpu().model(), target.coin(),
                            target.algorithm(), reference ? "FULL" : "VALIDATION", "QUEUED", null, limits,
                            List.of(), 0, SAMPLES_PER_STEP, reference ? batchIndex : null,
                            reference ? "Vollständige Referenzkurve" : "Wartet auf Ergebnis der Referenzkarte"));
                }
            }
        }
        return List.copyOf(runs);
    }

    static Map<String, GpuEfficiencyStore.Profile> reusableProfiles(Session previous) {
        Set<String> complete = previous.runs().stream().filter(RunStatus::terminalComplete)
                .map(RunStatus::id).collect(java.util.stream.Collectors.toSet());
        Map<String, GpuEfficiencyStore.Profile> reusable = new LinkedHashMap<>();
        for (GpuEfficiencyStore.Profile profile : previous.results()) {
            String id = profile.deviceId() + "|" + profile.coin() + "|" + profile.algorithm();
            if (complete.contains(id)) reusable.put(id, profile);
        }
        return Map.copyOf(reusable);
    }

    static List<RunStatus> applyCheckpoint(List<RunStatus> fresh, List<RunStatus> previous,
                                           Map<String, GpuEfficiencyStore.Profile> checkpoint) {
        Map<String, RunStatus> oldById = new LinkedHashMap<>();
        for (RunStatus run : previous) oldById.put(run.id(), run);
        return fresh.stream().map(run -> {
            RunStatus old = oldById.get(run.id());
            GpuEfficiencyStore.Profile profile = checkpoint.get(run.id());
            if (old == null || profile == null || !old.terminalComplete() || !old.mode().equals(run.mode())) return run;
            List<Integer> planned = old.plannedLimits().isEmpty() ? run.plannedLimits() : old.plannedLimits();
            return new RunStatus(run.id(), run.deviceId(), run.model(), run.coin(), run.algorithm(), run.mode(),
                    "COMPLETE", profile.bestStableWatts(), planned, profile.steps(), old.samples(),
                    run.samplesRequired(), run.referenceBatch(), "Aus unterbrochenem Sweep übernommen");
        }).toList();
    }

    private synchronized void setPlannedLimits(Target target, List<Integer> limits, String mode, String detail) {
        mutateRun(target, current -> new RunStatus(current.id(), current.deviceId(), current.model(), current.coin(),
                current.algorithm(), mode, "QUEUED", null, List.copyOf(limits), current.steps(), 0,
                SAMPLES_PER_STEP, current.referenceBatch(), detail), detail);
    }

    private synchronized void updateRun(Target target, String status, Integer limit,
                                        List<GpuEfficiencyStore.StepResult> steps, int samples, String detail) {
        mutateRun(target, current -> new RunStatus(current.id(), current.deviceId(), current.model(), current.coin(),
                current.algorithm(), current.mode(), status, limit, current.plannedLimits(), List.copyOf(steps),
                samples, SAMPLES_PER_STEP, current.referenceBatch(), detail), detail);
    }

    private synchronized void finishRun(Target target, String status, String detail) {
        mutateRun(target, current -> new RunStatus(current.id(), current.deviceId(), current.model(), current.coin(),
                current.algorithm(), current.mode(), status, current.limitWatts(), current.plannedLimits(),
                current.steps(), current.samples(), current.samplesRequired(), current.referenceBatch(), detail), detail);
    }

    private void mutateRun(Target target, java.util.function.Function<RunStatus, RunStatus> change, String phase) {
        Session old = session;
        List<RunStatus> next = new ArrayList<>(old.runs());
        for (int index = 0; index < next.size(); index++) {
            if (next.get(index).id().equals(runId(target))) {
                next.set(index, change.apply(next.get(index)));
                break;
            }
        }
        int completed = (int) next.stream().filter(RunStatus::terminal).count();
        session = new Session(true, phase, old.startedAt(), completed, next.size(), old.results(), List.copyOf(next),
                estimateRemainingSeconds(next, old.startedAt()));
    }

    private static String runId(Target target) {
        return target.gpu().deviceId() + "|" + target.coin() + "|" + target.algorithm();
    }

    static long estimateRemainingSeconds(List<RunStatus> runs, Instant startedAt) {
        long sequentialSeconds = 0;
        Map<String, Long> parallelGroups = new LinkedHashMap<>();
        for (RunStatus run : runs) {
            if (run.terminal()) continue;
            int plannedSteps = Math.max(1, run.plannedLimits().size());
            int finishedSteps = run.steps().size();
            boolean currentStepFinished = run.limitWatts() != null && run.steps().stream()
                    .anyMatch(step -> step.limitWatts() == run.limitWatts());
            int liveSamples = currentStepFinished ? 0 : run.samples();
            int remainingSamples = Math.max(0, (plannedSteps - finishedSteps) * SAMPLES_PER_STEP - liveSamples);
            long runSeconds = remainingSamples * SAMPLE_INTERVAL_MILLIS / 1_000
                    + Math.max(0, plannedSteps - finishedSteps) * ESTIMATED_STARTUP_SECONDS;
            if (run.mode().equals("VALIDATION")) {
                String group = String.join("|", run.coin(), run.algorithm(), run.model());
                parallelGroups.merge("validation|" + group, runSeconds, Math::max);
            } else if (run.referenceBatch() != null) {
                parallelGroups.merge("reference|" + run.referenceBatch(), runSeconds, Math::max);
            } else sequentialSeconds += runSeconds;
        }
        return sequentialSeconds + parallelGroups.values().stream().mapToLong(Long::longValue).sum();
    }

    private static List<RunStatus> finalizePendingRuns(List<RunStatus> runs, String status, String detail) {
        return runs.stream().map(run -> run.terminal() ? run : new RunStatus(run.id(), run.deviceId(), run.model(),
                run.coin(), run.algorithm(), run.mode(), status, run.limitWatts(), run.plannedLimits(), run.steps(),
                run.samples(), run.samplesRequired(), run.referenceBatch(), detail)).toList();
    }

    private static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (sorted.length == 0) return 0;
        return sorted.length % 2 == 1 ? sorted[sorted.length / 2] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
    }

    private static String formatRate(double hashrateHs) {
        if (hashrateHs >= 1_000_000_000d) return Math.round(hashrateHs / 1_000_000_000d) + " MH/s";
        if (hashrateHs >= 1_000_000d) return Math.round(hashrateHs / 1_000_000d) + " kH/s";
        return Math.round(hashrateHs) + " H/s";
    }

    private static String appendError(String current, String addition) {
        return current == null ? addition : current + "; " + addition;
    }

    @PreDestroy
    public void close() {
        cancel = true;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
                session = new Session(false, "Fehler: Wiederherstellung nach Abbruch nicht abgeschlossen",
                        session.startedAt(), session.phaseIndex(), session.phaseCount(), session.results(), session.runs(), 0L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record Target(String coin, String algorithm, LocalGpuPowerService.Gpu gpu, ConfigOverride override) {
    }

    /** Ephemeral fee-backend configuration for a coin the operator never configured. */
    record ConfigOverride(PearlMinerService.Config pearl, GpuCoinMinerService.Config gpuCoin) {
    }

    record SweepTask(Target target, String cohort, String mode, List<Integer> limits) {
    }

    private record SweepTaskResult(SweepTask task, GpuEfficiencyStore.Profile profile) {
    }

    public record RunStatus(String id, String deviceId, String model, String coin, String algorithm,
                            String mode, String status, Integer limitWatts, List<Integer> plannedLimits,
                            List<GpuEfficiencyStore.StepResult> steps, int samples, int samplesRequired,
                            Integer referenceBatch, String detail) {
        boolean terminal() {
            return status.equals("COMPLETE") || status.equals("FAILED") || status.equals("SKIPPED")
                    || status.equals("CANCELLED");
        }

        boolean terminalComplete() {
            return status.equals("COMPLETE");
        }
    }

    public record Session(boolean running, String phase, Instant startedAt, int phaseIndex, int phaseCount,
                          List<GpuEfficiencyStore.Profile> results, List<RunStatus> runs, Long secondsRemaining) {
        static Session idle() {
            return new Session(false, "Idle", null, 0, 0, List.of(), List.of(), 0L);
        }
    }
}
