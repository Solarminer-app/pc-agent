package de.verdox.solarminer.pcagent.mining.measurement;

import org.springframework.stereotype.Service;

/**
 * One local hardware-measurement run (benchmark session or efficiency sweep) at a time.
 * Node controls are refused while the lock is held so a remote decision can never race a
 * measurement that is pausing miners and rewriting GPU power limits.
 */
@Service
public class LocalRunLock {
    private volatile String holder;
    private int autoTuningHolders;

    public synchronized boolean tryBegin(String name) {
        if (holder != null || autoTuningHolders > 0) return false;
        holder = name;
        return true;
    }

    public synchronized void end() {
        holder = null;
    }

    /**
     * Auto Tuning operates on distinct GPUs. Several sessions may therefore hold the
     * measurement lane while every other measurement remains exclusive.
     */
    public synchronized boolean tryBeginAutoTuning() {
        if (holder != null) return false;
        autoTuningHolders++;
        return true;
    }

    public synchronized void endAutoTuning() {
        if (autoTuningHolders > 0) autoTuningHolders--;
    }

    public boolean busy() {
        return holder != null || autoTuningHolders > 0;
    }

    public String holder() {
        return holder == null && autoTuningHolders > 0 ? "auto-tuning" : holder;
    }
}
