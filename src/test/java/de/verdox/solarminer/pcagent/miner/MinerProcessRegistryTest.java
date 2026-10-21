package de.verdox.solarminer.pcagent.miner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
class MinerProcessRegistryTest {
    @Test void externalProcessCheckWaitsUntilAVisibleSiblingIsRegistered() throws Exception {
        var visible = new CompletableFuture<Process>();
        var allowRegistration = new CountDownLatch(1);
        Process process = null;
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var started = executor.submit(() -> MinerProcessRegistry.startGpu("GPU-visible-before-registration", () -> {
                Process child = new ProcessBuilder("sleep", "60").start();
                visible.complete(child);
                try { if (!allowRegistration.await(5, TimeUnit.SECONDS)) throw new IOException("test registration timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                return child;
            }));
            process = visible.get(5, TimeUnit.SECONDS);
            var handle = process.toHandle();
            var checking = new CountDownLatch(1);
            var external = executor.submit(() -> { checking.countDown(); return MinerProcessRegistry.unmanagedGpuCoin(handle); });
            assertTrue(checking.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> external.get(100, TimeUnit.MILLISECONDS),
                    "An adapter cannot classify the in-flight managed spawn as external");
            allowRegistration.countDown();
            assertSame(process, started.get(5, TimeUnit.SECONDS));
            assertFalse(external.get(5, TimeUnit.SECONDS));
            MinerProcessRegistry.unregisterGpuCoin(process);
            assertTrue(MinerProcessRegistry.unmanagedGpuCoin(handle), "A genuinely unregistered live process stays blocked");
            process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertFalse(MinerProcessRegistry.unmanagedGpuCoin(handle), "An exited snapshot is not an external miner");
        } finally {
            allowRegistration.countDown();
            if (process != null) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); MinerProcessRegistry.unregisterGpuCoin(process); }
            executor.close();
        }
    }

    @Test void staleExitCannotReleaseAnotherProcessWithTheSamePid() {
        Process old = org.mockito.Mockito.mock(Process.class);
        Process replacement = org.mockito.Mockito.mock(Process.class);
        org.mockito.Mockito.when(old.pid()).thenReturn(987654L);
        org.mockito.Mockito.when(replacement.pid()).thenReturn(987654L);
        org.mockito.Mockito.when(replacement.isAlive()).thenReturn(true);
        MinerProcessRegistry.registerGpuCoin(old, "GPU-reused-pid");
        MinerProcessRegistry.registerGpuCoin(replacement, "GPU-reused-pid");
        try {
            MinerProcessRegistry.unregisterGpuCoin(old);
            assertTrue(MinerProcessRegistry.gpuCoinUsesDevice("GPU-reused-pid"));
        } finally { MinerProcessRegistry.unregisterGpuCoin(replacement); }
    }

    @Test void concurrentAdaptersCannotLaunchOnTheSamePhysicalGpu() throws Exception {
        var launched = new CopyOnWriteArrayList<Process>();
        AtomicInteger starts = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) futures.add(executor.submit(() -> {
                ready.countDown(); go.await();
                try {
                    MinerProcessRegistry.startGpu("GPU-registry-race", () -> {
                        starts.incrementAndGet();
                        Process process = new ProcessBuilder("sleep", "60").start();
                        launched.add(process);
                        return process;
                    });
                    return true;
                } catch (IOException busy) {
                    assertEquals("worker_device_busy", busy.getMessage());
                    return false;
                }
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(5, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(1, starts.get());
            assertTrue(MinerProcessRegistry.gpuCoinUsesDevice("GPU-registry-race"));
            Process sibling = MinerProcessRegistry.startGpu("GPU-registry-sibling", () -> new ProcessBuilder("sleep", "60").start());
            launched.add(sibling);
            Process first = launched.getFirst(); first.destroyForcibly();
            assertTrue(first.waitFor(5, TimeUnit.SECONDS));
            assertFalse(MinerProcessRegistry.gpuCoinUsesDevice("GPU-registry-race"));
            assertTrue(sibling.isAlive());
            Process replacement = MinerProcessRegistry.startGpu("GPU-registry-race", () -> new ProcessBuilder("sleep", "60").start());
            launched.add(replacement);
            assertTrue(replacement.isAlive());
        } finally {
            for (Process process : launched) {
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
                MinerProcessRegistry.unregisterGpuCoin(process);
            }
        }
    }
}
