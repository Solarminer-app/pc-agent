package de.verdox.solarminer.pcagent.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.DownloadState;
import de.verdox.solarminer.pcagent.coin.FeeRollMode;
import de.verdox.solarminer.pcagent.coin.ProxyMode;
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
import de.verdox.solarminer.pcagent.mining.LocalRunLock;
import de.verdox.solarminer.pcagent.miner.CoinMiner;
import de.verdox.solarminer.pcagent.miner.CpuMiner;
import de.verdox.solarminer.pcagent.miner.GpuCoinMiner;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
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
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.miner.GpuState;

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
    private final LocalRunLock localRuns;
    private final MinerFactory miners;

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
                            MinerCatalogService minerCatalog,
                            LocalRunLock localRuns,
                            MinerFactory miners) {
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
        this.localRuns = localRuns;
        this.miners = miners;
    }

    /** The adapter for a mining coin, or null for unknown ids and the unassigned marker. */
    private CoinMiner adapter(Coin coin) { return coin == null || coin == Coin.NONE ? null : miners.miner(coin); }

    /**
     * A running benchmark or efficiency sweep owns the miners and the GPU power limits: it
     * snapshots the previous state to restore it afterwards, so a concurrent local control
     * would both corrupt the measurement and leave a worker running against the operator's
     * intent. Cancel the measurement run first.
     */
    private boolean localRunsFree() {
        return !localRuns.busy();
    }

    @GetMapping("identify")
    public boolean identify() {
        return true;
    }

    @PostMapping("/setPoolConfiguration")
    public boolean setPoolConfiguration(@RequestParam String poolUrl, @RequestParam String poolUser,
                                        @RequestParam double devFeePercentage) throws java.io.IOException {
        if (!lhmBootstrapService.readyForAgent()) return false;
        if (!proxyConfigurationService.matches(poolUrl, Coin.MONERO)) return false;
        xmrMinerService.hardStopMining();
        xmrConfigService.configureXmrig(XmrDownloadService.CONFIG_PATH, poolUrl, poolUser, false);
        return miningService.useMonero();
    }

    @GetMapping("/proxy")
    public ProxyOverview proxy() {
        List<ProxyOverview.CoinRoute> routes = Coin.miningCoins().stream()
                .map(coin -> new ProxyOverview.CoinRoute(coin.id(), coin.displayName(),
                        proxyConfigurationService.coinUrl(coin), proxyConfigurationService.feeReady(coin),
                        coin.experimental()))
                .toList();
        return new ProxyOverview(proxyConfigurationService.host(), proxyConfigurationService.isReachable(),
                proxyConfigurationService.standalone() ? "standalone" : "external",
                proxyConfigurationService.rollMode(),
                proxyConfigurationService.managedStatus(), proxyConfigurationService.managedDetail(),
                proxyConfigurationService.managedVersion(), routes);
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

    /** Header badge data: effective dev-fee tier plus the live split (never hardcoded). */
    @GetMapping("/fee-tier")
    public FeeTransparencyService.DevFeeSummary feeTier() { return feeTransparencyService.devFeeSummary(); }

    public record ReferralOverview(String key) { }

    @PostMapping("/proxy")
    public boolean configureProxy(@RequestParam String host) {
        if (host.equals(proxyConfigurationService.host())) return true;
        if (!proxyConfigurationService.configure(host)) return false;
        payoutDefaultsService.invalidate();
        return miningService.pauseAll("Proxy was changed");
    }

    @PostMapping("/proxy/mode")
    public boolean configureProxyMode(@RequestParam String mode) {
        if (ProxyMode.from(mode) == null) return false;
        if (!miningService.pauseAll("Proxy mode was configured")) return false;
        if (!proxyConfigurationService.setMode(mode)) return false;
        try {
            migrateMinerProxyRoutes();
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Proxy wurde gewählt, aber Miner-Routen konnten nicht aktualisiert werden: " + e.getMessage(), e);
        }
        payoutDefaultsService.invalidate();
        return true;
    }

    /** Existing miner files retain their endpoint; keep them aligned with the selected shared proxy. */
    private void migrateMinerProxyRoutes() throws java.io.IOException {
        for (Coin coin : Coin.miningCoins()) {
            String proxyUrl = proxyConfigurationService.coinUrl(coin);
            if (proxyUrl == null) continue;
            CoinMiner miner = adapter(coin);
            if (miner != null) miner.updateProxyRoute(proxyUrl);
        }
    }

    /**
     * Chooses how the proxy rolls fee targets per job: "random" (stateless independent rolls)
     * or "stateful" (a persistent per-coin urn that keeps the realised share on target).
     * The managed proxy restarts with the new flag, so miners are paused first.
     */
    @PostMapping("/proxy/roll-mode")
    public boolean configureProxyRollMode(@RequestParam String mode) {
        if (FeeRollMode.from(mode) == null) return false;
        if (!miningService.pauseAll("Fee roll mode was configured")) return false;
        return proxyConfigurationService.setRollMode(mode);
    }

    @PostMapping("/proxy/discover")
    public List<ProxyDiscoveryService.ProxyCandidate> discoverProxy() throws java.io.IOException {
        return proxyDiscoveryService.discover();
    }

    /**
     * Proxy connection state plus one entry per mineable coin. The coin set comes from the
     * {@link Coin} enum; nothing here enumerates coins. {@link #coinWireFields()} keeps the
     * historical flat wire fields ({@code <coinId>Url}, {@code <coinId>FeeReady}) that the
     * SolarMiner-Node and the operator UI still read, so the JSON stays backward compatible.
     */
    public record ProxyOverview(String host, boolean reachable, String mode, String feeRollMode,
                                String managedStatus, String managedDetail, String managedVersion,
                                List<CoinRoute> coinRoutes) {
        /** The proxy's stratum route and fee readiness for one coin. */
        public record CoinRoute(String coin, String name, String url, boolean feeReady, boolean experimental) { }

        @com.fasterxml.jackson.annotation.JsonAnyGetter
        public java.util.Map<String, Object> coinWireFields() {
            java.util.Map<String, Object> wire = new java.util.LinkedHashMap<>();
            for (CoinRoute route : coinRoutes) {
                wire.put(route.coin() + "Url", route.url());
                wire.put(route.coin() + "FeeReady", route.feeReady());
            }
            return wire;
        }
    }

    /**
     * Saves the payout route for any coin. The route shape is the coin-independent
     * {@link MinerConfig}; the owning adapter validates wallet and device syntax. CPU and
     * Pearl keep their dedicated flows because their config files differ from the SRB shape.
     */
    @PostMapping("/{coin}/configuration")
    public boolean setCoinConfiguration(@PathVariable String coin, @RequestBody MinerConfig config) throws java.io.IOException {
        if (!localRunsFree()) throw new IllegalArgumentException("Ein Messlauf (Benchmark oder Effizienz-Sweep) läuft gerade; breche ihn ab, bevor du die Miner-Konfiguration änderst");
        Coin parsed = Coin.byIdOrNull(coin);
        if (parsed == null || parsed == Coin.NONE) throw new IllegalArgumentException("Unbekannter Coin");
        if (parsed.isCpu())
            return setMoneroConfiguration(new MoneroConfiguration(config == null ? null : config.poolUrl(),
                    config == null ? null : config.wallet(), config == null ? null : config.worker()));
        if (parsed == Coin.PEARL) return setPearlConfiguration(config);
        return setGpuCoinConfiguration(parsed.id(), config);
    }

    /** CPU route save; kept as a plain method for the external Node compatibility endpoint. */
    public boolean setMoneroConfiguration(MoneroConfiguration request) throws java.io.IOException {
        if (request == null || request.worker() == null
                || !request.worker().matches("^[A-Za-z0-9_-]{1,32}$")
                || proxyConfigurationService.moneroUrl() == null) return false;
        String login;
        if (request.wallet() == null || request.wallet().isBlank()) {
            // Without an own payout address the fee-backend decides pool and wallet together,
            // so the local route ends up exactly on the house target the proxy fees towards.
            PayoutDefaultsService.DefaultPayout payout = payoutDefaultsService.resolve(Coin.MONERO.id()).orElse(null);
            if (payout == null) return false;
            login = payout.poolUrl() + ";" + payout.login() + ";x";
            payoutDefaultsService.markDefault(Coin.MONERO.id(), true);
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
            payoutDefaultsService.markDefault(Coin.MONERO.id(), false);
        }
        xmrMinerService.hardStopMining();
        xmrConfigService.configureXmrig(XmrDownloadService.CONFIG_PATH,
                proxyConfigurationService.moneroUrl(), login, false);
        return true;
    }

    public record MoneroConfiguration(String poolUrl, String wallet, String worker) { }

    /** Installs the selected miner binary for any known coin through the shared catalog. */
    @PostMapping("/{coin}/download")
    public boolean downloadCoin(@PathVariable String coin) {
        return miners.supports(coin) && minerCatalog.downloadSelected(coin);
    }

    @PostMapping("/{coin}/miners/{minerId}/download")
    public boolean downloadMiner(@PathVariable String coin, @PathVariable String minerId) {
        return minerCatalog.download(coin, minerId);
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
            Coin parsedCoin = Coin.byIdOrNull(coin);
            java.nio.file.Path directory = switch (parsedCoin) {
                case MONERO -> xmrDownloadService.installDirectory();
                case PEARL -> srbDownloadService.installDirectory();
                default -> null;
            };
            if (directory == null) return ResponseEntity.status(404)
                    .body(new DefenderExclusionResult(false, "Unbekannter Miner."));
            String downloadStatus = parsedCoin.isCpu()
                    ? xmrDownloadService.status() : srbDownloadService.status();
            if (!DownloadState.BLOCKED_BY_ANTIVIRUS.wireName().equals(downloadStatus))
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

    /** Removes the binary backing a coin; the owning adapter decides what "installed" means. */
    @PostMapping("/{coin}/remove")
    public boolean removeCoinMiner(@PathVariable String coin) {
        Coin parsed = Coin.byIdOrNull(coin);
        if (parsed == null) return false;
        if (parsed.isCpu()) {
            if (xmrDownloadService.state() == DownloadState.DOWNLOADING || !miningService.pauseMining(parsed.id())) return false;
            return xmrDownloadService.remove();
        }
        if (parsed.isGpu()) {
            // Every GPU coin shares one SRBMiner installation; removing it stops all coin runs first.
            if (srbDownloadService.state() == DownloadState.DOWNLOADING || !pearlMinerService.stop()) return false;
            for (Coin coinId : Coin.sharedSrbCoins()) if (!gpuCoins.stop(coinId.id())) return false;
            return srbDownloadService.remove();
        }
        return false;
    }

    /** Pearl route save; kept as a plain method for the external Node compatibility endpoint. */
    public boolean setPearlConfiguration(MinerConfig configuration) throws java.io.IOException {
        if (!localRunsFree()) throw new IllegalArgumentException("Ein Messlauf (Benchmark oder Effizienz-Sweep) läuft gerade; breche ihn ab, bevor du die Miner-Konfiguration änderst");
        boolean feeBackendPayout = configuration == null
                || configuration.wallet() == null || configuration.wallet().isBlank();
        MinerConfig effective = feeBackendPayout
                ? withFeeBackendPayout(configuration) : configuration;
        PearlMinerService.validate(effective);
        if (!proxyConfigurationService.matches(effective.proxyUrl(), Coin.PEARL))
            throw new IllegalArgumentException("SolarMiner-Proxy für Pearl fehlt oder stimmt nicht mit der gespeicherten Verbindung überein");
        pearlMinerService.configure(effective);
        payoutDefaultsService.markDefault(Coin.PEARL.id(), feeBackendPayout);
        return true;
    }

    private boolean setGpuCoinConfiguration(String coin, MinerConfig config) throws java.io.IOException {
        if (!GpuCoinMinerService.supported(coin)) throw new IllegalArgumentException("Unbekannter GPU-Coin");
        boolean feeBackendPayout = config == null || config.wallet() == null || config.wallet().isBlank();
        MinerConfig effective = feeBackendPayout ? withGpuCoinFeeBackendPayout(coin, config) : config;
        gpuCoins.configure(coin, effective);
        payoutDefaultsService.markDefault(coin, feeBackendPayout);
        return miningService.switchCoin(coin);
    }

    private MinerConfig withGpuCoinFeeBackendPayout(String coin, MinerConfig request) {
        if (request == null) throw new IllegalArgumentException("GPU-Coin-Konfiguration fehlt");
        PayoutDefaultsService.DefaultPayout payout = payoutDefaultsService.resolve(coin).orElseThrow(
                () -> new IllegalArgumentException("Kein SolarMiner-Standard-Auszahlungsziel für " + coin + " erreichbar"));
        String wallet = payout.walletPart();
        String worker = payout.workerPart();
        if (worker == null || worker.isBlank()) worker = "solarminer";
        return new MinerConfig(payout.poolUrl(), request.proxyUrl(), wallet, worker, request.devices());
    }

    @PostMapping("/{coin}/gpus/{vendor}/{index}/resume")
    public boolean resumeGpuCoin(@PathVariable String coin, @PathVariable String vendor, @PathVariable int index) {
        return localRunsFree() && lhmBootstrapService.readyForAgent()
                && miners.gpuMiner(coin).map(miner -> miner.resumeGpu(vendor, index)).orElse(false);
    }

    @PostMapping("/{coin}/gpus/{vendor}/{index}/pause")
    public boolean pauseGpuCoin(@PathVariable String coin, @PathVariable String vendor, @PathVariable int index) {
        return localRunsFree() && miners.gpuMiner(coin).map(miner -> miner.pauseGpu(vendor, index)).orElse(false);
    }

    /**
     * An empty wallet means the fee-backend payout is used. Its pool and worker always belong
     * together, so a half-entered own route is never mixed with the house wallet.
     */
    private MinerConfig withFeeBackendPayout(MinerConfig request) {
        if (request == null) throw new IllegalArgumentException("Pearl-Konfiguration fehlt");
        PayoutDefaultsService.DefaultPayout payout = payoutDefaultsService.resolve(Coin.PEARL.id()).orElseThrow(
                () -> new IllegalArgumentException("Kein SolarMiner-Standard-Auszahlungsziel für Pearl erreichbar"));
        String worker = payout.workerPart() != null ? payout.workerPart()
                : request.worker() == null || request.worker().isBlank() ? "solarminer" : request.worker();
        return new MinerConfig(payout.poolUrl(), request.proxyUrl(), payout.walletPart(),
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
        return Coin.miningCoins().stream().map(this::installation).toList();
    }

    private MinerInstallation installation(Coin coin) {
        MinerCatalogService.MinerOption selected = minerCatalog.selected(coin.id());
        return new MinerInstallation(coin.id(), coin.displayName(), selected.device(), selected.algorithm(), selected.installed(),
                selected.experimental(), selected.id(), minerCatalog.options(coin.id()));
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
            boolean pearlConfigured = adapter(Coin.PEARL).routeConfigured();
            List<CoinOverview> coins = Coin.miningCoins().stream().map(coin -> {
                CoinMiner miner = adapter(coin);
                return new CoinOverview(coin.id(), coin.displayName(), coin.ticker(), coin.deviceLabel(),
                        coin.displayAlgorithm(), overviewStatus(miner), miner.setupComplete(),
                        minerCatalog.selectedIsInstalled(coin.id()), coin.experimental(),
                        minerCatalog.selected(coin.id()), minerCatalog.options(coin.id()));
            }).toList();
            java.util.Map<String, GpuCoinOverview> gpuCoinViews = new java.util.LinkedHashMap<>();
            for (Coin coin : Coin.sharedSrbCoins()) gpuCoinViews.put(coin.id(), gpuOverview(coin.id(), gpus));
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
                    java.util.Map.copyOf(gpuCoinViews));
        }
    }

    /**
     * Overview-level status: the CPU row also reports externally started miners as mining
     * (its worker view), every other adapter reports its own process status.
     */
    private MinerStats.MinerStatus overviewStatus(CoinMiner miner) {
        return miner instanceof CpuMiner cpu ? cpu.getWorkerStats().miningStatus() : miner.status();
    }

    private GpuCoinOverview gpuOverview(String coin, List<LocalGpuPowerService.Gpu> gpus) {
        return new GpuCoinOverview(gpuCoins.configuration(coin), gpuCoins.lastError(),
                gpuCoins.running(coin), gpuCoins.gpuStates(coin, gpus));
    }

    /** Fee-backend payout per coin, shown so an empty wallet is never silently an unknown destination. */
    @GetMapping("/payout-defaults")
    public List<PayoutOverview> payoutDefaults() {
        return Coin.miningCoins().stream().map(coin -> {
            PayoutDefaultsService.DefaultView view = payoutDefaultsService.view(coin.id());
            return new PayoutOverview(coin.id(), view.available(), view.targetId(), view.poolUrl(),
                    view.maskedWallet(), payoutDefaultsService.usesDefault(coin.id()));
        }).toList();
    }

    public record PayoutOverview(String coin, boolean available, String targetId, String poolUrl,
                                 String maskedWallet, boolean inUse) { }

    /** The CPU adapter parses its own login format; the controller only re-wraps the route. */
    private MoneroConfiguration savedMoneroConfiguration() {
        MinerConfig route = miners.cpuMiner(Coin.MONERO.id()).map(CpuMiner::savedRoute).orElse(null);
        return route == null ? null : new MoneroConfiguration(route.poolUrl(), route.wallet(), route.worker());
    }

    /**
     * The configured payout targets per coin — the light slice the wallet strip needs.
     * The strip must not pull the full overview (driver discovery, fees, forecasts) just
     * to learn which wallets exist.
     */
    @GetMapping("/wallet-targets")
    public List<WalletTarget> walletTargets() {
        List<WalletTarget> targets = new java.util.ArrayList<>();
        for (Coin coin : Coin.miningCoins()) {
            MinerConfig config = savedRouteOf(coin);
            if (config != null && config.wallet() != null && !config.wallet().isBlank())
                targets.add(new WalletTarget(coin.id(), coin.displayName(), coin.ticker(), config.wallet(), config.poolUrl()));
        }
        return List.copyOf(targets);
    }

    /** The coin-independent saved payout route as reported by the coin's adapter. */
    private MinerConfig savedRouteOf(Coin coin) {
        CoinMiner miner = adapter(coin);
        if (miner instanceof CpuMiner cpu) return cpu.savedRoute();
        if (miner instanceof GpuCoinMiner gpu) return gpu.configuration();
        return null;
    }

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
                                MoneroConfiguration moneroConfiguration, MinerConfig pearlConfiguration,
                                DownloadReadiness monero,
                                PearlReadiness pearl,
                                List<PayoutOverview> payoutDefaults,
                                ReferralOverview referral,
                                List<FeeTransparencyService.FeeOverview> fees,
                                java.util.Map<String, GpuCoinOverview> gpuCoins) { }

    public record GpuCoinOverview(MinerConfig configuration, String minerError,
                                  boolean running, List<GpuState> gpus) { }

    public record DownloadReadiness(String downloadStatus, String downloadDetail, int downloadProgress,
                                    String minerError, String installDirectory) { }

    public record CoinOverview(String id, String name, String ticker, String device, String algorithm,
                               MinerStats.MinerStatus status, boolean configured, boolean binaryAvailable,
                               boolean experimental, MinerCatalogService.MinerOption selectedMiner,
                               List<MinerCatalogService.MinerOption> miners) { }

    public record PearlReadiness(boolean configured, boolean binaryAvailable,
                                 String downloadStatus, String downloadDetail, int downloadProgress,
                                 String minerError, String connectionDetail, boolean running,
                                 boolean poolHealthy, List<GpuState> gpus,
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
        return localRunsFree() && miningService.resumeMining(coin);
    }

    @PostMapping("/miners/{coin}/pause")
    public boolean pauseMiner(@PathVariable String coin) {
        return localRunsFree() && miningService.pauseMining(coin);
    }

    @PostMapping("/miners/{coin}/power-target")
    public boolean setMinerPowerTarget(@PathVariable String coin, @RequestParam long powerTarget) {
        return localRunsFree() && lhmBootstrapService.readyForAgent() && miningService.setTarget(coin, powerTarget);
    }

    @PostMapping("/coin")
    public boolean selectCoin(@RequestParam String coin) {
        if (!localRunsFree() || !lhmBootstrapService.readyForAgent()) return false;
        return miningService.switchCoin(coin);
    }

    @PostMapping("/setPowerTarget")
    public boolean setPowerTarget(@RequestParam long powerTarget) {
        return localRunsFree() && lhmBootstrapService.readyForAgent() && miningService.setTarget(powerTarget);
    }

    @PostMapping("/increasePowerTarget")
    public boolean increasePowerTarget(@RequestParam long powerTarget) {
        return localRunsFree() && lhmBootstrapService.readyForAgent() && miningService.increasePowerTarget(powerTarget);
    }

    @PostMapping("/decreasePowerTarget")
    public boolean decreasePowerTarget(@RequestParam long powerTarget) {
        return localRunsFree() && lhmBootstrapService.readyForAgent() && miningService.decreasePowerTarget(powerTarget);
    }

    @PostMapping("/pause")
    public boolean pause() {
        return localRunsFree() && miningService.pauseAll("Local dashboard global pause request");
    }

    @PostMapping("/resume")
    public boolean resume() {
        return localRunsFree() && lhmBootstrapService.readyForAgent() && miningService.resumeAll();
    }

    @GetMapping
    public MinerStats getMiningStats() {
        return miningService.getExternalStats();
    }
}
