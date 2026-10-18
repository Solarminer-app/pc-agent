package de.verdox.solarminer.pcagent.miner.peak;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.dto.Pools;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.*;
import de.verdox.solarminer.pcagent.miner.diagnostics.MinerLogDiagnostics;
import de.verdox.solarminer.pcagent.miner.engine.AbstractMinerEngine;
import de.verdox.solarminer.pcagent.miner.engine.WorkerRunState;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Pearl adapter for PeakMiner, one UUID-isolated process per selected NVIDIA GPU. */
@Service
public class PeakMinerService extends AbstractMinerEngine implements GpuCoinMiner {
    private static final Logger LOGGER = Logger.getLogger(PeakMinerService.class.getName());
    private final ObjectMapper mapper;
    private final ProxyConfigurationService proxy;
    private final PearlMinerService pearl;
    private final LocalGpuPowerService power;
    private final MinerConsoleService console;
    private final PayoutDefaultsService payouts;
    private final LocalRunLock localRuns;
    private final PeakDownloadService download;
    private final Path configFile;
    private volatile MinerConfig savedConfiguration;
    private final HttpClient metrics = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private volatile MinerConfig sweepOverride;
    private volatile MinerConfig benchmarkOverride;
    private final Map<String, WorkerRunState> runs = new ConcurrentHashMap<>();
    private final Map<String, Double> temperatures = new ConcurrentHashMap<>();
    private final Set<String> manuallyPaused = ConcurrentHashMap.newKeySet();
    private volatile String lastError;

    @org.springframework.beans.factory.annotation.Autowired
    public PeakMinerService(ObjectMapper mapper, ProxyConfigurationService proxy,
                                PearlMinerService pearl,
                                LocalGpuPowerService power, MinerConsoleService console,
                                PayoutDefaultsService payouts, LocalRunLock localRuns,
                                PeakDownloadService download,
                                @org.springframework.beans.factory.annotation.Value("${solarminer.peak.config-file:./solarminer-agent/peak/solarminer-pearl.json}") String configPath) {
        this.mapper = mapper; this.proxy = proxy; this.pearl = pearl;
        this.power = power; this.console = console; this.payouts = payouts; this.localRuns = localRuns;
        this.download = download;
        this.configFile = Path.of(configPath).toAbsolutePath().normalize();
        try {
            if (Files.isRegularFile(configFile)) {
                MinerConfig loaded = mapper.readValue(configFile.toFile(), MinerConfig.class);
                validateConfig(loaded);
                savedConfiguration = loaded;
            } else if (pearl.configuration() != null) {
                MinerConfig existing = pearl.configuration();
                if (existing.devices() != null && existing.devices().matches("NVIDIA:[0-9]+(?:,NVIDIA:[0-9]+)*")) {
                    validateConfig(existing);
                    savedConfiguration = existing;
                }
            }
        } catch (Exception failure) {
            lastError = "Saved Peak configuration is invalid: " + failure.getMessage();
        }
    }

    /** Focused-test compatibility constructor. */
    public PeakMinerService(ObjectMapper mapper, ProxyConfigurationService proxy,
                                PearlMinerService pearl,
                                LocalGpuPowerService power, MinerConsoleService console,
                                PayoutDefaultsService payouts, LocalRunLock localRuns,
                                PeakDownloadService download) {
        this(mapper, proxy, pearl, power, console, payouts, localRuns, download,
                "./solarminer-agent/peak/solarminer-pearl.json");
    }

    @Override public Coin coin() { return Coin.PEARL; }
    @Override public boolean configured() { return configuration() != null; }
    @Override public boolean routeConfigured() {
        MinerConfig config = configuration();
        return config != null && proxy.matches(config.proxyUrl(), Coin.PEARL.id());
    }
    @Override public boolean setupComplete() { return configured(); }
    @Override public boolean binaryAvailable() { return download.binaryAvailable(); }
    @Override public boolean running() { return runs.values().stream().anyMatch(WorkerRunState::running); }
    @Override public String lastError() { return lastError; }
    @Override public MinerStats.MinerStatus status() {
        if (runs.values().stream().anyMatch(state -> state.running()
                && state.status == MinerStats.MinerStatus.MINING)) return MinerStats.MinerStatus.MINING;
        return runs.values().stream().anyMatch(state -> state.status == MinerStats.MinerStatus.ERROR)
                ? MinerStats.MinerStatus.ERROR : MinerStats.MinerStatus.PAUSED;
    }

