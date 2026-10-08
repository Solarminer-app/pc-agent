package de.verdox.solarminer.pcagent.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.dto.Pools;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.EarningsForecastService;
import de.verdox.solarminer.pcagent.mining.PayoutDefaultsService;
import de.verdox.solarminer.pcagent.mining.ReferralConfigurationService;
import de.verdox.solarminer.pcagent.mining.FeeTransparencyService;
import de.verdox.solarminer.pcagent.mining.WalletBalanceService;
import de.verdox.solarminer.pcagent.mining.WindowsDefenderExclusionService;
import de.verdox.solarminer.pcagent.mining.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.ProxyDiscoveryService;
import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.pearl.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.pearl.SrbDownloadService;
import de.verdox.solarminer.pcagent.xmr.XmrConfigService;
import de.verdox.solarminer.pcagent.xmr.XmrMinerService;
import de.verdox.solarminer.pcagent.xmr.download.XmrDownloadService;
import de.verdox.solarminer.pcagent.lowlevel.sensor.WindowsLhmBootstrapService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.List;

@RestController
@RequestMapping("/api/agent/local")
@Tag(name = "PC mining agent")
public class MiningController {
    private final MiningService miningService;
    private final XmrConfigService xmrConfigService;
    private final ProxyConfigurationService proxyConfigurationService;
    private final PearlMinerService pearlMinerService;
    private final GpuCoinMinerService gpuCoins;
    private final SrbDownloadService srbDownloadService;
    private final XmrDownloadService xmrDownloadService;
    private final LocalGpuPowerService gpuPowerService;
    private final XmrMinerService xmrMinerService;
    private final WindowsLhmBootstrapService lhmBootstrapService;
    private final ProxyDiscoveryService proxyDiscoveryService;
    private final EarningsForecastService earningsForecastService;
    private final PayoutDefaultsService payoutDefaultsService;
    private final ReferralConfigurationService referralConfigurationService;
    private final FeeTransparencyService feeTransparencyService;
    private final WalletBalanceService walletBalanceService;
    private final WindowsDefenderExclusionService defenderExclusionService;
    private final MinerCatalogService minerCatalog;

    public MiningController(MiningService miningService, XmrConfigService xmrConfigService,
                            PearlMinerService pearlMinerService, GpuCoinMinerService gpuCoins, LocalGpuPowerService gpuPowerService,
                            XmrMinerService xmrMinerService, ProxyConfigurationService proxyConfigurationService,
                            SrbDownloadService srbDownloadService, XmrDownloadService xmrDownloadService,
                            WindowsLhmBootstrapService lhmBootstrapService,
                            ProxyDiscoveryService proxyDiscoveryService,
                            EarningsForecastService earningsForecastService,
                            PayoutDefaultsService payoutDefaultsService,
                            ReferralConfigurationService referralConfigurationService,
                            FeeTransparencyService feeTransparencyService,
                            WalletBalanceService walletBalanceService,
                            WindowsDefenderExclusionService defenderExclusionService,
                            MinerCatalogService minerCatalog) {
        this.miningService = miningService;
        this.xmrConfigService = xmrConfigService;
        this.proxyConfigurationService = proxyConfigurationService;
        this.pearlMinerService = pearlMinerService;
        this.gpuCoins = gpuCoins;
        this.srbDownloadService = srbDownloadService;
        this.xmrDownloadService = xmrDownloadService;
        this.gpuPowerService = gpuPowerService;
        this.xmrMinerService = xmrMinerService;
        this.lhmBootstrapService = lhmBootstrapService;
        this.proxyDiscoveryService = proxyDiscoveryService;
        this.earningsForecastService = earningsForecastService;
        this.payoutDefaultsService = payoutDefaultsService;
        this.referralConfigurationService = referralConfigurationService;
        this.feeTransparencyService = feeTransparencyService;
        this.walletBalanceService = walletBalanceService;
        this.defenderExclusionService = defenderExclusionService;
        this.minerCatalog = minerCatalog;
    }

    @GetMapping("identify")
    public boolean identify() {
        return true;
    }

