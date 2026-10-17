package de.verdox.solarminer.pcagent.mining.economics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single market-data seam for everything that reads SolarMiner's central currency service
 * (currency.solarminer.app): cached USD fiat exchange rates for the local UI, coin quotes,
 * and gross probability-based daily mining estimates. Formerly two services
 * (FiatRateService + EarningsForecastService) duplicating the same upstream and the same
 * TTL-cache/stale-fallback pattern. Pool payout schemes, stale shares and fees are not
 * deducted from forecasts.
 *
 * <p>Wire contract: the flat field names of {@link Forecast} and {@link Snapshot} are read
 * by the operator UI and by Solar-Miner-Node; they may only change additively.</p>
 */
@Service
public class MarketDataService {
    private static final Logger LOGGER = Logger.getLogger(MarketDataService.class.getName());
    private static final Duration FIAT_CACHE_TIME = Duration.ofHours(1);
    private static final Duration MARKET_CACHE_TIME = Duration.ofMinutes(10);
    private static final Set<String> SUPPORTED_FIAT = Set.of("EUR", "USD", "CHF");
    private static final double SECONDS_PER_DAY = 86_400.0;
    /** The forecast set derives from the closed coin enum; no separate coin table lives here. */
    private static final List<CoinDefinition> COINS = de.verdox.solarminer.pcagent.coin.Coin.miningCoins().stream()
            .map(coin -> new CoinDefinition(coin.id(), coin.ticker(), coin.algorithm()))
            .toList();

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final URI fiatRatesUrl;
    private final URI miningNetworksUrl;
    private final URI coinPricesUrl;

    // Fiat-rate cache (1 h TTL, stale fallback with 5 min retry).
    private volatile Snapshot fiatCached = new Snapshot("USD", Map.of("USD", 1.0), null, true);
    private volatile Instant fiatNextRefresh = Instant.EPOCH;

    // Market cache (10 min TTL; network snapshots refresh on a virtual thread).
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Map<String, NetworkSnapshot> snapshots = new ConcurrentHashMap<>();
    private volatile Instant nextRefreshAt = Instant.EPOCH;
    private volatile Instant nextPriceRefreshAt = Instant.EPOCH;
    private volatile Instant pricesUpdatedAt = Instant.EPOCH;
    private volatile Map<String, Double> marketPrices = Map.of();

