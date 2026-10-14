package de.verdox.solarminer.pcagent.mining.fee;

import de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.xmr.XmrConfigService;
import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;

/**
 * Operator-facing fee model. Dynamic SolarMiner/referral shares come from the proxy;
 * fixed entries document upstream fees and their source rather than silently estimating them.
 */
@Service
public class FeeTransparencyService {
    private final ObjectMapper mapper;
    private final ProxyConfigurationService proxy;
    private final ReferralConfigurationService referral;
    private final FeeTierService feeTiers;
    private final MinerCatalogService minerCatalog;
    private final AgentControlSettingsService controls;
    private final XmrConfigService xmr;
    private final PearlMinerService pearl;
    private final GpuCoinMinerService gpuCoins;
    private final int apiPort;
    private final de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient feeClient;

    @org.springframework.beans.factory.annotation.Autowired
    public FeeTransparencyService(ObjectMapper mapper, ProxyConfigurationService proxy,
                                  ReferralConfigurationService referral, FeeTierService feeTiers,
                                  MinerCatalogService minerCatalog,
                                  XmrConfigService xmr,
                                  PearlMinerService pearl, GpuCoinMinerService gpuCoins,
                                  AgentControlSettingsService controls,
                                  @Value("${solarminer.agent.proxy.api-port:8090}") int apiPort) {
        this.mapper = mapper; this.proxy = proxy; this.referral = referral; this.feeTiers = feeTiers;
        this.minerCatalog = minerCatalog; this.xmr = xmr; this.pearl = pearl;
        this.controls = controls;
        this.gpuCoins = gpuCoins; this.apiPort = apiPort;
        this.feeClient = new de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient(mapper);
    }

    /** Focused-test compatibility constructor. */
    public FeeTransparencyService(ObjectMapper mapper, ProxyConfigurationService proxy,
                                  ReferralConfigurationService referral, FeeTierService feeTiers,
                                  MinerCatalogService minerCatalog, XmrConfigService xmr,
                                  PearlMinerService pearl, GpuCoinMinerService gpuCoins, int apiPort) {
        this(mapper, proxy, referral, feeTiers, minerCatalog, xmr, pearl, gpuCoins, null, apiPort);
    }

    public List<FeeOverview> overview() {
        // Four independent HTTP deadlines must not add up when the proxy is slow/unreachable.
        try (var lookups = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var results = de.verdox.solarminer.pcagent.coin.Coin.miningCoins().stream()
                    .map(coin -> java.util.concurrent.CompletableFuture.supplyAsync(() -> forCoin(coin.id()), lookups))
                    .toList();
            return results.stream().map(java.util.concurrent.CompletableFuture::join).toList();
        }
    }

    // ------------------------------------------------------------- REST read model (audit R2)

    /**
     * Last completed fee collection. REST endpoints ({@code GET /local/fees}, the overview
     * aggregate and the SSE fee channels) read these snapshots; the fee-backend HTTP stays
     * exclusively in the scheduled collector below. An empty list means "not collected yet"
     * and renders as the same loading state the UI showed for an unreachable proxy.
     */
    private volatile List<FeeOverview> overviewSnapshot = List.of();
    private volatile DevFeeSummary devFeeSummarySnapshot;
    private volatile boolean feeRefreshQueued = true;

    public List<FeeOverview> overviewSnapshot() { return overviewSnapshot; }

    /**
     * Snapshot read for the header badge. Before the first collection completes this derives
     * the coin-independent tier/referral fields from in-memory settings and reports
     * {@code resolved=false}, which the UI already renders as "fee split loading".
     */
    public DevFeeSummary devFeeSummarySnapshot() {
        DevFeeSummary current = devFeeSummarySnapshot;
        if (current != null) return current;
        FeeTierService.Status tierStatus = feeTiers.status();
        return new DevFeeSummary(tierStatus.tier(), tierStatus.externalControlEnabled(),
                tierStatus.nodeRecentlyActive(), referral.get(), false, List.of());
    }