    private MinerConfig effectiveConfig() {
        return benchmarkOverride != null ? benchmarkOverride
                : sweepOverride != null ? sweepOverride : configuration();
    }

    @Override public MinerConfig configuration() { return savedConfiguration; }
    @Override public void applyConfig(MinerConfig config) throws IOException {
        validateConfig(config);
        if (!stopAll()) throw new IOException("Peak Miner could not be stopped before saving the configuration");
        if (!proxy.matches(config.proxyUrl(), Coin.PEARL.id()))
            throw new IllegalArgumentException("SolarMiner proxy route does not match Pearl");
        if (!de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(configFile, config, mapper, "peak-config-"))
            throw new IOException("Peak Miner configuration could not be written");
        savedConfiguration = config;
        manuallyPaused.retainAll(configuredDevices(config));
    }
    @Override public void validateConfig(MinerConfig config) {
        PearlMinerService.validate(config);
        validateDeviceSelection(config.devices());
    }

    static void validateDeviceSelection(String selection) {
        List<String> devices = selection == null ? List.of() : List.of(selection.split(","));
        if (devices.isEmpty()) throw new IllegalArgumentException("Peak Miner requires one or more NVIDIA GPUs");
        if (devices.stream().anyMatch(device -> !device.matches("NVIDIA:[0-9]+")))
            throw new IllegalArgumentException("Peak Miner requires one or more NVIDIA GPUs");
        if (devices.stream().distinct().count() != devices.size())
            throw new IllegalArgumentException("Peak Miner GPU selection contains duplicates");
    }
    @Override public boolean updateProxyRoute(String proxyUrl) throws IOException {
        MinerConfig current = configuration();
        if (current == null) return false;
        return applyAndReturn(new MinerConfig(current.poolUrl(), proxyUrl, current.wallet(), current.worker(), current.devices()));
    }
    private boolean applyAndReturn(MinerConfig config) throws IOException { applyConfig(config); return true; }

    @Override public List<LocalGpuPowerService.Gpu> selectedGpus() {
        MinerConfig config = effectiveConfig();
        Set<String> selected = configuredDevices(config);
        return power.discover().stream().filter(gpu -> selected.contains(gpuKey(gpu))).toList();
    }
    @Override public List<LocalGpuPowerService.Gpu> eligibleGpus() {
        return selectedGpus().stream().filter(gpu -> !manuallyPaused.contains(gpuKey(gpu))).toList();
    }

    private static Set<String> configuredDevices(MinerConfig config) {
        return config == null || config.devices() == null || config.devices().isBlank()
                ? Set.of() : Set.of(config.devices().split(","));
    }

    private static String gpuKey(LocalGpuPowerService.Gpu gpu) { return gpu.vendor() + ":" + gpu.index(); }