    @Autowired
    public MarketDataService(
            ObjectMapper mapper,
            @Value("${solarminer.fiat-rates-url:https://currency.solarminer.app/api/v1/public/exchange-rates}") URI fiatRatesUrl,
            @Value("${solarminer.earnings.mining-networks-url:https://currency.solarminer.app/api/v1/public/mining-networks}") URI miningNetworksUrl,
            @Value("${solarminer.earnings.coin-prices-url:https://currency.solarminer.app/api/v1/public/coin-prices}") URI coinPricesUrl) {
        this(mapper, fiatRatesUrl, miningNetworksUrl, coinPricesUrl,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
    }

    MarketDataService(ObjectMapper mapper, URI fiatRatesUrl, URI miningNetworksUrl, URI coinPricesUrl, HttpClient httpClient) {
        this.mapper = mapper;
        this.fiatRatesUrl = fiatRatesUrl;
        this.miningNetworksUrl = miningNetworksUrl;
        this.coinPricesUrl = coinPricesUrl;
        this.httpClient = httpClient;
    }

    /** Kept for unit fixtures that derive the coin-prices route from the mining-networks route. */
    MarketDataService(ObjectMapper mapper, URI miningNetworksUrl, HttpClient httpClient) {
        this(mapper, URI.create("http://127.0.0.1/api/v1/public/exchange-rates"), miningNetworksUrl,
                URI.create(miningNetworksUrl.toString().replace("/mining-networks", "/coin-prices")), httpClient);
    }

    // ---------------------------------------------------------------- fiat rates

    /** Returns the last completed fiat snapshot; dashboard reads never call the upstream. */
    public Snapshot rates() { return fiatCached; }

    /** Converts a stored fiat amount to USD using today's completed cached exchange snapshot. */
    public Double toUsd(double amount, String currency) {
        if (!Double.isFinite(amount) || currency == null) return null;
        String code = currency.toUpperCase(java.util.Locale.ROOT);
        if ("USD".equals(code)) return amount;
        Snapshot snapshot = fiatCached;
        if (snapshot.stale() || !LocalDate.now(ZoneOffset.UTC).equals(snapshot.dataUtcDate())) return null;
        Double rate = snapshot.rates().get(code);
        return rate != null && Double.isFinite(rate) && rate > 0 ? amount / rate : null;
    }

    /** Warms every market cache away from the first browser request (REST audit R4). */
    @EventListener(ApplicationReadyEvent.class)
    public void warmCaches() {
        Thread.startVirtualThread(this::refreshRatesIfNeeded);
        Thread.startVirtualThread(this::refreshPricesIfNeeded);
        Thread.startVirtualThread(this::refreshNetworksIfNeeded);
    }

    /** Currency-service I/O is a background concern, not a locale-rendering concern. */
    @Scheduled(fixedDelayString = "${solarminer.agent.fiat-rates-refresh-ms:60000}")
    public synchronized void refreshRatesIfNeeded() {
        Instant now = Instant.now();
        if (now.isBefore(fiatNextRefresh)) return;
        fiatNextRefresh = now.plus(FIAT_CACHE_TIME);
        try {
            LocalDate date = LocalDate.now(ZoneOffset.UTC);
            String separator = fiatRatesUrl.toString().contains("?") ? "&" : "?";
            URI uri = URI.create(fiatRatesUrl + separator + "date=" + URLEncoder.encode(date.toString(), StandardCharsets.UTF_8)
                    + "&timezone=UTC");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json").header("User-Agent", "SolarMiner-PC-Agent/1.0").GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("Currency service returned HTTP " + response.statusCode());
            fiatCached = parse(mapper.readTree(response.body()), date);
        } catch (Exception failure) {
            fiatCached = new Snapshot(fiatCached.baseCurrency(), fiatCached.rates(), fiatCached.dataUtcDate(), true);
            fiatNextRefresh = now.plus(Duration.ofMinutes(5));
        }
    }

    static Snapshot parse(JsonNode root, LocalDate date) {
        Map<String, Double> rates = new LinkedHashMap<>();
        rates.put("USD", 1.0);
        for (String currency : SUPPORTED_FIAT) {
            if ("USD".equals(currency)) continue;
            double value = root.path(currency.toLowerCase()).asDouble(0);
            if (Double.isFinite(value) && value > 0) rates.put(currency, value);
        }
        return new Snapshot("USD", Map.copyOf(rates), date, rates.size() < SUPPORTED_FIAT.size());
    }

    // ---------------------------------------------------------------- coin prices

    /**
     * The last completed coin-quote snapshot; dashboard reads never call the upstream
     * (REST audit R4). Quotes older than 2 h are treated as unavailable rather than served
     * as if they were current.
     */
    public Map<String, Double> prices() {
        return Instant.now().isAfter(pricesUpdatedAt.plus(Duration.ofHours(2))) ? Map.of() : marketPrices;
    }

    /** Background collection of coin quotes; the only place that touches the prices upstream. */
    @Scheduled(fixedDelayString = "${solarminer.agent.coin-prices-refresh-ms:60000}")
    public synchronized void refreshPricesIfNeeded() {
        Instant now = Instant.now();
        if (now.isBefore(nextPriceRefreshAt)) return;
        nextPriceRefreshAt = now.plus(MARKET_CACHE_TIME);
        try {
            HttpRequest request = HttpRequest.newBuilder(coinPricesUrl).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json").header("User-Agent", "SolarMiner-PC-Agent/1.0").GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("Currency prices HTTP " + response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            if (!root.isObject()) throw new IllegalArgumentException("Currency prices response is not an object");
            Map<String, Double> fresh = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> {
                double value = entry.getValue().asDouble();
                if (Double.isFinite(value) && value > 0) fresh.put(entry.getKey().toUpperCase(java.util.Locale.ROOT), value);
            });
            if (fresh.isEmpty()) throw new IllegalArgumentException("Currency prices response is empty");
            marketPrices = Map.copyOf(fresh);
            pricesUpdatedAt = Instant.now();
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Could not update coin prices", exception);
        }
    }

