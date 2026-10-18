package de.verdox.solarminer.pcagent.miner.diagnostics;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Maps raw miner log lines to operator-facing failure categories. Rules are keyed by miner
 * family, so a future miner plugs in by registering its own rules; orchestration (benchmark,
 * efficiency sweep, dashboards) only ever sees the {@link Category} and the detail text, never
 * a miner-specific string. Detail texts are agent-authored English and translate through the
 * closed frontend agent catalog.
 */
public final class MinerLogDiagnostics {

    public enum Category {
        /** The pinned miner build has no working kernel for this GPU architecture + algorithm. */
        GPU_ALGORITHM_UNSUPPORTED,
        /** The GPU driver rejected the miner's CUDA/OpenCL context. */
        GPU_DRIVER_FAILURE,
        /** The miner cannot reach or stay connected to the pool. */
        POOL_CONNECTION,
        /** The OS or security software blocked the miner binary or its memory access. */
        BINARY_BLOCKED,
        UNKNOWN
    }

    public record Diagnosis(Category category, String detail) { }

    private record Rule(String minerFamily, Pattern pattern, Category category, String detail) { }

    /**
     * The GPU_ALGORITHM_UNSUPPORTED rule covers SRBMiner's kernel-context failures such as
     * "couldn't create program for ctx->algorithm" and "Could not set up ctx -> algorithm" —
     * the classic symptom of an algorithm whose kernels do not exist for an older GPU
     * architecture (for example PearlHash on Pascal) in the pinned miner release.
     */
    private static final List<Rule> RULES = List.of(
            new Rule("srbminer",
                    Pattern.compile("(?i)(couldn'?t create program|could not set up ctx).*algorithm"),
                    Category.GPU_ALGORITHM_UNSUPPORTED,
                    "Diagnosis: this GPU cannot run the selected algorithm with the installed miner build; the miner has no working kernel for this GPU architecture. Check the miner's supported-GPU matrix, update the miner, or exclude this coin for this card in the benchmark selection."),
            new Rule("srbminer",
                    Pattern.compile("(?i)(error|failed|unable).{0,40}(cuda|opencl)"),
                    Category.GPU_DRIVER_FAILURE,
                    "Diagnosis: the GPU driver rejected the miner's CUDA/OpenCL context. Reinstall or update the GPU driver, then retry."),
            new Rule("srbminer",
                    Pattern.compile("(?i)could not connect to pool|pool (connection |connect )?(failed|error|timeout)"),
                    Category.POOL_CONNECTION,
                    "Diagnosis: the miner could not reach the pool. Check the SolarMiner proxy, the pool address and the network."),
            new Rule("xmrig",
                    Pattern.compile("(?i)(use of huge pages failed|mlock failed)"),
                    Category.BINARY_BLOCKED,
                    "Diagnosis: XMRig could not lock memory. Grant huge-page permissions to the agent or disable huge pages in the miner configuration."),
            new Rule("xmrig",
                    Pattern.compile("(?i)error while loading shared libraries|cannot execute binary"),
                    Category.BINARY_BLOCKED,
                    "Diagnosis: the miner binary could not be executed. Reinstall the miner from the miner library."),
            new Rule("xmrig",
                    Pattern.compile("(?i)(can'?t|cannot) connect to pool|failed to init pool connection"),
                    Category.POOL_CONNECTION,
                    "Diagnosis: XMRig could not reach the pool. Check the SolarMiner proxy, the pool address and the network."),
            new Rule("quanpool",
                    Pattern.compile("(?i)(unsupported[_ ]ptx|cuda.{0,40}(error|failed)|no cuda devices)"),
                    Category.GPU_DRIVER_FAILURE,
                    "Diagnosis: QuanPool Miner could not initialize CUDA. Check the NVIDIA driver and the miner's supported-GPU matrix."),
            new Rule("quanpool",
                    Pattern.compile("(?i)(failed to connect|connection refused|reconnecting|timed out)"),
                    Category.POOL_CONNECTION,
                    "Diagnosis: QuanPool Miner could not reach the pool. Check the SolarMiner proxy and the network."),
            new Rule("peakminer", Pattern.compile("(?i)(unsupported gpu|no compatible gpu|kernel.*unsupported|cuda.*failed)"),
                    Category.GPU_ALGORITHM_UNSUPPORTED,
                    "Diagnosis: PeakMiner could not initialize PearlHash on this GPU. Check the GPU and driver requirements."),
            new Rule("peakminer", Pattern.compile("(?i)(failed to connect|connection refused|pool.*timed out)"),
                    Category.POOL_CONNECTION,
                    "Diagnosis: PeakMiner could not reach the SolarMiner proxy or pool."),
            new Rule("wildrig-ng", Pattern.compile("(?i)(no usable gpu|unsupported gpu|kernel.*unsupported|cuda.*failed)"),
                    Category.GPU_ALGORITHM_UNSUPPORTED,
                    "Diagnosis: WildRig NG could not initialize Quantus on this GPU. Check the GPU and driver requirements."),
            new Rule("wildrig-ng", Pattern.compile("(?i)(connect failed|connection refused|pool unreachable|timed out)"),
                    Category.POOL_CONNECTION,
                    "Diagnosis: WildRig NG could not reach the SolarMiner proxy or pool."));

    /** Miner families the classifier knows; adapters pass their own family id at start. */
    public static final String SRBMINER = "srbminer";
    public static final String XMRIG = "xmrig";
    public static final String QUANPOOL = "quanpool";
    public static final String PEAKMINER = "peakminer";
    public static final String WILDRIG_NG = "wildrig-ng";

    public static Optional<Diagnosis> classify(String minerFamily, String line) {
        if (minerFamily == null || line == null || line.isBlank()) return Optional.empty();
        for (Rule rule : RULES) {
            if (rule.minerFamily().equals(minerFamily) && rule.pattern().matcher(line).find())
                return Optional.of(new Diagnosis(rule.category(), rule.detail()));
        }
        return Optional.empty();
    }
}
