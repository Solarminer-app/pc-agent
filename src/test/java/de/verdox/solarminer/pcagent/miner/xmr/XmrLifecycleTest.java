package de.verdox.solarminer.pcagent.miner.xmr;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.lowlevel.sensor.HardwareSensorReader;
import de.verdox.solarminer.pcagent.miner.*;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XmrLifecycleTest {
    @Test void unmanagedCpuMinerCannotCountAsAStartedManagedWorkerAndIsNeverKilled() {
        XmrMinerService service = service();
        ProcessHandle external = mock(ProcessHandle.class);
        try (var registry = mockStatic(MinerProcessRegistry.class)) {
            registry.when(() -> MinerProcessRegistry.running("xmrig")).thenReturn(List.of(external));
            assertFalse(MinerStartContext.operator(service::startAll));
            assertEquals("xmrig_external_miner_blocks_start", service.lastError());
            service.stopAll();
            registry.verify(() -> MinerProcessRegistry.stop("xmrig"), never());
            verify(external, never()).destroy();
            verify(external, never()).destroyForcibly();
        }
    }

    @Test void explicitStopDisarmsCpuRecoveryEvenWhenAnOldErrorArrivesLater() {
        XmrMinerService service = service();
        service.setWorkerStartPolicy(id -> false);
        assertFalse(MinerStartContext.operator(service::startAll));
        assertEquals("worker_manually_stopped", service.lastError());
        service.hardStopMining();
        ReflectionTestUtils.setField(service, "minerStatus", de.verdox.solarminer.pcagent.dto.MinerStats.MinerStatus.ERROR);
        ReflectionTestUtils.setField(service, "desiredPowerUsage", 100L);
        service.restartAfterUnexpectedExit();
        assertEquals(0, ReflectionTestUtils.getField(service, "crashRestartAttempts"));
    }

    @Test void exitFromAnOldCpuProcessCannotMarkItsReplacementAsFailed() {
        XmrMinerService service = service();
        Process old = mock(Process.class), replacement = mock(Process.class);
        ReflectionTestUtils.setField(service, "minerProcess", replacement);
        ReflectionTestUtils.setField(service, "crashRecoveryAllowed", true);
        ReflectionTestUtils.setField(service, "minerStatus", de.verdox.solarminer.pcagent.dto.MinerStats.MinerStatus.MINING);
        ReflectionTestUtils.setField(service, "currentHashesPerSecond", 123L);
        ReflectionTestUtils.invokeMethod(service, "processExited", old);
        assertEquals(de.verdox.solarminer.pcagent.dto.MinerStats.MinerStatus.MINING,
                ReflectionTestUtils.getField(service, "minerStatus"));
        assertEquals(123L, ReflectionTestUtils.getField(service, "currentHashesPerSecond"));
        assertNull(service.lastError());
    }

    @Test void cpuRecoveryRespectsMeasurementsAndAnExplicitStartWithoutAWattTarget() {
        XmrMinerService service = service();
        var lock = new de.verdox.solarminer.pcagent.mining.measurement.LocalRunLock();
        service.setLocalRuns(lock);
        ReflectionTestUtils.setField(service, "crashRecoveryAllowed", true);
        ReflectionTestUtils.setField(service, "minerStatus", de.verdox.solarminer.pcagent.dto.MinerStats.MinerStatus.ERROR);
        assertTrue(lock.tryBegin("benchmark"));
        service.restartAfterUnexpectedExit();
        assertEquals(0, ReflectionTestUtils.getField(service, "crashRestartAttempts"));
        lock.end();
        service.restartAfterUnexpectedExit();
        assertEquals(1, ReflectionTestUtils.getField(service, "crashRestartAttempts"));
    }

    private XmrMinerService service() {
        return new XmrMinerService(mock(XmrConfigService.class), new ObjectMapper(), mock(HardwareSensorReader.class),
                mock(ProxyConfigurationService.class), mock(MinerConsoleService.class), mock(PayoutDefaultsService.class),
                mock(WindowsHugePagesService.class));
    }
}