    // ---------------------------------------------------------------- forecasts

    /**
     * Builds forecasts purely from the last completed network snapshots (REST audit R4): a
     * dashboard read never triggers an upstream collection. Collection runs on the scheduler
     * and at startup; before the first snapshot completes, forecasts report "loading".
     */
    public List<Forecast> forecasts(List<MinerStats.Worker> workers) {
        return COINS.stream().map(coin -> forecast(coin.coin(), coin.ticker(),
                hashrate(workers, coin.algorithm()), snapshots.get(coin.coin()))).toList();
    }

    /** Forecast from a measured local profile, using the same cached network data as the UI. */
    public Forecast forecastForProfile(String coinId, double hashrateHps) {
        CoinDefinition definition = COINS.stream().filter(coin -> coin.coin().equals(coinId)).findFirst().orElse(null);
        if (definition == null || !Double.isFinite(hashrateHps) || hashrateHps <= 0) return null;
        return forecast(definition.coin(), definition.ticker(), hashrateHps, snapshots.get(coinId));
    }

    private static double hashrate(List<MinerStats.Worker> workers, String algorithm) {
        double value = workers.stream()
                .filter(worker -> algorithm.equalsIgnoreCase(worker.currentAlgorithm()))
                .mapToDouble(worker -> worker.terahashPerSecond() * 1_000_000_000_000.0).sum();
        return Double.isFinite(value) && value > 0 ? value : 0.0;
    }

    private Forecast forecast(String coin, String ticker, double hashrate, NetworkSnapshot network) {
        if (network == null) {
            return Forecast.unavailable(coin, ticker, hashrate, "Netzwerk- und Preisdaten werden geladen");
        }
        if (!network.available()) {
            return Forecast.unavailable(coin, ticker, hashrate, network.error());
        }
        double coinsPerDay = hashrate > 0 ? estimateDailyCoins(hashrate, network) : 0.0;
        String reason = network.stale() ? network.error() : hashrate > 0 ? null : "Miner liefert noch keine Hashrate";
        return new Forecast(coin, ticker, hashrate > 0, reason,
                hashrate, network.networkHashrateHps(), network.difficulty(), network.targetBlockSeconds(),
                network.blockReward(), network.priceUsd(), coinsPerDay, coinsPerDay * network.priceUsd(),
                network.collectedAt(), network.stale(), network.sources());
    }

    static double estimateDailyCoins(double hashrateHps, NetworkSnapshot network) {
        return hashrateHps / network.networkHashrateHps()
                * (SECONDS_PER_DAY / network.targetBlockSeconds()) * network.blockReward();
    }

    /**
     * Background collection of mining-network snapshots (REST audit R4). Runs on the scheduler
     * and at startup only; HTTP stays on a virtual thread so a slow currency service cannot
     * block the shared scheduler thread. Failures keep the previous snapshot as stale.
     */
    @Scheduled(fixedDelayString = "${solarminer.agent.mining-networks-refresh-ms:60000}")
    public void refreshNetworksIfNeeded() {
        Instant now = Instant.now();
        if (now.isBefore(nextRefreshAt) || !refreshing.compareAndSet(false, true)) return;
        nextRefreshAt = now.plus(MARKET_CACHE_TIME);
        Thread.startVirtualThread(() -> {
            try {
                Map<String, NetworkSnapshot> fresh = fetchSnapshots();
                for (CoinDefinition coin : COINS) {
                    NetworkSnapshot snapshot = fresh.get(coin.coin());
                    if (snapshot != null) snapshots.put(coin.coin(), snapshot);
                    else markFailed(coin.coin(), "Zentraler Currency-Service liefert keinen Snapshot");
                }
            } catch (Exception exception) {
                LOGGER.log(Level.WARNING, "Could not update earnings forecasts from currency.solarminer.app", exception);
                for (CoinDefinition coin : COINS) {
                    markFailed(coin.coin(), "Currency-Service-Abruf fehlgeschlagen (" + exception.getClass().getSimpleName() + ")");
                }
            } finally {
                refreshing.set(false);
            }
        });
    }

