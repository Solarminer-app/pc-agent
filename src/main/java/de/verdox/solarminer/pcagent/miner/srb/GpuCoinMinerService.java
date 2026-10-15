package de.verdox.solarminer.pcagent.miner.srb;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.dto.Pools;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.miner.MinerProcessRegistry;
import de.verdox.solarminer.pcagent.miner.MinerStopContext;
import de.verdox.solarminer.pcagent.miner.MinerShareTelemetry;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.miner.engine.AbstractSrbMinerEngine;
import de.verdox.solarminer.pcagent.miner.engine.SrbMonitorPolicy;
import de.verdox.solarminer.pcagent.miner.engine.WorkerRunState;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.miner.GpuState;
import de.verdox.solarminer.pcagent.mining.proxy.GpuStratumRelay;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;

/** Manages RVN/ETC GPU processes through the same SRBMiner installation as Pearl. */
@Service
public class GpuCoinMinerService extends AbstractSrbMinerEngine {
    private static final Logger LOGGER = Logger.getLogger(GpuCoinMinerService.class.getName());
    private final ObjectMapper mapper;
    private final ProxyConfigurationService proxy;
    private final PearlMinerService pearl;
    private final LocalGpuPowerService power;
    private final MinerConsoleService console;
    private final PayoutDefaultsService payoutDefaultsService;
    private final LocalRunLock localRuns;
    private final Path configDirectory;
    private final Map<String, MinerConfig> configs = new ConcurrentHashMap<>();
    /**
     * Ephemeral fee-backend configurations used only while the efficiency sweep measures a
     * coin the operator never configured. They are never written to disk and are removed
     * when the sweep ends, so the operator's configuration state is untouched.
     */
    private final Map<String, MinerConfig> sweepOverrides = new ConcurrentHashMap<>();
    private final Map<String, MinerConfig> benchmarkOverrides = new ConcurrentHashMap<>();
    private final Map<String, WorkerRunState> runs = new ConcurrentHashMap<>();
    private final Set<String> manuallyPaused = ConcurrentHashMap.newKeySet();
    private volatile String lastError;

    public GpuCoinMinerService(ObjectMapper mapper, ProxyConfigurationService proxy, PearlMinerService pearl,
                               LocalGpuPowerService power, MinerConsoleService console,
                               PayoutDefaultsService payoutDefaultsService, LocalRunLock localRuns,
                               @Value("${solarminer.gpu-coin.config-directory:./solarminer-agent/srbminer}") String directory) {
        this.mapper = mapper; this.proxy = proxy; this.pearl = pearl; this.power = power; this.console = console;
        this.payoutDefaultsService = payoutDefaultsService; this.localRuns = localRuns;
        this.configDirectory = Path.of(directory).toAbsolutePath().normalize();
        for (Coin coin : srbCoins()) {
            try {
                Path file = file(coin.id());
                if (Files.isRegularFile(file)) {
                    MinerConfig config = mapper.readValue(file.toFile(), MinerConfig.class);
                    validate(coin.id(), config);
                    configs.put(coin.id(), config);
                }
            } catch (Exception e) {
                lastError = coin.id() + ": gespeicherte Konfiguration ungültig: " + e.getMessage();
            }
        }
    }

    /** GPU coins served by this shared SRBMiner adapter (Pearl has its own adapter). */
    public static java.util.List<Coin> srbCoins() { return Coin.sharedSrbCoins(); }
    public static boolean supported(String coin) {
        Coin parsed = Coin.byIdOrNull(coin);
        return parsed != null && parsed.isSharedSrbCoin();
    }
    public static String algorithm(String coin) {
        Coin parsed = Coin.byIdOrNull(coin);
        if (parsed == null || !supported(parsed.id())) throw new IllegalArgumentException("Unbekannter GPU-Coin");
        return parsed.algorithm();
    }

