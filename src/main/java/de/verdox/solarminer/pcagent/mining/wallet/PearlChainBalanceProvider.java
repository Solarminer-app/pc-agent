package de.verdox.solarminer.pcagent.mining.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Pearl's public chain-explorer balance (refactoring Phase 4). Pearl is the one coin whose
 * operator-visible unpaid balance also exists on-chain, independent of the configured pool.
 * The read lives behind the {@link PoolBalanceProvider} seam instead of a PEARL branch in
 * {@link WalletBalanceService}, so the coordinator stays fully generic: a new coin with a
 * chain balance plugs in as another provider, no service edit.
 */
@Component
public class PearlChainBalanceProvider implements PoolBalanceProvider {
    private static final BigDecimal GRAINS_PER_PEARL = new BigDecimal("100000000");

    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public PearlChainBalanceProvider(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override public String id() { return "pearl-chain"; }

    /** Pool balances for Pearl stay with the pool adapters; this provider only reads the chain. */
    @Override public boolean supports(String poolUrl, String coin) { return false; }

    @Override
    public boolean supportsOnChain(String coin) {
        return Coin.PEARL.id().equals(coin);
    }

    @Override
    public BigDecimal onChainBalance(String coin, String wallet) throws Exception {
        URI uri = URI.create("https://pearlchain.live/api/explorer/address/" + wallet);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
        return parsePearlBalance(mapper.readTree(response.body()));
    }

    static BigDecimal parsePearlBalance(JsonNode json) {
        JsonNode grains = json.path("balance");
        if (!grains.isIntegralNumber() || grains.decimalValue().signum() < 0)
            throw new IllegalArgumentException("Missing Pearl balance");
        return grains.decimalValue().divide(GRAINS_PER_PEARL);
    }
}