    /** Reports that the fee split may have changed (referral/proxy/mode mutation). */
    public void invalidate() { feeRefreshQueued = true; }

    /**
     * Background fee collection (audit R2): runs the live overview + dev-fee fetch on a
     * virtual thread so a slow proxy never blocks the shared scheduler, then publishes both
     * snapshots atomically per read model. The 5 s cadence matches the old fee-tier section
     * TTL; {@link #invalidate()} only marks the data dirty for diagnostics — the cadence
     * itself bounds how long a mutation takes to appear (same as before the audit).
     */
    @Scheduled(fixedDelayString = "${solarminer.agent.fee-snapshot-refresh-ms:5000}")
    public void refreshFeeSnapshots() {
        Thread.startVirtualThread(() -> {
            try {
                overviewSnapshot = List.copyOf(overview());
                devFeeSummarySnapshot = devFeeSummary();
                feeRefreshQueued = false;
            } catch (RuntimeException failure) {
                // Keep the previous snapshots; the next tick retries after the interval.
                java.util.logging.Logger.getLogger(FeeTransparencyService.class.getName())
                        .log(java.util.logging.Level.WARNING, "Fee snapshot refresh failed", failure);
                feeRefreshQueued = true;
            }
        });
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmFeeSnapshots() {
        Thread.startVirtualThread(() -> {
            overviewSnapshot = List.copyOf(overview());
            devFeeSummarySnapshot = devFeeSummary();
        });
    }

    /**
     * Compact dev-fee split for the header badge: the SolarMiner/referrer shares
     * for the effective tier, read live from the proxy (never hardcoded — a
     * referral code can change or even lower the total, and the proxy tier
     * scales the split). The fee split is coin-independent, so one configured
     * coin is enough to resolve the distribution.
     */
    public DevFeeSummary devFeeSummary() {
        String tier = feeTiers.effectiveTier();
        List<DevFeePart> parts = new ArrayList<>();
        boolean resolved = false;
        for (var target : feeClient.feeTargets(proxy.host(), apiPort,
                de.verdox.solarminer.pcagent.coin.Coin.MONERO.id(), tier, referral.get(), Duration.ofSeconds(2))) {
            double percentage = target.percentage();
            if (percentage <= 0) continue;
            parts.add(new DevFeePart(target.house() ? "SOLARMINER" : "REFERRER", percentage));
            resolved = true;
        }
        FeeTierService.Status tierStatus = feeTiers.status();
        return new DevFeeSummary(tier, tierStatus.externalControlEnabled(), tierStatus.nodeRecentlyActive(),
                referral.get(), resolved, List.copyOf(parts));
    }

    public record DevFeeSummary(String tier, boolean externalControlEnabled, boolean nodeRecentlyActive,
                                String referral, boolean resolved, List<DevFeePart> parts) { }
    public record DevFeePart(String kind, double percentage) { }

    private FeeOverview forCoin(String coin) {
        List<FeePart> parts = new ArrayList<>();
        boolean routeAvailable = false;
        for (var target : feeClient.feeTargets(proxy.host(), apiPort, coin,
                feeTiers.effectiveTier(), referral.get(), Duration.ofSeconds(3))) {
            double percentage = target.percentage();
            if (percentage <= 0) continue;
            parts.add(new FeePart(target.house() ? "SOLARMINER" : "REFERRER",
                    target.house() ? "SolarMiner" : "Referrer " + referral.get(),
                    percentage, true, "Vom SolarMiner Fee-Backend geladen"));
            routeAvailable = true;
        }
        String pool = poolFor(coin);
        FeeReference poolFee = de.verdox.solarminer.pcagent.coin.Coin.byIdOrNull(coin) == de.verdox.solarminer.pcagent.coin.Coin.QUANTUS
                ? new FeeReference("Kryptex QTC Pool", 0, false,
                    "Pool fee varies between published guide and live page; verify before estimating")
                : poolFee(pool);
        parts.add(new FeePart("POOL", poolFee.label, poolFee.percentage, poolFee.known, poolFee.source));
        MinerCatalogService.MinerOption selectedMiner = effectiveMinerOption(coin);
        boolean mixedMiners = selectedMiner == null && assignedMinerIds(coin).size() > 1;
        FeeReference minerFee = selectedMiner == null
                ? mixedMiners
                    ? new FeeReference("Miner fee differs by worker", 0, false,
                        "Worker use different mining software; a coin-wide net estimate would be misleading")
                    : new FeeReference("Miner-Katalogeintrag fehlt", 0, false,
                        "Kein Miner im Katalog ausgewählt; Entwicklergebühr kann nicht bestimmt werden")
                : new FeeReference(selectedMiner.developerFeeLabel(),
                    selectedMiner.developerFeePercent() == null ? 0 : selectedMiner.developerFeePercent(),
                    selectedMiner.developerFeePercent() != null, selectedMiner.developerFeeSource());
        parts.add(new FeePart("MINER", minerFee.label, minerFee.percentage, minerFee.known, minerFee.source));
        return new FeeOverview(coin, referral.get(), feeTiers.effectiveTier(), routeAvailable, parts);
    }

    private MinerCatalogService.MinerOption effectiveMinerOption(String coin) {
        java.util.Set<String> assigned = assignedMinerIds(coin);
        if (assigned.size() > 1) return null;
        String id = assigned.isEmpty() ? null : assigned.iterator().next();
        if (id == null) return minerCatalog.selected(coin);
        return minerCatalog.options(coin).stream().filter(option -> option.id().equals(id)).findFirst().orElse(null);
    }

    private java.util.Set<String> assignedMinerIds(String coin) {
        if (controls == null || controls.get().workerCoins() == null) return java.util.Set.of();
        MinerCatalogService.MinerOption fallback = minerCatalog.selected(coin);
        String fallbackId = fallback == null ? "unknown" : fallback.id();
        return controls.get().workerCoins().entrySet().stream()
                .filter(entry -> coin.equals(entry.getValue()))
                .filter(entry -> !de.verdox.solarminer.pcagent.coin.WorkerIds.WILDCARD.equals(entry.getKey()))
                .map(entry -> controls.get().minerFor(entry.getKey(), fallbackId))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String poolFor(String coin) {
        if (de.verdox.solarminer.pcagent.coin.Coin.PEARL.id().equals(coin)) return pearl.configuration() == null ? null : pearl.configuration().poolUrl();
        if (GpuCoinMinerService.supported(coin)) return gpuCoins.configuration(coin) == null
                ? null : gpuCoins.configuration(coin).poolUrl();
        try { return xmr.readUserPoolFromConfig().poolUsername().split(";", -1)[0]; } catch (Exception ignored) { return null; }
    }

    private static FeeReference poolFee(String pool) {
        String host;
        try { host = pool == null ? "" : URI.create(pool).getHost(); } catch (Exception ignored) { host = ""; }
        if (host != null && host.endsWith("kryptex.network")) return new FeeReference("Kryptex Pool", 1.0, true, "https://pool.kryptex.com");
        if (host != null && host.endsWith("nicehash.com")) return new FeeReference("NiceHash Pool", 2.0, true, "https://www.nicehash.com");
        return new FeeReference("Eigener/noch nicht katalogisierter Pool", 0, false, "Poolgebühr unbekannt – bitte beim Pool prüfen");
    }

    private record FeeReference(String label, double percentage, boolean known, String source) { }
    public record FeeOverview(String coin, String referral, String tier, boolean routeAvailable, List<FeePart> parts) { }
    public record FeePart(String kind, String label, double percentage, boolean known, String source) { }
}
