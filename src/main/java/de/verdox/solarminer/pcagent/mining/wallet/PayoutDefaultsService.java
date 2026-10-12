package de.verdox.solarminer.pcagent.mining.wallet;

import de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.mining.fee.FeeTierService;
import de.verdox.solarminer.pcagent.mining.fee.ReferralConfigurationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;

/**
 * The payout wallet and target used when the operator did not enter their own. They are
 * never stored locally as the source of truth: every lookup reads the fee-backend house
 * target through the proxy, so an empty form follows the central dev-fee configuration
 * for any coin instead of failing or keeping a stale copy.
 */
@Service
public class PayoutDefaultsService {
    private static final Logger LOGGER = Logger.getLogger(PayoutDefaultsService.class.getName());
    private static final long RESOLVED_TTL_MS = 60_000;
    private static final long MISSING_TTL_MS = 15_000;

    private final ObjectMapper mapper;
    private final ProxyConfigurationService proxyConfigurationService;
    private final ReferralConfigurationService referralConfigurationService;
    private final FeeTierService feeTiers;
    private final Path flagsFile;
    private final int apiPort;
    private final de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient feeClient;
    private final Map<String, CachedPayout> cache = new ConcurrentHashMap<>();
    private final Set<String> defaultCoins = ConcurrentHashMap.newKeySet();

    public PayoutDefaultsService(ObjectMapper mapper, ProxyConfigurationService proxyConfigurationService,
                                 ReferralConfigurationService referralConfigurationService,
                                 FeeTierService feeTiers,
                                 @Value("${solarminer.agent.payout-file:./solarminer-agent/payout-defaults.json}") String flagsPath,
                                 @Value("${solarminer.agent.proxy.api-port:8090}") int apiPort) {
        this.mapper = mapper;
        this.proxyConfigurationService = proxyConfigurationService;
        this.referralConfigurationService = referralConfigurationService;
        this.feeTiers = feeTiers;
        this.flagsFile = Path.of(flagsPath).toAbsolutePath().normalize();
        this.apiPort = apiPort;
        this.feeClient = new de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient(mapper);
        defaultCoins.addAll(readFlags());
    }

    /**
     * @param login the upstream pool login of the house target, used verbatim so the local
     *              miner's own route ends up exactly where the proxy routes its fee jobs
     */
    public record DefaultPayout(String coin, String targetId, String poolUrl, String login, String password) {

        /** Pearl rebuilds its upstream login as {@code <wallet>/<worker>}; the house target uses the same shape. */
        public String walletPart() {
            int separator = separator();
            return separator < 0 ? login : login.substring(0, separator);
        }

        public String workerPart() {
            int separator = separator();
            return separator < 0 ? null : login.substring(separator + 1);
        }

        private int separator() {
            Coin parsed = de.verdox.solarminer.pcagent.coin.Coin.byIdOrNull(coin);
            return parsed != null && parsed.isSharedSrbCoin() ? login.lastIndexOf('.') : login.lastIndexOf('/');
        }

        public String maskedWallet() {
            String wallet = walletPart();
            return wallet.length() <= 12 ? wallet : wallet.substring(0, 6) + "…" + wallet.substring(wallet.length() - 4);
        }
    }

    private record CachedPayout(DefaultPayout payout, long loadedAt) {
        boolean fresh(long now, boolean present) {
            return now - loadedAt < (present ? RESOLVED_TTL_MS : MISSING_TTL_MS);
        }
    }

