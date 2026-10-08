package de.verdox.solarminer.pcagent.pearl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.dto.Pools;
import de.verdox.solarminer.pcagent.mining.MinerConsoleService;
import de.verdox.solarminer.pcagent.mining.MinerStopContext;
import de.verdox.solarminer.pcagent.mining.MinerShareTelemetry;
import de.verdox.solarminer.pcagent.mining.PayoutDefaultsService;
import de.verdox.solarminer.pcagent.mining.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.MinerProcessRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs one SRBMiner-MULTI process per selected GPU, independently of XMRig. */
@Service
public class PearlMinerService {
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
    private final Path executable;
    private final Path configFile;
    private final HttpClient apiClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final Map<String, GpuRun> runs = new ConcurrentHashMap<>();
    private final Set<String> manuallyPaused = ConcurrentHashMap.newKeySet();
    private volatile Config config;
    private volatile String lastError;

    private static final class GpuRun {
        final LocalGpuPowerService.Gpu gpu;
        final String key;
        final String consoleId;
        final AtomicReference<String> output = new AtomicReference<>("");
        volatile Process process;
        volatile MinerStats.MinerStatus status = MinerStats.MinerStatus.PAUSED;
        volatile String error;
        volatile String detail = "Miner nicht gestartet";
        volatile boolean jobReceived;
        volatile boolean poolHealthy;
        volatile double hashesPerSecond;
        volatile Long acceptedShares;
        volatile Long rejectedShares;
        volatile MinerShareTelemetry.Counters poolCounters = MinerShareTelemetry.Counters.unavailable();
        int apiPort;

        GpuRun(LocalGpuPowerService.Gpu gpu) {
            this.gpu = gpu;
            this.key = gpu.vendor() + ":" + gpu.index();
            this.consoleId = "pearl-" + gpu.vendor() + "-" + gpu.index();
        }

        boolean running() { return process != null && process.isAlive(); }

        MinerStats.MinerStatus visibleStatus() {
            return status;
        }
    }

