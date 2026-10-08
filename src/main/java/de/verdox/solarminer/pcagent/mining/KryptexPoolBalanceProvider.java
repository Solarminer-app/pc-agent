package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/** Kryptex's public wallet-balance API, mapped from PC-Agent coin ids to Kryptex coin ids. */
@Component
public class KryptexPoolBalanceProvider implements PoolBalanceProvider {
    private static final String API_BASE = "https://pool.kryptex.com";
    private static final Map<String, String> COINS = Map.of(
            "monero", "xmr",
            "pearl", "prl",
            "ravencoin", "rvn",
            "ethereumclassic", "etc",
            "quantus", "qtc");

    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public KryptexPoolBalanceProvider(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override public String id() { return "kryptex"; }

    @Override
    public boolean supports(String poolUrl, String coin) {
        if (poolUrl == null || coin == null || !COINS.containsKey(coin.toLowerCase(Locale.ROOT))) return false;
        try {
            String host = URI.create(poolUrl).getHost();
            return host != null && host.toLowerCase(Locale.ROOT).endsWith(".kryptex.network");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @Override
    public BigDecimal balance(String coin, String poolUrl, String wallet) throws Exception {
        String apiCoin = COINS.get(coin.toLowerCase(Locale.ROOT));
        String address = URLEncoder.encode(wallet, StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = URI.create(API_BASE + "/" + apiCoin + "/api/v1/miner/balance/" + address);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
        return parsePoolBalance(mapper.readTree(response.body()));
    }

    static BigDecimal parsePoolBalance(JsonNode json) {
        JsonNode total = json.path("total");
        if (!total.isNumber() || total.decimalValue().signum() < 0)
            throw new IllegalArgumentException("Missing pool balance");
        return total.decimalValue();
    }
}
