package de.verdox.solarminer.pcagent.support;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcessTerminationTest {
    @Test void waitsForGracefulExitInsteadOfForcingAfterFiftyMilliseconds() {
        ProcessHandle process = mock(ProcessHandle.class);
        when(process.pid()).thenReturn(100L);
        when(process.descendants()).thenAnswer(ignored -> java.util.stream.Stream.empty());
        long[] signalled = {Long.MAX_VALUE};
        doAnswer(ignored -> { signalled[0] = System.nanoTime(); return true; }).when(process).destroy();
        when(process.isAlive()).thenAnswer(ignored -> System.nanoTime() - signalled[0] < Duration.ofMillis(100).toNanos());
        assertTrue(ProcessTermination.terminate(List.of(process), Duration.ofSeconds(1)));
        verify(process, never()).destroyForcibly();
    }

    @Test void retainsChildrenAfterParentExitAndWaitsForForcedCompletion() {
        ProcessHandle root = mock(ProcessHandle.class), child = mock(ProcessHandle.class);
        when(root.pid()).thenReturn(101L); when(child.pid()).thenReturn(102L);
        AtomicBoolean rootAlive = new AtomicBoolean(true), childAlive = new AtomicBoolean(true);
        when(root.isAlive()).thenAnswer(ignored -> rootAlive.get());
        when(child.isAlive()).thenAnswer(ignored -> childAlive.get());
        when(root.descendants()).thenAnswer(ignored -> rootAlive.get() ? java.util.stream.Stream.of(child) : java.util.stream.Stream.empty());
        when(child.descendants()).thenAnswer(ignored -> java.util.stream.Stream.empty());
        doAnswer(ignored -> { rootAlive.set(false); return true; }).when(root).destroy();
        doAnswer(ignored -> { childAlive.set(false); return true; }).when(child).destroyForcibly();
        assertTrue(ProcessTermination.terminate(List.of(root), Duration.ofMillis(50)));
        verify(child).destroyForcibly();
        assertFalse(childAlive.get());
    }

    @Test void interruptionForcesTheOwnedTreeAndPreservesTheInterrupt() {
        ProcessHandle root = mock(ProcessHandle.class);
        when(root.pid()).thenReturn(103L);
        when(root.descendants()).thenAnswer(ignored -> java.util.stream.Stream.empty());
        when(root.isAlive()).thenReturn(true);
        Thread.currentThread().interrupt();
        try {
            assertFalse(ProcessTermination.terminate(List.of(root), Duration.ofSeconds(1)));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(root).destroyForcibly();
        } finally { Thread.interrupted(); }
    }
}
