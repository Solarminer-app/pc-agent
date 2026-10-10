package de.verdox.solarminer.pcagent.coin;

/**
 * Installation lifecycle of a downloadable miner binary and of the managed proxy release.
 * The wire names are part of the local REST contract with the operator UI and must never change.
 */
public enum DownloadState {
    PENDING("PENDING"),
    CHECKING("CHECKING"),
    DOWNLOADING("DOWNLOADING"),
    READY("READY"),
    FAILED("FAILED"),
    UNSUPPORTED("UNSUPPORTED"),
    BLOCKED_BY_ANTIVIRUS("BLOCKED_BY_ANTIVIRUS");

    private final String wireName;

    DownloadState(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }
}
