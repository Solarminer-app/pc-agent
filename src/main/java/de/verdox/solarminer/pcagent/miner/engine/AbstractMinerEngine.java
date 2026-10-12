package de.verdox.solarminer.pcagent.miner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.miner.MinerConsoleService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Common engine base for every miner adapter (refactoring Phase 3). Holds only what is
 * genuinely miner-independent: the console/mapper/logger accessors every engine needs, the
 * process-output drain loop (one reader thread, per-engine line handling in a callback) and
 * the bounded crash-restart budget that the XMRig watchdog and the SRB engines (O1) share.
 *
 * <p>Miner-family mechanics stay one level down: {@link AbstractSrbMinerEngine} carries the
 * SRBMiner monitor loop, stop escalation and hashrate helpers; XMRig keeps its JSON config,
 * thread/TDP heuristics and API polling engine-internal on purpose.</p>
 */
public abstract class AbstractMinerEngine {
    /** Bounded crash-restart attempts per worker (O1; identical bound across all engines). */
    public static final int MAX_CRASH_RESTARTS = 3;

    protected abstract MinerConsoleService console();
    protected abstract ObjectMapper mapper();
    protected abstract Logger logger();

    /** True while a worker still has crash-restart attempts left (shared O1 bound). */
    protected static boolean restartBudgetLeft(int attempts) {
        return attempts < MAX_CRASH_RESTARTS;
    }

    /**
     * Reads the process stdout/stderr line-by-line on a dedicated virtual thread and hands
     * every line to {@code onLine}; stream failures go to {@code onError}. The engines own
     * what a line means (console mirroring, redaction, diagnosis classification) — the loop
     * itself exists exactly once.
     */
    protected Thread drainProcessOutput(Process process, String threadName,
                                        Consumer<String> onLine, Consumer<Exception> onError) {
        return Thread.ofVirtual().name(threadName).start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) onLine.accept(line);
            } catch (IOException | RuntimeException e) {
                onError.accept(e);
            }
        });
    }
}
