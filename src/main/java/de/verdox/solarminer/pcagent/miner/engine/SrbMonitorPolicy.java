package de.verdox.solarminer.pcagent.miner.engine;

/**
 * Per-engine watchdog and detail-text policy for the shared SRBMiner monitor loop
 * (refactoring Phase 3). The GPU coins historically had the stricter, hashrate-aware
 * watchdog; Pearl historically had the simpler job-only watchdog. Owner decision O2
 * (2026-10-11) moved Pearl onto the hashrate-tolerant usable-job rule, the
 * reported-hashrate fallback chain and the hashrate display grace, while Pearl keeps its
 * job-freshness stall detection and its English detail texts. All numbers and messages
 * are the historical per-engine values.
 */
public record SrbMonitorPolicy(
        /** Seconds a freshly launched miner may take before its first usable pool job. */
        int startupJobSeconds,
        /** Seconds without a fresh pool signal before the worker is considered stalled. */
        int poolStallSeconds,
        /** 0 disables the hashrate watchdog (Pearl keeps its job-only stall rule). */
        int hashrateStartupSeconds,
        int hashrateStallSeconds,
        /** GPU coins abort when the proxy fee route disappears; Pearl never checked it. */
        boolean feeRouteCheck,
        /** Pearl's stall clock resets on a fresh job; GPU coins on a live pool connection. */
        boolean jobFreshnessStall,
        /** Hold the last positive hashrate briefly when the API reports 0 (O2a: both). */
        boolean hashrateGrace,
        /** A worker still producing hashes proves a usable pool job (O2a: both). */
        boolean usableJobFromHashrate,
        /** Pearl surfaces non-200 API answers as its own detail text instead of a failure. */
        boolean reportHttpErrorsSeparately,
        /** Pearl: pool-connected plus a fresh job; GPU coins: pool-connected plus hashrate. */
        boolean healthyRequiresHashrate,
        String apiUnreachableDetail,
        String hashingDetail,
        String connectedDetail,
        String waitingDetail,
        String hashrateFailMessage,
        PoolFailMessage poolFailMessage) {

    /** Builds the engine's historical pool-watchdog failure message. */
    @FunctionalInterface
    public interface PoolFailMessage {
        String build(boolean startupTimeout, String gpuLabel, String outputTail);
    }

    /** The GPU-coin watchdog (German detail texts, fee-route check, hashrate watchdog). */
    public static SrbMonitorPolicy gpuCoin() {
        return new SrbMonitorPolicy(90, 120, 300, 120, true, false, true, true, false, true,
                "Miner-API nicht erreichbar",
                "GPU hasht, letzter Pool-Job vor %d s",
                "Pool verbunden, warte auf GPU-Hashrate",
                "Warte auf Pool-Job",
                "GPU liefert trotz Pool-Jobs keine Hashrate; SRBMiner- und GPU-Treiber prüfen",
                (startupTimeout, label, output) -> "Pool-Job oder Fee-Route nicht verfügbar");
    }

    /** The Pearl watchdog (English detail texts, no fee-route check, job-freshness stall). */
    public static SrbMonitorPolicy pearl() {
        return new SrbMonitorPolicy(90, 120, 0, 0, false, true, true, true, true, false,
                "Miner API is not reachable yet",
                "Pool connected, last job %d s ago",
                "Waiting for pool connection and mining job",
                "Waiting for pool connection and mining job",
                null,
                (startupTimeout, label, output) -> "SRBMiner on " + label + " has not received a mining job "
                        + (startupTimeout ? "within 90" : "for 120") + " seconds: " + output);
    }

    /** True when a pool job counts as usable; O2a lets a positive hashrate rescue stale jobs. */
    public boolean jobUsable(boolean poolConnected, boolean recentJob, double hashrate) {
        return poolConnected && (recentJob || (usableJobFromHashrate && hashrate > 0));
    }
}
