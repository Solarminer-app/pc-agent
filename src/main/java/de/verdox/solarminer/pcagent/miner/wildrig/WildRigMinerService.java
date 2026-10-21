package de.verdox.solarminer.pcagent.miner.wildrig;

import de.verdox.solarminer.pcagent.miner.MinerStartContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.dto.Pools;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.*;
import de.verdox.solarminer.pcagent.miner.diagnostics.MinerLogDiagnostics;
import de.verdox.solarminer.pcagent.miner.engine.AbstractMinerEngine;
import de.verdox.solarminer.pcagent.miner.engine.WorkerRunState;
import de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.mining.proxy.GpuStratumRelay;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import de.verdox.solarminer.pcagent.miner.pearl.PearlMinerService;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/** Quantus adapter for WildRig NG, one UUID-isolated process per selected NVIDIA GPU. */
@Service
public class WildRigMinerService extends AbstractMinerEngine implements GpuCoinMiner {
    private static final Logger LOGGER = Logger.getLogger(WildRigMinerService.class.getName());
    private final ObjectMapper mapper;
    private final ProxyConfigurationService proxy;
    private final GpuCoinMinerService configurations;
    private final PearlMinerService pearl;
    private final LocalGpuPowerService power;
    private final MinerConsoleService console;
    private final PayoutDefaultsService payouts;
    private final LocalRunLock localRuns;
    private final WildRigDownloadService download;
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
    public WildRigMinerService(ObjectMapper mapper, ProxyConfigurationService proxy,
                                GpuCoinMinerService configurations, PearlMinerService pearl,
                                LocalGpuPowerService power, MinerConsoleService console,
                                PayoutDefaultsService payouts, LocalRunLock localRuns,
                                WildRigDownloadService download,
                                @org.springframework.beans.factory.annotation.Value("${solarminer.wildrig.config-file:./solarminer-agent/wildrig/solarminer-quantus.json}") String configPath) {
        this.mapper = mapper; this.proxy = proxy; this.configurations = configurations; this.pearl = pearl;
        this.power = power; this.console = console; this.payouts = payouts; this.localRuns = localRuns;
        this.download = download;
        this.configFile = Path.of(configPath).toAbsolutePath().normalize();
        try {
            if (Files.isRegularFile(configFile)) {
                MinerConfig loaded = mapper.readValue(configFile.toFile(), MinerConfig.class);
                validateConfig(loaded);
                savedConfiguration = loaded;
            } else if (configurations.configuration(Coin.QUANTUS.id()) != null) {
                // One-time compatibility with beta builds that stored both Quantus miners in
                // SRBMiner's coin file. Only a WildRig-valid NVIDIA selection is adopted.
                MinerConfig legacy = configurations.configuration(Coin.QUANTUS.id());
                validateConfig(legacy);
                savedConfiguration = legacy;
            }
        } catch (Exception failure) {
            lastError = "Saved WildRig configuration is invalid: " + failure.getMessage();
        }
    }

    /** Focused-test compatibility constructor. */
    public WildRigMinerService(ObjectMapper mapper, ProxyConfigurationService proxy,
                                GpuCoinMinerService configurations, PearlMinerService pearl,
                                LocalGpuPowerService power, MinerConsoleService console,
                                PayoutDefaultsService payouts, LocalRunLock localRuns,
                                WildRigDownloadService download) {
        this(mapper, proxy, configurations, pearl, power, console, payouts, localRuns, download,
                "./solarminer-agent/wildrig/solarminer-quantus.json");
    }

    @Override public Coin coin() { return Coin.QUANTUS; }
    @Override public boolean configured() { return configuration() != null; }
    @Override public boolean routeConfigured() {
        MinerConfig config = configuration();
        return config != null && proxy.matches(config.proxyUrl(), Coin.QUANTUS.id());
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
        if (de.verdox.solarminer.pcagent.miner.MinerStartContext.savedConfigurationRequested()) return configuration();
        return benchmarkOverride != null ? benchmarkOverride
                : sweepOverride != null && de.verdox.solarminer.pcagent.miner.MinerStartContext.measurementRequested() ? sweepOverride : configuration();
    }

