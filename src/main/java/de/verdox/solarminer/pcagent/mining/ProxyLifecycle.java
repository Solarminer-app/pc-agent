package de.verdox.solarminer.pcagent.mining;

/**
 * Lifecycle of the managed local proxy child process and its startup gate. The wire names
 * are part of the local REST contract (proxy gate and dashboard) and must never change.
 */
public enum ProxyLifecycle {
    EXTERNAL("external"),
    STARTING("starting"),
    RUNNING("running"),
    CHECKING("checking"),
    DOWNLOADING("downloading"),
    FAILED("failed");

    private final String wireName;

    ProxyLifecycle(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }
}