    @PostMapping("/setPoolConfiguration")
    public boolean setPoolConfiguration(@RequestParam String poolUrl, @RequestParam String poolUser,
                                        @RequestParam double devFeePercentage) throws java.io.IOException {
        if (!lhmBootstrapService.readyForAgent()) return false;
        if (!proxyConfigurationService.matches(poolUrl, "monero")) return false;
        xmrMinerService.hardStopMining();
        xmrConfigService.configureXmrig(XmrDownloadService.CONFIG_PATH, poolUrl, poolUser, false);
        return miningService.useMonero();
    }

    @GetMapping("/proxy")
    public ProxyOverview proxy() {
        return new ProxyOverview(proxyConfigurationService.host(), proxyConfigurationService.moneroUrl(),
                proxyConfigurationService.pearlUrl(), proxyConfigurationService.ravencoinUrl(),
                proxyConfigurationService.ethereumclassicUrl(), proxyConfigurationService.decredUrl(), proxyConfigurationService.quantusUrl(), proxyConfigurationService.isReachable(),
                proxyConfigurationService.standalone() ? "standalone" : "external",
                proxyConfigurationService.rollMode(),
                proxyConfigurationService.managedStatus(), proxyConfigurationService.managedDetail(),
                proxyConfigurationService.managedVersion(),
                proxyConfigurationService.feeReady("monero"), proxyConfigurationService.feeReady("pearl"),
                proxyConfigurationService.feeReady("ravencoin"), proxyConfigurationService.feeReady("ethereumclassic"),
                proxyConfigurationService.feeReady("decred"), proxyConfigurationService.feeReady("quantus"));
    }

    /** The node may set this through the LAN API; local edits are intentionally allowed but not authoritative. */
    @GetMapping("/referral")
    public ReferralOverview referral() { return new ReferralOverview(referralConfigurationService.get()); }

    @PostMapping("/referral")
    public boolean setReferral(@RequestParam String key) {
        boolean updated = referralConfigurationService.set(key);
        if (updated) {
            payoutDefaultsService.invalidate();
            proxyConfigurationService.invalidateFeeCache();
        }
        return updated;
    }

    @GetMapping("/fees")
    public List<FeeTransparencyService.FeeOverview> fees() { return feeTransparencyService.overview(); }

    public record ReferralOverview(String key) { }

    @PostMapping("/proxy")
    public boolean configureProxy(@RequestParam String host) {
        if (!lhmBootstrapService.readyForAgent()) return false;
        if (host.equals(proxyConfigurationService.host())) return true;
        if (!proxyConfigurationService.configure(host)) return false;
        payoutDefaultsService.invalidate();
        return miningService.pauseAll("Proxy was changed");
    }

    @PostMapping("/proxy/mode")
    public boolean configureProxyMode(@RequestParam String mode) {
        if (!lhmBootstrapService.readyForAgent()) return false;
        if (!"local".equals(mode) && !"external".equals(mode)) return false;
        if (!miningService.pauseAll("Proxy mode was configured")) return false;
        if (!proxyConfigurationService.setMode(mode)) return false;
        payoutDefaultsService.invalidate();
        return true;
    }

    /**
     * Chooses how the proxy rolls fee targets per job: "random" (stateless independent rolls)
     * or "stateful" (a persistent per-coin urn that keeps the realised share on target).
     * The managed proxy restarts with the new flag, so miners are paused first.
     */
    @PostMapping("/proxy/roll-mode")
    public boolean configureProxyRollMode(@RequestParam String mode) {
        if (!lhmBootstrapService.readyForAgent()) return false;
        if (!"random".equals(mode) && !"stateful".equals(mode)) return false;
        if (!miningService.pauseAll("Fee roll mode was configured")) return false;
        return proxyConfigurationService.setRollMode(mode);
    }

    @PostMapping("/proxy/discover")
    public List<ProxyDiscoveryService.ProxyCandidate> discoverProxy() throws java.io.IOException {
        if (!lhmBootstrapService.readyForAgent()) return List.of();
        return proxyDiscoveryService.discover();
    }

