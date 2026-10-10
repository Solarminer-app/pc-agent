package de.verdox.solarminer.pcagent.coin;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The closed set of coins the PC-Agent knows about. Every coin identity, algorithm name and
 * hardware rule lives here; orchestration code must never compare raw coin strings.
 * Coin-specific runtime behaviour (parameters, APIs, commands) belongs to the miner adapters
 * in {@code de.verdox.solarminer.pcagent.miner}, not to this enum.
 */
public enum Coin {
    NONE("none", "Nicht zugewiesen", null, null, DeviceKind.NONE, false, null),
    MONERO("monero", "Monero", "XMR", "randomx", DeviceKind.CPU, false, "RandomX"),
    PEARL("pearl", "Pearl", "PRL", "pearlhash", DeviceKind.GPU, false, "PearlHash"),
    RAVENCOIN("ravencoin", "Ravencoin", "RVN", "kawpow", DeviceKind.GPU, true, "KAWPOW"),
    ETHEREUMCLASSIC("ethereumclassic", "Ethereum Classic", "ETC", "etchash", DeviceKind.GPU, true, "ETCHash"),
    DECRED("decred", "Decred", "DCR", "blake3_decred", DeviceKind.GPU, true, "BLAKE3", "blake3"),
    QUANTUS("quantus", "Quantus", "QTC", "quantus", DeviceKind.GPU, true, "QPoW (Poseidon2)", "qpow");

    /** Algorithm names as reported by worker telemetry, mapped back to their coin. */
    private static final Map<String, Coin> BY_ALGORITHM;
    private static final Map<String, Coin> BY_ID;

    static {
        Map<String, Coin> algorithms = new LinkedHashMap<>();
        Map<String, Coin> ids = new LinkedHashMap<>();
        for (Coin coin : values()) {
            ids.put(coin.id, coin);
            if (coin.algorithm != null) algorithms.put(coin.algorithm, coin);
            for (String alias : coin.algorithmAliases) algorithms.put(alias, coin);
        }
        BY_ALGORITHM = Map.copyOf(algorithms);
        BY_ID = Map.copyOf(ids);
    }

    private final String id;
    private final String displayName;
    private final String ticker;
    private final String algorithm;
    private final String displayAlgorithm;
    private final boolean experimental;
    private final String[] algorithmAliases;
    private final DeviceKind deviceKind;

    Coin(String id, String displayName, String ticker, String algorithm, DeviceKind deviceKind,
         boolean experimental, String displayAlgorithm, String... algorithmAliases) {
        this.id = id;
        this.displayName = displayName;
        this.ticker = ticker;
        this.algorithm = algorithm;
        this.experimental = experimental;
        this.displayAlgorithm = displayAlgorithm;
        this.deviceKind = deviceKind;
        this.algorithmAliases = algorithmAliases;
    }

    /** Stable wire identifier used in persisted files, REST payloads and worker settings. */
    public String id() { return id; }

    /** Operator-facing coin label. */
    public String displayName() { return displayName; }

    public String ticker() { return ticker; }

    /** Canonical lowercase algorithm id as miners report it. */
    public String algorithm() { return algorithm; }

    /** Algorithm label as the worker UI has always shown it (case preserved). */
    public String displayAlgorithm() { return displayAlgorithm; }

    /**
     * Algorithm label shown for a worker assigned to this coin. Historical contract:
     * CPU/Pearl use display names, GPU coins report their lowercase miner algorithm.
     */
    public String assignmentAlgorithm() {
        return isGpu() && this != PEARL ? algorithm : displayAlgorithm;
    }

    /** True while the coin's end-to-end route is still experimental in the operator UI. */
    public boolean experimental() { return experimental; }

    public DeviceKind deviceKind() { return deviceKind; }

    /** Device label used by the miner catalog ("CPU" / "GPU"). */
    public String deviceLabel() {
        return switch (deviceKind) {
            case CPU -> "CPU";
            case GPU -> "GPU";
            case NONE -> null;
        };
    }
    public boolean isGpu() { return deviceKind == DeviceKind.GPU; }
    public boolean isCpu() { return deviceKind == DeviceKind.CPU; }

    /**
     * True for GPU coins whose processes are managed by the shared SRBMiner adapter;
     * Pearl runs through its own adapter despite sharing the binary installation.
     */
    public boolean isSharedSrbCoin() { return isGpu() && this != PEARL; }

    /** GPU coins served by the shared SRBMiner adapter, in registration order. */
    public static List<Coin> sharedSrbCoins() {
        return Arrays.stream(values()).filter(Coin::isSharedSrbCoin).toList();
    }

    public static Optional<Coin> fromId(String value) {
        if (value == null) return Optional.empty();
        return Optional.ofNullable(BY_ID.get(value.toLowerCase(Locale.ROOT)));
    }

    public static Coin byIdOrNull(String value) { return fromId(value).orElse(null); }

    /** Resolves a miner-reported algorithm name (any case, including aliases) to its coin. */
    public static Optional<Coin> byAlgorithm(String value) {
        if (value == null) return Optional.empty();
        return Optional.ofNullable(BY_ALGORITHM.get(value.toLowerCase(Locale.ROOT)));
    }

    /** True when a worker row reporting {@code algorithm} belongs to this coin. */
    public boolean matchesAlgorithm(String algorithm) {
        return byAlgorithm(algorithm).map(this::equals).orElse(false);
    }

    /** All coins that can be actively mined, in registration order (excludes {@link #NONE}). */
    public static List<Coin> miningCoins() {
        return Arrays.stream(values()).filter(coin -> coin != NONE).toList();
    }

    /** All GPU-mineable coins, in registration order. */
    public static List<Coin> gpuCoins() {
        return Arrays.stream(values()).filter(Coin::isGpu).toList();
    }

    /** Local rule: which coins a CPU/GPU worker may be assigned at all. */
    public boolean assignableTo(boolean cpuWorker) {
        return this == NONE || (cpuWorker ? isCpu() : isGpu());
    }

    public enum DeviceKind { CPU, GPU, NONE }
}
