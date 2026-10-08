package de.verdox.solarminer.pcagent.mining;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Cross-platform view and shutdown guard for miner processes, including processes started elsewhere. */
public final class MinerProcessRegistry {
    private static final java.util.Set<Long> MANAGED_GPU_COIN_PIDS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Map<Long, String> GPU_COIN_DEVICES = new java.util.concurrent.ConcurrentHashMap<>();
    private MinerProcessRegistry() { }

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
        return matches(handle, "xmrig") || matches(handle, "srbminer");
    }

    private static boolean matchesExecutable(String value, String miner) {
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return switch (miner) {
            case "xmrig" -> name.equals("xmrig") || name.equals("xmrig.exe");
            case "srbminer" -> name.equals("srbminer-multi") || name.equals("srbminer-multi.exe");
            default -> false;
        };
    }

    /** Stop descendants first, then every miner process visible to this agent's OS account. */
    public static boolean stopAll() {
        return stop(runningMiners());
    }

    public static boolean stop(String miner) { return stop(running(miner)); }

    public static void registerGpuCoin(Process process) { MANAGED_GPU_COIN_PIDS.add(process.pid()); }
    public static void registerGpuCoin(Process process, String deviceId) {
        registerGpuCoin(process);
        GPU_COIN_DEVICES.put(process.pid(), deviceId);
    }
    public static boolean gpuCoinUsesDevice(String deviceId) {
        return running("srbminer").stream().filter(MinerProcessRegistry::managedGpuCoin)
                .anyMatch(handle -> deviceId.equals(GPU_COIN_DEVICES.get(handle.pid())) || !GPU_COIN_DEVICES.containsKey(handle.pid()));
    }
    public static void unregisterGpuCoin(Process process) {
        MANAGED_GPU_COIN_PIDS.remove(process.pid());
        GPU_COIN_DEVICES.remove(process.pid());
    }
    public static boolean managedGpuCoin(ProcessHandle handle) { return MANAGED_GPU_COIN_PIDS.contains(handle.pid()); }
    public static boolean stopSrbExceptGpuCoins() {
        return stop(running("srbminer").stream().filter(handle -> !managedGpuCoin(handle)).toList());
    }

    private static boolean stop(List<ProcessHandle> miners) {
        miners = new ArrayList<>(miners);
        for (ProcessHandle miner : miners) {
            miner.descendants().forEach(ProcessHandle::destroy);
            miner.destroy();
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        for (ProcessHandle miner : miners) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try { TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50))); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            if (miner.isAlive()) miner.destroyForcibly();
            miner.descendants().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        }
        return miners.stream().noneMatch(ProcessHandle::isAlive);
    }
}
