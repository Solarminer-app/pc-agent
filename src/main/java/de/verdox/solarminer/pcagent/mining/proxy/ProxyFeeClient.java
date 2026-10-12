package de.verdox.solarminer.pcagent.mining.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;

/**
 * The single HTTP client and parser for the stratum proxy's fee API. Every fee
 * consumer (transparency UI, payout defaults, fee-ready gating, tier push) used
 * to carry its own HttpClient plus a private copy of the target parsing; this
 * class keeps the wire contract of {@code GET /api/v1/fees/{coin}/targets} in
 * exactly one place. Caching and failure policy stay with the callers — this
 * client only performs one request and parses, or returns an empty list for
 * any non-200/unreachable/error case (fail-closed, like every former copy).
 *
 * <p>Stateless; consumers construct one from their existing ObjectMapper so
 * their constructor signatures (and test fixtures) stay unchanged.</p>
 */
public class ProxyFeeClient {

    /** One fee target as the proxy's fee API reports it (C1 targets shape). */
    public record FeeTarget(String targetId, String poolAddress, String workerName, String password,
                            double percentage, boolean house) { }

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public ProxyFeeClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Fetches the fee targets for {@code coin} from {@code host:apiPort}.
     * {@code tier} and {@code referral} are optional query parts. An empty list
     * means "no usable answer" (unreachable, non-200, malformed body); callers
     * must treat that as not-fee-ready, never as fee-ready.
     */
    public List<FeeTarget> feeTargets(String host, int apiPort, String coin, String tier, String referral,
                                      Duration timeout) {
        if (host == null || coin == null) return List.of();
        try {
            StringBuilder query = new StringBuilder();
            if (tier != null && !tier.isBlank()) query.append("?tier=").append(tier);
            if (referral != null && !referral.isBlank())
                query.append(query.isEmpty() ? "?" : "&").append("referral=").append(referral);
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://" + host + ":" + apiPort + "/api/v1/fees/" + coin + "/targets" + query))
                    .timeout(timeout).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return List.of();
            return parseTargets(mapper.readTree(response.body()));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /** Parses the targets array shape; a non-array body yields an empty list. */
    public List<FeeTarget> parseTargets(JsonNode body) {
        List<FeeTarget> targets = new ArrayList<>();
        if (body == null || !body.isArray()) return List.of();
        for (JsonNode target : body) {
            targets.add(new FeeTarget(
                    target.path("targetId").asText(""),
                    target.path("poolAddress").asText(""),
                    target.path("workerName").asText(""),
                    target.path("password").asText(""),
                    target.path("percentage").asDouble(),
                    target.path("house").asBoolean(false)));
        }
        return List.copyOf(targets);
    }

    /** Pushes the fee tier; true on 200/204 (the proxy's accepted answers). */
    public boolean pushTier(String host, int apiPort, String tierJsonBody, Duration timeout) {
        if (host == null) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + apiPort + "/api/v1/fees/tier"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(tierJsonBody))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 || response.statusCode() == 204;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The one stratum-pool-address validation rule (previously duplicated in
     * PayoutDefaultsService and ProxyConfigurationService).
     */
    public static boolean validPoolAddress(String value) {
        try {
            URI uri = URI.create(value);
            return ("stratum+tcp".equals(uri.getScheme()) || "stratum+ssl".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getPort() > 0 && uri.getPort() <= 65535
                    && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
