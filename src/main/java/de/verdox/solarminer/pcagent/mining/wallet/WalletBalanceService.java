package de.verdox.solarminer.pcagent.mining.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates wallet reads through pool/chain adapters and normalizes unavailable states. */
@Service
public class WalletBalanceService {
    private static final Duration CACHE_TIME = Duration.ofMinutes(1);

    private final List<PoolBalanceProvider> providers;
    private final Map<String, CachedBalance> cache = new ConcurrentHashMap<>();

    public WalletBalanceService(List<PoolBalanceProvider> providers) {
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

        // Phase 4: on-chain reads are a provider capability, not a coin branch — a coin
        // with a chain balance plugs in as another PoolBalanceProvider (Pearl example).
        PoolBalanceProvider chainProvider = providers.stream()
                .filter(candidate -> candidate.supportsOnChain(target.coin()))
                .findFirst().orElse(null);
        BigDecimal onChainBalance = chainProvider == null ? null : cached(
                "chain:" + chainProvider.id() + ":" + target.coin() + ":" + target.wallet().trim(),
                () -> chainProvider.onChainBalance(target.coin(), target.wallet().trim()));
        String onChainStatus = chainProvider == null ? "NOT_SUPPORTED"
                : onChainBalance == null ? "UNAVAILABLE" : "AVAILABLE";
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

    /** Retained as a package-level parser seam for fixture-based response checks. */
    static BigDecimal parsePoolBalance(JsonNode json) {
        return KryptexPoolBalanceProvider.parsePoolBalance(json);
    }

    static BigDecimal parsePearlBalance(JsonNode json) {
        return PearlChainBalanceProvider.parsePearlBalance(json);
    }

    @FunctionalInterface private interface BalanceRead { BigDecimal get() throws Exception; }
    private record CachedBalance(BigDecimal amount, Instant expiresAt) { }
    public record Target(String coin, String poolUrl, String wallet) { }
    public record Balance(String coin, BigDecimal poolBalance, BigDecimal onChainBalance,
                          String poolStatus, String onChainStatus) { }
}
