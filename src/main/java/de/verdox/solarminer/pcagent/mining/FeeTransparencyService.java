package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.pearl.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.xmr.XmrConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    private final XmrConfigService xmr;
    private final PearlMinerService pearl;
    private final GpuCoinMinerService gpuCoins;
    private final int apiPort;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public FeeTransparencyService(ObjectMapper mapper, ProxyConfigurationService proxy,
                                  ReferralConfigurationService referral, FeeTierService feeTiers,
                                  XmrConfigService xmr,
                                  PearlMinerService pearl, GpuCoinMinerService gpuCoins,
                                  @Value("${solarminer.agent.proxy.api-port:8090}") int apiPort) {
        this.mapper = mapper; this.proxy = proxy; this.referral = referral; this.feeTiers = feeTiers; this.xmr = xmr; this.pearl = pearl;
        this.gpuCoins = gpuCoins; this.apiPort = apiPort;
    }

    public List<FeeOverview> overview() {
        // Four independent HTTP deadlines must not add up when the proxy is slow/unavailable.
        try (var lookups = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var results = de.verdox.solarminer.pcagent.coin.Coin.miningCoins().stream()
                    .map(coin -> java.util.concurrent.CompletableFuture.supplyAsync(() -> forCoin(coin.id()), lookups))
                    .toList();
            return results.stream().map(java.util.concurrent.CompletableFuture::join).toList();
        }
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
        try {
            if (proxy.host() != null) {
                StringBuilder query = new StringBuilder("?tier=").append(tier);
                if (!referral.get().isBlank()) query.append("&referral=").append(referral.get());
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + proxy.host() + ":" + apiPort
                                + "/api/v1/fees/" + de.verdox.solarminer.pcagent.coin.Coin.MONERO.id() + "/targets" + query))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonNode targets = mapper.readTree(response.body());
                    if (targets.isArray()) for (JsonNode target : targets) {
                        double percentage = target.path("percentage").asDouble();
                        if (percentage <= 0) continue;
                        boolean house = target.path("house").asBoolean(false);
                        parts.add(new DevFeePart(house ? "SOLARMINER" : "REFERRER", percentage));
                        resolved = true;
                    }
                }
            }
        } catch (Exception ignored) { }
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
        try {
            if (proxy.host() != null) {
                StringBuilder query = new StringBuilder("?tier=").append(feeTiers.effectiveTier());
                if (!referral.get().isBlank()) query.append("&referral=").append(referral.get());
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + proxy.host() + ":" + apiPort
                                + "/api/v1/fees/" + coin + "/targets" + query))
                        .timeout(Duration.ofSeconds(3)).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonNode targets = mapper.readTree(response.body());
                    if (targets.isArray()) for (JsonNode target : targets) {
                        double percentage = target.path("percentage").asDouble();
                        if (percentage <= 0) continue;
                        boolean house = target.path("house").asBoolean(false);
                        parts.add(new FeePart(house ? "SOLARMINER" : "REFERRER", house ? "SolarMiner" : "Referrer " + referral.get(),
                                percentage, true, "Vom SolarMiner Fee-Backend geladen"));
                        routeAvailable = true;
                    }
                }
            }
        } catch (Exception ignored) { }
        String pool = poolFor(coin);
        FeeReference poolFee = de.verdox.solarminer.pcagent.coin.Coin.byIdOrNull(coin) == de.verdox.solarminer.pcagent.coin.Coin.QUANTUS
                ? new FeeReference("Kryptex QTC Pool", 0, false,
                    "Pool fee varies between published guide and live page; verify before estimating")
                : poolFee(pool);
        parts.add(new FeePart("POOL", poolFee.label, poolFee.percentage, poolFee.known, poolFee.source));
        FeeReference minerFee = switch (de.verdox.solarminer.pcagent.coin.Coin.byIdOrNull(coin)) {
            case MONERO -> new FeeReference("XMRig Entwickler-Spende", 1.0, true, "https://xmrig.com/docs/miner/config/network");
            case RAVENCOIN -> new FeeReference("SRBMiner-MULTI KAWPOW Entwicklergebühr", 0.85, true,
                    "SRBMiner-MULTI 3.7.1 --list-algorithms");
            case ETHEREUMCLASSIC -> new FeeReference("SRBMiner-MULTI ETCHash Entwicklergebühr", 0.65, true,
                    "SRBMiner-MULTI 3.7.1 --list-algorithms");
            case DECRED -> new FeeReference("SRBMiner-MULTI BLAKE3-Decred Entwicklergebühr", 0, false,
                    "Gebühr für die gewählte SRBMiner-Version muss vor Start verifiziert werden");
            case QUANTUS -> new FeeReference("SRBMiner-MULTI QPoW Entwicklergebühr", 2.5, true,
                    "https://pool.kryptex.com/qtc");
            default -> new FeeReference("SRBMiner-MULTI Entwicklergebühr", 2.0, true, "https://github.com/doktor83/SRBMiner-Multi");
        };
        parts.add(new FeePart("MINER", minerFee.label, minerFee.percentage, minerFee.known, minerFee.source));
        return new FeeOverview(coin, referral.get(), feeTiers.effectiveTier(), routeAvailable, parts);
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