    public record ProxyOverview(String host, String moneroUrl, String pearlUrl,
                                String ravencoinUrl, String ethereumclassicUrl, String decredUrl, String quantusUrl, boolean reachable,
                                String mode, String feeRollMode, String managedStatus, String managedDetail, String managedVersion,
                                boolean moneroFeeReady, boolean pearlFeeReady,
                                boolean ravencoinFeeReady, boolean ethereumclassicFeeReady, boolean decredFeeReady, boolean quantusFeeReady) { }

    @PostMapping("/monero/configuration")
    public boolean setMoneroConfiguration(@RequestBody MoneroConfiguration request) throws java.io.IOException {
        if (request == null || request.worker() == null
                || !request.worker().matches("^[A-Za-z0-9_-]{1,32}$")
                || proxyConfigurationService.moneroUrl() == null) return false;
        String login;
        if (request.wallet() == null || request.wallet().isBlank()) {
            // Without an own payout address the fee-backend decides pool and wallet together,
            // so the local route ends up exactly on the house target the proxy fees towards.
            PayoutDefaultsService.DefaultPayout payout = payoutDefaultsService.resolve("monero").orElse(null);
            if (payout == null) return false;
            login = payout.poolUrl() + ";" + payout.login() + ";x";
            payoutDefaultsService.markDefault("monero", true);
        } else {
            if (request.poolUrl() == null) return false;
            java.net.URI pool;
            try { pool = java.net.URI.create(request.poolUrl()); }
            catch (IllegalArgumentException e) { return false; }
            if (!("stratum+tcp".equals(pool.getScheme()) || "stratum+ssl".equals(pool.getScheme()))
                    || pool.getHost() == null || pool.getPort() < 1 || pool.getPort() > 65535
                    || pool.getRawUserInfo() != null || (pool.getRawPath() != null && !pool.getRawPath().isEmpty())
                    || pool.getRawQuery() != null || pool.getRawFragment() != null
                    || !request.wallet().matches("^[1-9A-HJ-NP-Za-km-z]{95,120}$")) return false;
            login = request.poolUrl() + ";" + request.wallet() + "." + request.worker() + ";x";
            payoutDefaultsService.markDefault("monero", false);
        }
        xmrMinerService.hardStopMining();
        xmrConfigService.configureXmrig(XmrDownloadService.CONFIG_PATH,
                proxyConfigurationService.moneroUrl(), login, false);
        return true;
    }

    public record MoneroConfiguration(String poolUrl, String wallet, String worker) { }

    @PostMapping("/pearl/download")
    public boolean retryPearlDownload() {
        return minerCatalog.downloadSelected("pearl");
    }

    @PostMapping("/{coin}/download")
    public boolean downloadGpuCoin(@PathVariable String coin) {
        return GpuCoinMinerService.supported(coin) && minerCatalog.downloadSelected(coin);
    }

    @PostMapping("/{coin}/miners/{minerId}/download")
    public boolean downloadMiner(@PathVariable String coin, @PathVariable String minerId) {
        return minerCatalog.download(coin, minerId);
    }

    @PostMapping("/monero/download")
    public boolean installMonero() {
        return minerCatalog.downloadSelected("monero");
    }

    @PostMapping("/{coin}/defender-exclusion")
    public ResponseEntity<DefenderExclusionResult> addDefenderExclusion(
            @PathVariable String coin, jakarta.servlet.http.HttpServletRequest request) {
        try {
            if (!java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()
                    || !isLocalUiRequest(request))
                return ResponseEntity.status(403).body(new DefenderExclusionResult(false,
                        "Die Defender-Ausnahme muss von der lokalen PC-Agent-Seite angefordert werden."));
            if (!defenderExclusionService.isWindows())
                return ResponseEntity.status(400).body(new DefenderExclusionResult(false,
                        "Diese Funktion ist nur unter Windows verfügbar."));
            java.nio.file.Path directory = switch (coin) {
                case "monero" -> xmrDownloadService.installDirectory();
                case "pearl" -> srbDownloadService.installDirectory();
                default -> null;
            };
            if (directory == null) return ResponseEntity.status(404)
                    .body(new DefenderExclusionResult(false, "Unbekannter Miner."));
            String downloadStatus = "monero".equals(coin)
                    ? xmrDownloadService.status() : srbDownloadService.status();
            if (!"BLOCKED_BY_ANTIVIRUS".equals(downloadStatus))
                return ResponseEntity.status(409).body(new DefenderExclusionResult(false,
                        "Eine Defender-Ausnahme ist nur nach einer erkannten Blockierung verfügbar."));
            defenderExclusionService.addMinerDirectory(coin, directory);
            return ResponseEntity.ok(new DefenderExclusionResult(true,
                    "Windows Defender hat die Ausnahme für " + directory + " bestätigt."));
        } catch (Exception failure) {
            String detail = failure.getMessage() == null ? "Unbekannter Windows-Fehler." : failure.getMessage();
            return ResponseEntity.status(400).body(new DefenderExclusionResult(false, detail));
        }
    }