    private Path file(String coin) { return configDirectory.resolve("solarminer-" + coin + ".json"); }
    public MinerConfig configuration(String coin) { return configs.get(coin); }
    /** Operator config wins; the ephemeral sweep override is only consulted for unconfigured coins. */
    private MinerConfig effectiveConfig(String coin) {
        MinerConfig benchmark = benchmarkOverrides.get(coin);
        if (benchmark != null) return benchmark;
        MinerConfig configured = configs.get(coin);
        return configured != null ? configured : sweepOverrides.get(coin);
    }
    public void setSweepOverride(String coin, MinerConfig config) { sweepOverrides.put(coin, config); }
    public void clearSweepOverride(String coin) { sweepOverrides.remove(coin); }
    public void setBenchmarkOverride(String coin, MinerConfig config) { benchmarkOverrides.put(coin, config); }
    public void clearBenchmarkOverride(String coin) { benchmarkOverrides.remove(coin); }
    public boolean binaryAvailable() { return pearl.binaryAvailable(); }
    public String lastError() { return lastError; }
    /** True when the given stratum url is this agent's proxy endpoint for the coin. */
    public boolean routeMatches(String coin, String stratumUrl) { return proxy.matches(stratumUrl, coin); }
    /** SRBMiner started outside the agent (shared binary with Pearl). */
    public boolean hasExternalSrbMiner() { return pearl.hasExternalMinerProcess(); }
    /** Mirror an orchestration event into a coin's aggregate console. */
    public void appendConsoleEvent(String coin, String message) { console.append(coin, message); }

    public static void validate(String coin, MinerConfig config) {
        Coin parsed = Coin.byIdOrNull(coin);
        if (!supported(coin)) throw new IllegalArgumentException("Unbekannter GPU-Coin");
        if (config == null) throw new IllegalArgumentException("Konfiguration fehlt");
        de.verdox.solarminer.pcagent.miner.engine.RouteValidator.validateRoute(config,
                de.verdox.solarminer.pcagent.miner.engine.WalletRule.forCoin(parsed),
                "Pool muss stratum+tcp/ssl://host:port sein",
                "Proxy muss stratum+tcp://host:port sein",
                "Ungültige " + coin + "-Wallet",
                "Worker muss 1–32 Zeichen aus Buchstaben, Zahlen, _ oder - enthalten");
        de.verdox.solarminer.pcagent.miner.engine.RouteValidator.validateDevices(config.devices(),
                "^((NVIDIA|AMD):[0-9]+)(,((NVIDIA|AMD):[0-9]+))*$",
                "Mindestens eine GPU im Format NVIDIA:0 oder AMD:0 wählen");
    }

    public synchronized void configure(String coin, MinerConfig config) throws IOException {
        validate(coin, config);
        if (!proxy.matches(config.proxyUrl(), coin)) throw new IllegalArgumentException("SolarMiner-Proxy-Route stimmt nicht");
        if (!stop(coin)) throw new IOException("GPU-Miner konnten nicht angehalten werden");
        writeConfig(coin, config);
        configs.put(coin, config);
        manuallyPaused.removeIf(key -> key.startsWith(coin + ":"));
        lastError = null;
    }

    /** Updates only an existing coin's proxy endpoint after the shared connection changes. */
    public synchronized boolean updateProxyRoute(String coin, String proxyUrl) throws IOException {
        MinerConfig updated = de.verdox.solarminer.pcagent.miner.engine.RouteValidator
                .withProxyRoute(configs.get(coin), proxyUrl);
        if (updated == null) return false;
        validate(coin, updated);
        if (!proxy.matches(proxyUrl, coin)) throw new IllegalArgumentException("SolarMiner-Proxy-Route stimmt nicht");
        writeConfig(coin, updated);
        configs.put(coin, updated);
        return true;
    }

    private void writeConfig(String coin, MinerConfig config) throws IOException {
        if (!de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(file(coin), config, mapper, coin + "-"))
            throw new IOException("GPU coin configuration could not be written");
    }

