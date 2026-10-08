package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates wallet reads through pool-specific adapters and normalizes unavailable states. */
@Service
public class WalletBalanceService {
    private static final Duration CACHE_TIME = Duration.ofMinutes(1);
    private static final BigDecimal GRAINS_PER_PEARL = new BigDecimal("100000000");

    private final ObjectMapper mapper;
    private final List<PoolBalanceProvider> providers;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, CachedBalance> cache = new ConcurrentHashMap<>();

    public WalletBalanceService(ObjectMapper mapper, List<PoolBalanceProvider> providers) {
        this.mapper = mapper;
        this.providers = List.copyOf(providers);
    }

    public List<Balance> balances(List<Target> targets) {
        List<CompletableFuture<Balance>> reads = targets.stream()
                .map(target -> CompletableFuture.supplyAsync(() -> balance(target))).toList();
        return reads.stream().map(CompletableFuture::join).toList();
    }

    private Balance balance(Target target) {
        if (target.wallet() == null || target.wallet().isBlank())
            return new Balance(target.coin(), null, null, "NOT_CONFIGURED", "NOT_CONFIGURED");

        PoolBalanceProvider provider = providers.stream()
                .filter(candidate -> candidate.supports(target.poolUrl(), target.coin()))
                .findFirst().orElse(null);
        BigDecimal poolBalance = provider == null ? null : cached(
                "pool:" + provider.id() + ":" + target.coin() + ":" + target.wallet().trim(),
                () -> provider.balance(target.coin(), target.poolUrl(), target.wallet().trim()));
        String poolStatus = provider == null ? "UNSUPPORTED_POOL"
                : poolBalance == null ? "UNAVAILABLE" : "AVAILABLE";

        BigDecimal onChainBalance = null;
        String onChainStatus = "NOT_SUPPORTED";
        if ("pearl".equals(target.coin())) {
            String wallet = target.wallet().trim();
            onChainBalance = cached("chain:pearl:" + wallet, () -> readPearlChainBalance(wallet));
            onChainStatus = onChainBalance == null ? "UNAVAILABLE" : "AVAILABLE";
        }
        return new Balance(target.coin(), poolBalance, onChainBalance, poolStatus, onChainStatus);
    }

    private BigDecimal cached(String key, BalanceRead read) {
        CachedBalance previous = cache.get(key);
        if (previous != null && Instant.now().isBefore(previous.expiresAt())) return previous.amount();
        try {
            BigDecimal amount = read.get();
            cache.put(key, new CachedBalance(amount, Instant.now().plus(CACHE_TIME)));
            return amount;
        } catch (Exception ignored) {
            // Never turn an unavailable upstream into a zero balance; retry transient failures soon.
            cache.put(key, new CachedBalance(null, Instant.now().plusSeconds(15)));
            return null;
        }
    }

    private BigDecimal readPearlChainBalance(String wallet) throws Exception {
        URI uri = URI.create("https://pearlchain.live/api/explorer/address/" + wallet);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
        return parsePearlBalance(mapper.readTree(response.body()));
    }

    /** Retained as a package-level parser seam for fixture-based response checks. */
    static BigDecimal parsePoolBalance(JsonNode json) {
        return KryptexPoolBalanceProvider.parsePoolBalance(json);
    }

    static BigDecimal parsePearlBalance(JsonNode json) {
        JsonNode grains = json.path("balance");
        if (!grains.isIntegralNumber() || grains.decimalValue().signum() < 0)
            throw new IllegalArgumentException("Missing Pearl balance");
        return grains.decimalValue().divide(GRAINS_PER_PEARL);
    }

    @FunctionalInterface private interface BalanceRead { BigDecimal get() throws Exception; }
    private record CachedBalance(BigDecimal amount, Instant expiresAt) { }
    public record Target(String coin, String poolUrl, String wallet) { }
    public record Balance(String coin, BigDecimal poolBalance, BigDecimal onChainBalance,
                          String poolStatus, String onChainStatus) { }
}
