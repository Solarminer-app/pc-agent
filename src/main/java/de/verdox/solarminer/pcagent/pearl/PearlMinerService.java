package de.verdox.solarminer.pcagent.pearl;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.dto.Pools;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;
import de.verdox.solarminer.pcagent.miner.MinerStopContext;
import de.verdox.solarminer.pcagent.miner.MinerShareTelemetry;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.miner.MinerProcessRegistry;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.miner.GpuCoinMiner;
import de.verdox.solarminer.pcagent.miner.engine.AbstractSrbMinerEngine;
import de.verdox.solarminer.pcagent.miner.engine.SrbMonitorPolicy;
import de.verdox.solarminer.pcagent.miner.engine.WorkerRunState;
import de.verdox.solarminer.pcagent.miner.srb.SrbDownloadService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.miner.GpuState;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;

/** Runs one SRBMiner-MULTI process per selected GPU, independently of XMRig. */
@Service
public class PearlMinerService extends AbstractSrbMinerEngine implements GpuCoinMiner {
    private static final Logger LOGGER = Logger.getLogger(PearlMinerService.class.getName());
    private static final Pattern NVIDIA = Pattern.compile("GPU(\\d+)\\s+\\[CUDA]\\[(\\d+)][^\\r\\n]*");
    private static final Pattern AMD = Pattern.compile("GPU(\\d+)\\s+\\[\\d+]\\[(\\d+)][^\\r\\n]*");
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern PCI_ADDRESS = Pattern.compile("(?i)([0-9a-f]{4,8}):([0-9a-f]{2}):([0-9a-f]{2}\\.[0-7])");
    private final ObjectMapper mapper;
    private final ProxyConfigurationService proxyConfigurationService;
    private final MinerConsoleService console;
    private final PayoutDefaultsService payoutDefaultsService;
    private final LocalGpuPowerService gpuPowerService;
    private final LocalRunLock localRuns;
    private final Path executable;
    private final Path configFile;
    /** Map key stays the Pearl-local worker key {@code vendor:index}. */
    private final Map<String, WorkerRunState> runs = new ConcurrentHashMap<>();
    private final Set<String> manuallyPaused = ConcurrentHashMap.newKeySet();
    private volatile MinerConfig config;
    /**
     * Ephemeral fee-backend configuration used only while the efficiency sweep measures Pearl
     * without an operator configuration. It is never written to disk and is removed when the
     * sweep ends, so the operator's configuration state is untouched.
     */
    private volatile MinerConfig sweepOverride;
    private volatile MinerConfig benchmarkOverride;
    private volatile String lastError;