    /**
     * O2b (owner 2026-10-11): a saved fee-backend payout is re-read on every start so a
     * superseded house pool or wallet never keeps mining somewhere else. Closes the drift
     * gap where only XMRig and Pearl had this refresh. The operator's own route is untouched.
     */
    private void refreshDefaultPayout(String coin) {
        MinerConfig current = configs.get(coin);
        if (current == null || !payoutDefaultsService.usesDefault(coin)) return;
        PayoutDefaultsService.DefaultPayout payout = payoutDefaultsService.resolve(coin).orElse(null);
        if (payout == null) return;
        String worker = payout.workerPart() != null ? payout.workerPart() : current.worker();
        if (payout.poolUrl().equals(current.poolUrl()) && payout.walletPart().equals(current.wallet())
                && worker.equals(current.worker())) return;
        try {
            MinerConfig refreshed = new MinerConfig(payout.poolUrl(), current.proxyUrl(), payout.walletPart(), worker, current.devices());
            validate(coin, refreshed);
            writeConfig(coin, refreshed);
            configs.put(coin, refreshed);
            console.append(coin, "[SolarMiner] Payout updated to the current SolarMiner default target");
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Default " + coin + " payout could not be refreshed", e);
        }
    }

    public List<LocalGpuPowerService.Gpu> selected(String coin) {
        MinerConfig config = effectiveConfig(coin);
        if (config == null) return List.of();
        List<String> devices = List.of(config.devices().split(","));
        return power.discover().stream().filter(gpu -> devices.contains(gpu.vendor() + ":" + gpu.index())).toList();
    }

    public List<LocalGpuPowerService.Gpu> eligible(String coin) {
        return selected(coin).stream().filter(gpu ->
                !manuallyPaused.contains(coin + ":" + gpu.vendor() + ":" + gpu.index())).toList();
    }

    public synchronized boolean start(String coin) {
        if (!supported(coin)) return false;
        manuallyPaused.removeIf(key -> key.startsWith(coin + ":"));
        resetRestartBudget(coin);
        boolean success = !selected(coin).isEmpty();
        for (LocalGpuPowerService.Gpu gpu : selected(coin)) success = startGpu(coin, gpu.vendor(), gpu.index()) && success;
        if (!success && selected(coin).isEmpty()) lastError = "Keine konfigurierte GPU erkannt";
        return success;
    }

    /**
     * O1 (owner 2026-10-11): bounded crash restart for SRB GPU workers, mirroring the XMRig
     * watchdog (max {@code MAX_CRASH_RESTARTS} attempts per worker, 30 s scan cadence).
     * Skipped while a local measurement run owns the hardware, so a benchmark or efficiency
     * sweep never races a restart. The budget refills on an operator start/stop/resume.
     */
    @Scheduled(fixedDelay = 30_000)
    public synchronized void restartAfterUnexpectedExit() {
        if (localRuns.busy()) return;
        for (WorkerRunState run : List.copyOf(runs.values())) {
            if (!restartEligible(run)) continue;
            run.restartAttempts++;
            console.append(run.coin, "[SolarMiner] SRBMiner watchdog restart " + run.restartAttempts
                    + "/" + MAX_CRASH_RESTARTS + " (" + run.gpuLabel() + ")");
            startGpu(run.coin, run.gpu.vendor(), run.gpu.index());
        }
    }

    /** An operator-initiated action refills the bounded O1 restart budget for this coin. */
    private void resetRestartBudget(String coin) {
        for (WorkerRunState run : runs.values()) if (run.coin.equals(coin)) run.restartAttempts = 0;
    }

