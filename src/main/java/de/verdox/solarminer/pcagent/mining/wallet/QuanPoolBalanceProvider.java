package de.verdox.solarminer.pcagent.mining.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
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

/** Read-only adapter for QuanPool's public, wallet-address based mainnet account API. */
@Component
public class QuanPoolBalanceProvider implements PoolBalanceProvider {
    private static final String API_BASE = "https://quanpool.com/api/miner/mainnet/";
    private static final BigDecimal PLANCK_PER_QTC = new BigDecimal("1000000000000");

    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public QuanPoolBalanceProvider(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override public String id() { return "quanpool"; }

    @Override
    public boolean supports(String poolUrl, String coin) {
        if (!Coin.QUANTUS.id().equals(coin) || poolUrl == null) return false;
        try {
            String host = URI.create(poolUrl).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("quanpool.com") || host.endsWith(".quanpool.com");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @Override
    public PoolAccount account(String coin, String poolUrl, String wallet) throws Exception {
        URI uri = accountUri(wallet);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
        return parseAccount(mapper.readTree(response.body()));
    }

    static URI accountUri(String wallet) {
        if (wallet == null || wallet.isBlank()) throw new IllegalArgumentException("Missing Quantus payout address");
        String address = URLEncoder.encode(wallet.trim(), StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create(API_BASE + address);
    }

    static PoolAccount parseAccount(JsonNode json) {
        JsonNode seen = json.path("seen");
        if (!seen.isBoolean()) throw new IllegalArgumentException("Missing QuanPool account state");
        if (!seen.booleanValue()) return new PoolAccount(BigDecimal.ZERO, BigDecimal.ZERO);
        JsonNode balance = json.path("balance");
        return new PoolAccount(planck(balance.path("pending")), planck(balance.path("paid_total")));
    }

    private static BigDecimal planck(JsonNode value) {
        if (!value.isNumber() && !value.isTextual()) throw new IllegalArgumentException("Missing QuanPool balance");
        try {
            BigDecimal amount = new BigDecimal(value.asText());
            amount.toBigIntegerExact();
            if (amount.signum() < 0) throw new ArithmeticException("negative");
            return amount.divide(PLANCK_PER_QTC);
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid QuanPool balance", invalid);
        }
    }
}
