package de.verdox.solarminer.pcagent.miner.quanpool;

import com.fasterxml.jackson.databind.JsonNode;
import de.verdox.solarminer.pcagent.miner.MinerShareTelemetry;

/** Normalized snapshot of quanpool-miner's documented HiveOS metrics endpoint. */
public record QuanPoolStats(double hashrateHs, double temperatureCelsius,
                            MinerShareTelemetry.Counters shares, boolean hasActiveJob,
                            double feePercent, String version, String transport) {

    public static QuanPoolStats parse(JsonNode root) {
        if (root == null || !root.isObject()) throw new IllegalArgumentException("QuanPool miner metrics are invalid");
        double multiplier = switch (root.path("hs_units").asText("hs").toLowerCase(java.util.Locale.ROOT)) {
            case "khs" -> 1_000d;
            case "mhs" -> 1_000_000d;
            case "ghs" -> 1_000_000_000d;
            case "ths" -> 1_000_000_000_000d;
            default -> 1d;
        };
        double rate = sum(root.path("hs")) * multiplier;
        double temperature = maximum(root.path("temp"));
        JsonNode acceptedRejected = root.path("ar");
        Long accepted = arrayLong(acceptedRejected, 0);
        Long rejected = arrayLong(acceptedRejected, 1);
        Long stale = nonNegativeLong(root.get("stale_shares"));
        Double difficulty = nonNegativeDouble(root.get("share_difficulty"));
        Double best = nonNegativeDouble(root.get("best_share_difficulty"));
        Long latency = nonNegativeLong(root.get("share_roundtrip_ms"));
        if (latency == null || latency == 0) latency = nonNegativeLong(root.get("pool_rtt_ms"));
        return new QuanPoolStats(rate, temperature,
                new MinerShareTelemetry.Counters(accepted, rejected, stale, difficulty, best, latency),
                root.path("active_jobs").asLong(0) > 0,
                Math.max(0, root.path("fee_percent").asDouble(0)),
                root.path("ver").asText(""), root.path("transport").asText(""));
    }

    private static double sum(JsonNode values) {
        if (!values.isArray()) return 0;
        double result = 0;
        for (JsonNode value : values) if (value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() > 0)
            result += value.asDouble();
        return result;
    }

    private static double maximum(JsonNode values) {
        if (!values.isArray()) return 0;
        double result = 0;
        for (JsonNode value : values) if (value.isNumber() && Double.isFinite(value.asDouble()))
            result = Math.max(result, value.asDouble());
        return result;
    }

    private static Long arrayLong(JsonNode values, int index) {
        return values.isArray() && values.size() > index ? nonNegativeLong(values.get(index)) : null;
    }

    private static Long nonNegativeLong(JsonNode value) {
        return value != null && value.isNumber() && Double.isFinite(value.asDouble())
                ? Math.max(0, value.asLong()) : null;
    }

    private static Double nonNegativeDouble(JsonNode value) {
        return value != null && value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= 0
                ? value.asDouble() : null;
    }
}
