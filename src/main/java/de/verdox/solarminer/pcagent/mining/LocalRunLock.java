package de.verdox.solarminer.pcagent.mining;

import org.springframework.stereotype.Service;

/**
 * One local hardware-measurement run (benchmark session or efficiency sweep) at a time.
 * Node controls are refused while the lock is held so a remote decision can never race a
 * measurement that is pausing miners and rewriting GPU power limits.
 */
@Service
public class LocalRunLock {
    private volatile String holder;

    public synchronized boolean tryBegin(String name) {
        if (holder != null) return false;
        holder = name;
        return true;
    }

    public synchronized void end() {
        holder = null;
    }

    public boolean busy() {
        return holder != null;
    }

    public String holder() {
        return holder;
    }
}
