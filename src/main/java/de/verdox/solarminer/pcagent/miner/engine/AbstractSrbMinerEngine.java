package de.verdox.solarminer.pcagent.miner.engine;

import com.fasterxml.jackson.databind.JsonNode;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.miner.diagnostics.MinerLogDiagnostics;
import de.verdox.solarminer.pcagent.miner.MinerShareTelemetry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Shared SRBMiner engine mechanics (refactoring Phase 3, owner-approved drifts O1/O2 of
 * 2026-10-11). Both SRB engines ({@code PearlMinerService} and {@code GpuCoinMinerService})
 * run the same binary against the same miner API, so output draining, the health monitor
 * loop, the stop escalation and the crash-restart bookkeeping exist exactly once here.
 * Command construction, device mapping, login encoding, pause bookkeeping and status
 * aggregation stay in the owning adapters — those are the genuinely engine-specific parts.
 *
 * <p>Approved behaviour changes folded in:</p>
 * <ul>
 *   <li>O1: both SRB engines get the XMRig-style bounded crash restart
 *       ({@link #MAX_CRASH_RESTARTS} attempts per worker; each service runs the 30 s scan).</li>
 *   <li>O2a: Pearl's monitor adopts the GPU-coin watchdog — hashrate-aware usable-job rule
 *       (fixes KAWPOW false alarms), the reported-hashrate fallback chain and the hashrate
 *       display grace. Pearl keeps its job-freshness stall clock, its job-based pool health
 *       and its English detail texts via {@link SrbMonitorPolicy}.</li>
 * </ul>
 */
public abstract class AbstractSrbMinerEngine extends AbstractMinerEngine {
    /** Seconds a briefly missing API hashrate keeps the last positive value on screen. */
    static final Duration HASHRATE_GRACE = Duration.ofSeconds(20);

    protected final HttpClient apiClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    /** Aggregate console id worker lines are mirrored into (Pearl: "pearl", GPU coins: coin id). */
    protected abstract String aggregateConsoleId(WorkerRunState run);
    /** Aggregate-line prefix for control messages (both engines: "vendor:index"). */
    protected abstract String aggregateTag(WorkerRunState run);
    /** Aggregate-line prefix for drained output lines (Pearl: "vendor:index", GPU coins: index). */
    protected abstract String drainTag(WorkerRunState run);

    /**
     * Starts the virtual output-drain thread: redacts for diagnosis, classifies failure
     * categories once per run, mirrors lines to the worker and aggregate consoles and
     * optionally keeps a rolling redacted tail for exit diagnostics.
     * {@code redactConsoleLines} matches the historical per-engine console behaviour
     * (Pearl showed raw lines, GPU coins showed redacted ones). {@code redactor} removes
     * wallet/pool secrets from raw miner output before it reaches the operator.
     */
    protected Thread startOutputDrain(WorkerRunState run, Process process, String threadName,
                                      boolean redactConsoleLines, boolean trackOutputTail,
                                      java.util.function.UnaryOperator<String> redactor) {
        return drainProcessOutput(process, threadName, line -> {
            String safe = redactor.apply(line);
            MinerLogDiagnostics.classify(MinerLogDiagnostics.SRBMINER, safe).ifPresent(found -> {
                if (run.diagnosis == null) {
                    run.diagnosis = found;
                    console().append(run.consoleId, "[SolarMiner] " + found.detail());
                }
            });
            String shown = redactConsoleLines ? safe : line;
            console().append(run.consoleId, shown);
            console().append(aggregateConsoleId(run), "[" + drainTag(run) + "] " + shown);
            if (trackOutputTail) run.output.updateAndGet(previous -> {
                String combined = (previous.isBlank() ? "" : previous + " | ") + safe;
                return combined.length() > 1200 ? combined.substring(combined.length() - 1200) : combined;
            });
        }, e -> logger().log(java.util.logging.Level.FINE, "Miner output ended for " + run.key, e));
    }

    /**
     * The unified health monitor loop (5 s poll). {@code policy} selects the per-engine
     * watchdog numbers and detail texts; {@code feeRouteReady} is only consulted when the
     * policy enables the fee-route check (GPU coins). The loop exits when the process dies,
     * the run is replaced or the worker left the MINING state.
     */
    protected void monitorLoop(WorkerRunState run, Process process, SrbMonitorPolicy policy,
                               BooleanSupplier feeRouteReady, Predicate<WorkerRunState> stillCurrent) {
        Instant launched = Instant.now(), lastPoolConnection = launched, lastHashrate = launched, lastFreshJob = launched;
        Instant lastPositiveHashrate = null;
        boolean hadJob = false, hadHashrate = false;
        while (process.isAlive() && run.process == process && stillCurrent.test(run)) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + run.apiPort + "/"))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> response = apiClient.send(request, HttpResponse.BodyHandlers.ofString());
                synchronized (run) {
                    if (!run.monitors(process)) return;
                    if (!process.isAlive()) return;
                    if (response.statusCode() != 200 && policy.reportHttpErrorsSeparately()) {
                        // Pearl historically reported non-200 as its own detail and kept polling.
                        run.healthy = false;
                        run.detail = "Miner API returned HTTP " + response.statusCode();
                    } else {
                        if (response.statusCode() != 200) throw new IOException("Miner-API HTTP " + response.statusCode());
                        JsonNode algorithms = mapper().readTree(response.body()).path("algorithms");
                        JsonNode info = algorithms.isArray() && !algorithms.isEmpty()
                                ? algorithms.get(0) : mapper().createObjectNode();
                        JsonNode pool = info.path("pool");
                        double rate = reportedHashrate(info);
                        Instant now = Instant.now();
                        if (rate > 0) lastPositiveHashrate = now;
                        run.hashrate = policy.hashrateGrace()
                                ? displayedHashrate(rate, run.hashrate, lastPositiveHashrate, now)
                                : (Double.isFinite(rate) && rate > 0 ? rate : 0);
                        MinerShareTelemetry.applySrbShares(pool, run);
                        long age = pool.path("last_job_received").asLong(0);
                        boolean poolConnected = pool.path("uptime").asLong(0) > 0;
                        boolean recentJob = poolConnected && age > 0 && age < 120;
                        if (poolConnected) lastPoolConnection = Instant.now();
                        // KAWPOW jobs can validly outlive the short job-refresh cadence used by
                        // some pools. A worker that is still producing hashes therefore proves
                        // that it received a usable job; do not turn an old last_job_received
                        // counter into a false pool outage (O2a: this now applies to Pearl too).
                        // A usable job also resets the Pearl-style job-freshness stall clock.
                        if (policy.jobUsable(poolConnected, recentJob, rate)) { hadJob = true; lastFreshJob = now; }
                        // Pearl's historical pool health was job-based; the GPU coins require a
                        // live hashrate. Both keep their own semantics via the policy.
                        run.healthy = poolConnected && (policy.healthyRequiresHashrate()
                                ? rate > 0 : (recentJob || (policy.usableJobFromHashrate() && rate > 0)));
                        if (rate > 0) { hadHashrate = true; lastHashrate = now; }
                        if (run.healthy) run.detail = String.format(policy.hashingDetail(), age);
                        else if (poolConnected) run.detail = policy.connectedDetail();
                        else run.detail = policy.waitingDetail();
                    }
                }
            } catch (Exception e) {
                synchronized (run) {
                    if (!run.monitors(process)) return;
                    if (!process.isAlive()) return;
                    run.hashrate = policy.hashrateGrace()
                            ? displayedHashrate(0, run.hashrate, lastPositiveHashrate, Instant.now()) : 0;
                    run.healthy = false;
                    run.detail = policy.apiUnreachableDetail();
                }
            }
            synchronized (run) {
                if (!run.monitors(process)) return;
                // Pearl's stall clock historically tracked fresh jobs; the GPU coins' tracks the
                // live pool connection. Both keep their own clock via the policy.
                Instant stallAnchor = policy.jobFreshnessStall() ? lastFreshJob : lastPoolConnection;
                if ((!hadJob && Duration.between(launched, Instant.now()).toSeconds() >= policy.startupJobSeconds())
                        || (hadJob && Duration.between(stallAnchor, Instant.now()).toSeconds() >= policy.poolStallSeconds())
                        || (policy.feeRouteCheck() && !feeRouteReady.getAsBoolean())) {
                    fail(run, policy.poolFailMessage().build(!hadJob, run.gpuLabel(), run.output.get()));
                    terminateFailedProcess(process);
                    return;
                }
                if (policy.hashrateStartupSeconds() > 0 && hadJob
                        && ((!hadHashrate && Duration.between(launched, Instant.now()).toSeconds() >= policy.hashrateStartupSeconds())
                        || (hadHashrate && Duration.between(lastHashrate, Instant.now()).toSeconds() >= policy.hashrateStallSeconds()))) {
                    fail(run, policy.hashrateFailMessage());
                    terminateFailedProcess(process);
                    return;
                }
            }
            try { Thread.sleep(5000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** True when the monitored process died on its own while the worker was still mining. */
    protected boolean unexpectedExit(WorkerRunState run, Process done) {
        return run.monitors(done);
    }

    /**
     * Graceful-then-forced stop of one worker process with the historical 5 s/5 s
     * escalation. {@code verboseStopLog} mirrors Pearl's dual-console stop bookkeeping;
     * the GPU coins keep their single console line.
     */
    protected boolean stopProcess(WorkerRunState run, String stopSource,
                                  boolean verboseStopLog, String stoppedLine) {
        Process process = run.process;
        if (process == null || !process.isAlive()) return true;
        if (verboseStopLog) {
            console().append(run.consoleId, "[SolarMiner] Stop requested by: " + stopSource);
            console().append(aggregateConsoleId(run), "[" + aggregateTag(run) + "] Stop requested by: " + stopSource);
        } else {
            console().append(run.consoleId, "[SolarMiner] Stop: " + stopSource);
        }
        boolean stopped = run.finishStop(de.verdox.solarminer.pcagent.support.ProcessTermination.terminate(
                List.of(process.toHandle()), Duration.ofSeconds(5)));
        if (stopped && stoppedLine != null) {
            console().append(run.consoleId, "[SolarMiner] " + stoppedLine);
            console().append(aggregateConsoleId(run), "[" + aggregateTag(run) + "] " + stoppedLine);
        }
        return stopped;
    }

    /** True when a crashed worker may still be restarted by the O1 watchdog (per-run bound). */
    protected static boolean restartEligible(WorkerRunState run) {
        return run.restartEligible();
    }

    /** SRBMiner has used gpu.total, 1m and 1min hashrate fields across releases. */
    public static double reportedHashrate(JsonNode info) {
        JsonNode hashrate = info.path("hashrate");
        for (JsonNode value : List.of(hashrate.path("gpu").path("total"), hashrate.path("1m"), hashrate.path("1min"))) {
            double rate = value.asDouble(0);
            if (Double.isFinite(rate) && rate > 0) return rate;
        }
        return 0;
    }

    /** Keeps the last positive hashrate briefly visible when the API momentarily reports 0. */
    public static double displayedHashrate(double reported, double previous, Instant lastPositive, Instant now) {
        if (Double.isFinite(reported) && reported > 0) return reported;
        return previous > 0 && lastPositive != null && !now.isAfter(lastPositive.plus(HASHRATE_GRACE)) ? previous : 0;
    }

    /** Marks one worker run failed; the adapter owns lastError and console policy. */
    protected abstract boolean fail(WorkerRunState run, String error);
}