    public record DefenderExclusionResult(boolean success, String message) { }

    private boolean isLocalUiRequest(jakarta.servlet.http.HttpServletRequest request) {
        String serverName = request.getServerName().toLowerCase(java.util.Locale.ROOT);
        if (!(serverName.equals("localhost") || serverName.equals("127.0.0.1") || serverName.equals("::1")))
            return false;
        String origin = request.getHeader("Origin");
        if (origin == null) return false;
        try {
            java.net.URI uri = java.net.URI.create(origin);
            String originHost = uri.getHost();
            return originHost != null && (originHost.equalsIgnoreCase("localhost")
                    || originHost.equals("127.0.0.1") || originHost.equals("::1"))
                    && uri.getPort() == request.getServerPort()
                    && uri.getScheme().equalsIgnoreCase(request.getScheme());
        } catch (IllegalArgumentException invalidOrigin) {
            return false;
        }
    }

    @PostMapping("/pearl/remove")
    public boolean removePearlMiner() {
        if ("DOWNLOADING".equals(srbDownloadService.status()) || !pearlMinerService.stop()
                || !gpuCoins.stop("ravencoin") || !gpuCoins.stop("ethereumclassic") || !gpuCoins.stop("decred") || !gpuCoins.stop("quantus")) return false;
        return srbDownloadService.remove();
    }

    @PostMapping("/{coin}/remove")
    public boolean removeGpuCoinMiner(@PathVariable String coin) {
        return GpuCoinMinerService.supported(coin) && removePearlMiner();
    }

    @PostMapping("/monero/remove")
    public boolean removeMoneroMiner() {
        if ("DOWNLOADING".equals(xmrDownloadService.status()) || !miningService.pauseMining("monero")) return false;
        return xmrDownloadService.remove();
    }

    @PostMapping("/pearl/configuration")
    public boolean setPearlConfiguration(@RequestBody PearlMinerService.Config configuration) throws java.io.IOException {
        boolean feeBackendPayout = configuration == null
                || configuration.wallet() == null || configuration.wallet().isBlank();
        PearlMinerService.Config effective = feeBackendPayout
                ? withFeeBackendPayout(configuration) : configuration;
        PearlMinerService.validate(effective);
        if (!proxyConfigurationService.matches(effective.proxyUrl(), "pearl"))
            throw new IllegalArgumentException("SolarMiner-Proxy für Pearl fehlt oder stimmt nicht mit der gespeicherten Verbindung überein");
        pearlMinerService.configure(effective);
        payoutDefaultsService.markDefault("pearl", feeBackendPayout);
        return true;
    }

    @PostMapping("/{coin}/configuration")
    public boolean setGpuCoinConfiguration(@PathVariable String coin,
                                           @RequestBody GpuCoinMinerService.Config config) throws java.io.IOException {
        if (!GpuCoinMinerService.supported(coin)) throw new IllegalArgumentException("Unbekannter GPU-Coin");
        boolean feeBackendPayout = config == null || config.wallet() == null || config.wallet().isBlank();
        GpuCoinMinerService.Config effective = feeBackendPayout ? withGpuCoinFeeBackendPayout(coin, config) : config;
        gpuCoins.configure(coin, effective);
        payoutDefaultsService.markDefault(coin, feeBackendPayout);
        return miningService.switchCoin(coin);
    }