    @Override public synchronized boolean startAll() {
        manuallyPaused.clear();
        runs.values().forEach(state -> state.restartAttempts = 0);
        return startForBudget();
    }
    @Override public synchronized boolean startForBudget() {
        List<LocalGpuPowerService.Gpu> selected = eligibleGpus();
        boolean success = !selected.isEmpty();
        for (LocalGpuPowerService.Gpu gpu : selected)
            success = startGpu(gpu.vendor(), gpu.index()) && success;
        if (!success && selected.isEmpty()) lastError = "No configured Peak NVIDIA GPU was detected";
        return success;
    }
    @Override public synchronized boolean startGpu(String vendor, int index) {
        String key = vendor + ":" + index;
        if (!"NVIDIA".equals(vendor)) return fail("Peak Miner requires an NVIDIA GPU");
        if (manuallyPaused.contains(key)) return false;
        LocalGpuPowerService.Gpu gpu = selectedGpus().stream()
                .filter(card -> card.vendor().equals(vendor) && card.index() == index).findFirst().orElse(null);
        if (gpu == null) return fail(key + " is not configured or was not detected");
        String model = gpu.model().toUpperCase(java.util.Locale.ROOT);
        if (model.matches(".*(?:GTX|TESLA P[0-9]+|TESLA V[0-9]+|TITAN V|QUADRO P[0-9]+).*"))
            return fail("The GPU model is not supported by this miner");
        WorkerRunState state = runs.computeIfAbsent(key, ignored -> new WorkerRunState(Coin.PEARL.id(), gpu,
                MinerConsoleService.gpuConsoleId(Coin.PEARL, vendor, index), "Waiting for a pool job"));
        if (state.running()) return true;
        if (!power.enforceConfiguredPowerLimit(gpu)) return fail(state, "Treiber bestätigte den Power-Cap nicht");
        refreshDefaultPayout();
        MinerConfig config = effectiveConfig();
        if (!binaryAvailable()) return fail(state, "PeakMiner is not installed");
        if (config == null || !proxy.matches(config.proxyUrl(), Coin.PEARL.id()))
            return fail(state, "Pearl SolarMiner proxy route is not configured");
        if (!proxy.miningReady(Coin.PEARL.id()))
            return fail(state, "Pearl SolarMiner proxy is unreachable");
        if (!proxy.feeReady(Coin.PEARL.id()))
            return fail(state, "Pearl SolarMiner fee target is unavailable");
        if (pearl.runningGpuDeviceIds().contains(gpu.deviceId()) || pearl.hasExternalMinerProcess()
                || MinerProcessRegistry.gpuCoinUsesDevice(gpu.deviceId()) || hasExternalMinerProcess())
            return fail(state, "Another miner already controls " + key);
        try {
            String suffix = "-n" + index;
            String worker = config.worker().substring(0, Math.min(config.worker().length(), 32 - suffix.length())) + suffix;
            int metricsPort = freeMetricsPort();
            List<String> command = buildCommand(download.executablePath(), config.proxyUrl(),
                    config.poolUrl(), config.wallet(), worker, metricsPort);
            Process process;
            try {
                ProcessBuilder builder = new ProcessBuilder(command).directory(download.installDirectory().toFile())
                        .redirectErrorStream(true);
                isolateGpu(builder, gpu);
                process = builder.start();
            } catch (IOException | RuntimeException failure) {
                throw failure;
            }
            state.process = process; state.apiPort = metricsPort;
            state.status = MinerStats.MinerStatus.MINING;
            state.healthy = false; state.hashrate = 0; state.error = null;
            state.acceptedShares = null; state.rejectedShares = null;
            state.poolCounters = MinerShareTelemetry.Counters.unavailable(); state.diagnosis = null;
            lastError = null;
            MinerProcessRegistry.registerGpuCoin(process, gpu.deviceId());
            if (runs.values().stream().noneMatch(candidate -> candidate != state && candidate.running()))
                console.started(Coin.PEARL.id());
            console.started(state.consoleId);
            console.append(Coin.PEARL.id(), "[" + key + "] Peak Miner start");
            drainOutput(state, process, config);
            Thread.ofVirtual().name("peak-health-" + index).start(() -> monitor(state, process));
            process.onExit().thenAccept(done -> {
                MinerProcessRegistry.unregisterGpuCoin(done);
                if (runs.get(key) == state && state.status == MinerStats.MinerStatus.MINING)
                    fail(state, "Peak Miner exited (code " + done.exitValue() + ")");
            });
            return true;
        } catch (IOException | RuntimeException failure) {
            if (state.relay != null) state.relay.close();
            return fail(state, "Could not start Peak Miner: " + failure.getMessage());
        }
    }

    static void isolateGpu(ProcessBuilder builder, LocalGpuPowerService.Gpu gpu) {
        if (!"NVIDIA".equals(gpu.vendor()) || gpu.deviceId() == null || !gpu.deviceId().startsWith("GPU-"))
            throw new IllegalArgumentException("Peak Miner requires a detected NVIDIA GPU UUID");
        builder.environment().put("CUDA_VISIBLE_DEVICES", gpu.deviceId());
    }

