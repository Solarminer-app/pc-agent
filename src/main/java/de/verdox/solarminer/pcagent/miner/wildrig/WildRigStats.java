package de.verdox.solarminer.pcagent.miner.wildrig;

import com.fasterxml.jackson.databind.JsonNode;
import de.verdox.solarminer.pcagent.miner.MinerShareTelemetry;

/** WildRig NG /2/summary snapshot, including its per-GPU rate and share arrays. */
record WildRigStats(double hashrateHs, double temperatureCelsius, MinerShareTelemetry.Counters shares,
                    boolean hasActiveJob, double feePercent, String version, String transport) {
    static WildRigStats parse(JsonNode root) {
        JsonNode threads = root == null ? null : root.path("hashrate").path("threads");
        if (root == null || !root.isObject() || threads == null || !threads.isArray()
                || !root.path("results").isObject() || !root.path("donate_level").isNumber())
            throw new IllegalArgumentException("WildRig NG metrics are invalid");
        double rate = 0;
        for (JsonNode thread : threads) {
            if (!thread.isArray() || thread.isEmpty() || !thread.get(0).isNumber()
                    || !Double.isFinite(thread.get(0).asDouble()) || thread.get(0).asDouble() < 0)
                throw new IllegalArgumentException("WildRig NG hashrate is invalid");
            rate += thread.get(0).asDouble();
        }
        if (!Double.isFinite(rate)) throw new IllegalArgumentException("WildRig NG hashrate is invalid");
        double temperature = 0;
        for (JsonNode value : root.path("hwmon").path("temp"))
            if (value.isNumber() && Double.isFinite(value.asDouble())) temperature = Math.max(temperature, value.asDouble());
        JsonNode results = root.path("results");
        return new WildRigStats(rate, temperature,
                new MinerShareTelemetry.Counters(sum(results.path("shares_accepted")),
                        sum(results.path("shares_rejected")), sum(results.path("shares_ignored")),
                        number(results.path("diff_current")), null, counter(root.path("connection").path("ping"))),
                root.path("connection").path("uptime").asLong(0) > 0
                        && results.path("diff_current").asDouble(0) > 0,
                root.path("donate_level").asDouble(), root.path("version").asText(""),
                root.path("connection").path("pool").asText(""));
    }
    private static Long counter(JsonNode value) {
        return value.isIntegralNumber() && value.asLong() >= 0 ? value.asLong() : null;
    }
    private static Long sum(JsonNode values) {
        if (!values.isArray()) return null;
        long total = 0;
        for (JsonNode value : values) {
            Long count = counter(value);
            if (count == null) return null;
            total = Math.addExact(total, count);
        }
        return total;
    }
    private static Double number(JsonNode value) {
        return value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= 0 ? value.asDouble() : null;
    }
}