    public PearlMinerService(ObjectMapper mapper, ProxyConfigurationService proxyConfigurationService,
                             LocalGpuPowerService gpuPowerService, MinerConsoleService console,
                             PayoutDefaultsService payoutDefaultsService,
                             @Value("${solarminer.pearl.binary:./solarminer-agent/srbminer/SRBMiner-MULTI}") String binary,
                             @Value("${solarminer.pearl.config:./solarminer-agent/srbminer/solarminer-config.json}") String configPath) {
        this.mapper = mapper;
        this.proxyConfigurationService = proxyConfigurationService;
        this.gpuPowerService = gpuPowerService;
        this.console = console;
        this.payoutDefaultsService = payoutDefaultsService;
        String binaryName = System.getProperty("os.name", "").toLowerCase().contains("win") && !binary.endsWith(".exe")
                ? binary + ".exe" : binary;
        this.executable = Path.of(binaryName).toAbsolutePath().normalize();
        this.configFile = Path.of(configPath).toAbsolutePath().normalize();
        try {
            Path legacy = configFile.resolveSibling("../krig-miner/solarminer-config.json").normalize();
            Path saved = Files.isRegularFile(configFile) ? configFile : legacy;
            if (Files.isRegularFile(saved)) {
                Config loaded = mapper.readValue(saved.toFile(), Config.class);
                validate(loaded);
                config = loaded;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Pearl miner configuration could not be loaded", e);
        }
    }

    public synchronized void configure(Config next) throws IOException {
        validate(next);
        Config previous = config;
        boolean routeChanged = previous == null || !Objects.equals(previous.poolUrl(), next.poolUrl())
                || !Objects.equals(previous.proxyUrl(), next.proxyUrl())
                || !Objects.equals(previous.wallet(), next.wallet())
                || !Objects.equals(previous.worker(), next.worker());
        if (routeChanged) {
            if (!stop()) throw new IOException("Pearl-GPU-Prozesse konnten nicht angehalten werden");
            runs.clear();
        } else {
            for (GpuRun run : List.copyOf(runs.values())) {
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

    private void writeConfig(Config next) throws IOException {
        Files.createDirectories(configFile.getParent());
        Path temp = Files.createTempFile(configFile.getParent(), "pearl-", ".json");
        try {
            mapper.writeValue(temp.toFile(), next);
            try {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * A saved fee-backend payout is re-read on every start so a superseded house pool or wallet
     * never keeps mining somewhere else. The operator's own route is left untouched.
     */
    private void refreshDefaultPayout() {
        Config current = config;
        if (current == null || !payoutDefaultsService.usesDefault("pearl")) return;
        PayoutDefaultsService.DefaultPayout payout = payoutDefaultsService.resolve("pearl").orElse(null);
        if (payout == null) return;
        String worker = payout.workerPart() != null ? payout.workerPart() : current.worker();
        if (payout.poolUrl().equals(current.poolUrl()) && payout.walletPart().equals(current.wallet())
                && worker.equals(current.worker())) return;
        try {
            Config refreshed = new Config(payout.poolUrl(), current.proxyUrl(), payout.walletPart(), worker, current.devices());
            writeConfig(refreshed);
            config = refreshed;
            console.append("pearl", "[SolarMiner] Payout updated to the current SolarMiner default target");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Default Pearl payout could not be refreshed", e);
        }
    }

    public static void validate(Config value) {
        if (value == null || value.poolUrl() == null ||
                !value.poolUrl().matches("^stratum\\+(tcp|ssl)://[A-Za-z0-9.-]+:[1-9][0-9]{0,4}$"))
            throw new IllegalArgumentException("Pearl pool must be stratum+tcp/ssl://host:port");
        int port = URI.create(value.poolUrl()).getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Pearl pool port must be 1-65535");
        if (value.proxyUrl() == null || !value.proxyUrl().matches("^stratum\\+tcp://[A-Za-z0-9.-]+:[1-9][0-9]{0,4}$")
                || URI.create(value.proxyUrl()).getPort() > 65535)
            throw new IllegalArgumentException("Pearl proxy must be stratum+tcp://host:port");
        if (value.wallet() == null || !value.wallet().matches("^prl1[023456789acdefghjklmnpqrstuvwxyz]{20,120}$"))
            throw new IllegalArgumentException("A Pearl prl1 payout address is required");
        if (value.worker() == null || !value.worker().matches("^[A-Za-z0-9_-]{1,32}$"))
            throw new IllegalArgumentException("Pearl worker must contain 1-32 letters, digits, _ or -");
        if (value.devices() != null && !value.devices().matches("^(all|((NVIDIA|AMD):)?[0-9]+(,((NVIDIA|AMD):)?[0-9]+)*)$"))
            throw new IllegalArgumentException("Pearl devices must be vendor:index values or 'all'");
    }

    private boolean selected(LocalGpuPowerService.Gpu gpu) {
        return selected(gpu, config);
    }

    private static boolean selected(LocalGpuPowerService.Gpu gpu, Config current) {
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
        return startForBudget();
    }

    public synchronized boolean startForBudget() {
        List<LocalGpuPowerService.Gpu> cards = eligibleGpus();
        if (cards.isEmpty()) {
            lastError = "No configured Pearl GPU detected";
            console.append("pearl", "[SolarMiner] " + lastError);
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

    private synchronized boolean startGpu(LocalGpuPowerService.Gpu gpu) {
        String key = gpu.vendor() + ":" + gpu.index();
        GpuRun run = runs.computeIfAbsent(key, ignored -> new GpuRun(gpu));
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
        boolean noOtherPearlGpuRunning = runs.values().stream().noneMatch(GpuRun::running);
        if (noOtherPearlGpuRunning) console.started("pearl");
        console.started(run.consoleId);
        console.append("pearl", "[" + key + "] New miner start");
        if (config == null || !proxyConfigurationService.matches(config.proxyUrl(), "pearl")
                || !proxyConfigurationService.miningReady("pearl") || !Files.isRegularFile(executable)) {
            return fail(run, !Files.isRegularFile(executable) ? "SRBMiner-MULTI executable is missing"
                    : "Pearl requires a reachable SolarMiner proxy with a loaded fee target");
        }
        try {
            int srbId = mappedGpuId(gpu);
            int apiPort = 12000 + srbId;
            if (apiPort > 65535) throw new IOException("SRBMiner GPU ID is outside the API port range");
            URI proxy = URI.create(config.proxyUrl());
            String suffix = "-" + (gpu.vendor().equals("NVIDIA") ? "n" : "a") + gpu.index();
            String worker = config.worker().substring(0, Math.min(config.worker().length(), 32 - suffix.length())) + suffix;
            List<String> command = List.of(executable.toString(), "--disable-cpu", "--algorithm", "pearlhash",
                    "--pool", proxy.getHost() + ":" + proxy.getPort(), "--wallet", config.wallet(),
                    "--worker", encodedWorker(config.poolUrl(), worker), "--tls", "false",
                    "--api-enable", "--api-port", Integer.toString(apiPort), "--gpu-id", Integer.toString(srbId));
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
            run.jobReceived = false;
            run.poolHealthy = false;
            run.output.set("");
            lastError = null;
            Thread outputThread = Thread.ofVirtual().name("pearl-output-" + key).start(() -> drain(run, started));
            Thread.ofVirtual().name("pearl-health-" + key).start(() -> monitor(run, started));
            started.onExit().thenAccept(done -> {
                try { outputThread.join(1000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (runs.get(key) != run || run.process != done) return;
                if (run.status == MinerStats.MinerStatus.MINING)
                    fail(run, "SRBMiner on " + key + " exited (code " + done.exitValue() + ")"
                            + (run.output.get().isBlank() ? "" : ": " + run.output.get()));
            });
            return true;
        } catch (IOException e) {
            return fail(run, "SRBMiner on " + key + " could not be started: " + e.getMessage());
        }
    }

    private boolean fail(GpuRun run, String error) {
        run.status = MinerStats.MinerStatus.ERROR;
        run.error = error;
        run.detail = error;
        run.poolHealthy = false;
        run.hashesPerSecond = 0;
        lastError = error;
        LOGGER.warning(error);
        console.append(run.consoleId, "[SolarMiner] " + error);
        console.append("pearl", "[" + run.key + "] " + error);
        return false;
    }

    private void monitor(GpuRun run, Process started) {
        Instant launched = Instant.now();
        Instant lastHealthy = launched;
        while (started.isAlive() && run.process == started && runs.get(run.key) == run) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + run.apiPort + "/"))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> response = apiClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonNode algorithms = mapper.readTree(response.body()).path("algorithms");
                    JsonNode pool = algorithms.isArray() && !algorithms.isEmpty()
                            ? algorithms.get(0).path("pool") : mapper.createObjectNode();
                    JsonNode hashrate = algorithms.isArray() && !algorithms.isEmpty()
                            ? algorithms.get(0).path("hashrate") : mapper.createObjectNode();
                    double reportedHashrate = hashrate.path("gpu").path("total").asDouble(0);
                    if (!(reportedHashrate > 0)) reportedHashrate = hashrate.path("1min").asDouble(0);
                    run.hashesPerSecond = reportedHashrate;
                    updateShares(run, pool);
                    long uptime = pool.path("uptime").asLong(0);
                    long lastJob = pool.path("last_job_received").asLong(0);
                    if (uptime > 0 && lastJob > 0 && lastJob < 120) {
                        run.jobReceived = true;
                        run.poolHealthy = true;
                        lastHealthy = Instant.now();
                        run.detail = "Pool connected, last job " + lastJob + " s ago";
                    } else {
                        run.poolHealthy = false;
                        run.detail = "Waiting for pool connection and mining job";
                    }
                } else {
                    run.poolHealthy = false;
                    run.detail = "Miner API returned HTTP " + response.statusCode();
                }
            } catch (Exception e) {
                run.poolHealthy = false;
                run.detail = "Miner API is not reachable yet";
            }
            if (!started.isAlive() || run.process != started || run.status != MinerStats.MinerStatus.MINING) return;
            boolean startupTimeout = !run.jobReceived && Duration.between(launched, Instant.now()).toSeconds() >= 90;
            boolean stalled = run.jobReceived && Duration.between(lastHealthy, Instant.now()).toSeconds() >= 120;
            if (startupTimeout || stalled) {
                fail(run, "SRBMiner on " + run.key + " has not received a mining job "
                        + (startupTimeout ? "within 90" : "for 120") + " seconds: " + run.output.get());
                started.destroy();
                return;
            }
            try { Thread.sleep(5000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void drain(GpuRun run, Process started) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(started.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String safe = redact(line);
                console.append(run.consoleId, line);
                console.append("pearl", "[" + run.key + "] " + line);
                run.output.updateAndGet(previous -> {
                    String combined = (previous.isBlank() ? "" : previous + " | ") + safe;
                    return combined.length() > 1200 ? combined.substring(combined.length() - 1200) : combined;
                });
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Pearl miner output ended", e);
        }
    }

    int mappedGpuId(LocalGpuPowerService.Gpu gpu) throws IOException {
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
                ? findNvidiaGpuId(output, pciAddress(gpu.deviceId()))
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
        String expected = canonicalPciAddress(pciAddress);
        if (expected == null) return -1;
        Matcher matcher = NVIDIA.matcher(ANSI_ESCAPE.matcher(output).replaceAll(""));
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
        GpuRun run = runs.get(vendor + ":" + index);
        if (run == null) return true;
        run.status = MinerStats.MinerStatus.PAUSED;
        run.poolHealthy = false;
        run.hashesPerSecond = 0;
        run.acceptedShares = null;
        run.rejectedShares = null;
        run.poolCounters = MinerShareTelemetry.Counters.unavailable();
        run.detail = "Miner pausiert";
        Process process = run.process;
        if (process == null || !process.isAlive()) return true;
        String source = MinerStopContext.source();
        console.append(run.consoleId, "[SolarMiner] Stop requested by: " + source);
        console.append("pearl", "[" + run.key + "] Stop requested by: " + source);
        process.destroy();
        try {
            if (!process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)) return false;
            }
            console.append(run.consoleId, "[SolarMiner] SRBMiner stopped");
            console.append("pearl", "[" + run.key + "] SRBMiner stopped");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return false;
        }
    }

    public synchronized boolean stop() {
        boolean success = true;
        for (GpuRun run : runs.values()) success = stopGpu(run.gpu.vendor(), run.gpu.index()) && success;
        success = MinerProcessRegistry.stopSrbExceptGpuCoins() && success;
        return success;
    }

    public boolean running() { return runs.values().stream().anyMatch(GpuRun::running) || hasExternalMiner(); }
    public boolean poolHealthy() { return runs.values().stream().anyMatch(run -> run.running() && run.poolHealthy); }
    public String connectionDetail() {
        long healthy = runs.values().stream().filter(run -> run.running() && run.poolHealthy).count();
        long running = runs.values().stream().filter(GpuRun::running).count();
        return running > 0 ? healthy + " von " + running + " GPU-Minern mit Pool verbunden"
                : hasExternalMiner() ? "SRBMiner läuft außerhalb des PC-Agent; Pool- und GPU-Status nicht verfügbar"
                : lastError != null ? lastError : "GPU-Miner pausiert";
    }
    public String lastError() { return lastError; }
    public Config configuration() { return config; }
    public boolean binaryAvailable() { return Files.isRegularFile(executable); }
    public Path executablePath() { return executable; }
    public Path configurationPath() { return configFile; }
    public List<GpuState> gpuStates(List<LocalGpuPowerService.Gpu> cards) {
        boolean externalMiner = hasExternalMiner();
        return cards.stream().map(gpu -> {
            GpuRun run = runs.get(gpu.vendor() + ":" + gpu.index());
            return new GpuState(gpu.vendor(), gpu.index(), gpu.model(), selected(gpu),
                    run == null ? externalMiner ? MinerStats.MinerStatus.MINING : MinerStats.MinerStatus.PAUSED : run.visibleStatus(),
                    run != null ? run.running() : externalMiner, run != null && run.poolHealthy,
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
        Config current = config;
        if (current == null) return List.of();
        List<Pools> pools = List.of(new Pools(current.poolUrl(), current.wallet() + "/" + current.worker(), ""));
        return cards.stream().filter(this::selected).map(gpu -> {
            GpuRun run = runs.get(gpu.vendor() + ":" + gpu.index());
            boolean externalMiner = hasExternalMiner();
            boolean reported = run != null && !externalMiner && run.status == MinerStats.MinerStatus.MINING;
            MinerShareTelemetry.Counters counters = run == null ? MinerShareTelemetry.Counters.unavailable() : run.poolCounters;
            return new MinerStats.Worker(externalMiner ? MinerStats.MinerStatus.MINING : run == null ? MinerStats.MinerStatus.PAUSED : run.visibleStatus(),
                    "SRBMiner " + gpu.model() + " (" + gpu.vendor() + ":" + gpu.index() + ")", "PearlHash",
                    run == null || externalMiner ? 0.0 : run.hashesPerSecond / 1_000_000_000_000.0, 0.0,
                    gpuPowerService.appliedTarget(gpu), gpu.minWatts(), gpu.maxWatts(), gpu.maxWatts(),
                    gpu.currentWatts() == null ? 0 : Math.round(gpu.currentWatts()), pools,
                    "GPU", gpu.model(), gpu.deviceId(),
                    reported ? run.acceptedShares : null,
                    reported ? run.rejectedShares : null,
                    reported ? new MinerStats.PoolTelemetry(counters.difficulty(), counters.bestShare(), counters.stale(), counters.latencyMs())
                            : MinerStats.PoolTelemetry.unavailable());
        }).toList();
    }

    /** SRBMiner has used both pool.accepted and pool.shares.accepted across releases. */
    private static void updateShares(GpuRun run, JsonNode pool) {
        MinerShareTelemetry.Counters counters = MinerShareTelemetry.srbMiner(pool);
        run.acceptedShares = counters.accepted();
        run.rejectedShares = counters.rejected();
        run.poolCounters = counters;
    }

    private boolean hasExternalMiner() {
        return MinerProcessRegistry.running("srbminer").stream()
                .filter(handle -> !MinerProcessRegistry.managedGpuCoin(handle))
                .anyMatch(handle -> runs.values().stream().noneMatch(run -> run.process != null && run.process.pid() == handle.pid()));
    }

    public boolean hasExternalMinerProcess() { return hasExternalMiner(); }

    /** Adds an orchestration event to the aggregate and every currently known GPU console. */
    public synchronized void appendBenchmarkEvent(String message) {
        console.append("pearl", message);
        for (GpuRun run : runs.values()) console.append(run.consoleId, message);
    }

    /** Managed processes only; external miners are rejected before an installed benchmark begins. */
    public synchronized Set<String> runningGpuDeviceIds() {
        return runs.values().stream().filter(GpuRun::running).map(run -> run.gpu.deviceId()).collect(java.util.stream.Collectors.toUnmodifiableSet());
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
        Config current = config;
        if (current == null) return line;
        return line.replace(current.wallet(), "[wallet]").replace(current.poolUrl(), "[pool]");
    }

    @PreDestroy public void shutdown() { stop(); }

    public record Config(String poolUrl, String proxyUrl, String wallet, String worker, String devices) { }
    public record GpuState(String vendor, int index, String model, boolean selected,
                           MinerStats.MinerStatus status, boolean running, boolean poolHealthy, boolean manuallyPaused,
                           String connectionDetail, String lastError) { }
}