    @Override public MinerConfig configuration() { return savedConfiguration; }
    @Override public synchronized void applyConfig(MinerConfig config) throws IOException {
        validateConfig(config);
        if (!proxy.matches(config.proxyUrl(), Coin.QUANTUS.id()))
            throw new IllegalArgumentException("SolarMiner proxy route does not match Quantus");
        if (!config.sameRoute(savedConfiguration)) {
            if (!stopAll()) throw new IOException("WildRig Miner could not be stopped before saving the configuration");
        } else {
            for (WorkerRunState state : List.copyOf(runs.values())) {
                if (!config.selects(state.gpu.vendor(), state.gpu.index())) {
                    if (!stopGpu(state.gpu.vendor(), state.gpu.index()))
                        throw new IOException("WildRig Miner could not be stopped before saving the configuration");
                    runs.remove(gpuKey(state.gpu), state);
                }
            }
        }
        if (!de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(configFile, config, mapper, "wildrig-config-"))
            throw new IOException("WildRig Miner configuration could not be written");
        savedConfiguration = config;
        manuallyPaused.retainAll(configuredDevices(config));
    }
    @Override public void validateConfig(MinerConfig config) {
        GpuCoinMinerService.validate(Coin.QUANTUS.id(), config);
        validateDeviceSelection(config.devices());
    }

