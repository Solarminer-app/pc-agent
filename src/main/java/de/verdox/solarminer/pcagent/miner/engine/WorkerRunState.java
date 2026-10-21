package de.verdox.solarminer.pcagent.miner.engine;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.miner.diagnostics.MinerLogDiagnostics;
import de.verdox.solarminer.pcagent.miner.MinerShareTelemetry;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;

import java.util.concurrent.atomic.AtomicReference;
import de.verdox.solarminer.pcagent.mining.proxy.GpuStratumRelay;

/**
 * One supervised GPU worker run, shared by the Pearl adapter and the SRB GPU-coin adapter
 * (refactoring Phase 3). It replaces the near-duplicate {@code PearlMinerService.GpuRun} and
 * {@code GpuCoinMinerService.Run} classes; the GPU-coin-only relay lives in the generic
 * {@link #relay} slot. Status aggregation for the wire stays in the owning adapter.
 */
public final class WorkerRunState implements MinerShareTelemetry.ShareSink {
    /** Coin id this run belongs to (part of the unified key). */
    public final String coin;
    public final LocalGpuPowerService.Gpu gpu;
    /** Unified map key: {@code coin:vendor:index}. */
    public final String key;
    public final String consoleId;
    /** Rolling tail of redacted miner output (used by the Pearl exit diagnostics). */
    public final AtomicReference<String> output = new AtomicReference<>("");
    public volatile Process process;
    /** GPU-coin stratum relay slot; null for engines that do not use a relay. */
    public volatile de.verdox.solarminer.pcagent.mining.proxy.GpuStratumRelay relay;
    public volatile MinerStats.MinerStatus status = MinerStats.MinerStatus.PAUSED;
    public volatile boolean healthy;
    public volatile double hashrate;
    public volatile Long acceptedShares;
    public volatile Long rejectedShares;
    public volatile MinerShareTelemetry.Counters poolCounters = MinerShareTelemetry.Counters.unavailable();
    public volatile String detail;
    public volatile String error;
    /** Rule-mapped failure diagnosis from the miner's own log, e.g. unsupported kernel. */
    public volatile MinerLogDiagnostics.Diagnosis diagnosis;
    public volatile int apiPort;
    /** Bounded crash-restart attempts for this worker; reset on operator start/stop. */
    public int restartAttempts;
    /** Stop intent is independent of diagnostic status and survives late monitor failures. */
    public volatile boolean restartAllowed = true;
    private volatile java.time.Instant retryAfter = java.time.Instant.MIN;

    /** Retry 30/60/120 seconds after failure; operator resume resets the attempt counter. */
    public synchronized void scheduleRecovery() {
        retryAfter = java.time.Instant.now().plusSeconds(30L << Math.min(restartAttempts, 2));
    }

    /** Each launch attempt has its own state; old API/output/exit callbacks keep the old state. */
    public static WorkerRunState nextAttempt(WorkerRunState previous, String coin,
                                             LocalGpuPowerService.Gpu gpu, String consoleId, String detail) {
        if (previous != null && previous.running()) return previous;
        WorkerRunState next = new WorkerRunState(coin, gpu, consoleId, detail);
        if (previous != null) {
            synchronized (previous) {
                previous.restartAllowed = false;
                next.restartAttempts = previous.restartAttempts;
            }
        }
        return next;
    }

    /** Withdraw recovery before any potentially blocking process termination. */
    public synchronized void requestStop(String detail) {
        restartAllowed = false;
        status = MinerStats.MinerStatus.PAUSED;
        healthy = false;
        hashrate = 0;
        acceptedShares = null;
        rejectedShares = null;
        poolCounters = MinerShareTelemetry.Counters.unavailable();
        this.detail = detail;
        error = null;
        diagnosis = null;
        restartAttempts = 0;
    }

    /** A failed stop remains visible but never becomes a crash-restart request. */
    public synchronized boolean finishStop(boolean terminated) {
        if (!terminated) {
            status = MinerStats.MinerStatus.ERROR;
            detail = error = "worker_stop_failed";
        }
        return terminated;
    }

    public boolean monitors(Process candidate) {
        return restartAllowed && process == candidate && status == MinerStats.MinerStatus.MINING;
    }

    public boolean restartEligible() {
        return restartAllowed && status == MinerStats.MinerStatus.ERROR && !running()
                && restartAttempts < AbstractMinerEngine.MAX_CRASH_RESTARTS
                && !java.time.Instant.now().isBefore(retryAfter);
    }

    public WorkerRunState(String coin, LocalGpuPowerService.Gpu gpu, String consoleId, String initialDetail) {
        this.coin = coin;
        this.gpu = gpu;
        this.key = coin + ":" + gpu.vendor() + ":" + gpu.index();
        this.consoleId = consoleId;
        this.detail = initialDetail;
    }

    /** Console tag used in every aggregate-line prefix (historically {@code vendor:index}). */
    public String gpuLabel() { return gpu.vendor() + ":" + gpu.index(); }

    public boolean running() { return process != null && process.isAlive(); }

    @Override public void setAcceptedShares(Long accepted) { this.acceptedShares = accepted; }
    @Override public void setRejectedShares(Long rejected) { this.rejectedShares = rejected; }
    @Override public void setPoolCounters(MinerShareTelemetry.Counters counters) { this.poolCounters = counters; }
}