    public Optional<DefaultPayout> resolve(String coin) {
        String key = canonical(coin);
        if (key == null || proxyConfigurationService.host() == null) return Optional.empty();
        CachedPayout cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.fresh(now, cached.payout() != null)) {
            return Optional.ofNullable(cached.payout());
        }
        DefaultPayout payout = fetch(key);
        cache.put(key, new CachedPayout(payout, now));
        return Optional.ofNullable(payout);
    }

    /**
     * Cache-only read for REST query paths (REST audit R3): never performs fee-backend I/O and
     * never triggers a fetch. The scheduled pre-warm below keeps the entries fresh; a coin with
     * no cached target simply reports as unavailable until the next background collection.
     */
    public Optional<DefaultPayout> resolveCached(String coin) {
        String key = canonical(coin);
        if (key == null) return Optional.empty();
        CachedPayout cached = cache.get(key);
        return cached == null ? Optional.empty() : Optional.ofNullable(cached.payout());
    }

    /**
     * Keeps every coin's house target warm in the cache so REST reads never hit the fee
     * backend (REST audit R3). The per-entry TTLs (60 s present / 15 s missing) decide which
     * coins are re-fetched per pass; the fetch itself stays on a virtual thread so a dead
     * proxy cannot block the shared scheduler.
     */
    @Scheduled(fixedDelayString = "${solarminer.agent.payout-defaults-refresh-ms:15000}")
    public void prewarmScheduled() {
        if (refreshing.get() || proxyConfigurationService.host() == null) return;
        Thread.startVirtualThread(this::prewarm);
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmOnStartup() {
        Thread.startVirtualThread(this::prewarm);
    }

    private final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();

    public void prewarm() {
        if (!refreshing.compareAndSet(false, true)) return;
        try {
            long now = System.currentTimeMillis();
            for (de.verdox.solarminer.pcagent.coin.Coin coin : de.verdox.solarminer.pcagent.coin.Coin.miningCoins()) {
                String key = coin.id();
                CachedPayout cached = cache.get(key);
                if (cached != null && cached.fresh(now, cached.payout() != null)) continue;
                DefaultPayout payout = fetch(key);
                cache.put(key, new CachedPayout(payout, System.currentTimeMillis()));
            }
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "Payout default pre-warm failed", failure);
        } finally {
            refreshing.set(false);
        }
    }

    public boolean available(String coin) {
        return resolve(coin).isPresent();
    }

    /** True while the operator chose the fee-backend payout for this coin instead of their own. */
    public boolean usesDefault(String coin) {
        String key = canonical(coin);
        return key != null && defaultCoins.contains(key);
    }

    public void markDefault(String coin, boolean useDefault) {
        String key = canonical(coin);
        if (key == null) return;
        if (useDefault ? defaultCoins.add(key) : defaultCoins.remove(key)) storeFlags();
    }

    /** Drops cached lookups so the next start reads the fee-backend again, e.g. after a proxy change. */
    public void invalidate() {
        cache.clear();
    }

    private DefaultPayout fetch(String coin) {
        List<de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient.FeeTarget> targets = feeClient.feeTargets(
                proxyConfigurationService.host(), apiPort, coin, feeTiers.effectiveTier(),
                referralConfigurationService.get(), Duration.ofSeconds(3));
        DefaultPayout first = null;
        for (var target : targets) {
            DefaultPayout candidate = toPayout(coin, target);
            if (candidate == null) continue;
            if (target.house()) return candidate;
            if (first == null) first = candidate;
        }
        // New GPU coins have no legacy fee targets: an unmarked referral target must
        // never become the operator's default payout destination.
        Coin parsed = de.verdox.solarminer.pcagent.coin.Coin.byIdOrNull(coin);
        return parsed != null && parsed.isSharedSrbCoin() ? null : first;
    }

    private DefaultPayout toPayout(String coin, de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient.FeeTarget target) {
        if (target.percentage() <= 0 || target.workerName().isBlank()
                || !de.verdox.solarminer.pcagent.mining.proxy.ProxyFeeClient.validPoolAddress(target.poolAddress())) return null;
        return new DefaultPayout(coin, target.targetId(), target.poolAddress(), target.workerName(), target.password());
    }

    private static String canonical(String coin) {
        String key = coin == null ? null : coin.trim().toLowerCase(Locale.ROOT);
        return key == null || key.isEmpty() || key.length() > 32 || !key.matches("^[a-z0-9_-]+$") ? null : key;
    }

    private Set<String> readFlags() {
        try {
            if (!Files.isRegularFile(flagsFile)) return Set.of();
            JsonNode coins = mapper.readTree(flagsFile.toFile()).path("defaultPayoutCoins");
            Set<String> stored = new LinkedHashSet<>();
            if (coins.isArray()) coins.forEach(node -> {
                String key = canonical(node.asText(""));
                if (key != null) stored.add(key);
            });
            return stored;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Payout default selection could not be read", e);
            return Set.of();
        }
    }

    private void storeFlags() {
        try {
            String pretty = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of("defaultPayoutCoins", Set.copyOf(defaultCoins)));
            de.verdox.solarminer.pcagent.support.AtomicFileStore.writeText(flagsFile, pretty, "payout-defaults-");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Payout default selection could not be stored", e);
        }
    }

    /** Masked view of the fee-backend payout used by the operator-facing UI. */
    public record DefaultView(String coin, boolean available, String targetId, String poolUrl, String maskedWallet) { }

    public DefaultView view(String coin) {
        return resolveCached(coin)
                .map(p -> new DefaultView(p.coin(), true, p.targetId(), p.poolUrl(), p.maskedWallet()))
                .orElseGet(() -> new DefaultView(canonical(coin), false, null, null, null));
    }
}