    static List<String> buildCommand(java.nio.file.Path executable, String proxyUrl, String poolUrl,
                                     String wallet, String worker, int metricsPort) {
        // PeakMiner repeats --worker in the wallet field. The existing Pearl proxy
        // already accepts this semicolon route as a single verbatim login, avoiding
        // a dependency on a newer proxy release for the Beta path.
        String login = poolUrl + ";" + wallet + "/" + worker + ";x";
        return List.of(executable.toString(), "--coin", "pearl", "--url", proxyUrl,
                "--user", login, "--devices", "0",
                "--api-port", Integer.toString(metricsPort), "--no-color", "--job-timeout", "120");
    }

    private void drainOutput(WorkerRunState state, Process process, MinerConfig config) {
        drainProcessOutput(process, "peak-output", line -> {
            String safe = line.replace(config.wallet(), "[wallet]").replace(config.poolUrl(), "[pool]")
                    .replace(Base64.getUrlEncoder().withoutPadding().encodeToString(
                            config.poolUrl().getBytes(StandardCharsets.UTF_8)), "[route]");
            MinerLogDiagnostics.classify(MinerLogDiagnostics.PEAKMINER, safe).ifPresent(found -> {
                if (state.diagnosis == null) state.diagnosis = found;
            });
            console.append(state.consoleId, safe);
            console.append(Coin.PEARL.id(), "[" + state.gpuLabel() + "] " + safe);
        }, failure -> LOGGER.log(Level.FINE, "Peak miner output ended", failure));
    }

