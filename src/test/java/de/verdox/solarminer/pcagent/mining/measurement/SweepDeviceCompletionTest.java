package de.verdox.solarminer.pcagent.mining.measurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.*;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.*;
import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real asynchronous queue; only the slow physical measurement is replaced. */
class SweepDeviceCompletionTest {
    @TempDir Path directory;
    @Test void completedGpuRestoresAndStartsBeforeSiblingAndIsNeverTouchedAgain() throws Exception {
        exercise(false, false);
    }
    @Test void laterCancellationDoesNotStopAnAlreadyReleasedGpu() throws Exception {
        exercise(true, false);
    }
    @Test void repeatedOperatorStopWinsOverRequestedAutoStart() throws Exception {
        exercise(false, true);
    }
    void exercise(boolean cancel, boolean repeatStop) throws Exception {
        var cards = List.of(gpu(0), gpu(1));
        var factory = mock(MinerFactory.class); var miner = mock(GpuCoinMiner.class);
        var other = mock(GpuCoinMiner.class);
        when(factory.gpuMiner(any(Coin.class))).thenReturn(other);
        when(other.stopGpu(anyString(), anyInt())).thenReturn(true);
        when(factory.gpuMinerAdapters(any())).thenReturn(List.of());
        when(factory.gpuMinerAdapters(Coin.PEARL)).thenReturn(List.of(new MinerFactory.GpuMinerAdapter("srbminer-multi", miner)));
        when(factory.gpuMiner(Coin.PEARL, "srbminer-multi")).thenReturn(miner);
        when(miner.binaryAvailable()).thenReturn(true); when(miner.stopGpu(anyString(), anyInt())).thenReturn(true);
        when(miner.configuration()).thenReturn(new MinerConfig("pool", "proxy", "wallet", "worker", "NVIDIA:0,NVIDIA:1"));
        when(miner.selectedGpus()).thenReturn(cards); when(miner.reportedAlgorithm()).thenReturn("PearlHash");
        var power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(cards); when(power.refresh()).thenReturn(cards);
        when(power.restorePowerLimits(anyMap())).thenReturn(true);
        var controls = new AgentControlSettingsService(new ObjectMapper(), directory.resolve("settings.json").toString());
        controls.setWorkerManuallyStopped("gpu0", true);
        var workers = mock(WorkerAssignmentService.class); var started = new CountDownLatch(1);
        when(workers.startWithHeldRunLock(anyString())).thenAnswer(call -> {
            assertFalse(MinerStartContext.measurementRequested());
            assertTrue(MinerStartContext.savedConfigurationRequested());
            assertFalse(controls.get().manuallyStopped(call.getArgument(0)));
            if ("gpu0".equals(call.getArgument(0))) started.countDown(); return true;
        });
        var lock = new LocalRunLock();
        var service = spy(new EfficiencySweepService(factory, power, mock(MinerConsoleService.class), mock(PayoutDefaultsService.class),
                mock(ProxyConfigurationService.class), mock(GpuEfficiencyStore.class), mock(BenchmarkSharingService.class), lock, 15, 85, .1, 240));
        service.setControls(controls); service.setAssignments(workers);
        var siblingMeasuring = new CountDownLatch(1); var finishSibling = new CountDownLatch(1);
        doAnswer(call -> {
            EfficiencySweepService.Target target = call.getArgument(0);
            if ("gpu1".equals(target.gpu().deviceId())) { siblingMeasuring.countDown(); assertTrue(finishSibling.await(5, TimeUnit.SECONDS)); }
            else { assertTrue(siblingMeasuring.await(5, TimeUnit.SECONDS)); if (repeatStop) controls.setWorkerManuallyStopped("gpu0", true); }
            service.finishRun(target, SweepRunState.COMPLETE, "lab_finished");
            return new GpuEfficiencyStore.Profile(target.gpu().deviceId(), "RTX 3080", "pearl", "PearlHash", 120, 1000d, 100d, 10d,
                    Instant.now(), List.of(de.verdox.solarminer.pcagent.mining.measurement.ThermalTestEvidence.step(120, 1000d, 100d, 60d, true, null)), "srbminer-multi");
        }).when(service).sweepTarget(any(), any(), any());
        try {
            service.startConfigured(false, List.of("pearl"), List.of("gpu0", "gpu1"), 2, false, "QUICK", true);
            assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
                while (service.status().devices().stream().noneMatch(row -> row.deviceId().equals("gpu0"))) Thread.sleep(5);
            });
            assertTrue(service.status().running(), "Sibling is still measuring");
            assertEquals("efficiency-sweep", lock.holder());
            var completed = service.status().devices().stream().filter(row -> row.deviceId().equals("gpu0")).findFirst().orElseThrow();
            assertTrue(completed.released());
            assertEquals(repeatStop ? "FAILED" : "STARTED", completed.miningState());
            if (repeatStop) verify(workers, never()).startWithHeldRunLock("gpu0");
            else assertEquals(0, started.getCount());
            verify(power).restorePowerLimits(Map.of("gpu0", 150));
            int before = (int) mockingDetails(miner).getInvocations().stream().filter(row -> row.getMethod().getName().equals("stopGpu") && Integer.valueOf(0).equals(row.getArgument(1))).count();
            if (cancel) service.cancel();
            finishSibling.countDown();
            assertTimeoutPreemptively(Duration.ofSeconds(4), () -> { while (service.status().running()) Thread.sleep(5); });
            verify(power, times(1)).restorePowerLimits(Map.of("gpu0", 150));
            int after = (int) mockingDetails(miner).getInvocations().stream().filter(row -> row.getMethod().getName().equals("stopGpu") && Integer.valueOf(0).equals(row.getArgument(1))).count();
            assertEquals(before, after, "Session cleanup cannot stop the released GPU again");
            verify(miner, never()).restoreState(any()); assertFalse(lock.busy());
        } finally { finishSibling.countDown(); service.close(); }
    }
    @Test void expiredQuickBudgetDoesNotStartAnotherMeasurementOrPublishACompleteCurve() throws Exception {
        var card = gpu(0); var factory = mock(MinerFactory.class); var miner = mock(GpuCoinMiner.class);
        when(factory.gpuMiner(any(Coin.class))).thenReturn(miner);
        when(factory.gpuMinerAdapters(any())).thenReturn(List.of());
        when(factory.gpuMinerAdapters(Coin.PEARL)).thenReturn(List.of(new MinerFactory.GpuMinerAdapter("srbminer-multi", miner)));
        when(factory.gpuMiner(Coin.PEARL, "srbminer-multi")).thenReturn(miner);
        when(miner.configuration()).thenReturn(new MinerConfig("pool", "proxy", "wallet", "worker", "NVIDIA:0"));
        when(miner.selectedGpus()).thenReturn(List.of(card)); when(miner.binaryAvailable()).thenReturn(true);
        when(miner.reportedAlgorithm()).thenReturn("PearlHash"); when(miner.stopGpu(anyString(), anyInt())).thenReturn(true);
        var power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(card)); when(power.refresh()).thenReturn(List.of(card));
        when(power.restorePowerLimits(anyMap())).thenReturn(true);
        var store = mock(GpuEfficiencyStore.class); var workers = mock(WorkerAssignmentService.class);
        var service = spy(new EfficiencySweepService(factory, power, mock(MinerConsoleService.class), mock(PayoutDefaultsService.class),
                mock(ProxyConfigurationService.class), store, mock(BenchmarkSharingService.class), new LocalRunLock(), 15, 85, .1, 240));
        service.setAssignments(workers);
        doAnswer(call -> {
            @SuppressWarnings("unchecked") var deadline = (ThreadLocal<Long>) org.springframework.test.util.ReflectionTestUtils.getField(service, "taskDeadline");
            deadline.set(System.nanoTime() - 1); // Advance the real task past its budget, without an 18-minute sleep.
            return call.callRealMethod();
        }).when(service).sweepTarget(any(), any(), any());
        try {
            service.startConfigured(false, List.of("pearl"), List.of("gpu0"), 1, false, "QUICK", true);
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> { while (service.status().running()) Thread.sleep(5); });
            assertEquals(SweepRunState.CANCELLED, service.status().runs().getFirst().status());
            assertEquals("lab_budget_reached", service.status().runs().getFirst().detail());
            verify(miner, never()).resumeGpu(anyString(), anyInt()); verify(store, never()).save(any());
            verify(workers, never()).startWithHeldRunLock(anyString());
            assertTrue(service.status().devices().getFirst().released());
        } finally { service.close(); }
    }

    static LocalGpuPowerService.Gpu gpu(int i) {
        return new LocalGpuPowerService.Gpu("NVIDIA", i, "gpu" + i, "RTX 3080", 100, 200, 100, 200, 150, 60d, "test", true, null, 150);
    }
}