    public PearlMinerService(ObjectMapper mapper, ProxyConfigurationService proxyConfigurationService,
                             LocalGpuPowerService gpuPowerService, MinerConsoleService console,
                             PayoutDefaultsService payoutDefaultsService, LocalRunLock localRuns,
                             @Value("${solarminer.pearl.binary:./solarminer-agent/srbminer/SRBMiner-MULTI}") String binary,
                             @Value("${solarminer.pearl.config:./solarminer-agent/srbminer/solarminer-config.json}") String configPath) {
        this.mapper = mapper;
        this.proxyConfigurationService = proxyConfigurationService;
        this.gpuPowerService = gpuPowerService;
        this.console = console;
        this.payoutDefaultsService = payoutDefaultsService;
        this.localRuns = localRuns;
        String binaryName = System.getProperty("os.name", "").toLowerCase().contains("win") && !binary.endsWith(".exe")
                ? binary + ".exe" : binary;
        this.executable = Path.of(binaryName).toAbsolutePath().normalize();
        this.configFile = Path.of(configPath).toAbsolutePath().normalize();
        try {
            Path legacy = configFile.resolveSibling("../krig-miner/solarminer-config.json").normalize();
            Path saved = Files.isRegularFile(configFile) ? configFile : legacy;
            if (Files.isRegularFile(saved)) {
                MinerConfig loaded = mapper.readValue(saved.toFile(), MinerConfig.class);
                validate(loaded);
                config = loaded;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Pearl miner configuration could not be loaded", e);
        }
    }

    public synchronized void configure(MinerConfig next) throws IOException {
        validate(next);
        MinerConfig previous = config;
        boolean routeChanged = previous == null || !Objects.equals(previous.poolUrl(), next.poolUrl())
                || !Objects.equals(previous.proxyUrl(), next.proxyUrl())
                || !Objects.equals(previous.wallet(), next.wallet())
                || !Objects.equals(previous.worker(), next.worker());
        if (routeChanged) {
            if (!stop()) throw new IOException("Pearl-GPU-Prozesse konnten nicht angehalten werden");
            runs.clear();
        } else {
            for (WorkerRunState run : List.copyOf(runs.values())) {
                if (!selected(run.gpu, next)) {
                    if (!stopGpu(run.gpu.vendor(), run.gpu.index()))
                        throw new IOException("Pearl-GPU " + run.key + " konnte nicht angehalten werden");
                    runs.remove(run.key, run);
                }
            }
        }
        writeConfig(next);
        config = next;
        manuallyPaused.removeIf(key -> selectedGpus().stream().noneMatch(gpu -> key.equals(gpu.vendor() + ":" + gpu.index())));
        lastError = null;
    }

    /** Updates only the proxy endpoint after the operator changes the shared proxy connection. */
    public synchronized boolean updateProxyRoute(String proxyUrl) throws IOException {
        MinerConfig updated = de.verdox.solarminer.pcagent.miner.engine.RouteValidator
                .withProxyRoute(config, proxyUrl);
        if (updated == null) return false;
        validate(updated);
        if (!proxyConfigurationService.matches(proxyUrl, Coin.PEARL))
            throw new IllegalArgumentException("SolarMiner-Proxy-Route stimmt nicht");
        writeConfig(updated);
        config = updated;
        return true;
    }

    private void writeConfig(MinerConfig next) throws IOException {
        if (!de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(configFile, next, mapper, "pearl-"))
            throw new IOException("Pearl miner configuration could not be written");
    }

    /**
     * A saved fee-backend payout is re-read on every start so a superseded house pool or wallet
     * never keeps mining somewhere else. The operator's own route is left untouched.
     */
    private void refreshDefaultPayout() {
        MinerConfig current = config;
        if (current == null || !payoutDefaultsService.usesDefault(Coin.PEARL.id())) return;
        PayoutDefaultsService.DefaultPayout payout = payoutDefaultsService.resolve(Coin.PEARL.id()).orElse(null);
        if (payout == null) return;
        String worker = payout.workerPart() != null ? payout.workerPart() : current.worker();
        if (payout.poolUrl().equals(current.poolUrl()) && payout.walletPart().equals(current.wallet())
                && worker.equals(current.worker())) return;
        try {
            MinerConfig refreshed = new MinerConfig(payout.poolUrl(), current.proxyUrl(), payout.walletPart(), worker, current.devices());
            writeConfig(refreshed);
            config = refreshed;
            console.append(Coin.PEARL.id(), "[SolarMiner] Payout updated to the current SolarMiner default target");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Default Pearl payout could not be refreshed", e);
        }
    }

    public static void validate(MinerConfig value) {
        de.verdox.solarminer.pcagent.miner.engine.RouteValidator.validateRoute(value,
                de.verdox.solarminer.pcagent.miner.engine.WalletRule.PEARL,
                "Pearl pool must be stratum+tcp/ssl://host:port",
                "Pearl proxy must be stratum+tcp://host:port",
                "A Pearl prl1 payout address is required",
                "Pearl worker must contain 1-32 letters, digits, _ or -");
        de.verdox.solarminer.pcagent.miner.engine.RouteValidator.validateDevices(value.devices(),
                "^(all|((NVIDIA|AMD):)?[0-9]+(,((NVIDIA|AMD):)?[0-9]+)*)$",
                "Pearl devices must be vendor:index values or 'all'");
    }

    public void setSweepOverride(MinerConfig value) { sweepOverride = value; }
    public void clearSweepOverride() { sweepOverride = null; }
    @Override public void setBenchmarkOverride(MinerConfig value) { benchmarkOverride = value; }
    @Override public void clearBenchmarkOverride() { benchmarkOverride = null; }

    /** Operator config wins; the ephemeral sweep override is only consulted while unconfigured. */
    private MinerConfig effectiveConfig() {
        MinerConfig benchmark = benchmarkOverride;
        if (benchmark != null) return benchmark;
        MinerConfig current = config;
        return current != null ? current : sweepOverride;
    }

    private boolean selected(LocalGpuPowerService.Gpu gpu) {
        return selected(gpu, effectiveConfig());
    }

    private static boolean selected(LocalGpuPowerService.Gpu gpu, MinerConfig current) {
        if (current == null) return false;
        String devices = current.devices();
        return devices == null || devices.equals("all") || List.of(devices.split(",")).contains(gpu.vendor() + ":" + gpu.index())
                || List.of(devices.split(",")).contains(Integer.toString(gpu.index()));
    }

    public List<LocalGpuPowerService.Gpu> selectedGpus() {
        return gpuPowerService.discover().stream().filter(this::selected).toList();
    }

    public List<LocalGpuPowerService.Gpu> eligibleGpus() {
        return selectedGpus().stream().filter(gpu -> !manuallyPaused.contains(gpu.vendor() + ":" + gpu.index())).toList();
    }

    public synchronized boolean start() {
        manuallyPaused.clear();
        resetRestartBudget();
        return startForBudget();
    }

    public synchronized boolean startForBudget() {
        List<LocalGpuPowerService.Gpu> cards = eligibleGpus();
        if (cards.isEmpty()) {
            lastError = "No configured Pearl GPU detected";
            console.append(Coin.PEARL.id(), "[SolarMiner] " + lastError);
            return false;
        }
        boolean success = true;
        for (LocalGpuPowerService.Gpu gpu : cards) success = startGpu(gpu) && success;
        return success;
    }

    public synchronized boolean startGpu(String vendor, int index) {
        return selectedGpus().stream().filter(g -> g.vendor().equals(vendor) && g.index() == index)
                .findFirst().map(this::startGpu).orElse(false);
    }

    public synchronized boolean resumeGpuManually(String vendor, int index) {
        manuallyPaused.remove(vendor + ":" + index);
        WorkerRunState resumed = runs.get(vendor + ":" + index);
        if (resumed != null) resumed.restartAttempts = 0;
        return startGpu(vendor, index);
    }

    public synchronized boolean pauseGpuManually(String vendor, int index) {
        if (selectedGpus().stream().noneMatch(gpu -> gpu.vendor().equals(vendor) && gpu.index() == index)) return false;
        manuallyPaused.add(vendor + ":" + index);
        return stopGpu(vendor, index);
    }

    public synchronized boolean pauseSelectedManually() {
        for (LocalGpuPowerService.Gpu gpu : selectedGpus())
            manuallyPaused.add(gpu.vendor() + ":" + gpu.index());
        return stop();
    }

    /**
     * O1 (owner 2026-10-11): bounded crash restart for Pearl workers, mirroring the XMRig
     * watchdog (max {@code MAX_CRASH_RESTARTS} attempts per worker, 30 s scan cadence).
     * Skipped while a local measurement run owns the hardware. The budget refills on an
     * operator start/stop/resume, exactly like the XMRig stop path.
     */
    @Scheduled(fixedDelay = 30_000)
    public synchronized void restartAfterUnexpectedExit() {
        if (localRuns.busy()) return;
        for (WorkerRunState run : List.copyOf(runs.values())) {
            if (!restartEligible(run)) continue;
            run.restartAttempts++;
            console.append(Coin.PEARL.id(), "[SolarMiner] SRBMiner watchdog restart " + run.restartAttempts
                    + "/" + MAX_CRASH_RESTARTS + " (" + run.gpuLabel() + ")");
            startGpu(run.gpu);
        }
    }

    /** An operator-initiated action refills the bounded O1 restart budget. */
    private void resetRestartBudget() {
        for (WorkerRunState run : runs.values()) run.restartAttempts = 0;
    }

    private synchronized boolean startGpu(LocalGpuPowerService.Gpu gpu) {
        String key = gpu.vendor() + ":" + gpu.index();
        WorkerRunState run = runs.computeIfAbsent(key, ignored ->
                new WorkerRunState(Coin.PEARL.id(), gpu, workerConsoleId(gpu), "Miner nicht gestartet"));
        if (run.running()) return true;
        if (MinerProcessRegistry.gpuCoinUsesDevice(gpu.deviceId()))
            return fail(run, "This GPU is already mining RVN or ETC; pause that GPU worker first");
        if (hasExternalMiner()) {
            run.status = MinerStats.MinerStatus.MINING;
            run.detail = "SRBMiner is running externally; no second process started";
            run.error = null;
            return true;
        }
        refreshDefaultPayout();
        MinerConfig route = effectiveConfig();
        boolean noOtherPearlGpuRunning = runs.values().stream().noneMatch(WorkerRunState::running);
        if (noOtherPearlGpuRunning) console.started(Coin.PEARL.id());
        console.started(run.consoleId);
        console.append(Coin.PEARL.id(), "[" + key + "] New miner start");
        if (route == null || !proxyConfigurationService.matches(route.proxyUrl(), Coin.PEARL)
                || !proxyConfigurationService.miningReady(Coin.PEARL) || !binaryAvailable()) {
            return fail(run, !binaryAvailable() ? "Reviewed SRBMiner-MULTI version is missing"
                    : "Pearl requires a reachable SolarMiner proxy with a loaded fee target");
        }
        try {
            int srbId = mappedGpuId(gpu);
            int apiPort = 12000 + srbId;
            if (apiPort > 65535) throw new IOException("SRBMiner GPU ID is outside the API port range");
            URI proxy = URI.create(route.proxyUrl());
            String suffix = "-" + (gpu.vendor().equals("NVIDIA") ? "n" : "a") + gpu.index();
            String worker = route.worker().substring(0, Math.min(route.worker().length(), 32 - suffix.length())) + suffix;
            List<String> command = buildCommand(executable, proxy, route.wallet(),
                    encodedWorker(route.poolUrl(), worker), apiPort, srbId);
            Process started = new ProcessBuilder(command).directory(executable.getParent().toFile())
                    .redirectErrorStream(true).start();
            run.process = started;
            run.apiPort = apiPort;
            run.status = MinerStats.MinerStatus.MINING;
            run.error = null;
            run.detail = "SRBMiner is starting";
            run.acceptedShares = null;
            run.rejectedShares = null;
            run.poolCounters = MinerShareTelemetry.Counters.unavailable();
            run.healthy = false;
            run.hashrate = 0;
            run.diagnosis = null;
            run.output.set("");
            lastError = null;
            Thread outputThread = startOutputDrain(run, started, "pearl-output-" + key, false, true, this::redact);
            Thread.ofVirtual().name("pearl-health-" + key).start(() -> monitorLoop(run, started, SrbMonitorPolicy.pearl(),
                    () -> true, candidate -> runs.get(candidate.gpu.vendor() + ":" + candidate.gpu.index()) == candidate));
            started.onExit().thenAccept(done -> {
                try { outputThread.join(1000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (unexpectedExit(run, done))
                    fail(run, "SRBMiner on " + key + " exited (code " + done.exitValue() + ")"
                            + (run.output.get().isBlank() ? "" : ": " + run.output.get()));
            });
            return true;
        } catch (IOException e) {
            return fail(run, "SRBMiner on " + key + " could not be started: " + e.getMessage());
        }
    }

    static String workerConsoleId(LocalGpuPowerService.Gpu gpu) {
        return MinerConsoleService.gpuConsoleId(Coin.PEARL, gpu.vendor(), gpu.index());
    }

    @Override
    public de.verdox.solarminer.pcagent.miner.diagnostics.MinerLogDiagnostics.Diagnosis diagnosis(String vendor, int index) {
        WorkerRunState run = runs.get(vendor + ":" + index);
        return run == null ? null : run.diagnosis;
    }

    @Override protected MinerConsoleService console() { return console; }
    @Override protected ObjectMapper mapper() { return mapper; }
    @Override protected Logger logger() { return LOGGER; }
    @Override protected String aggregateConsoleId(WorkerRunState run) { return Coin.PEARL.id(); }
    @Override protected String aggregateTag(WorkerRunState run) { return run.gpuLabel(); }
    @Override protected String drainTag(WorkerRunState run) { return run.gpuLabel(); }

    @Override
    protected boolean fail(WorkerRunState run, String error) {
        var diagnosis = run.diagnosis;
        if (diagnosis != null && !error.contains(diagnosis.detail())) error = error + " · " + diagnosis.detail();
        run.status = MinerStats.MinerStatus.ERROR;
        run.error = error;
        run.detail = error;
        run.healthy = false;
        run.hashrate = 0;
        lastError = error;
        LOGGER.warning(error);
        console.append(run.consoleId, "[SolarMiner] " + error);
        console.append(Coin.PEARL.id(), "[" + run.gpuLabel() + "] " + error);
        return false;
    }

    static List<String> buildCommand(Path executable, URI proxy, String wallet, String worker,
                                     int apiPort, int srbId) {
        // SRBMiner 3.7.3 selects its PearlHash kernel from the CUDA SM itself. The temporary
        // --pearl-k1/--pearl-k2 switches must not be reconstructed from marketing model names.
        return List.of(executable.toString(), "--disable-cpu", "--algorithm", "pearlhash",
                "--pool", proxy.getHost() + ":" + proxy.getPort(), "--wallet", wallet,
                "--worker", worker, "--tls", "false", "--api-enable", "--api-port",
                Integer.toString(apiPort), "--gpu-id", Integer.toString(srbId));
    }

    /** Shared SRBMiner device mapping used by the generic SRB coin adapter. */
    public int mappedGpuId(LocalGpuPowerService.Gpu gpu) throws IOException {
        // On Linux SRBMiner can return an empty device list when stdout is a pipe,
        // even though the same CUDA devices are listed on a terminal.
        List<String> command = System.getProperty("os.name", "").toLowerCase().contains("linux")
                ? List.of("script", "-q", "-e", "-c",
                        "exec '" + executable.toString().replace("'", "'\\''") + "' --list-devices", "/dev/null")
                : List.of(executable.toString(), "--list-devices");
        Process listing = new ProcessBuilder(command)
                .directory(executable.getParent().toFile()).redirectErrorStream(true).start();
        try {
            if (!listing.waitFor(15, TimeUnit.SECONDS)) {
                listing.destroyForcibly();
                throw new IOException("SRBMiner GPU listing timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listing.destroyForcibly();
            throw new IOException("SRBMiner GPU listing interrupted", e);
        }
        String output = new String(listing.getInputStream().readNBytes(65536), StandardCharsets.UTF_8);
        if (listing.exitValue() != 0) throw new IOException("SRBMiner GPU listing failed (Exit-Code " + listing.exitValue() + "): " + output.strip());
        if (output.isBlank()) throw new IOException("SRBMiner did not produce a GPU device list");
        int id = gpu.vendor().equals("NVIDIA")
                ? findGpuIdByPci(output, NVIDIA, pciAddress(gpu.deviceId()))
                : canonicalPciAddress(gpu.deviceId()) != null
                    ? findGpuIdByPci(output, AMD, gpu.deviceId())
                    : findGpuId(output, gpu.vendor(), gpu.index());
        if (id >= 0) return id;
        throw new IOException("GPU " + gpu.vendor() + ":" + gpu.index()
                + " was not found in the SRBMiner device list: " + ANSI_ESCAPE.matcher(output).replaceAll("").strip());
    }

    static int findGpuId(String output, String vendor, int index) {
        String plain = ANSI_ESCAPE.matcher(output).replaceAll("");
        Matcher matcher = (vendor.equals("NVIDIA") ? NVIDIA : AMD).matcher(plain);
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(2)) == index && !matcher.group().contains("disabled by default"))
                return Integer.parseInt(matcher.group(1));
        }
        return -1;
    }

    private String pciAddress(String deviceId) throws IOException {
        Process query = new ProcessBuilder("nvidia-smi", "-i", deviceId,
                "--query-gpu=pci.bus_id", "--format=csv,noheader")
                .redirectErrorStream(true).start();
        try {
            if (!query.waitFor(5, TimeUnit.SECONDS)) {
                query.destroyForcibly();
                throw new IOException("NVIDIA PCI address could not be read in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            query.destroyForcibly();
            throw new IOException("NVIDIA PCI query interrupted", e);
        }
        String output = new String(query.getInputStream().readNBytes(4096), StandardCharsets.UTF_8).strip();
        if (query.exitValue() != 0 || canonicalPciAddress(output) == null)
            throw new IOException("NVIDIA PCI address is unavailable for " + deviceId);
        return output;
    }

    static int findNvidiaGpuId(String output, String pciAddress) {
        return findGpuIdByPci(output, NVIDIA, pciAddress);
    }

    static int findAmdGpuId(String output, String pciAddress) {
        return findGpuIdByPci(output, AMD, pciAddress);
    }

    private static int findGpuIdByPci(String output, Pattern devicePattern, String pciAddress) {
        String expected = canonicalPciAddress(pciAddress);
        if (expected == null) return -1;
        Matcher matcher = devicePattern.matcher(ANSI_ESCAPE.matcher(output).replaceAll(""));
        int match = -1;
        while (matcher.find()) {
            String line = matcher.group();
            Matcher address = PCI_ADDRESS.matcher(line);
            if (address.find() && expected.equals(canonicalPciAddress(address.group()))
                    && !line.contains("disabled by default")) {
                if (match >= 0) return -1;
                match = Integer.parseInt(matcher.group(1));
            }
        }
        return match;
    }

    private static String canonicalPciAddress(String value) {
        if (value == null) return null;
        Matcher matcher = PCI_ADDRESS.matcher(value.strip());
        if (!matcher.matches()) return null;
        return Long.parseLong(matcher.group(1), 16) + ":" + matcher.group(2).toLowerCase()
                + ":" + matcher.group(3).toLowerCase();
    }

    private static String encodedWorker(String poolUrl, String worker) {
        String pool = Base64.getUrlEncoder().withoutPadding().encodeToString(poolUrl.getBytes(StandardCharsets.UTF_8));
        return "sm1." + pool + "." + worker;
    }

    public synchronized boolean stopGpu(String vendor, int index) {
        WorkerRunState run = runs.get(vendor + ":" + index);
        if (run == null) return true;
        run.status = MinerStats.MinerStatus.PAUSED;
        run.healthy = false;
        run.hashrate = 0;
        run.acceptedShares = null;
        run.rejectedShares = null;
        run.poolCounters = MinerShareTelemetry.Counters.unavailable();
        run.detail = "Miner pausiert";
        // An explicit stop refills the bounded O1 restart budget (mirrors the XMRig stop path).
        run.restartAttempts = 0;
        return stopProcess(run, MinerStopContext.source(), true, "SRBMiner stopped");
    }

    public synchronized boolean stop() {
        boolean success = true;
        for (WorkerRunState run : runs.values()) success = stopGpu(run.gpu.vendor(), run.gpu.index()) && success;
        success = MinerProcessRegistry.stopSrbExceptGpuCoins() && success;
        return success;
    }

    public boolean running() { return runs.values().stream().anyMatch(WorkerRunState::running) || hasExternalMiner(); }
    public boolean poolHealthy() { return runs.values().stream().anyMatch(run -> run.running() && run.healthy); }
    public String connectionDetail() {
        long healthy = runs.values().stream().filter(run -> run.running() && run.healthy).count();
        long running = runs.values().stream().filter(WorkerRunState::running).count();
        return running > 0 ? healthy + " von " + running + " GPU-Minern mit Pool verbunden"
                : hasExternalMiner() ? "SRBMiner läuft außerhalb des PC-Agent; Pool- und GPU-Status nicht verfügbar"
                : lastError != null ? lastError : "GPU-Miner pausiert";
    }
    public String lastError() { return lastError; }
    public MinerConfig configuration() { return config; }
    /**
     * A legacy unversioned binary is deliberately not startable. SRBMiner 3.7.3 changed the
     * PearlHash kernel selection for recent NVIDIA architectures, so merely finding an older
     * executable is no longer sufficient evidence that every advertised GPU starts correctly.
     */
    public boolean binaryAvailable() { return compatibleBinary(executable); }

    /**
     * Whether an SRBMiner executable is present, even when it is not the reviewed release.
     * The catalog uses this to offer an explicit, checksum-verified update instead of making
     * an older installation indistinguishable from a missing one.
     */
    public boolean binaryPresent() { return Files.isRegularFile(executable); }

    static boolean compatibleBinary(Path executable) {
        if (!Files.isRegularFile(executable)) return false;
        try {
            return Files.readString(versionPath(executable)).strip().equals(SrbDownloadService.SRBMINER_VERSION);
        } catch (IOException ignored) {
            return false;
        }
    }

    /** Shared SRBMiner version-marker location for install and removal flows. */
    public static Path versionPath(Path executable) {
        return executable.resolveSibling(".solarminer-srbminer-version");
    }
    public Path executablePath() { return executable; }
    public Path configurationPath() { return configFile; }
    public List<GpuState> gpuStates(List<LocalGpuPowerService.Gpu> cards) {
        boolean externalMiner = hasExternalMiner();
        return cards.stream().map(gpu -> {
            WorkerRunState run = runs.get(gpu.vendor() + ":" + gpu.index());
            return new GpuState(gpu.vendor(), gpu.index(), gpu.model(), selected(gpu),
                    run == null ? externalMiner ? MinerStats.MinerStatus.MINING : MinerStats.MinerStatus.PAUSED : run.status,
                    run != null ? run.running() : externalMiner, run != null && run.healthy,
                    manuallyPaused.contains(gpu.vendor() + ":" + gpu.index()),
                    run == null ? externalMiner ? "SRBMiner läuft außerhalb des PC-Agent; GPU-Zuordnung nicht verfügbar" : "Noch nicht gestartet" : run.detail,
                    run == null ? null : run.error);
        }).toList();
    }

    public MinerStats.MinerStatus status() {
        if (poolHealthy()) return MinerStats.MinerStatus.MINING;
        if (running()) return MinerStats.MinerStatus.MINING;
        if (runs.values().stream().anyMatch(run -> run.status == MinerStats.MinerStatus.ERROR))
            return MinerStats.MinerStatus.ERROR;
        return MinerStats.MinerStatus.PAUSED;
    }

    public List<MinerStats.Worker> workerStats(List<LocalGpuPowerService.Gpu> cards) {
        MinerConfig current = effectiveConfig();
        if (current == null) return List.of();
        List<Pools> pools = List.of(new Pools(current.poolUrl(), current.wallet() + "/" + current.worker(), ""));
        return cards.stream().filter(this::selected).map(gpu -> {
            WorkerRunState run = runs.get(gpu.vendor() + ":" + gpu.index());
            boolean externalMiner = hasExternalMiner();
            boolean reported = run != null && !externalMiner && run.status == MinerStats.MinerStatus.MINING;
            MinerShareTelemetry.Counters counters = run == null ? MinerShareTelemetry.Counters.unavailable() : run.poolCounters;
            return new MinerStats.Worker(externalMiner ? MinerStats.MinerStatus.MINING : run == null ? MinerStats.MinerStatus.PAUSED : run.status,
                    "SRBMiner " + gpu.model() + " (" + gpu.vendor() + ":" + gpu.index() + ")", "PearlHash",
                    run == null || externalMiner ? 0.0 : run.hashrate / 1_000_000_000_000.0, 0.0,
                    gpuPowerService.appliedTarget(gpu), gpu.minWatts(), gpu.maxWatts(), gpu.maxWatts(),
                    gpu.currentWatts() == null ? 0 : Math.round(gpu.currentWatts()), pools,
                    "GPU", gpu.model(), gpu.deviceId(),
                    reported ? run.acceptedShares : null,
                    reported ? run.rejectedShares : null,
                    reported ? new MinerStats.PoolTelemetry(counters.difficulty(), counters.bestShare(), counters.stale(), counters.latencyMs())
                            : MinerStats.PoolTelemetry.unavailable());
        }).toList();
    }

    private boolean hasExternalMiner() {
        return MinerProcessRegistry.running("srbminer").stream()
                .filter(handle -> !MinerProcessRegistry.managedGpuCoin(handle))
                .anyMatch(handle -> runs.values().stream().noneMatch(run -> run.process != null && run.process.pid() == handle.pid()));
    }

    public boolean hasExternalMinerProcess() { return hasExternalMiner(); }

    /** Adds an orchestration event to the aggregate and every currently known GPU console. */
    public synchronized void appendBenchmarkEvent(String message) {
        console.append(Coin.PEARL.id(), message);
        for (WorkerRunState run : runs.values()) console.append(run.consoleId, message);
    }

    /** Managed processes only; external miners are rejected before an installed benchmark begins. */
    public synchronized Set<String> runningGpuDeviceIds() {
        return runs.values().stream().filter(WorkerRunState::running).map(run -> run.gpu.deviceId()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public synchronized Set<String> manuallyPausedGpuKeys() { return Set.copyOf(manuallyPaused); }

    public synchronized boolean restoreWorkerState(Set<String> pausedBefore, Set<String> runningBefore) {
        boolean success = stop();
        manuallyPaused.clear();
        if (pausedBefore != null) manuallyPaused.addAll(pausedBefore);
        for (LocalGpuPowerService.Gpu gpu : selectedGpus()) {
            if (runningBefore != null && runningBefore.contains(gpu.deviceId())) success = startGpu(gpu) && success;
        }
        return success;
    }

    private String redact(String line) {
        MinerConfig current = config;
        if (current == null) return line;
        return line.replace(current.wallet(), "[wallet]").replace(current.poolUrl(), "[pool]");
    }

    @PreDestroy public void shutdown() { stop(); }

    // --- GpuCoinMiner contract: the coin-independent view used by orchestration through the MinerFactory. ---

    @Override public Coin coin() { return Coin.PEARL; }
    @Override public boolean configured() { return config != null; }
    @Override public boolean routeConfigured() {
        MinerConfig current = config;
        return current != null && proxyConfigurationService.matches(current.proxyUrl(), Coin.PEARL.id());
    }
    @Override public boolean startAll() { return start(); }
    @Override public boolean pauseAll() { return pauseSelectedManually(); }
    @Override public boolean stopAll() { return stop(); }
    @Override public boolean pauseGpu(String vendor, int index) { return pauseGpuManually(vendor, index); }
    @Override public boolean resumeGpu(String vendor, int index) { return resumeGpuManually(vendor, index); }
    @Override public boolean setPowerCap(long watts) { return gpuPowerService.setTotalPowerTarget(watts, selectedGpus()); }
    @Override public void applyConfig(MinerConfig next) throws IOException { configure(next); }
    @Override public void validateConfig(MinerConfig next) { validate(next); }
    @Override public String reportedAlgorithm() { return Coin.PEARL.displayAlgorithm(); }

}
