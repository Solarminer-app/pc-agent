package de.verdox.solarminer.pcagent.coin;

/** Stable identifiers used in worker assignment settings. */
public final class WorkerIds {
    /** The virtual worker id representing the local CPU. */
    public static final String CPU = "cpu";
    /** Legacy wildcard key covering every GPU worker in pre-profile settings files. */
    public static final String WILDCARD = "*";

    private WorkerIds() { }

    public static boolean isCpu(String workerId) { return CPU.equals(workerId); }
}
