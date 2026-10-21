package de.verdox.solarminer.pcagent.miner;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Cross-platform view and shutdown guard for miner processes, including processes started elsewhere. */
public final class MinerProcessRegistry {
    private record ManagedGpuProcess(Process process, String deviceId) { }
    private static final java.util.Map<Long, ManagedGpuProcess> GPU_PROCESSES = new java.util.concurrent.ConcurrentHashMap<>();
    private MinerProcessRegistry() { }

    @FunctionalInterface
    public interface ProcessStarter { Process start() throws java.io.IOException; }

    /** Serializes check + spawn + registration across all GPU miner families. */
    public static synchronized Process startGpu(String deviceId, ProcessStarter starter) throws java.io.IOException {
        if (gpuCoinUsesDevice(deviceId)) throw new java.io.IOException("worker_device_busy");
        Process process = starter.start();
        registerGpuCoin(process, deviceId);
        process.onExit().thenAccept(MinerProcessRegistry::unregisterGpuCoin);
        return process;
    }

    public static List<ProcessHandle> runningMiners() {
        return ProcessHandle.allProcesses().filter(ProcessHandle::isAlive)
                .filter(MinerProcessRegistry::isMiner).toList();
    }

    public static List<ProcessHandle> running(String miner) {
        return runningMiners().stream().filter(handle -> matches(handle, miner)).toList();
    }

    public static boolean matches(ProcessHandle handle, String miner) {
        return handle.info().command().map(command -> matchesExecutable(command, miner)).orElse(false)
                || handle.info().commandLine().map(command -> {
                    String first = command.strip().split("\\s+", 2)[0].replace("\"", "");
                    return matchesExecutable(first, miner);
                }).orElse(false);
    }

    private static boolean isMiner(ProcessHandle handle) {
        return matches(handle, "xmrig") || matches(handle, "srbminer") || matches(handle, "quanpool")
                || matches(handle, "peakminer") || matches(handle, "wildrig-ng");
    }

    private static boolean matchesExecutable(String value, String miner) {
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return switch (miner) {
            case "xmrig" -> name.equals("xmrig") || name.equals("xmrig.exe");
            case "srbminer" -> name.equals("srbminer-multi") || name.equals("srbminer-multi.exe");
            case "quanpool" -> name.equals("quanpool-miner") || name.matches("quanpool-miner-[0-9.]+(?:-linux-x86_64)?");
            case "peakminer" -> name.equals("peakminer") || name.equals("peakminer.exe");
            case "wildrig-ng" -> name.equals("wildrig-ng") || name.equals("wildrig-ng.exe");
            default -> false;
        };
    }

    /** Stop descendants first, then every miner process visible to this agent's OS account. */
    public static boolean stopAll() {
        return stop(runningMiners());
    }

    public static boolean stop(String miner) { return stop(running(miner)); }

    public static void registerGpuCoin(Process process) { registerGpuCoin(process, null); }

    public static void registerGpuCoin(Process process, String deviceId) {
        GPU_PROCESSES.put(process.pid(), new ManagedGpuProcess(process, deviceId));
    }

    public static boolean gpuCoinUsesDevice(String deviceId) {
        return GPU_PROCESSES.values().stream().anyMatch(managed -> managed.process().isAlive()
                && (managed.deviceId() == null || deviceId.equals(managed.deviceId())));
    }

    public static void unregisterGpuCoin(Process process) {
        // A delayed callback must not remove a new process if the OS has reused its PID.
        GPU_PROCESSES.computeIfPresent(process.pid(), (pid, managed) ->
                managed.process() == process ? null : managed);
    }

    public static boolean managedGpuCoin(ProcessHandle handle) {
        ManagedGpuProcess managed = GPU_PROCESSES.get(handle.pid());
        return managed != null && managed.process().isAlive();
    }
    /** Wait for an in-flight check/spawn/register before classifying a visible process. */
    public static synchronized boolean unmanagedGpuCoin(ProcessHandle handle) {
        return handle.isAlive() && !managedGpuCoin(handle);
    }

    public static boolean stopSrbExceptGpuCoins() {
        return stop(running("srbminer").stream().filter(MinerProcessRegistry::unmanagedGpuCoin).toList());
    }

    private static boolean stop(List<ProcessHandle> miners) {
        return de.verdox.solarminer.pcagent.support.ProcessTermination.terminate(miners, Duration.ofSeconds(3));
    }
}
