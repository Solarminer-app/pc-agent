package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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
 * Supplies gross, probability-based daily mining estimates from SolarMiner's
 * public currency service. Pool payout schemes, stale shares and fees are not deducted.
 */
@Service
public class EarningsForecastService {
    private static final Logger LOGGER = Logger.getLogger(EarningsForecastService.class.getName());
    private static final Duration CACHE_TIME = Duration.ofMinutes(10);
    private static final double SECONDS_PER_DAY = 86_400.0;
    private static final List<CoinDefinition> COINS = List.of(
            new CoinDefinition("monero", "XMR", "randomx"),
            new CoinDefinition("pearl", "PRL", "pearlhash"),
            new CoinDefinition("ravencoin", "RVN", "kawpow"),
            new CoinDefinition("ethereumclassic", "ETC", "etchash"),
            new CoinDefinition("decred", "DCR", "blake3_decred"),
            new CoinDefinition("quantus", "QTC", "quantus"));

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final URI miningNetworksUrl;
    private final URI coinPricesUrl;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Map<String, NetworkSnapshot> snapshots = new ConcurrentHashMap<>();
    private volatile Instant nextRefreshAt = Instant.EPOCH;
    private volatile Instant nextPriceRefreshAt = Instant.EPOCH;
    private volatile Instant pricesUpdatedAt = Instant.EPOCH;
    private volatile Map<String, Double> marketPrices = Map.of();

    @Autowired
    public EarningsForecastService(
            ObjectMapper mapper,
            @Value("${solarminer.earnings.mining-networks-url:https://currency.solarminer.app/api/v1/public/mining-networks}") URI miningNetworksUrl,
            @Value("${solarminer.earnings.coin-prices-url:https://currency.solarminer.app/api/v1/public/coin-prices}") URI coinPricesUrl) {
        this(mapper, miningNetworksUrl, coinPricesUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
    }

    EarningsForecastService(ObjectMapper mapper, URI miningNetworksUrl, HttpClient httpClient) {
        this(mapper, miningNetworksUrl, URI.create(miningNetworksUrl.toString().replace("/mining-networks", "/coin-prices")), httpClient);
    }

    EarningsForecastService(ObjectMapper mapper, URI miningNetworksUrl, URI coinPricesUrl, HttpClient httpClient) {
        this.mapper = mapper;
        this.miningNetworksUrl = miningNetworksUrl;
        this.coinPricesUrl = coinPricesUrl;
        this.httpClient = httpClient;
    }

    /** Coin quotes are independent of mining-network availability (notably BTC, DCR and QTC). */
    public synchronized Map<String, Double> prices() {
        Instant now = Instant.now();
        if (now.isAfter(nextPriceRefreshAt)) {
            nextPriceRefreshAt = now.plus(CACHE_TIME);
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
        return Instant.now().isAfter(pricesUpdatedAt.plus(Duration.ofHours(2))) ? Map.of() : marketPrices;
    }

    public List<Forecast> forecasts(List<MinerStats.Worker> workers) {
        refreshIfNeeded();
        return COINS.stream().map(coin -> forecast(coin.coin(), coin.ticker(),
                hashrate(workers, coin.algorithm()), snapshots.get(coin.coin()))).toList();
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

    private void refreshIfNeeded() {
        Instant now = Instant.now();
        if (now.isBefore(nextRefreshAt) || !refreshing.compareAndSet(false, true)) return;
        nextRefreshAt = now.plus(CACHE_TIME);
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