    public synchronized boolean startGpu(String coin, String vendor, int index) {
        if (!supported(coin)) return false;
        LocalGpuPowerService.Gpu gpu = selected(coin).stream()
                .filter(card -> card.vendor().equals(vendor) && card.index() == index).findFirst().orElse(null);
        if (gpu == null) return false;
        String key = coin + ":" + vendor + ":" + index;
        if (manuallyPaused.contains(key)) return false;
        WorkerRunState run = runs.computeIfAbsent(key, ignored ->
                new WorkerRunState(coin, gpu,
                        MinerConsoleService.gpuConsoleId(Coin.byIdOrNull(coin), vendor, index), "Noch nicht gestartet"));
        if (run.running()) return true;
        if (!power.enforceUserLimits(gpu)) return fail(run, "Treiber bestätigte den Power-Cap nicht");
        refreshDefaultPayout(coin);
        MinerConfig config = effectiveConfig(coin);
        if (config == null || !proxy.matches(config.proxyUrl(), coin) || !proxy.miningReady(coin)
                || !proxy.feeReady(coin) || !binaryAvailable())
            return fail(run, "SolarMiner-Proxy, Fee-Ziel oder SRBMiner für " + coin + " nicht bereit");
        if (pearl.runningGpuDeviceIds().contains(gpu.deviceId()) || runningOther(coin, gpu.deviceId()) || hasUnmanagedMiner())
            return fail(run, "SRBMiner läuft bereits für einen anderen Coin oder außerhalb des PC-Agent");
        try {
            int gpuId = pearl.mappedGpuId(gpu);
            int apiPort = 13000 + apiPortOffset(Coin.byIdOrNull(coin)) + gpuId;
            String suffix = "-" + (vendor.equals("NVIDIA") ? "n" : "a") + index;
            String worker = config.worker().substring(0, Math.min(config.worker().length(), 32 - suffix.length())) + suffix;
            String login = encodedLogin(config, worker);
            if (run.relay != null) run.relay.close();
            var relay = new de.verdox.solarminer.pcagent.mining.proxy.GpuStratumRelay(config.proxyUrl(), login, mapper);
            run.relay = relay;
            MinerConfig relayed = new MinerConfig(config.poolUrl(), relay.localUrl(), config.wallet(), config.worker(), config.devices());
            List<String> command = buildCommand(pearl.executablePath(), coin, relayed, login, apiPort, gpuId);
            Process process = new ProcessBuilder(command).directory(pearl.executablePath().getParent().toFile())
                    .redirectErrorStream(true).start();
            MinerProcessRegistry.registerGpuCoin(process, gpu.deviceId());
            run.process = process; run.apiPort = apiPort; run.status = MinerStats.MinerStatus.MINING;
            run.healthy = false; run.hashrate = 0; run.error = null; run.detail = "Warte auf Pool-Job";
            run.acceptedShares = null; run.rejectedShares = null;
            run.poolCounters = MinerShareTelemetry.Counters.unavailable();
            run.diagnosis = null;
            run.output.set("");
            lastError = null;
            // The aggregate coin console belongs to the whole GPU worker set.
            // Reset it only for the first worker; starting a second GPU must not
            // erase output already emitted by the first one.
            boolean noOtherCoinGpuRunning = runs.values().stream()
                    .noneMatch(candidate -> candidate != run && candidate.coin.equals(coin) && candidate.running());
            if (noOtherCoinGpuRunning) console.started(coin);
            console.started(run.consoleId);
            console.append(coin, "[" + gpu.vendor() + ":" + gpu.index() + "] New miner start");
            startOutputDrain(run, process, coin + "-output-" + index, true, false,
                    raw -> raw.replace(config.wallet(), "[wallet]").replace(config.poolUrl(), "[pool]"));
            Thread.ofVirtual().name(coin + "-health-" + index).start(() -> monitorLoop(run, process, SrbMonitorPolicy.gpuCoin(),
                    () -> proxy.miningReady(run.coin) && proxy.feeReady(run.coin),
                    candidate -> runs.get(candidate.key) == candidate));
            process.onExit().thenAccept(done -> {
                relay.close();
                MinerProcessRegistry.unregisterGpuCoin(done);
                if (unexpectedExit(run, done))
                    fail(run, "SRBMiner beendet (Code " + done.exitValue() + ")");
            });
            return true;
        } catch (IOException e) {
            if (run.relay != null) run.relay.close();
            return fail(run, "SRBMiner-Start fehlgeschlagen: " + e.getMessage());
        }
    }

    public synchronized boolean startForBudget(String coin) {
        boolean success = !eligible(coin).isEmpty();
        for (LocalGpuPowerService.Gpu gpu : eligible(coin))
            success = startGpu(coin, gpu.vendor(), gpu.index()) && success;
        return success;
    }

