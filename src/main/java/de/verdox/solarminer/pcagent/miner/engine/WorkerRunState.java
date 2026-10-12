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
