package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Cached USD exchange rates from SolarMiner's central currency service for consistent local UI display. */
@Service
public class FiatRateService {
    private static final Duration CACHE_TIME = Duration.ofHours(1);
    private static final Set<String> SUPPORTED = Set.of("EUR", "USD", "CHF");

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final URI endpoint;
    private volatile Snapshot cached = new Snapshot("USD", Map.of("USD", 1.0), null, true);
    private volatile Instant nextRefresh = Instant.EPOCH;

    @Autowired
    public FiatRateService(ObjectMapper mapper,
                           @Value("${solarminer.fiat-rates-url:https://currency.solarminer.app/api/v1/public/exchange-rates}") URI endpoint) {
        this(mapper, endpoint, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
    }

    FiatRateService(ObjectMapper mapper, URI endpoint, HttpClient http) {
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.http = http;
    }

    public synchronized Snapshot rates() {
        Instant now = Instant.now();
        if (now.isBefore(nextRefresh)) return cached;
        nextRefresh = now.plus(CACHE_TIME);
        try {
            LocalDate date = LocalDate.now(ZoneOffset.UTC);
            String separator = endpoint.toString().contains("?") ? "&" : "?";
            URI uri = URI.create(endpoint + separator + "date=" + URLEncoder.encode(date.toString(), StandardCharsets.UTF_8)
                    + "&timezone=UTC");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json").header("User-Agent", "SolarMiner-PC-Agent/1.0").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("Currency service returned HTTP " + response.statusCode());
            cached = parse(mapper.readTree(response.body()), date);
        } catch (Exception failure) {
            cached = new Snapshot(cached.baseCurrency(), cached.rates(), cached.dataUtcDate(), true);
            nextRefresh = now.plus(Duration.ofMinutes(5));
        }
        return cached;
    }

    static Snapshot parse(JsonNode root, LocalDate date) {
        Map<String, Double> rates = new LinkedHashMap<>();
        rates.put("USD", 1.0);
        for (String currency : SUPPORTED) {
            if ("USD".equals(currency)) continue;
            double value = root.path(currency.toLowerCase()).asDouble(0);
            if (Double.isFinite(value) && value > 0) rates.put(currency, value);
        }
        return new Snapshot("USD", Map.copyOf(rates), date, rates.size() < SUPPORTED.size());
    }

    public record Snapshot(String baseCurrency, Map<String, Double> rates, LocalDate dataUtcDate, boolean stale) { }
}