    static void validateDeviceSelection(String selection) {
        List<String> devices = selection == null ? List.of() : List.of(selection.split(","));
        if (devices.isEmpty()) throw new IllegalArgumentException("WildRig Miner requires one or more NVIDIA GPUs");
        if (devices.stream().anyMatch(device -> !device.matches("NVIDIA:[0-9]+")))
            throw new IllegalArgumentException("WildRig Miner requires one or more NVIDIA GPUs");
        if (devices.stream().distinct().count() != devices.size())
            throw new IllegalArgumentException("WildRig Miner GPU selection contains duplicates");
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
        if (MinerStartContext.operatorRequested()) runs.values().forEach(state -> state.restartAttempts = 0);
        return startForBudget();
    }
    @Override public synchronized boolean startForBudget() {
        List<LocalGpuPowerService.Gpu> selected = eligibleGpus();
        boolean success = !selected.isEmpty();
        for (LocalGpuPowerService.Gpu gpu : selected)
            success = startGpu(gpu.vendor(), gpu.index()) && success;
        if (!success && selected.isEmpty()) lastError = "No configured WildRig NVIDIA GPU was detected";
        return success;
    }
    @Override public synchronized boolean startGpu(String vendor, int index) {
        String key = vendor + ":" + index;
        if (!"NVIDIA".equals(vendor)) return fail("WildRig Miner requires an NVIDIA GPU");
        if (manuallyPaused.contains(key)) return false;
        LocalGpuPowerService.Gpu gpu = selectedGpus().stream()
                .filter(card -> card.vendor().equals(vendor) && card.index() == index).findFirst().orElse(null);
        if (gpu == null) return fail(key + " is not configured or was not detected");
        String model = gpu.model().toUpperCase(java.util.Locale.ROOT);
        if (model.matches(".*(?:GTX [79][0-9]{2}|TESLA M[0-9]+|TITAN X).*"))
            return fail("The GPU model is not supported by this miner");
        if (!workerStartAllowed(gpu.deviceId())) {
            lastError = "worker_manually_stopped";
            return false;
        }
        WorkerRunState previousAttempt = runs.get(key);
        if (previousAttempt != null && previousAttempt.status == MinerStats.MinerStatus.ERROR
                && !MinerStartContext.mayRetryFailure()) {
            lastError = previousAttempt.error;
            return false;
        }
        WorkerRunState state = runs.compute(key, (ignored, previous) -> WorkerRunState.nextAttempt(previous, Coin.QUANTUS.id(), gpu,
                MinerConsoleService.gpuConsoleId(Coin.QUANTUS, vendor, index), "Waiting for a pool job"));
        if (state.running()) return state.restartAllowed;
        if (!power.enforceConfiguredPowerLimit(gpu)) return fail(state, "Treiber bestätigte den Power-Cap nicht");
        refreshDefaultPayout();
        MinerConfig config = effectiveConfig();
        if (!binaryAvailable()) return fail(state, "WildRig NG is not installed");
        if (config == null || !proxy.matches(config.proxyUrl(), Coin.QUANTUS.id()))
            return fail(state, "Quantus SolarMiner proxy route is not configured");
        if (!proxy.miningReady(Coin.QUANTUS.id()))
            return fail(state, "Quantus SolarMiner proxy is unreachable");
        if (!proxy.feeReady(Coin.QUANTUS.id()))
            return fail(state, "Quantus SolarMiner fee target is unavailable");
        if (pearl.runningGpuDeviceIds().contains(gpu.deviceId()) || pearl.hasExternalMinerProcess()
                || MinerProcessRegistry.gpuCoinUsesDevice(gpu.deviceId()) || hasExternalMinerProcess())
            return fail(state, "Another miner already controls " + key);
        try {
            String suffix = "-n" + index;
            String worker = config.worker().substring(0, Math.min(config.worker().length(), 32 - suffix.length())) + suffix;
            String login = encodedLogin(config, worker);
            GpuStratumRelay relay = new GpuStratumRelay(config.proxyUrl(), login, mapper);
            state.relay = relay;
            int metricsPort = freeMetricsPort();
            List<String> command = buildCommand(download.executablePath(), relay.localUrl(), login, metricsPort);
            Process process;
            try {
                ProcessBuilder builder = new ProcessBuilder(command).directory(download.installDirectory().toFile())
                        .redirectErrorStream(true);
                isolateGpu(builder, gpu);
                process = MinerProcessRegistry.startGpu(gpu.deviceId(), builder::start);
            } catch (IOException | RuntimeException failure) {
                relay.close();
                throw failure;
            }
            state.process = process; state.apiPort = metricsPort;
            state.status = MinerStats.MinerStatus.MINING;
            state.healthy = false; state.hashrate = 0; state.error = null;
            state.acceptedShares = null; state.rejectedShares = null;
            state.poolCounters = MinerShareTelemetry.Counters.unavailable(); state.diagnosis = null;
            lastError = null;
            if (runs.values().stream().noneMatch(candidate -> candidate != state && candidate.running()))
                console.started(Coin.QUANTUS.id());
            console.started(state.consoleId);
            console.append(Coin.QUANTUS.id(), "[" + key + "] WildRig Miner start");
            drainOutput(state, process, config);
            Thread.ofVirtual().name("wildrig-health-" + index).start(() -> monitor(state, process));
            process.onExit().thenAccept(done -> {
                relay.close(); MinerProcessRegistry.unregisterGpuCoin(done);
                if (runs.get(key) == state && state.monitors(done))
                    fail(state, "WildRig Miner exited (code " + done.exitValue() + ")");
            });
            return true;
        } catch (IOException | RuntimeException failure) {
            if (state.relay != null) state.relay.close();
            return fail(state, "Could not start WildRig Miner: " + failure.getMessage());
        }
    }

    static void isolateGpu(ProcessBuilder builder, LocalGpuPowerService.Gpu gpu) {
        if (!"NVIDIA".equals(gpu.vendor()) || gpu.deviceId() == null || !gpu.deviceId().startsWith("GPU-"))
            throw new IllegalArgumentException("WildRig Miner requires a detected NVIDIA GPU UUID");
        builder.environment().put("CUDA_VISIBLE_DEVICES", gpu.deviceId());
    }

