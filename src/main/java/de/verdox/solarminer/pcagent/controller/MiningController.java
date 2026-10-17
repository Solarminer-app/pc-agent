package de.verdox.solarminer.pcagent.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.DownloadState;
import de.verdox.solarminer.pcagent.coin.FeeRollMode;
import de.verdox.solarminer.pcagent.coin.ProxyMode;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.dto.Pools;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.economics.MarketDataService;
import de.verdox.solarminer.pcagent.mining.fee.ReferralConfigurationService;
import de.verdox.solarminer.pcagent.mining.fee.FeeTransparencyService;
import de.verdox.solarminer.pcagent.mining.wallet.WalletBalanceService;
import de.verdox.solarminer.pcagent.mining.WindowsDefenderExclusionService;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.proxy.ManagedProxyService;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyDiscoveryService;
import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock;
import de.verdox.solarminer.pcagent.miner.CoinMiner;
import de.verdox.solarminer.pcagent.miner.CpuMiner;
import de.verdox.solarminer.pcagent.miner.GpuCoinMiner;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.srb.SrbDownloadService;
import de.verdox.solarminer.pcagent.xmr.XmrConfigService;
import de.verdox.solarminer.pcagent.xmr.XmrMinerService;
import de.verdox.solarminer.pcagent.xmr.download.XmrDownloadService;
import de.verdox.solarminer.pcagent.lowlevel.sensor.WindowsLhmBootstrapService;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.List;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.miner.GpuState;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;

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
    private final MarketDataService earningsForecastService;
    private final PayoutDefaultsService payoutDefaultsService;
    private final ReferralConfigurationService referralConfigurationService;
    private final FeeTransparencyService feeTransparencyService;
    private final WalletBalanceService walletBalanceService;
    private final WindowsDefenderExclusionService defenderExclusionService;
    private final MinerCatalogService minerCatalog;
    private final LocalRunLock localRuns;
    private final MinerFactory miners;
    /** REST audit R1: proxy reachability/fee-readiness arrives from the background health store. */
    private final de.verdox.solarminer.pcagent.mining.proxy.ProxyHealthService proxyHealth;
    /** Wallet/pool/network reads are refreshed off-thread; REST only returns this snapshot. */
    private volatile WalletsSnapshot walletSnapshotCache = new WalletsSnapshot(List.of(), List.of(), java.util.Map.of());
    /**
     * Loading plan P3: every local mutation reports the dashboard sections it changed so the
     * SSE publisher re-evaluates them on the next sweep (&lt; 1 s push) instead of waiting for
     * the section TTL. Injected lazily because this controller is itself a section producer.
     */
    private final AgentStatusService status;
    private de.verdox.solarminer.pcagent.mining.economics.WorkerCoinRevenueModeService workerCoinRevenueMode;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWorkerCoinRevenueMode(
            de.verdox.solarminer.pcagent.mining.economics.WorkerCoinRevenueModeService workerCoinRevenueMode) {
        this.workerCoinRevenueMode = workerCoinRevenueMode;
    }

    private void resetWorkerRevenueCooldown(String deviceId) {
        if (workerCoinRevenueMode != null) workerCoinRevenueMode.resetCooldown(deviceId);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MiningController(MiningService miningService, XmrConfigService xmrConfigService,
                            PearlMinerService pearlMinerService, GpuCoinMinerService gpuCoins, LocalGpuPowerService gpuPowerService,
                            XmrMinerService xmrMinerService, ProxyConfigurationService proxyConfigurationService,
                            SrbDownloadService srbDownloadService, XmrDownloadService xmrDownloadService,
                            WindowsLhmBootstrapService lhmBootstrapService,
                            ProxyDiscoveryService proxyDiscoveryService,
                            MarketDataService earningsForecastService,
                            PayoutDefaultsService payoutDefaultsService,
                            ReferralConfigurationService referralConfigurationService,
                            FeeTransparencyService feeTransparencyService,
                            WalletBalanceService walletBalanceService,
                            WindowsDefenderExclusionService defenderExclusionService,
                            MinerCatalogService minerCatalog,
                            LocalRunLock localRuns,
                            MinerFactory miners,
                            de.verdox.solarminer.pcagent.mining.proxy.ProxyHealthService proxyHealth,
                            @Lazy AgentStatusService status) {
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
        this.proxyHealth = proxyHealth;
        this.status = status;
    }

    /** Compatibility constructor for focused controller tests from before the P3 wiring. */
    MiningController(MiningService miningService, XmrConfigService xmrConfigService,
                     PearlMinerService pearlMinerService, GpuCoinMinerService gpuCoins, LocalGpuPowerService gpuPowerService,
                     XmrMinerService xmrMinerService, ProxyConfigurationService proxyConfigurationService,
                     SrbDownloadService srbDownloadService, XmrDownloadService xmrDownloadService,
                     WindowsLhmBootstrapService lhmBootstrapService,
                     ProxyDiscoveryService proxyDiscoveryService,
                     MarketDataService earningsForecastService,
                     PayoutDefaultsService payoutDefaultsService,
                     ReferralConfigurationService referralConfigurationService,
                     FeeTransparencyService feeTransparencyService,
                     WalletBalanceService walletBalanceService,
                     WindowsDefenderExclusionService defenderExclusionService,
                     MinerCatalogService minerCatalog,
                     LocalRunLock localRuns,
                     MinerFactory miners) {
        this(miningService, xmrConfigService, pearlMinerService, gpuCoins, gpuPowerService, xmrMinerService,
                proxyConfigurationService, srbDownloadService, xmrDownloadService, lhmBootstrapService,
                proxyDiscoveryService, earningsForecastService, payoutDefaultsService, referralConfigurationService,
                feeTransparencyService, walletBalanceService, defenderExclusionService, minerCatalog,
                localRuns, miners,
                new de.verdox.solarminer.pcagent.mining.proxy.ProxyHealthService(proxyConfigurationService), null);
    }

    /** Loading plan P3: report changed sections/channels so the SSE publisher wakes immediately. */
    private void reportChanged(String... names) {
        if (status != null) status.invalidate(names);
        for (String name : names) if ("wallets".equals(name)) {
            Thread.startVirtualThread(this::refreshWalletSnapshot);
            break;
        }
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmWalletSnapshot() {
        Thread.startVirtualThread(this::refreshWalletSnapshot);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${solarminer.agent.wallet-snapshot-refresh-ms:60000}")
    public void refreshWalletSnapshot() {
        List<WalletTarget> targets = walletTargets();
        List<WalletBalanceService.Balance> balances = walletBalanceService.balances(targets.stream()
                .map(target -> new WalletBalanceService.Target(target.coin(), target.poolUrl(), target.wallet()))
                .toList());
        walletSnapshotCache = new WalletsSnapshot(targets, balances, earningsForecastService.prices());
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
        boolean applied = miningService.useMonero();
        if (applied) reportChanged("overview", "wallets", "fee-tier");
        return applied;
    }

    @GetMapping("/proxy")
    public ProxyOverview proxy() {
        // REST audit R1: reachability and per-coin fee readiness come from the background
        // ProxyHealthService snapshot. An unreachable proxy delays the background refresh,
        // never this request: the dashboard always renders the last consistent observation.
        var health = proxyHealth.snapshot();
        List<ProxyOverview.CoinRoute> routes = Coin.miningCoins().stream()
                .map(coin -> new ProxyOverview.CoinRoute(coin.id(), coin.displayName(),
                        proxyConfigurationService.coinUrl(coin), proxyHealth.feeReady(coin.id()),
                        coin.experimental()))
                .toList();
        return new ProxyOverview(proxyConfigurationService.host(), health.reachable(),
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
            proxyHealth.invalidate();
            feeTransparencyService.invalidate();
            reportChanged("fee-tier", "overview", "wallets");
        }
        return updated;
    }

    @GetMapping("/fees")
    public List<FeeTransparencyService.FeeOverview> fees() { return feeTransparencyService.overviewSnapshot(); }

    /** Header badge data: effective dev-fee tier plus the live split (never hardcoded). */
    @GetMapping("/fee-tier")
    public FeeTransparencyService.DevFeeSummary feeTier() { return feeTransparencyService.devFeeSummarySnapshot(); }

    public record ReferralOverview(String key) { }

    @PostMapping("/proxy")
    public boolean configureProxy(@RequestParam String host) {
        if (host.equals(proxyConfigurationService.host())) return true;
        if (!proxyConfigurationService.configure(host)) return false;
        payoutDefaultsService.invalidate();
        proxyHealth.invalidate();
        feeTransparencyService.invalidate();
        boolean paused = miningService.pauseAll("Proxy was changed");
        reportChanged("overview", "proxy-gate", "fee-tier", "wallets");
        return paused;
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
        proxyHealth.invalidate();
        feeTransparencyService.invalidate();
        reportChanged("overview", "proxy-gate", "fee-tier", "wallets");
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
        boolean applied = proxyConfigurationService.setRollMode(mode);
        if (applied) reportChanged("overview", "proxy-gate", "fee-tier");
        return applied;
    }

    @PostMapping("/proxy/discover")
    public List<ProxyDiscoveryService.ProxyCandidate> discoverProxy() throws java.io.IOException {
        return proxyDiscoveryService.discover();
    }

    // Phase 5d (owner decision O3): the boot gate that keeps the dashboard closed until the
    // downloaded Stratum proxy is running merged from ProxyGateController into this single
    // proxy resource; the UI moved to these paths in the same step.

    @GetMapping("/proxy/gate")
    public ManagedProxyService.ProxyGate proxyGate() { return proxyConfigurationService.managedGate(); }

    @GetMapping("/proxy/gate/log")
    public ManagedProxyService.ProxyLogChunk proxyGateLog(@RequestParam(defaultValue = "0") long offset)
            throws java.io.IOException {
        return proxyConfigurationService.managedProxyLog(offset);
    }

    @PostMapping("/proxy/gate/retry")
    public boolean retryProxyGate() {
        boolean retried = proxyConfigurationService.retryManagedProxy();
        if (retried) reportChanged("proxy-gate", "overview");
        return retried;
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
                || proxyConfigurationService.coinUrl(Coin.MONERO) == null) return false;
        String login;
        if (request.wallet() == null || request.wallet().isBlank()) {
            // Without an own payout address the fee-backend decides pool and wallet together,
            // so the local route ends up exactly on the house target the proxy fees towards.
            PayoutDefaultsService.DefaultPayout payout = payoutDefaultsService.resolveCached(Coin.MONERO.id()).orElse(null);
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
                proxyConfigurationService.coinUrl(Coin.MONERO), login, false);
        reportChanged("overview", "wallets");
        return true;
    }

    public record MoneroConfiguration(String poolUrl, String wallet, String worker) { }

    /** Installs the selected miner binary for any known coin through the shared catalog. */
    @PostMapping("/{coin}/download")
    public boolean downloadCoin(@PathVariable String coin) {
        boolean started = miners.supports(coin) && minerCatalog.downloadSelected(coin);
        if (started) reportChanged("miner-options", "overview");
        return started;
    }

    @PostMapping("/{coin}/miners/{minerId}/download")
    public boolean downloadMiner(@PathVariable String coin, @PathVariable String minerId) {
        boolean started = minerCatalog.download(coin, minerId);
        if (started) reportChanged("miner-options", "overview");
        return started;
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
        MinerCatalogService.MinerOption selected = minerCatalog.selected(coin);
        return selected != null && removeCoinMiner(coin, selected.id());
    }

    /** Software-ID scoped removal: uninstalling an alternative never deletes the selected package by accident. */
    @PostMapping("/{coin}/miners/{minerId}/remove")
    public boolean removeCoinMiner(@PathVariable String coin, @PathVariable String minerId) {
        Coin parsed = Coin.byIdOrNull(coin);
        if (parsed == null) return false;
        boolean removed;
        if (parsed.isCpu() && "xmrig".equals(minerId)) {
            removed = xmrDownloadService.state() != DownloadState.DOWNLOADING
                    && miningService.pauseMining(parsed.id()) && xmrDownloadService.remove();
        } else if (parsed == Coin.QUANTUS && "quanpool-miner".equals(minerId)) {
            removed = minerCatalog.remove(parsed.id(), minerId);
        } else if (parsed.isGpu() && "srbminer-multi".equals(minerId)) {
            // Every GPU coin shares one SRBMiner installation; removing it stops all coin runs first.
            removed = srbDownloadService.state() != DownloadState.DOWNLOADING
                    && pearlMinerService.stop()
                    && Coin.sharedSrbCoins().stream().allMatch(coinId -> gpuCoins.stop(coinId.id()))
                    && srbDownloadService.remove();
        } else removed = false;
        if (removed) {
            minerCatalog.refreshInstallState();
            reportChanged("miner-options", "overview", "workers", "wallets");
        }
        return removed;
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
        reportChanged("overview", "wallets");
        return true;
    }

    private boolean setGpuCoinConfiguration(String coin, MinerConfig config) throws java.io.IOException {
        if (!GpuCoinMinerService.supported(coin)) throw new IllegalArgumentException("Unbekannter GPU-Coin");
        boolean feeBackendPayout = config == null || config.wallet() == null || config.wallet().isBlank();
        MinerConfig effective = feeBackendPayout ? withGpuCoinFeeBackendPayout(coin, config) : config;
        Coin parsed = Coin.byIdOrNull(coin);
        List<MinerFactory.GpuMinerAdapter> assignedAdapters = miners.gpuMinerAdapters(parsed).stream()
                .filter(adapter -> gpuPowerService.discover().stream()
                        .anyMatch(gpu -> miners.workerUses(parsed, gpu.deviceId(), adapter.softwareId())))
                .toList();
        if (assignedAdapters.isEmpty()) {
            miners.gpuMiner(coin).orElseThrow(() -> new IllegalArgumentException("Unbekannter GPU-Coin")).applyConfig(effective);
        } else {
            java.util.Map<MinerFactory.GpuMinerAdapter, MinerConfig> perAdapter = new java.util.LinkedHashMap<>();
            for (MinerFactory.GpuMinerAdapter adapter : assignedAdapters) {
                String devices = gpuPowerService.discover().stream()
                        .filter(gpu -> miners.workerUses(parsed, gpu.deviceId(), adapter.softwareId()))
                        .map(gpu -> gpu.vendor() + ":" + gpu.index()).collect(java.util.stream.Collectors.joining(","));
                MinerConfig tailored = new MinerConfig(effective.poolUrl(), effective.proxyUrl(), effective.wallet(),
                        effective.worker(), devices);
                adapter.miner().validateConfig(tailored);
                perAdapter.put(adapter, tailored);
            }
            for (var entry : perAdapter.entrySet()) entry.getKey().miner().applyConfig(entry.getValue());
        }
        payoutDefaultsService.markDefault(coin, feeBackendPayout);
        boolean switched = miningService.switchCoin(coin);
        reportChanged("overview", "wallets", "workers");
        return switched;
    }

    private MinerConfig withGpuCoinFeeBackendPayout(String coin, MinerConfig request) {
        if (request == null) throw new IllegalArgumentException("GPU-Coin-Konfiguration fehlt");
        PayoutDefaultsService.DefaultPayout payout = payoutDefaultsService.resolveCached(coin).orElseThrow(
                () -> new IllegalArgumentException("Kein SolarMiner-Standard-Auszahlungsziel für " + coin + " erreichbar"));
        String wallet = payout.walletPart();
        String worker = payout.workerPart();
        if (worker == null || worker.isBlank()) worker = "solarminer";
        return new MinerConfig(payout.poolUrl(), request.proxyUrl(), wallet, worker, request.devices());
    }

    @PostMapping("/{coin}/gpus/{vendor}/{index}/resume")
    public boolean resumeGpuCoin(@PathVariable String coin, @PathVariable String vendor, @PathVariable int index) {
        boolean resumed = localRunsFree() && lhmBootstrapService.readyForAgent()
                && workerGpuMiner(coin, vendor, index).map(miner -> miner.resumeGpu(vendor, index)).orElse(false);
        if (resumed) reportChanged("overview", "workers", "power-control");
        return resumed;
    }

    @PostMapping("/{coin}/gpus/{vendor}/{index}/pause")
    public boolean pauseGpuCoin(@PathVariable String coin, @PathVariable String vendor, @PathVariable int index) {
        boolean paused = localRunsFree() && workerGpuMiner(coin, vendor, index)
                .map(miner -> miner.pauseGpu(vendor, index)).orElse(false);
        if (paused) {
            gpuPowerService.discover().stream().filter(gpu -> gpu.vendor().equals(vendor) && gpu.index() == index)
                    .findFirst().ifPresent(gpu -> resetWorkerRevenueCooldown(gpu.deviceId()));
            reportChanged("overview", "workers", "power-control");
        }
        return paused;
    }

    private java.util.Optional<GpuCoinMiner> workerGpuMiner(String coin, String vendor, int index) {
        return gpuPowerService.discover().stream()
                .filter(gpu -> gpu.vendor().equals(vendor) && gpu.index() == index).findFirst()
                .flatMap(gpu -> miners.gpuMinerForWorker(coin, gpu.deviceId()));
    }

    /**
     * An empty wallet means the fee-backend payout is used. Its pool and worker always belong
     * together, so a half-entered own route is never mixed with the house wallet.
     */
    private MinerConfig withFeeBackendPayout(MinerConfig request) {
        if (request == null) throw new IllegalArgumentException("Pearl-Konfiguration fehlt");
        PayoutDefaultsService.DefaultPayout payout = payoutDefaultsService.resolveCached(Coin.PEARL.id()).orElseThrow(
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
        Coin parsed = Coin.byIdOrNull(coin);
        if (parsed == null || parsed == Coin.NONE) return false;
        MinerCatalogService.MinerOption current = minerCatalog.selected(coin);
        if (current != null && !current.id().equals(minerId) && !adapter(parsed).stopAll()) return false;
        boolean selected = minerCatalog.select(coin, minerId);
        if (selected) reportChanged("miner-options", "overview");
        return selected;
    }

    @GetMapping("/overview")
    public AgentOverview overview() {
        // REST audit R1/R2/R3/R4: every external read model (proxy health, fees, payout
        // defaults, market forecasts) is collected by its own background scheduler; this
        // request only composes in-memory state. Hardware discovery runs at startup and on
        // the background refresher, never on the browser request thread.
        List<LocalGpuPowerService.Gpu> gpus = gpuPowerService.snapshot();
        // A GET must be observational. Default-route provisioning may contact the fee backend
        // and write XMRig configuration; doing it here made merely opening the dashboard wait
        // behind setup work. The miner's start/readiness path still provisions when required.
        MinerStats stats = miningService.getStats(gpus);
        List<MarketDataService.Forecast> earnings = earningsForecastService.forecasts(stats.workers());
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
                System.getProperty("os.arch", "unknown"), coins, earnings, gpus, proxy(), savedMoneroConfiguration(),
                pearlMinerService.configuration(),
                new DownloadReadiness(xmrDownloadService.status(), xmrDownloadService.detail(), xmrDownloadService.progress(),
                        xmrMinerService.lastStartError(), xmrDownloadService.installDirectory().toString()),
                new PearlReadiness(pearlConfigured,
                        pearlMinerService.binaryAvailable(),
                        srbDownloadService.status(), srbDownloadService.detail(), srbDownloadService.progress(),
                        pearlMinerService.lastError(), pearlMinerService.connectionDetail(),
                        pearlMinerService.running(), pearlMinerService.poolHealthy(), pearlMinerService.gpuStates(gpus),
                        srbDownloadService.installDirectory().toString()),
                payoutDefaults(), new ReferralOverview(referralConfigurationService.get()),
                feeTransparencyService.overviewSnapshot(),
                java.util.Map.copyOf(gpuCoinViews));
    }

    /**
     * Overview-level status: the CPU row also reports externally started miners as mining
     * (its worker view), every other adapter reports its own process status.
     */
    private MinerStats.MinerStatus overviewStatus(CoinMiner miner) {
        return miner instanceof CpuMiner cpu ? cpu.getWorkerStats().miningStatus() : miner.status();
    }

    private GpuCoinOverview gpuOverview(String coin, List<LocalGpuPowerService.Gpu> gpus) {
        GpuCoinMiner miner = miners.gpuMiner(coin).orElse(null);
        return miner == null ? new GpuCoinOverview(null, null, false, List.of())
                : new GpuCoinOverview(miner.configuration(), miner.lastError(), miner.running(), miner.gpuStates(gpus));
    }

    /** Fee-backend payout per coin, shown so an empty wallet is never silently an unknown destination. */
    @GetMapping("/payout-defaults")
    public List<PayoutOverview> payoutDefaults() {
        // REST audit R3: pure cache reads (PayoutDefaultsService pre-warms on its scheduler);
        // no per-coin fee-backend HTTP and no fan-out on the request path anymore.
        return Coin.miningCoins().stream()
                .map(coin -> {
                    PayoutDefaultsService.DefaultView view = payoutDefaultsService.view(coin.id());
                    return new PayoutOverview(coin.id(), view.available(), view.targetId(), view.poolUrl(),
                            view.maskedWallet(), payoutDefaultsService.usesDefault(coin.id()));
                })
                .toList();
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
        return walletSnapshotCache;
    }

    @GetMapping("/wallet-balances")
    public List<WalletBalanceService.Balance> walletBalances() {
        return walletSnapshotCache.balances();
    }

    public record AgentOverview(MinerStats stats, String activeCoin, String platform, String architecture,
                                List<CoinOverview> coins,
                                List<MarketDataService.Forecast> earnings,
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
    public List<MarketDataService.Forecast> earnings() {
        return earningsForecastService.forecasts(miningService.getWorkerStats());
    }

    /**
     * Node-facing forecast with the fees the running Agent can actually account for. The
     * original gross fields stay intact for older consumers; the additive net fields are only
     * populated when the proxy fee route, pool fee and selected miner fee are all known.
     */
    public List<NodeEarningsForecast> nodeEarnings() {
        java.util.Map<String, FeeTransparencyService.FeeOverview> fees = feeTransparencyService.overviewSnapshot().stream()
                .collect(java.util.stream.Collectors.toMap(FeeTransparencyService.FeeOverview::coin,
                        value -> value, (left, right) -> left));
        return earnings().stream().map(forecast -> withKnownFees(forecast, fees.get(forecast.coin()))).toList();
    }

    static NodeEarningsForecast withKnownFees(MarketDataService.Forecast forecast,
                                               FeeTransparencyService.FeeOverview fees) {
        boolean complete = fees != null && fees.routeAvailable() && !fees.parts().isEmpty()
                && fees.parts().stream().allMatch(FeeTransparencyService.FeePart::known);
        double percentage = complete ? fees.parts().stream().mapToDouble(FeeTransparencyService.FeePart::percentage).sum() : 0;
        double factor = Math.max(0, 1 - percentage / 100.0);
        Double netCoins = complete ? forecast.coinsPerDay() * factor : null;
        Double netUsd = complete ? forecast.usdPerDay() * factor : null;
        return new NodeEarningsForecast(forecast.coin(), forecast.ticker(), forecast.available(),
                forecast.unavailableReason(), forecast.hashrateHps(), forecast.networkHashrateHps(),
                forecast.difficulty(), forecast.targetBlockSeconds(), forecast.blockReward(), forecast.priceUsd(),
                forecast.coinsPerDay(), forecast.usdPerDay(), forecast.updatedAt(), forecast.stale(),
                forecast.sources(), forecast.coinsPerDay(), forecast.usdPerDay(), netCoins, netUsd,
                complete ? percentage : null, complete);
    }

    public record NodeEarningsForecast(String coin, String ticker, boolean available, String unavailableReason,
                                       double hashrateHps, double networkHashrateHps, double difficulty,
                                       double targetBlockSeconds, double blockReward, double priceUsd,
                                       double coinsPerDay, double usdPerDay, java.time.Instant updatedAt,
                                       boolean stale, List<String> sources,
                                       double grossCoinsPerDay, double grossUsdPerDay,
                                       Double netCoinsPerDay, Double netUsdPerDay,
                                       Double knownFeePercent, boolean knownFeesComplete) { }

    @GetMapping("/market-prices")
    public java.util.Map<String, Double> marketPrices() {
        return walletSnapshotCache.prices();
    }

    @PostMapping("/miners/{coin}/resume")
    public boolean resumeMiner(@PathVariable String coin) {
        boolean resumed = localRunsFree() && miningService.resumeMining(coin);
        if (resumed) reportChanged("overview", "workers", "power-control");
        return resumed;
    }

    @PostMapping("/miners/{coin}/pause")
    public boolean pauseMiner(@PathVariable String coin) {
        boolean paused = localRunsFree() && miningService.pauseMining(coin);
        if (paused) {
            if (workerCoinRevenueMode != null) {
                workerCoinRevenueMode.resetCooldownForCoin(coin);
                workerCoinRevenueMode.cancelLocalStartsForCoin(coin);
            }
            reportChanged("overview", "workers", "power-control");
        }
        return paused;
    }

    @PostMapping("/miners/{coin}/power-target")
    public boolean setMinerPowerTarget(@PathVariable String coin, @RequestParam long powerTarget) {
        boolean applied = localRunsFree() && lhmBootstrapService.readyForAgent() && miningService.setTarget(coin, powerTarget);
        if (applied) reportChanged("overview", "power-control");
        return applied;
    }

    @PostMapping("/coin")
    public boolean selectCoin(@RequestParam String coin) {
        if (!localRunsFree() || !lhmBootstrapService.readyForAgent()) return false;
        boolean switched = miningService.switchCoin(coin);
        if (switched) reportChanged("overview", "workers");
        return switched;
    }

    @PostMapping("/setPowerTarget")
    public boolean setPowerTarget(@RequestParam long powerTarget) {
        boolean applied = localRunsFree() && lhmBootstrapService.readyForAgent() && miningService.setTarget(powerTarget);
        if (applied) reportChanged("overview", "power-control");
        return applied;
    }

    @PostMapping("/increasePowerTarget")
    public boolean increasePowerTarget(@RequestParam long powerTarget) {
        boolean applied = localRunsFree() && lhmBootstrapService.readyForAgent() && miningService.increasePowerTarget(powerTarget);
        if (applied) reportChanged("overview", "power-control");
        return applied;
    }

    @PostMapping("/decreasePowerTarget")
    public boolean decreasePowerTarget(@RequestParam long powerTarget) {
        boolean applied = localRunsFree() && lhmBootstrapService.readyForAgent() && miningService.decreasePowerTarget(powerTarget);
        if (applied) reportChanged("overview", "power-control");
        return applied;
    }

    @PostMapping("/pause")
    public boolean pause() {
        boolean paused = localRunsFree() && miningService.pauseAll("Local dashboard global pause request");
        if (paused) {
            if (workerCoinRevenueMode != null) {
                workerCoinRevenueMode.resetAllCooldowns();
                workerCoinRevenueMode.cancelAllLocalStarts();
            }
            reportChanged("overview", "workers", "power-control", "energy");
        }
        return paused;
    }

    @PostMapping("/resume")
    public boolean resume() {
        boolean resumed = localRunsFree() && lhmBootstrapService.readyForAgent() && miningService.resumeAll();
        if (resumed) reportChanged("overview", "workers", "power-control", "energy");
        return resumed;
    }

    @GetMapping
    public MinerStats getMiningStats() {
        return miningService.getExternalStats();
    }
}
