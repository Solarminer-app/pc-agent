package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    private final Path flagsFile;
    private final int apiPort;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, CachedPayout> cache = new ConcurrentHashMap<>();
    private final Set<String> defaultCoins = ConcurrentHashMap.newKeySet();

    public PayoutDefaultsService(ObjectMapper mapper, ProxyConfigurationService proxyConfigurationService,
                                 ReferralConfigurationService referralConfigurationService,
                                 @Value("${solarminer.agent.payout-file:./solarminer-agent/payout-defaults.json}") String flagsPath,
                                 @Value("${solarminer.agent.proxy.api-port:8090}") int apiPort) {
        this.mapper = mapper;
        this.proxyConfigurationService = proxyConfigurationService;
        this.referralConfigurationService = referralConfigurationService;
        this.flagsFile = Path.of(flagsPath).toAbsolutePath().normalize();
        this.apiPort = apiPort;
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
            return java.util.Set.of("ravencoin", "ethereumclassic", "decred", "quantus").contains(coin)
                    ? login.lastIndexOf('.') : login.lastIndexOf('/');
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
        try {
            String host = proxyConfigurationService.host();
            String referralQuery = referralConfigurationService.get().isBlank() ? "" : "?referral=" + referralConfigurationService.get();
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://" + host + ":" + apiPort + "/api/v1/fees/" + coin + "/targets" + referralQuery))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonNode targets = mapper.readTree(response.body());
            if (!targets.isArray()) return null;
            DefaultPayout first = null;
            for (JsonNode target : targets) {
                DefaultPayout candidate = toPayout(coin, target);
                if (candidate == null) continue;
                if (target.path("house").asBoolean(false)) return candidate;
                if (first == null) first = candidate;
            }
            // New GPU coins have no legacy fee targets: an unmarked referral target must
            // never become the operator's default payout destination.
            return java.util.Set.of("ravencoin", "ethereumclassic", "decred", "quantus").contains(coin) ? null : first;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.log(Level.FINE, "Fee-backend payout target could not be resolved for " + coin, e);
            return null;
        }
    }

    private DefaultPayout toPayout(String coin, JsonNode target) {
        String pool = target.path("poolAddress").asText("");
        String login = target.path("workerName").asText("");
        if (target.path("percentage").asDouble() <= 0 || login.isBlank() || !validPool(pool)) return null;
        return new DefaultPayout(coin, target.path("targetId").asText(""), pool, login,
                target.path("password").asText(""));
    }

    private static boolean validPool(String value) {
        try {
            URI uri = URI.create(value);
            return ("stratum+tcp".equals(uri.getScheme()) || "stratum+ssl".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getPort() > 0 && uri.getPort() <= 65535
                    && uri.getUserInfo() == null && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
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
            Files.createDirectories(flagsFile.getParent());
            Path temp = Files.createTempFile(flagsFile.getParent(), "payout-defaults-", ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter()
                        .writeValue(temp.toFile(), Map.of("defaultPayoutCoins", Set.copyOf(defaultCoins)));
                try {
                    Files.move(temp, flagsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, flagsFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Payout default selection could not be stored", e);
        }
    }

    /** Masked view of the fee-backend payout used by the operator-facing UI. */
    public record DefaultView(String coin, boolean available, String targetId, String poolUrl, String maskedWallet) { }

    public DefaultView view(String coin) {
        return resolve(coin)
                .map(p -> new DefaultView(p.coin(), true, p.targetId(), p.poolUrl(), p.maskedWallet()))
                .orElseGet(() -> new DefaultView(canonical(coin), false, null, null, null));
    }
}