    static List<String> buildCommand(java.nio.file.Path executable, String proxyUrl, String login, int metricsPort) {
        return List.of(executable.toString(), "--algo", "quantus", "--url", proxyUrl,
                "--user", login, "--pass", "x", "--gpu-list", "0",
                "--gpu-no-amd", "--gpu-no-intel", "--ui-plain", "--no-watchdog",
                "--api-port", Integer.toString(metricsPort));
    }

    static String encodedLogin(MinerConfig config, String worker) {
        String route = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(config.poolUrl().getBytes(StandardCharsets.UTF_8));
        return config.wallet() + ".sm1." + route + "." + worker;
    }

    private void drainOutput(WorkerRunState state, Process process, MinerConfig config) {
        drainProcessOutput(process, "wildrig-output", line -> {
            String safe = line.replace(config.wallet(), "[wallet]").replace(config.poolUrl(), "[pool]")
                    .replace(Base64.getUrlEncoder().withoutPadding().encodeToString(
                            config.poolUrl().getBytes(StandardCharsets.UTF_8)), "[route]");
            MinerLogDiagnostics.classify(MinerLogDiagnostics.WILDRIG_NG, safe).ifPresent(found -> {
                if (state.diagnosis == null) state.diagnosis = found;
            });
            console.append(state.consoleId, safe);
            console.append(Coin.QUANTUS.id(), "[" + state.gpuLabel() + "] " + safe);
        }, failure -> LOGGER.log(Level.FINE, "WildRig miner output ended", failure));
    }

