package de.verdox.solarminer.pcagent.miner.peak;

import com.fasterxml.jackson.databind.JsonNode;
import de.verdox.solarminer.pcagent.miner.MinerShareTelemetry;

/** PeakMiner /summary snapshot; the documented rate is kH/s. */
record PeakStats(double hashrateHs, double temperatureCelsius, MinerShareTelemetry.Counters shares,
                 boolean hasActiveJob, double feePercent, String version, String transport) {
    static PeakStats parse(JsonNode root) {
        if (root == null || !root.isObject() || !root.path("gpus").isArray()
                || !root.path("hashrate").isNumber() || !root.path("dev_fee_percent").isNumber())
            throw new IllegalArgumentException("PeakMiner metrics are invalid");
        double rate = root.path("hashrate").asDouble() * 1_000d;
        if (!Double.isFinite(rate) || rate < 0) throw new IllegalArgumentException("PeakMiner hashrate is invalid");
        double temperature = 0;
        for (JsonNode gpu : root.path("gpus"))
            if (gpu.path("temperature_c").isNumber()) temperature = Math.max(temperature, gpu.path("temperature_c").asDouble());
        JsonNode pool = root.path("pool");
        return new PeakStats(rate, temperature,
                new MinerShareTelemetry.Counters(counter(root.path("accepted_shares")),
                        counter(root.path("invalid_shares")), null,
                        nonNegative(pool.path("difficulty")), null,
                        counter(pool.path("ping_ms"))),
                pool.path("connected").asBoolean(false), root.path("dev_fee_percent").asDouble(),
                root.path("version").asText(""), pool.path("url").asText(""));
    }
    private static Long counter(JsonNode value) {
        return value.isIntegralNumber() && value.asLong() >= 0 ? value.asLong() : null;
    }
    private static Double nonNegative(JsonNode value) {
        return value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= 0 ? value.asDouble() : null;
    }
}