    private boolean runningOther(String coin, String deviceId) {
        return runs.values().stream().anyMatch(run -> !run.coin.equals(coin) && run.gpu.deviceId().equals(deviceId) && run.running());
    }

    static String encodedLogin(MinerConfig config, String worker) {
        String route = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(config.poolUrl().getBytes(StandardCharsets.UTF_8));
        return config.wallet() + ".sm1." + route + "." + worker;
    }

    static List<String> buildCommand(Path executable, String coin, MinerConfig config, String login,
                                     int apiPort, int gpuId) {
        URI uri = URI.create(config.proxyUrl());
        List<String> command = new ArrayList<>(List.of(executable.toString(), "--disable-cpu", "--algorithm-gpu", algorithm(coin),
                "--pool", uri.getHost() + ":" + uri.getPort(), "--wallet", login,
                "--tls", "false", "--api-enable", "--api-port", Integer.toString(apiPort),
                "--gpu-id", Integer.toString(gpuId)));
        // Select the proxy's dialect explicitly, including extranonce negotiation.
        switch (Coin.byIdOrNull(coin)) {
            case ETHEREUMCLASSIC -> command.addAll(List.of("--esm", "2"));
            case RAVENCOIN -> command.addAll(List.of("--nicehash", "true"));
            default -> { }
        }
        return List.copyOf(command);
    }

    private boolean hasUnmanagedMiner() {
        return pearl.hasExternalMinerProcess();
    }

    public de.verdox.solarminer.pcagent.miner.diagnostics.MinerLogDiagnostics.Diagnosis diagnosis(String coin, String vendor, int index) {
        WorkerRunState run = runs.get(coin + ":" + vendor + ":" + index);
        return run == null ? null : run.diagnosis;
    }

    @Override protected MinerConsoleService console() { return console; }
    @Override protected ObjectMapper mapper() { return mapper; }
    @Override protected Logger logger() { return LOGGER; }
    @Override protected String aggregateConsoleId(WorkerRunState run) { return run.coin; }
    @Override protected String aggregateTag(WorkerRunState run) { return run.gpuLabel(); }
    @Override protected String drainTag(WorkerRunState run) { return Integer.toString(run.gpu.index()); }

    @Override
    protected boolean fail(WorkerRunState run, String error) {
        var diagnosis = run.diagnosis;
        if (diagnosis != null && !error.contains(diagnosis.detail())) error = error + " · " + diagnosis.detail();
        run.status = MinerStats.MinerStatus.ERROR; run.error = error; run.detail = error;
        run.healthy = false; run.hashrate = 0; lastError = error;
        console.append(run.consoleId, "[SolarMiner] " + error);
        console.append(run.coin, "[" + run.gpu.vendor() + ":" + run.gpu.index() + "] " + error);
        return false;
    }

    public synchronized boolean stopGpu(String coin, String vendor, int index) {
        WorkerRunState run = runs.get(coin + ":" + vendor + ":" + index);
        if (run == null) return true;
        run.status = MinerStats.MinerStatus.PAUSED; run.healthy = false; run.hashrate = 0;
        run.acceptedShares = null; run.rejectedShares = null; run.poolCounters = MinerShareTelemetry.Counters.unavailable(); run.detail = "Pausiert";
        // An explicit stop refills the bounded O1 restart budget (mirrors the XMRig stop path).
        run.restartAttempts = 0;
        if (run.relay != null) run.relay.close();
        return stopProcess(run, MinerStopContext.source(), false, null);
    }

