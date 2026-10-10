package de.verdox.solarminer.pcagent.miner;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;

import java.util.List;

/**
 * The common lifecycle contract every coin miner fulfils. Orchestration code (power budgets,
 * worker assignment, benchmark/sweep, fee guard) talks only to this interface; the concrete
 * binary, parameters and APIs stay inside the adapters.
 */
public interface CoinMiner {
    Coin coin();

    /** True when a local coin configuration exists (planning semantics). */
    boolean configured();

    /** Operator-facing readiness: this coin's saved route matches the currently selected proxy. */
    boolean routeConfigured();

    /**
     * What the operator overview shows as "configured". Route-owning adapters (CPU, Pearl)
     * report {@link #routeConfigured()}; the shared SRB adapter counts a saved configuration.
     */
    default boolean setupComplete() { return routeConfigured(); }

    boolean binaryAvailable();

    boolean running();

    boolean hasExternalMinerProcess();

    /** Most recent adapter-local start/health diagnostic. */
    String lastError();

    MinerStats.MinerStatus status();

    List<MinerStats.Worker> workerStats(List<LocalGpuPowerService.Gpu> cards);

    /** Start every selected worker of this coin. */
    boolean startAll();

    /** Stop the coin's processes without recording a manual pause. */
    boolean stopAll();

    /** Stop the coin's processes and mark them manually paused until resumed. */
    boolean pauseAll();

    /**
     * Mechanically apply a power target for this coin (CPU thread budget or GPU power cap).
     * It never starts or stops processes; the orchestrator owns that ordering.
     */
    boolean setPowerCap(long watts);

    /**
     * Re-points this coin's saved miner route at the shared proxy endpoint after the
     * operator changed the proxy connection. Coins without a saved route stay untouched.
     */
    boolean updateProxyRoute(String proxyUrl) throws java.io.IOException;

    /** Mirror an orchestration event into this coin's operator consoles. */
    void appendBenchmarkEvent(String message);
}