    private GpuCoinMinerService.Config withGpuCoinFeeBackendPayout(String coin, GpuCoinMinerService.Config request) {
        if (request == null) throw new IllegalArgumentException("GPU-Coin-Konfiguration fehlt");
        PayoutDefaultsService.DefaultPayout payout = payoutDefaultsService.resolve(coin).orElseThrow(
                () -> new IllegalArgumentException("Kein SolarMiner-Standard-Auszahlungsziel für " + coin + " erreichbar"));
        String wallet = payout.walletPart();
        String worker = payout.workerPart();
        if (worker == null || worker.isBlank()) worker = "solarminer";
        return new GpuCoinMinerService.Config(payout.poolUrl(), request.proxyUrl(), wallet, worker, request.devices());
    }

    @PostMapping("/{coin}/gpus/{vendor}/{index}/resume")
    public boolean resumeGpuCoin(@PathVariable String coin, @PathVariable String vendor, @PathVariable int index) {
        return lhmBootstrapService.readyForAgent() && gpuCoins.resumeGpu(coin, vendor, index);
    }

    @PostMapping("/{coin}/gpus/{vendor}/{index}/pause")
    public boolean pauseGpuCoin(@PathVariable String coin, @PathVariable String vendor, @PathVariable int index) {
        return gpuCoins.pauseGpu(coin, vendor, index);
    }