    private void markFailed(String coin, String error) {
        snapshots.compute(coin, (ignored, previous) -> previous == null
                ? NetworkSnapshot.unavailable(error, List.of("currency.solarminer.app"))
                : previous.asStale(error));
    }

    Map<String, NetworkSnapshot> fetchSnapshots() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(miningNetworksUrl).timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .header("User-Agent", "SolarMiner-PC-Agent/1.0")
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("currency.solarminer.app returned HTTP " + response.statusCode());
        }
        JsonNode root = mapper.readTree(response.body());
        if (!root.isArray()) throw new IllegalArgumentException("Currency-service response is not an array");
        Map<String, NetworkSnapshot> result = new LinkedHashMap<>();
        for (JsonNode value : root) {
            String coin = value.path("coin").asText();
            if (COINS.stream().noneMatch(definition -> definition.coin().equals(coin))) continue;
            result.put(coin, parsePublicSnapshot(value));
        }
        return result;
    }

    static NetworkSnapshot parsePublicSnapshot(JsonNode value) {
        if (!value.path("available").asBoolean(false)) {
            return NetworkSnapshot.unavailable("Currency-Service meldet Daten als nicht verfügbar",
                    List.of("currency.solarminer.app"));
        }
        double networkHashrate = value.path("networkHashrateHps").asDouble();
        double difficulty = value.path("difficulty").asDouble();
        double target = value.path("targetBlockSeconds").asDouble();
        double reward = value.path("blockReward").asDouble();
        double price = value.path("priceUsd").asDouble();
        Instant updatedAt = Instant.parse(value.path("updatedAt").asText());
        validate(networkHashrate, difficulty, target, reward, price);

        Set<String> sources = new LinkedHashSet<>();
        sources.add("currency.solarminer.app");
        addSource(sources, value.path("networkSource").asText());
        addSource(sources, value.path("priceSource").asText());
        boolean stale = value.path("stale").asBoolean(false);
        return new NetworkSnapshot(true, stale ? "Zentrale Currency-Daten sind veraltet" : null,
                networkHashrate, difficulty, target, reward, price, updatedAt, stale,
                new ArrayList<>(sources));
    }

    private static void addSource(Set<String> sources, String source) {
        if (source != null && !source.isBlank()) sources.add(source);
    }

    private static void validate(double networkHashrate, double difficulty, double target, double reward, double price) {
        if (!(networkHashrate > 0) || !(difficulty > 0) || !(target > 0) || !(reward > 0) || !(price > 0)) {
            throw new IllegalArgumentException("Incomplete mining market data");
        }
    }

    private record CoinDefinition(String coin, String ticker, String algorithm) {
    }

    record NetworkSnapshot(boolean available, String error, double networkHashrateHps, double difficulty,
                           double targetBlockSeconds, double blockReward, double priceUsd, Instant collectedAt,
                           boolean stale, List<String> sources) {
        static NetworkSnapshot unavailable(String error, List<String> sources) {
            return new NetworkSnapshot(false, error, 0, 0, 0, 0, 0, Instant.now(), false, sources);
        }

        NetworkSnapshot asStale(String refreshError) {
            return new NetworkSnapshot(available, refreshError, networkHashrateHps, difficulty, targetBlockSeconds,
                    blockReward, priceUsd, collectedAt, true, sources);
        }
    }

    public record Snapshot(String baseCurrency, Map<String, Double> rates, LocalDate dataUtcDate, boolean stale) { }

    public record Forecast(String coin, String ticker, boolean available, String unavailableReason,
                           double hashrateHps, double networkHashrateHps, double difficulty,
                           double targetBlockSeconds, double blockReward, double priceUsd,
                           double coinsPerDay, double usdPerDay, Instant updatedAt,
                           boolean stale, List<String> sources) {
        static Forecast unavailable(String coin, String ticker, double hashrate, String reason) {
            return new Forecast(coin, ticker, false, reason, hashrate, 0, 0, 0, 0, 0, 0, 0,
                    null, false, List.of());
        }
    }
}
