package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.JsonNode;

/** Normalizes the share-counter shapes exposed by the supported local miner APIs. */
public final class MinerShareTelemetry {
    private MinerShareTelemetry() { }

    public record Counters(Long accepted, Long rejected, Long stale, Double difficulty, Double bestShare, Long latencyMs) {
        public static Counters unavailable() { return new Counters(null, null, null, null, null, null); }
    }

    public static Counters xmrig(JsonNode results) { return xmrig(results, null); }

    /** XMRig reports counters per mining result and pool latency per connection entry. */
    public static Counters xmrig(JsonNode results, JsonNode connection) {
        JsonNode node = unwrap(results);
        if (node == null) return Counters.unavailable();
        Long accepted = number(node, "shares_good", "accepted", "accepted_shares");
        Long rejected = number(node, "shares_rejected", "rejected", "rejected_shares");
        Long total = number(node, "shares_total", "total");
        if (rejected == null && total != null && accepted != null) rejected = Math.max(0, total - accepted);
        if (accepted == null && total != null && rejected != null) accepted = Math.max(0, total - rejected);
        Long stale = number(node, "shares_stale", "stale");
        Double difficulty = decimal(node, "diff", "difficulty");
        Double bestShare = decimal(node, "best_diff", "best_share", "best");
        Long latency = null;
        JsonNode connectionEntry = unwrap(connection);
        if (connectionEntry != null) {
            latency = number(connectionEntry, "latency", "latency_ms");
            if (difficulty == null) difficulty = decimal(connectionEntry, "diff", "difficulty");
            if (stale == null) stale = number(connectionEntry, "stale");
        }
        return new Counters(accepted, rejected, stale, difficulty, bestShare, latency);
    }

    public static Counters srbMiner(JsonNode pool) {
        JsonNode node = unwrap(pool);
        if (node == null) return Counters.unavailable();
        JsonNode nested = node.path("shares");
        Long accepted = first(number(node, "accepted", "accepted_shares"), number(nested, "accepted", "accepted_shares"));
        Long rejected = first(number(node, "rejected", "rejected_shares"), number(nested, "rejected", "rejected_shares"));
        Long stale = first(number(node, "stale", "stale_shares"), number(nested, "stale", "stale_shares"));
        Double difficulty = decimal(node, "difficulty", "diff");
        Double bestShare = decimal(node, "best_ever_share", "best_session_share", "best_share", "best_diff");
        Long latency = number(node, "latency", "latency_ms");
        return new Counters(accepted, rejected, stale, difficulty, bestShare, latency);
    }

    /**
     * Both miner APIs hand out these nodes as a bare object in some releases and as a
     * single-element array in others, so an array must not silently read as "no data".
     */
    private static JsonNode unwrap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isArray()) return node.isEmpty() ? null : unwrap(node.get(0));
        return node.isObject() ? node : null;
    }

    private static Long number(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) continue;
            if (value.isIntegralNumber()) return Math.max(0, value.asLong());
            if (value.isNumber() && Double.isFinite(value.asDouble())) return Math.max(0L, Math.round(value.asDouble()));
        }
        return null;
    }

    private static Double decimal(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) continue;
            if (value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= 0) return value.asDouble();
            if (value.isTextual()) {
                try {
                    double parsed = Double.parseDouble(value.asText().trim());
                    if (Double.isFinite(parsed) && parsed >= 0) return parsed;
                } catch (NumberFormatException ignored) { /* A non-numeric difficulty stays unavailable. */ }
            }
        }
        return null;
    }

    private static Long first(Long primary, Long fallback) { return primary != null ? primary : fallback; }
}
