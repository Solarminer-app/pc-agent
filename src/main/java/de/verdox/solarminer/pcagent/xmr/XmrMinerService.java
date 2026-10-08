package de.verdox.solarminer.pcagent.xmr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.MinerStopContext;
import de.verdox.solarminer.pcagent.mining.MinerConsoleService;
import de.verdox.solarminer.pcagent.mining.PayoutDefaultsService;
import de.verdox.solarminer.pcagent.mining.MinerProcessRegistry;
import de.verdox.solarminer.pcagent.mining.MinerShareTelemetry;
import de.verdox.solarminer.pcagent.xmr.download.XmrDownloadService;
import de.verdox.solarminer.pcagent.lowlevel.sensor.HardwareSensorReader;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class XmrMinerService {
    private static final Logger LOGGER = Logger.getLogger(XmrMinerService.class.getName());
    private static final Path XMRIG_DIR = Paths.get("./solarminer-agent/xmrig/").toAbsolutePath().normalize();

    private final XmrConfigService configService;
    private final ProxyConfigurationService proxyConfigurationService;
    private final PayoutDefaultsService payoutDefaultsService;
    private final MinerConsoleService console;
    private final ObjectMapper objectMapper;
    private final HardwareSensorReader sensorReader;
    private final String processorName;
    private Process minerProcess;

    private final int totalLogicalProcessors;
    private int currentMaxThreads;
    @Getter
    private final long estimatedMaxCpuWattage;

    private volatile MinerStats.MinerStatus minerStatus = MinerStats.MinerStatus.PAUSED;
    private volatile String lastStartError;

    @Getter
    private long desiredPowerUsage = 0;

    private final ExecutorService streamReaderExecutor = Executors.newCachedThreadPool();
    private ScheduledExecutorService apiPollerExecutor;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private volatile long currentHashesPerSecond = 0;
    // Null means that XMRig has not supplied a counter yet; unknown is not zero shares.
    private volatile Long acceptedShares;
    private volatile Long rejectedShares;
    private volatile MinerShareTelemetry.Counters poolCounters = MinerShareTelemetry.Counters.unavailable();
    private int apiErrorCount = 0;
    private volatile int crashRestartAttempts;

    public XmrMinerService(XmrConfigService configService, ObjectMapper objectMapper, HardwareSensorReader sensorReader,
                           ProxyConfigurationService proxyConfigurationService, MinerConsoleService console,
                           PayoutDefaultsService payoutDefaultsService) {
        this.configService = configService;
        this.proxyConfigurationService = proxyConfigurationService;
        this.console = console;
        this.payoutDefaultsService = payoutDefaultsService;
        this.objectMapper = objectMapper;
        this.sensorReader = sensorReader;

        SystemInfo systemInfo = new SystemInfo();
        CentralProcessor processor = systemInfo.getHardware().getProcessor();
        this.processorName = processor.getProcessorIdentifier().getName();
        this.totalLogicalProcessors = processor.getLogicalProcessorCount();
        this.currentMaxThreads = this.totalLogicalProcessors;
        this.estimatedMaxCpuWattage = estimateCpuTdp(processor.getProcessorIdentifier().getName(), totalLogicalProcessors);
    }

    /** Retry a bounded number of unexpected exits while a non-zero local target remains set. */
    @Scheduled(fixedDelay = 30_000)
    public synchronized void restartAfterUnexpectedExit() {
        if (minerStatus != MinerStats.MinerStatus.ERROR || desiredPowerUsage <= 0 || isMiningProcessAlive()) return;
        if (crashRestartAttempts >= 3) return;
        crashRestartAttempts++;
        console.append("monero", "[SolarMiner] XMRig watchdog restart " + crashRestartAttempts + "/3");
        startMining();
    }

    @PreDestroy
    public void onShutdown() {
        LOGGER.info("Stopping XMR Miner service...");
        hardStopMining();
    }

    public MinerStats.Worker getWorkerStats() {
        if (!isManagedProcessAlive() && !MinerProcessRegistry.running("xmrig").isEmpty()) {
            return new MinerStats.Worker(MinerStats.MinerStatus.MINING, processorName + " (extern gestartet)", "RandomX",
                    0, readCPUTemperature(), desiredPowerUsage, 0, estimatedMaxCpuWattage,
                    estimatedMaxCpuWattage, 0, List.of(configService.readUserPoolFromConfig()), "CPU", processorName, "cpu", null, null,
                    MinerStats.PoolTelemetry.unavailable());
        }
        if (minerStatus == MinerStats.MinerStatus.MINING && !isMiningProcessAlive()) {
            minerStatus = MinerStats.MinerStatus.ERROR;
            currentHashesPerSecond = 0;
        }

        return new MinerStats.Worker(
                minerStatus,
                processorName,
                "RandomX",
                currentHashesPerSecond / Math.pow(10, 12),
                readCPUTemperature(),
                desiredPowerUsage,
                0,
                estimatedMaxCpuWattage,
                estimatedMaxCpuWattage,
                getWattage(),
                List.of(configService.readUserPoolFromConfig()), "CPU", processorName, "cpu",
                minerStatus == MinerStats.MinerStatus.MINING ? acceptedShares : null,
                minerStatus == MinerStats.MinerStatus.MINING ? rejectedShares : null,
                poolTelemetry());
    }

    /** Pool readings only while the managed miner runs; a stopped miner must not keep an old difficulty on screen. */
    private MinerStats.PoolTelemetry poolTelemetry() {
        if (minerStatus != MinerStats.MinerStatus.MINING) return MinerStats.PoolTelemetry.unavailable();
        MinerShareTelemetry.Counters counters = poolCounters;
        return new MinerStats.PoolTelemetry(counters.difficulty(), counters.bestShare(), counters.stale(), counters.latencyMs());
    }

    public boolean readyForStart() {
        ensureDefaultConfiguration();
        return binaryAvailable() && configService.isProxyRouteConfigured();
    }

    /**
     * A fresh PC-Agent install has no XMRig config yet. In that case, initialize it from the
     * current fee-backend house target so Monero is startable without inventing a user wallet.
     * Never replace an existing config here; explicit user routes remain the user's choice.
     */
    public synchronized boolean ensureDefaultConfiguration() {
        if (configService.isProxyRouteConfigured()) return true;
        if (configService.hasConfiguredPoolLogin() || proxyConfigurationService.moneroUrl() == null) return false;
        PayoutDefaultsService.DefaultPayout payout = payoutDefaultsService.resolve("monero").orElse(null);
        if (payout == null) return false;
        try {
            configService.configureXmrig(XmrDownloadService.CONFIG_PATH, proxyConfigurationService.moneroUrl(),
                    payout.poolUrl() + ";" + payout.login() + ";x", false);
            payoutDefaultsService.markDefault("monero", true);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Default Monero payout could not be initialized", e);
            return false;
        }
    }

    /**
     * A saved fee-backend payout is re-read on every start so a superseded house pool or wallet
     * never keeps mining somewhere else. If the fee-backend is unreachable the validated route
     * that is already in config.json stays in use.
     */
    private void refreshDefaultPayout() {
        if (!payoutDefaultsService.usesDefault("monero")) return;
        PayoutDefaultsService.DefaultPayout payout = payoutDefaultsService.resolve("monero").orElse(null);
        if (payout == null) return;
        try {
            if (configService.updateProxyRoute(XmrDownloadService.CONFIG_PATH, proxyConfigurationService.moneroUrl(),
                    payout.poolUrl() + ";" + payout.login() + ";x")) {
                console.append("monero", "[SolarMiner] Payout updated to the current SolarMiner default target");
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Default Monero payout could not be refreshed", e);
        }
    }

    public synchronized void startMining() {
        if (isMiningProcessAlive()) {
            LOGGER.info("XMRig is already running.");
            return;
        }
        if (!MinerProcessRegistry.running("xmrig").isEmpty()) {
            minerStatus = MinerStats.MinerStatus.MINING;
            LOGGER.info("An XMRig process is already running outside this agent; refusing to start a duplicate.");
            return;
        }
        console.started("monero");
        lastStartError = null;
        acceptedShares = null;
        rejectedShares = null;
        poolCounters = MinerShareTelemetry.Counters.unavailable();

        ensureDefaultConfiguration();

        if (!configService.isProxyRouteConfigured() || !proxyConfigurationService.miningReady("monero")) {
            LOGGER.severe("Cannot start XMRig: a reachable SolarMiner proxy with a loaded fee route is required in standalone mode");
            lastStartError = "Start rejected: SolarMiner proxy or Monero fee target is not ready";
            console.append("monero", "[SolarMiner] " + lastStartError);
            minerStatus = MinerStats.MinerStatus.ERROR;
            return;
        }
        refreshDefaultPayout();

        String os = System.getProperty("os.name").toLowerCase();
        String executableName = os.contains("win") ? "xmrig.exe" : "xmrig";
        File executableFile = XMRIG_DIR.resolve(executableName).toFile();

        if (!executableFile.exists()) {
            LOGGER.severe("Cannot start mining: Executable not found at " + executableFile.getAbsolutePath());
            lastStartError = "Start rejected: XMRig executable is missing: " + executableFile.getAbsolutePath();
            console.append("monero", "[SolarMiner] " + lastStartError);
            minerStatus = MinerStats.MinerStatus.ERROR;
            return;
        }

        if (!os.contains("win")) {
            executableFile.setExecutable(true);
        }

        try {
            LOGGER.info("Starting XMRig process with max " + currentMaxThreads + " threads...");
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command(executableFile.getAbsolutePath(), "--config=config.json");
            processBuilder.directory(XMRIG_DIR.toFile());
            processBuilder.redirectErrorStream(true);

            minerProcess = processBuilder.start();
            Process started = minerProcess;
            minerStatus = MinerStats.MinerStatus.MINING;
            apiErrorCount = 0;

            var outputTask = streamReaderExecutor.submit(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(started.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        console.append("monero", line);
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error reading XMRig output stream", e);
                    console.append("monero", "[SolarMiner] Could not read miner output: " + e.getMessage());
                }
            });

            started.onExit().thenAccept(process -> {
                try { outputTask.get(1, TimeUnit.SECONDS); }
                catch (Exception ignored) { }
                if (minerStatus == MinerStats.MinerStatus.MINING) {
                    LOGGER.severe("XMRig process crashed or exited unexpectedly with code: " + process.exitValue());
                    console.append("monero", "[SolarMiner] XMRig exited with code " + process.exitValue());
                    lastStartError = "XMRig exited with code " + process.exitValue();
                    minerStatus = MinerStats.MinerStatus.ERROR;
                }
                currentHashesPerSecond = 0;
            });

            if (apiPollerExecutor != null && !apiPollerExecutor.isShutdown()) {
                apiPollerExecutor.shutdownNow();
            }
            apiPollerExecutor = Executors.newSingleThreadScheduledExecutor();
            apiPollerExecutor.scheduleAtFixedRate(this::fetchHashrateFromApi, 5, 5, TimeUnit.SECONDS);

            LOGGER.info("XMRig started successfully. PID: " + minerProcess.pid());

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to start XMRig process: " + e.getMessage(), e);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (e instanceof java.nio.file.AccessDeniedException || reason.toLowerCase().contains("access is denied")
                    || reason.toLowerCase().contains("zugriff verweigert")) {
                lastStartError = "Windows denied the XMRig start due to missing permissions. Check file permissions and Windows security prompts. Details: " + reason;
            } else {
                lastStartError = "XMRig could not be started: " + reason;
            }
            console.append("monero", "[SolarMiner] " + lastStartError);
            minerStatus = MinerStats.MinerStatus.ERROR;
            currentHashesPerSecond = 0;
        }
    }

    public synchronized void hardStopMining() {
        if (apiPollerExecutor != null && !apiPollerExecutor.isShutdown()) {
            apiPollerExecutor.shutdownNow();
        }

        if (isManagedProcessAlive()) {
            console.append("monero", "[SolarMiner] Stop requested by: " + MinerStopContext.source());
            LOGGER.info("Sending kill signal to XMRig process...");
            minerProcess.destroyForcibly();
            try {
                minerProcess.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            LOGGER.info("XMRig process terminated.");
            console.append("monero", "[SolarMiner] XMRig stopped");
        }
        MinerProcessRegistry.stop("xmrig");

        minerStatus = MinerStats.MinerStatus.STOPPED;
        crashRestartAttempts = 0;
        currentHashesPerSecond = 0;
        acceptedShares = null;
        rejectedShares = null;
        poolCounters = MinerShareTelemetry.Counters.unavailable();
        minerProcess = null;
    }

    private void fetchHashrateFromApi() {
        if (!isMiningProcessAlive()) {
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:1999/2/summary"))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer token")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode hashrateNode = root.path("hashrate").path("total");

                if (hashrateNode.isArray() && !hashrateNode.isEmpty() && !hashrateNode.get(0).isNull()) {
                    this.currentHashesPerSecond = (long) hashrateNode.get(0).asDouble();
                    this.apiErrorCount = 0; // Reset error count on success
                }
                MinerShareTelemetry.Counters shares = MinerShareTelemetry.xmrig(root.path("results"), root.path("connection"));
                acceptedShares = shares.accepted();
                rejectedShares = shares.rejected();
                poolCounters = shares;
            } else {
                handleApiError();
            }
        } catch (Exception e) {
            handleApiError();
        }
    }

    private void handleApiError() {
        apiErrorCount++;
        if (apiErrorCount > 6) {
            this.currentHashesPerSecond = 0;
            LOGGER.log(Level.FINE, "API is unresponsive. Hashrate zeroed out.");
        }
    }

    public synchronized void setDesiredPowerUsage(long watts) {
        LOGGER.info("Setting desired power target to: " + watts + "W (Estimated max: " + estimatedMaxCpuWattage + "W)");
        this.desiredPowerUsage = watts;

        if (watts <= 0) {
            hardStopMining();
            return;
        }

        if (watts >= estimatedMaxCpuWattage) {
            this.currentMaxThreads = this.totalLogicalProcessors;
        } else {
            double percentage = (double) watts / estimatedMaxCpuWattage;
            this.currentMaxThreads = Math.max(1, (int) Math.floor(this.totalLogicalProcessors * percentage));
        }

        LOGGER.info("Calculated thread limit based on power target: " + currentMaxThreads + " of " + totalLogicalProcessors + " threads.");

        if (minerProcess != null && minerProcess.isAlive()) {
            hardStopMining();
            startMining();
        }
    }

    /** Lowest estimated CPU budget that still maps to one XMRig worker thread. */
    public long getMinimumControllablePowerWatts() {
        return Math.max(1, (long) Math.ceil((double) estimatedMaxCpuWattage / totalLogicalProcessors));
    }

    public double readCPUTemperature() {
        return sensorReader.getCpuTemperatureCelsius();
    }

    public long getWattage() {
        if(isMiningProcessAlive()) {
            return Math.round(sensorReader.getCpuPowerWatts());
        }
        return 0;
    }

    private void applyThreadLimitToConfig() {
        Path configPath = XMRIG_DIR.resolve("config.json");
        configService.setMaxThreads(configPath, currentMaxThreads);
    }

    private long estimateCpuTdp(String cpuName, int coreCount) {
        cpuName = cpuName.toLowerCase();

        if (cpuName.contains(" i9") || cpuName.contains(" ryzen 9")) {
            return cpuName.endsWith("k") || cpuName.endsWith("x") ? 150 : 100;
        } else if (cpuName.contains(" i7") || cpuName.contains(" ryzen 7")) {
            return cpuName.endsWith("k") || cpuName.endsWith("x") ? 125 : 65;
        } else if (cpuName.contains(" i5") || cpuName.contains(" ryzen 5")) {
            return 65;
        } else if (cpuName.endsWith("u") || cpuName.endsWith("p")) {
            return 28;
        }
        return Math.min(200L, coreCount * 15L);
    }

    public boolean isMiningProcessAlive() {
        return isManagedProcessAlive() || !MinerProcessRegistry.running("xmrig").isEmpty();
    }

    private boolean isManagedProcessAlive() { return minerProcess != null && minerProcess.isAlive(); }

    public boolean hasExternalMinerProcess() {
        return !isManagedProcessAlive() && !MinerProcessRegistry.running("xmrig").isEmpty();
    }

    public String lastStartError() { return lastStartError; }

    public boolean binaryAvailable() {
        String executableName = System.getProperty("os.name", "").toLowerCase().contains("win") ? "xmrig.exe" : "xmrig";
        return XMRIG_DIR.resolve(executableName).toFile().isFile();
    }
}