    public synchronized boolean stop(String coin) {
        boolean success = true;
        for (WorkerRunState run : new ArrayList<>(runs.values()))
            if (run.coin.equals(coin)) success = stopGpu(coin, run.gpu.vendor(), run.gpu.index()) && success;
        return success;
    }
    public synchronized boolean pause(String coin) {
        for (LocalGpuPowerService.Gpu gpu : selected(coin)) manuallyPaused.add(coin + ":" + gpu.vendor() + ":" + gpu.index());
        return stop(coin);
    }
    public synchronized boolean pauseGpu(String coin, String vendor, int index) {
        manuallyPaused.add(coin + ":" + vendor + ":" + index); return stopGpu(coin, vendor, index);
    }
    public synchronized boolean resumeGpu(String coin, String vendor, int index) {
        manuallyPaused.remove(coin + ":" + vendor + ":" + index);
        WorkerRunState run = runs.get(coin + ":" + vendor + ":" + index);
        if (run != null) run.restartAttempts = 0;
        return startGpu(coin, vendor, index);
    }
    public boolean running(String coin) { return runs.values().stream().anyMatch(run -> run.coin.equals(coin) && run.running()); }
    public MinerStats.MinerStatus status(String coin) {
        if (runs.values().stream().anyMatch(run -> run.coin.equals(coin) && run.running()
                && run.status == MinerStats.MinerStatus.MINING)) return MinerStats.MinerStatus.MINING;
        if (runs.values().stream().anyMatch(run -> run.coin.equals(coin) && run.status == MinerStats.MinerStatus.ERROR)) return MinerStats.MinerStatus.ERROR;
        return MinerStats.MinerStatus.PAUSED;
    }
    public List<GpuState> gpuStates(String coin, List<LocalGpuPowerService.Gpu> cards) {
        MinerConfig config = effectiveConfig(coin);
        List<String> selected = config != null ? List.of(config.devices().split(",")) : List.of();
        return cards.stream().map(gpu -> {
            String key = coin + ":" + gpu.vendor() + ":" + gpu.index();
            WorkerRunState run = runs.get(key);
            return new GpuState(gpu.vendor(), gpu.index(), gpu.model(), selected.contains(gpu.vendor() + ":" + gpu.index()),
                    run == null ? MinerStats.MinerStatus.PAUSED : run.status,
                    run != null && run.running(), run != null && run.healthy, manuallyPaused.contains(key),
                    run == null ? "Noch nicht gestartet" : run.detail, run == null ? null : run.error);
        }).toList();
    }
    public List<MinerStats.Worker> workerStats(String coin, List<LocalGpuPowerService.Gpu> cards) {
        MinerConfig config = effectiveConfig(coin);
        if (config == null) return List.of();
        List<Pools> pools = List.of(new Pools(config.poolUrl(), config.wallet() + "/" + config.worker(), ""));
        return gpuStates(coin, cards).stream().filter(GpuState::selected).map(state -> {
            LocalGpuPowerService.Gpu gpu = cards.stream().filter(card -> card.vendor().equals(state.vendor()) && card.index() == state.index()).findFirst().orElseThrow();
            WorkerRunState run = runs.get(coin + ":" + gpu.vendor() + ":" + gpu.index());
            boolean reported = run != null && run.status == MinerStats.MinerStatus.MINING;
            MinerShareTelemetry.Counters counters = run == null ? MinerShareTelemetry.Counters.unavailable() : run.poolCounters;
            return new MinerStats.Worker(state.status(), "SRBMiner " + gpu.model() + " (" + coin + ")", algorithm(coin),
                    reported ? run.hashrate / 1_000_000_000_000.0 : 0, 0.0,
                    power.appliedTarget(gpu), gpu.minWatts(), gpu.maxWatts(), gpu.maxWatts(),
                    gpu.currentWatts() == null ? 0 : Math.round(gpu.currentWatts()), pools, "GPU", gpu.model(), gpu.deviceId(),
                    reported ? run.acceptedShares : null,
                    reported ? run.rejectedShares : null,
                    reported ? new MinerStats.PoolTelemetry(counters.difficulty(), counters.bestShare(), counters.stale(), counters.latencyMs())
                            : MinerStats.PoolTelemetry.unavailable());
        }).toList();
    }
    /** SRBMiner API port block reserved per coin so parallel coins never share a status port. */
    static int apiPortOffset(Coin coin) {
        return switch (coin) {
            case RAVENCOIN -> 0;
            case ETHEREUMCLASSIC -> 1000;
            case DECRED -> 2000;
            default -> 3000;
        };
    }

    @PreDestroy public void shutdown() { for (Coin coin : srbCoins()) stop(coin.id()); }
}