    /**
     * An empty wallet means the fee-backend payout is used. Its pool and worker always belong
     * together, so a half-entered own route is never mixed with the house wallet.
     */
    private PearlMinerService.Config withFeeBackendPayout(PearlMinerService.Config request) {
        if (request == null) throw new IllegalArgumentException("Pearl-Konfiguration fehlt");
        PayoutDefaultsService.DefaultPayout payout = payoutDefaultsService.resolve("pearl").orElseThrow(
                () -> new IllegalArgumentException("Kein SolarMiner-Standard-Auszahlungsziel für Pearl erreichbar"));
        String worker = payout.workerPart() != null ? payout.workerPart()
                : request.worker() == null || request.worker().isBlank() ? "solarminer" : request.worker();
        return new PearlMinerService.Config(payout.poolUrl(), request.proxyUrl(), payout.walletPart(),
                worker, request.devices());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<java.util.Map<String, String>> invalidConfiguration(IllegalArgumentException exception) {
        String message = exception.getMessage() == null ? "Ungültige Konfiguration" : exception.getMessage();
        return ResponseEntity.badRequest().body(java.util.Map.of("message", message));
    }

    @GetMapping("/coin")
    public String activeCoin() {
        return miningService.activeCoin();
    }

    /** Local file checks only: navigation must not wait for GPU discovery, pools or market data. */
    @GetMapping("/miner-catalog")
    public List<MinerInstallation> minerCatalog() {
        return List.of(
                installation("monero", "Monero"), installation("pearl", "Pearl"),
                installation("ravencoin", "Ravencoin"), installation("ethereumclassic", "Ethereum Classic"),
                installation("decred", "Decred"), installation("quantus", "Quantus"));
    }

    private MinerInstallation installation(String coin, String name) {
        MinerCatalogService.MinerOption selected = minerCatalog.selected(coin);
        return new MinerInstallation(coin, name, selected.device(), selected.algorithm(), selected.installed(),
                selected.experimental(), selected.id(), minerCatalog.options(coin));
    }

    public record MinerInstallation(String id, String name, String device, String algorithm,
                                    boolean binaryAvailable, boolean experimental, String selectedMinerId,
                                    List<MinerCatalogService.MinerOption> miners) { }

    @GetMapping("/miner-options")
    public List<MinerCatalogService.MinerOption> minerOptions() { return minerCatalog.allOptions(); }

    @PostMapping("/{coin}/miner")
    public boolean selectMiner(@PathVariable String coin, @RequestParam String minerId) {
        return minerCatalog.select(coin, minerId);
    }

    @GetMapping("/overview")
    public AgentOverview overview() {
        List<LocalGpuPowerService.Gpu> gpus = gpuPowerService.discover();
        xmrMinerService.ensureDefaultConfiguration();
        // Independent display lookups run concurrently; miner start gates stay in their services.
        try (var displayLookups = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var proxyResult = java.util.concurrent.CompletableFuture.supplyAsync(this::proxy, displayLookups);
            var payoutResult = java.util.concurrent.CompletableFuture.supplyAsync(this::payoutDefaults, displayLookups);
            var feeResult = java.util.concurrent.CompletableFuture.supplyAsync(feeTransparencyService::overview, displayLookups);
            MinerStats stats = miningService.getStats(gpus);
            List<EarningsForecastService.Forecast> earnings = earningsForecastService.forecasts(stats.workers());
            String active = miningService.activeCoin();
            boolean xmrConfigured = xmrConfigService.isProxyRouteConfigured();
            boolean pearlConfigured = pearlMinerService.configuration() != null
                    && proxyConfigurationService.matches(pearlMinerService.configuration().proxyUrl(), "pearl");
            List<CoinOverview> coins = List.of(
                    new CoinOverview("monero", "Monero", "XMR", "CPU", "RandomX",
                            xmrMinerService.getWorkerStats().miningStatus(),
                            xmrConfigured, minerCatalog.selectedIsInstalled("monero"), false, minerCatalog.selected("monero"), minerCatalog.options("monero")),
                    new CoinOverview("pearl", "Pearl", "PRL", "GPU", "PearlHash",
                            pearlMinerService.status(),
                            pearlConfigured, minerCatalog.selectedIsInstalled("pearl"), false, minerCatalog.selected("pearl"), minerCatalog.options("pearl")),
                    new CoinOverview("ravencoin", "Ravencoin", "RVN", "GPU", "KAWPOW",
                            gpuCoins.status("ravencoin"), gpuCoins.configuration("ravencoin") != null,
                            minerCatalog.selectedIsInstalled("ravencoin"), true, minerCatalog.selected("ravencoin"), minerCatalog.options("ravencoin")),
                    new CoinOverview("ethereumclassic", "Ethereum Classic", "ETC", "GPU", "ETCHash",
                            gpuCoins.status("ethereumclassic"), gpuCoins.configuration("ethereumclassic") != null,
                            minerCatalog.selectedIsInstalled("ethereumclassic"), true, minerCatalog.selected("ethereumclassic"), minerCatalog.options("ethereumclassic")),
                    new CoinOverview("decred", "Decred", "DCR", "GPU", "BLAKE3",
                            gpuCoins.status("decred"), gpuCoins.configuration("decred") != null,
                            minerCatalog.selectedIsInstalled("decred"), true, minerCatalog.selected("decred"), minerCatalog.options("decred")),
                    new CoinOverview("quantus", "Quantus", "QTC", "GPU", "QPoW (Poseidon2)",
                            gpuCoins.status("quantus"), gpuCoins.configuration("quantus") != null,
                            minerCatalog.selectedIsInstalled("quantus"), true, minerCatalog.selected("quantus"), minerCatalog.options("quantus")));
            return new AgentOverview(stats, active, System.getProperty("os.name", "unknown"),
                    System.getProperty("os.arch", "unknown"), coins, earnings, gpus, proxyResult.join(), savedMoneroConfiguration(),
                    pearlMinerService.configuration(),
                    new DownloadReadiness(xmrDownloadService.status(), xmrDownloadService.detail(), xmrDownloadService.progress(),
                            xmrMinerService.lastStartError(), xmrDownloadService.installDirectory().toString()),
                    new PearlReadiness(pearlConfigured,
                            pearlMinerService.binaryAvailable(),
                            srbDownloadService.status(), srbDownloadService.detail(), srbDownloadService.progress(),
                            pearlMinerService.lastError(), pearlMinerService.connectionDetail(),
                            pearlMinerService.running(), pearlMinerService.poolHealthy(), pearlMinerService.gpuStates(gpus),
                            srbDownloadService.installDirectory().toString()),
                    payoutResult.join(), new ReferralOverview(referralConfigurationService.get()), feeResult.join(),
                    java.util.Map.of("ravencoin", gpuOverview("ravencoin", gpus),
                            "ethereumclassic", gpuOverview("ethereumclassic", gpus),
                            "decred", gpuOverview("decred", gpus), "quantus", gpuOverview("quantus", gpus)));
        }
    }

    private GpuCoinOverview gpuOverview(String coin, List<LocalGpuPowerService.Gpu> gpus) {
        return new GpuCoinOverview(gpuCoins.configuration(coin), gpuCoins.lastError(),
                gpuCoins.running(coin), gpuCoins.gpuStates(coin, gpus));
    }

    /** Fee-backend payout per coin, shown so an empty wallet is never silently an unknown destination. */
    @GetMapping("/payout-defaults")
    public List<PayoutOverview> payoutDefaults() {
        return java.util.List.of("monero", "pearl", "ravencoin", "ethereumclassic", "decred", "quantus").stream()
                .map(coin -> {
                    PayoutDefaultsService.DefaultView view = payoutDefaultsService.view(coin);
                    return new PayoutOverview(coin, view.available(), view.targetId(), view.poolUrl(),
                            view.maskedWallet(), payoutDefaultsService.usesDefault(coin));
                }).toList();
    }

    public record PayoutOverview(String coin, boolean available, String targetId, String poolUrl,
                                 String maskedWallet, boolean inUse) { }

    private MoneroConfiguration savedMoneroConfiguration() {
        if (!xmrConfigService.isProxyRouteConfigured()) return null;
        Pools pool = xmrConfigService.readUserPoolFromConfig();
        String[] login = pool.poolUsername().split(";", -1);
        if (login.length != 3) return null;
        int workerSeparator = login[1].lastIndexOf('.');
        if (workerSeparator < 1 || workerSeparator == login[1].length() - 1) return null;
        return new MoneroConfiguration(login[0], login[1].substring(0, workerSeparator),
                login[1].substring(workerSeparator + 1));
    }

    /**
     * The configured payout targets per coin — the light slice the wallet strip needs.
     * The strip must not pull the full overview (driver discovery, fees, forecasts) just
     * to learn which wallets exist.
     */
    @GetMapping("/wallet-targets")
    public List<WalletTarget> walletTargets() {
        MoneroConfiguration monero = savedMoneroConfiguration();
        PearlMinerService.Config pearl = pearlMinerService.configuration();
        List<WalletTarget> targets = new java.util.ArrayList<>();
        if (monero != null && monero.wallet() != null && !monero.wallet().isBlank())
            targets.add(new WalletTarget("monero", "Monero", "XMR", monero.wallet(), monero.poolUrl()));
        if (pearl != null && pearl.wallet() != null && !pearl.wallet().isBlank())
            targets.add(new WalletTarget("pearl", "Pearl", "PRL", pearl.wallet(), pearl.poolUrl()));
        for (var entry : COIN_IDENTITIES) {
            GpuCoinMinerService.Config config = gpuCoins.configuration(entry.id());
            if (config != null && config.wallet() != null && !config.wallet().isBlank())
                targets.add(new WalletTarget(entry.id(), entry.name(), entry.ticker(), config.wallet(), config.poolUrl()));
        }
        return List.copyOf(targets);
    }

    /** Same coin identities the overview exposes; kept here so the strip never pulls the overview. */
    private record CoinIdentity(String id, String name, String ticker) { }
    private static final List<CoinIdentity> COIN_IDENTITIES = List.of(
            new CoinIdentity("ravencoin", "Ravencoin", "RVN"),
            new CoinIdentity("ethereumclassic", "Ethereum Classic", "ETC"),
            new CoinIdentity("decred", "Decred", "DCR"),
            new CoinIdentity("quantus", "Quantus", "QTC"));

    public record WalletTarget(String coin, String name, String ticker, String wallet, String poolUrl) { }

    /** Batched wallet-strip snapshot: one push channel, one REST fallback, never the full overview. */
    public record WalletsSnapshot(List<WalletTarget> targets,
                                  List<WalletBalanceService.Balance> balances,
                                  java.util.Map<String, Double> prices) { }

    @GetMapping("/wallet-snapshot")
    public WalletsSnapshot walletSnapshot() {
        return new WalletsSnapshot(walletTargets(), walletBalances(), marketPrices());
    }

    @GetMapping("/wallet-balances")
    public List<WalletBalanceService.Balance> walletBalances() {
        return walletBalanceService.balances(walletTargets().stream()
                .map(target -> new WalletBalanceService.Target(target.coin(), target.poolUrl(), target.wallet()))
                .toList());
    }

    public record AgentOverview(MinerStats stats, String activeCoin, String platform, String architecture,
                                List<CoinOverview> coins,
                                List<EarningsForecastService.Forecast> earnings,
                                List<LocalGpuPowerService.Gpu> gpus, ProxyOverview proxy,
                                MoneroConfiguration moneroConfiguration, PearlMinerService.Config pearlConfiguration,
                                DownloadReadiness monero,
                                PearlReadiness pearl,
                                List<PayoutOverview> payoutDefaults,
                                ReferralOverview referral,
                                List<FeeTransparencyService.FeeOverview> fees,
                                java.util.Map<String, GpuCoinOverview> gpuCoins) { }

    public record GpuCoinOverview(GpuCoinMinerService.Config configuration, String minerError,
                                  boolean running, List<GpuCoinMinerService.GpuState> gpus) { }

    public record DownloadReadiness(String downloadStatus, String downloadDetail, int downloadProgress,
                                    String minerError, String installDirectory) { }

    public record CoinOverview(String id, String name, String ticker, String device, String algorithm,
                               MinerStats.MinerStatus status, boolean configured, boolean binaryAvailable,
                               boolean experimental, MinerCatalogService.MinerOption selectedMiner,
                               List<MinerCatalogService.MinerOption> miners) { }

    public record PearlReadiness(boolean configured, boolean binaryAvailable,
                                 String downloadStatus, String downloadDetail, int downloadProgress,
                                 String minerError, String connectionDetail, boolean running,
                                 boolean poolHealthy, List<PearlMinerService.GpuState> gpus,
                                 String installDirectory) { }

    @GetMapping("/earnings")
    public List<EarningsForecastService.Forecast> earnings() {
        return earningsForecastService.forecasts(miningService.getWorkerStats());
    }

    @GetMapping("/market-prices")
    public java.util.Map<String, Double> marketPrices() {
        return earningsForecastService.prices();
    }

    @PostMapping("/miners/{coin}/resume")
    public boolean resumeMiner(@PathVariable String coin) {
        return miningService.resumeMining(coin);
    }

    @PostMapping("/miners/{coin}/pause")
    public boolean pauseMiner(@PathVariable String coin) {
        return miningService.pauseMining(coin);
    }

    @PostMapping("/miners/{coin}/power-target")
    public boolean setMinerPowerTarget(@PathVariable String coin, @RequestParam long powerTarget) {
        return lhmBootstrapService.readyForAgent() && miningService.setTarget(coin, powerTarget);
    }

    @PostMapping("/pearl/gpus/{vendor}/{index}/resume")
    public boolean resumePearlGpu(@PathVariable String vendor, @PathVariable int index) {
        return lhmBootstrapService.readyForAgent() && pearlMinerService.resumeGpuManually(vendor, index);
    }

    @PostMapping("/pearl/gpus/{vendor}/{index}/pause")
    public boolean pausePearlGpu(@PathVariable String vendor, @PathVariable int index) {
        return pearlMinerService.pauseGpuManually(vendor, index);
    }

    @PostMapping("/coin")
    public boolean selectCoin(@RequestParam String coin) {
        if (!lhmBootstrapService.readyForAgent()) return false;
        return miningService.switchCoin(coin);
    }

    @PostMapping("/setPowerTarget")
    public boolean setPowerTarget(@RequestParam long powerTarget) {
        return lhmBootstrapService.readyForAgent() && miningService.setTarget(powerTarget);
    }

    @PostMapping("/increasePowerTarget")
    public boolean increasePowerTarget(@RequestParam long powerTarget) {
        return lhmBootstrapService.readyForAgent() && miningService.increasePowerTarget(powerTarget);
    }

    @PostMapping("/decreasePowerTarget")
    public boolean decreasePowerTarget(@RequestParam long powerTarget) {
        return lhmBootstrapService.readyForAgent() && miningService.decreasePowerTarget(powerTarget);
    }

    @PostMapping("/pause")
    public boolean pause() {
        return miningService.pauseAll("Local dashboard global pause request");
    }

    @PostMapping("/resume")
    public boolean resume() {
        return lhmBootstrapService.readyForAgent() && miningService.resumeAll();
    }

    @GetMapping
    public MinerStats getMiningStats() {
        return miningService.getExternalStats();
    }
}