    private void monitor(WorkerRunState state, Process process) {
        int failures = 0;
        long started = System.nanoTime();
        long lastJob = started;
        while (process.isAlive() && runs.get(gpuKey(state.gpu)) == state
                && state.status == MinerStats.MinerStatus.MINING) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + state.apiPort + "/summary"))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> response = metrics.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                PeakStats stats = PeakStats.parse(mapper.readTree(response.body()));
                // Version and fee are protocol/economic invariants. A mismatch is not a
                // transient metrics outage and must stop this run immediately.
                if (!download.version().equals(stats.version())) {
                    fail(state, "Unexpected Peak miner metrics version " + stats.version());
                    process.destroy();
                    return;
                }
                if (Math.abs(stats.feePercent() - 2.0) > 0.0001) {
                    fail(state, "Unexpected Peak miner developer fee " + stats.feePercent() + "%");
                    process.destroy();
                    return;
                }
                failures = 0;
                state.hashrate = stats.hashrateHs();
                temperatures.put(gpuKey(state.gpu), stats.temperatureCelsius());
                state.acceptedShares = stats.shares().accepted();
                state.rejectedShares = stats.shares().rejected();
                state.poolCounters = stats.shares();
                state.healthy = stats.hasActiveJob() && stats.hashrateHs() > 0;
                if (stats.hasActiveJob()) lastJob = System.nanoTime();
                state.detail = state.healthy ? "Peak Miner is hashing through the SolarMiner proxy"
                        : stats.hasActiveJob() ? "Pool connected; waiting for GPU hashrate" : "Waiting for a pool job";
                if (!stats.hasActiveJob() && Duration.ofNanos(System.nanoTime() - lastJob).toSeconds() >= 120) {
                    fail(state, "Peak Miner received no pool job for 120 seconds");
                    process.destroy();
                    return;
                }
                if (!proxy.miningReady(Coin.PEARL.id()) || !proxy.feeReady(Coin.PEARL.id())) {
                    fail(state, "Peak Miner lost its metrics or SolarMiner fee route");
                    process.destroy();
                    return;
                }
            } catch (Exception failure) {
                failures++;
                state.healthy = false;
                state.detail = "Peak miner metrics are unavailable";
                if (failures >= 24 || !proxy.miningReady(Coin.PEARL.id()) || !proxy.feeReady(Coin.PEARL.id())) {
                    fail(state, "Peak Miner lost its metrics or SolarMiner fee route");
                    process.destroy();
                    return;
                }
            }
            try { Thread.sleep(5_000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void refreshDefaultPayout() {
        MinerConfig current = configuration();
        if (current == null || !payouts.usesDefault(Coin.PEARL.id())) return;
        PayoutDefaultsService.DefaultPayout payout = payouts.resolve(Coin.PEARL.id()).orElse(null);
        if (payout == null) return;
        String worker = payout.workerPart() == null ? current.worker() : payout.workerPart();
        if (payout.poolUrl().equals(current.poolUrl()) && payout.walletPart().equals(current.wallet())
                && worker.equals(current.worker())) return;
        try {
            MinerConfig refreshed = new MinerConfig(payout.poolUrl(), current.proxyUrl(),
                    payout.walletPart(), worker, current.devices());
            if (de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(configFile, refreshed, mapper, "peak-config-"))
                savedConfiguration = refreshed;
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "Pearl default payout could not be refreshed", failure);
        }
    }

    private static int freeMetricsPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private boolean fail(String error) { lastError = error; return false; }
    private boolean fail(WorkerRunState state, String error) {
        state.status = MinerStats.MinerStatus.ERROR; state.healthy = false; state.hashrate = 0;
        state.error = error; state.detail = error; lastError = error;
        console.append(state.consoleId, "[SolarMiner] " + error);
        console.append(Coin.PEARL.id(), "[" + state.gpuLabel() + "] " + error);
        return false;
    }

    @Override public synchronized boolean stopGpu(String vendor, int index) {
        String key = vendor + ":" + index;
        WorkerRunState state = runs.get(key);
        if (state == null) return true;
        state.status = MinerStats.MinerStatus.PAUSED; state.healthy = false; state.hashrate = 0;
        temperatures.remove(key);
        state.acceptedShares = null; state.rejectedShares = null; state.poolCounters = MinerShareTelemetry.Counters.unavailable();
        state.detail = "Miner paused"; state.restartAttempts = 0;
        if (state.relay != null) state.relay.close();
        Process process = state.process;
        if (process == null || !process.isAlive()) return true;
        console.append(state.consoleId, "[SolarMiner] Stop: " + MinerStopContext.source());
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            return process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); process.destroyForcibly(); return false;
        }
    }
    @Override public synchronized boolean stopAll() {
        boolean success = true;
        for (WorkerRunState state : new ArrayList<>(runs.values()))
            success = stopGpu(state.gpu.vendor(), state.gpu.index()) && success;
        return success;
    }
    @Override public synchronized boolean pauseAll() {
        selectedGpus().forEach(gpu -> manuallyPaused.add(gpuKey(gpu)));
        return stopAll();
    }
    @Override public synchronized boolean pauseGpu(String vendor, int index) {
        manuallyPaused.add(vendor + ":" + index);
        return stopGpu(vendor, index);
    }
    @Override public synchronized boolean resumeGpu(String vendor, int index) {
        String key = vendor + ":" + index;
        manuallyPaused.remove(key);
        WorkerRunState state = runs.get(key);
        if (state != null) state.restartAttempts = 0;
        return startGpu(vendor, index);
    }
    @Override public boolean setPowerCap(long watts) { return power.setTotalPowerTarget(watts, selectedGpus()); }
    @Override public void appendBenchmarkEvent(String message) { console.append(Coin.PEARL.id(), message); }
    @Override public boolean hasExternalMinerProcess() {
        return MinerProcessRegistry.running("peakminer").stream().anyMatch(handle -> !MinerProcessRegistry.managedGpuCoin(handle));
    }
    @Override public MinerLogDiagnostics.Diagnosis diagnosis(String vendor, int index) {
        WorkerRunState state = runs.get(vendor + ":" + index);
        return state == null ? null : state.diagnosis;
    }
    @Override public List<GpuState> gpuStates(List<LocalGpuPowerService.Gpu> cards) {
        return cards.stream().map(gpu -> {
            String key = gpuKey(gpu);
            boolean selected = selectedGpus().stream().anyMatch(candidate -> candidate.deviceId().equals(gpu.deviceId()));
            WorkerRunState state = selected ? runs.get(key) : null;
            return new GpuState(gpu.vendor(), gpu.index(), gpu.model(), selected,
                    state == null ? MinerStats.MinerStatus.PAUSED : state.status,
                    state != null && state.running(), state != null && state.healthy,
                    selected && manuallyPaused.contains(key), state == null ? "Not started yet" : state.detail,
                    state == null ? null : state.error);
        }).toList();
    }
    @Override public List<MinerStats.Worker> workerStats(List<LocalGpuPowerService.Gpu> cards) {
        MinerConfig config = effectiveConfig();
        if (config == null) return List.of();
        return selectedGpus().stream().map(gpu -> {
            WorkerRunState state = runs.get(gpuKey(gpu));
            boolean reported = state != null && state.status == MinerStats.MinerStatus.MINING;
            MinerShareTelemetry.Counters counters = state == null ? MinerShareTelemetry.Counters.unavailable() : state.poolCounters;
            return new MinerStats.Worker(state == null ? MinerStats.MinerStatus.PAUSED : state.status,
                    "PeakMiner " + gpu.model() + " (pearlhash)", Coin.PEARL.algorithm(),
                    reported ? state.hashrate / 1_000_000_000_000d : 0,
                    reported ? temperatures.getOrDefault(gpuKey(gpu), 0d) : 0,
                    power.appliedTarget(gpu), gpu.minWatts(), gpu.maxWatts(), gpu.maxWatts(),
                    gpu.currentWatts() == null ? 0 : Math.round(gpu.currentWatts()),
                    List.of(new Pools(config.poolUrl(), config.wallet() + "/" + config.worker(), "")),
                    "GPU", gpu.model(), gpu.deviceId(), reported ? state.acceptedShares : null,
                    reported ? state.rejectedShares : null,
                    reported ? new MinerStats.PoolTelemetry(counters.difficulty(), counters.bestShare(), counters.stale(), counters.latencyMs())
                            : MinerStats.PoolTelemetry.unavailable());
        }).toList();
    }
    @Override public Set<String> runningGpuDeviceIds() {
        return runs.values().stream().filter(WorkerRunState::running).map(state -> state.gpu.deviceId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    @Override public Set<String> manuallyPausedGpuKeys() { return Set.copyOf(manuallyPaused); }
    @Override public boolean restoreWorkerState(Set<String> pausedBefore, Set<String> runningBefore) {
        boolean stopped = stopAll();
        manuallyPaused.clear();
        if (pausedBefore != null) manuallyPaused.addAll(pausedBefore);
        boolean success = stopped;
        if (runningBefore != null) for (LocalGpuPowerService.Gpu gpu : selectedGpus())
            if (runningBefore.contains(gpu.deviceId()) && !manuallyPaused.contains(gpuKey(gpu)))
                success = startGpu(gpu.vendor(), gpu.index()) && success;
        return success;
    }
    @Override public void setSweepOverride(MinerConfig config) { validateConfig(config); sweepOverride = config; }
    @Override public void clearSweepOverride() { sweepOverride = null; }
    @Override public void setBenchmarkOverride(MinerConfig config) { validateConfig(config); benchmarkOverride = config; }
    @Override public void clearBenchmarkOverride() { benchmarkOverride = null; }
    @Override public String reportedAlgorithm() { return Coin.PEARL.displayAlgorithm(); }

    @Scheduled(fixedDelay = 30_000)
    public synchronized void restartAfterUnexpectedExit() {
        if (localRuns.busy()) return;
        for (WorkerRunState state : List.copyOf(runs.values())) {
            if (manuallyPaused.contains(gpuKey(state.gpu)) || state.status != MinerStats.MinerStatus.ERROR
                    || (state.error != null && state.error.startsWith("Another miner already controls "))
                    || state.running() || state.restartAttempts >= MAX_CRASH_RESTARTS) continue;
            int attempts = ++state.restartAttempts;
            startGpu(state.gpu.vendor(), state.gpu.index());
            WorkerRunState restarted = runs.get(gpuKey(state.gpu));
            if (restarted != null) restarted.restartAttempts = attempts;
        }
    }

    @Override protected MinerConsoleService console() { return console; }
    @Override protected ObjectMapper mapper() { return mapper; }
    @Override protected Logger logger() { return LOGGER; }
    @PreDestroy public void shutdown() { stopAll(); }
}