    private void monitor(WorkerRunState state, Process process) {
        int failures = 0;
        long started = System.nanoTime();
        long lastJob = started;
        while (process.isAlive() && runs.get(gpuKey(state.gpu)) == state
                && state.status == MinerStats.MinerStatus.MINING) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + state.apiPort + "/2/summary"))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> response = metrics.send(request, HttpResponse.BodyHandlers.ofString());
                synchronized (state) {
                    if (!state.monitors(process)) return;
                    if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                    WildRigStats stats = WildRigStats.parse(mapper.readTree(response.body()));
                    // Version and fee are protocol/economic invariants. A mismatch is not a
                    // transient metrics outage and must stop this run immediately.
                    if (!download.version().equals(stats.version())) {
                        fail(state, "Unexpected WildRig miner metrics version " + stats.version());
                        terminateFailedProcess(process);
                        return;
                    }
                    if (Math.abs(stats.feePercent() - 2.0) > 0.0001) {
                        fail(state, "Unexpected WildRig miner developer fee " + stats.feePercent() + "%");
                        terminateFailedProcess(process);
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
                    state.detail = state.healthy ? "WildRig Miner is hashing through the SolarMiner proxy"
                            : stats.hasActiveJob() ? "Pool connected; waiting for GPU hashrate" : "Waiting for a pool job";
                    if (!stats.hasActiveJob() && Duration.ofNanos(System.nanoTime() - lastJob).toSeconds() >= 120) {
                        fail(state, "WildRig Miner received no pool job for 120 seconds");
                        terminateFailedProcess(process);
                        return;
                    }
                    if (!proxy.miningReady(Coin.QUANTUS.id()) || !proxy.feeReady(Coin.QUANTUS.id())) {
                        fail(state, "WildRig Miner lost its metrics or SolarMiner fee route");
                        terminateFailedProcess(process);
                        return;
                    }
                }
            } catch (Exception failure) {
                synchronized (state) {
                    if (!state.monitors(process)) return;
                    failures++;
                    state.healthy = false;
                    state.detail = "WildRig miner metrics are unavailable";
                    if (failures >= 24 || !proxy.miningReady(Coin.QUANTUS.id()) || !proxy.feeReady(Coin.QUANTUS.id())) {
                        fail(state, "WildRig Miner lost its metrics or SolarMiner fee route");
                        terminateFailedProcess(process);
                        return;
                    }
                }
            }
            try { Thread.sleep(5_000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void refreshDefaultPayout() {
        MinerConfig current = configuration();
        if (current == null || !payouts.usesDefault(Coin.QUANTUS.id())) return;
        PayoutDefaultsService.DefaultPayout payout = payouts.resolve(Coin.QUANTUS.id()).orElse(null);
        if (payout == null) return;
        String worker = payout.workerPart() == null ? current.worker() : payout.workerPart();
        if (payout.poolUrl().equals(current.poolUrl()) && payout.walletPart().equals(current.wallet())
                && worker.equals(current.worker())) return;
        try {
            MinerConfig refreshed = new MinerConfig(payout.poolUrl(), current.proxyUrl(),
                    payout.walletPart(), worker, current.devices());
            if (de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(configFile, refreshed, mapper, "wildrig-config-"))
                savedConfiguration = refreshed;
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "Quantus default payout could not be refreshed", failure);
        }
    }

    private static int freeMetricsPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private boolean fail(String error) { lastError = error; return false; }
    private boolean fail(WorkerRunState state, String error) {
        synchronized (state) {
            if (!state.restartAllowed) return false;
            state.scheduleRecovery();
            state.status = MinerStats.MinerStatus.ERROR; state.healthy = false; state.hashrate = 0;
            state.error = error; state.detail = error; lastError = error;
            console.append(state.consoleId, "[SolarMiner] " + error);
            console.append(Coin.QUANTUS.id(), "[" + state.gpuLabel() + "] " + error);
            return false;
        }
    }

    @Override public synchronized boolean stopDevice(String deviceId) {
        boolean stopped = true;
        for (WorkerRunState run : List.copyOf(runs.values()))
            if (deviceId.equals(run.gpu.deviceId()))
                stopped = stopGpu(run.gpu.vendor(), run.gpu.index()) && stopped;
        return stopped;
    }

    @Override public synchronized boolean stopGpu(String vendor, int index) {
        String key = vendor + ":" + index;
        WorkerRunState state = runs.get(key);
        if (state == null) return true;
        state.requestStop("Miner paused");
        temperatures.remove(key);
        if (state.relay != null) state.relay.close();
        Process process = state.process;
        if (process == null || !process.isAlive()) return true;
        console.append(state.consoleId, "[SolarMiner] Stop: " + MinerStopContext.source());
        return state.finishStop(de.verdox.solarminer.pcagent.support.ProcessTermination.terminate(
                List.of(process.toHandle()), Duration.ofSeconds(5)));
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
        if (state != null && MinerStartContext.operatorRequested()) state.restartAttempts = 0;
        return startGpu(vendor, index);
    }
    @Override public boolean setPowerCap(long watts) { return power.setTotalPowerTarget(watts, selectedGpus()); }
    @Override public void appendBenchmarkEvent(String message) { console.append(Coin.QUANTUS.id(), message); }
    @Override public boolean hasExternalMinerProcess() {
        return MinerProcessRegistry.running("wildrig-ng").stream().anyMatch(MinerProcessRegistry::unmanagedGpuCoin);
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
                    "WildRig NG " + gpu.model() + " (quantus)", Coin.QUANTUS.algorithm(),
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
    @Override public String reportedAlgorithm() { return Coin.QUANTUS.displayAlgorithm(); }

    @Scheduled(fixedDelay = 30_000)
    public synchronized void restartAfterUnexpectedExit() {
        if (localRuns.busy()) return;
        for (WorkerRunState state : List.copyOf(runs.values())) {
            if (manuallyPaused.contains(gpuKey(state.gpu)) || !state.restartEligible()
                    || (state.error != null && state.error.startsWith("Another miner already controls "))) continue;
            int attempts = ++state.restartAttempts;
            MinerStartContext.recovery(() -> startGpu(state.gpu.vendor(), state.gpu.index()));
            WorkerRunState restarted = runs.get(gpuKey(state.gpu));
            if (restarted != null) restarted.restartAttempts = attempts;
        }
    }

    @Override protected MinerConsoleService console() { return console; }
    @Override protected ObjectMapper mapper() { return mapper; }
    @Override protected Logger logger() { return LOGGER; }
    @PreDestroy public void shutdown() { stopAll(); }
}
