package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.mining.MiningService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperatingPointServiceTest {
    @Test
    void ordinaryMiningCreatesMeasuredTargetProfileAfterTwelveConsistentSamples() {
        MiningPerformanceProfileStore store = mock(MiningPerformanceProfileStore.class);
        OperatingPointService service = new OperatingPointService(mock(MiningService.class), mock(LocalRunLock.class), store);
        MinerStats.Worker worker = mock(MinerStats.Worker.class);
        when(worker.deviceId()).thenReturn("gpu-1");
        when(worker.hardwareType()).thenReturn("GPU");
        when(worker.hardwareModel()).thenReturn("RTX Test");
        when(worker.currentAlgorithm()).thenReturn("quantus");
        when(worker.miningStatus()).thenReturn(MinerStats.MinerStatus.MINING);
        when(worker.powerTargetWatts()).thenReturn(180L);
        when(worker.approximatedPowerUsageWatts()).thenReturn(170L);
        when(worker.terahashPerSecond()).thenReturn(0.000001);
        Instant start = Instant.parse("2026-10-16T10:00:00Z");
        for (int index = 0; index < 11; index++) service.accept(List.of(worker), start.plusSeconds(index * 5L));
        verify(store, never()).save(any());
        service.accept(List.of(worker), start.plusSeconds(55));
        var captor = org.mockito.ArgumentCaptor.forClass(MiningPerformanceProfileStore.Profile.class);
        verify(store).save(captor.capture());
        assertEquals("LIVE", captor.getValue().source());
        assertEquals(180, captor.getValue().powerTargetWatts());
        assertEquals("quantus", captor.getValue().coin());
    }

    @Test
    void ongoingMiningSavesANewProfileWhenThePowerTargetChanges() {
        MiningPerformanceProfileStore store = mock(MiningPerformanceProfileStore.class);
        OperatingPointService service = new OperatingPointService(mock(MiningService.class), mock(LocalRunLock.class), store);
        MinerStats.Worker worker = mock(MinerStats.Worker.class);
        when(worker.deviceId()).thenReturn("gpu-1");
        when(worker.hardwareType()).thenReturn("GPU");
        when(worker.hardwareModel()).thenReturn("RTX Test");
        when(worker.currentAlgorithm()).thenReturn("quantus");
        when(worker.miningStatus()).thenReturn(MinerStats.MinerStatus.MINING);
        when(worker.approximatedPowerUsageWatts()).thenReturn(170L);
        when(worker.terahashPerSecond()).thenReturn(0.000001);
        when(worker.powerTargetWatts()).thenReturn(180L);
        Instant start = Instant.parse("2026-10-16T10:00:00Z");
        for (int index = 0; index < 12; index++) service.accept(List.of(worker), start.plusSeconds(index * 5L));
        when(worker.powerTargetWatts()).thenReturn(150L);
        for (int index = 12; index < 24; index++) service.accept(List.of(worker), start.plusSeconds(index * 5L));

        var captor = org.mockito.ArgumentCaptor.forClass(MiningPerformanceProfileStore.Profile.class);
        org.mockito.Mockito.verify(store, org.mockito.Mockito.times(2)).save(captor.capture());
        assertEquals(List.of(180, 150), captor.getAllValues().stream()
                .map(MiningPerformanceProfileStore.Profile::powerTargetWatts).toList());
    }

    @Test
    void ordinaryCpuMiningAlsoUpdatesLocalProfiles() {
        MiningPerformanceProfileStore store = mock(MiningPerformanceProfileStore.class);
        OperatingPointService service = new OperatingPointService(mock(MiningService.class), mock(LocalRunLock.class), store);
        MinerStats.Worker worker = mock(MinerStats.Worker.class);
        when(worker.deviceId()).thenReturn("cpu");
        when(worker.hardwareType()).thenReturn("CPU");
        when(worker.hardwareModel()).thenReturn("Test CPU");
        when(worker.currentAlgorithm()).thenReturn("randomx");
        when(worker.miningStatus()).thenReturn(MinerStats.MinerStatus.MINING);
        when(worker.powerTargetWatts()).thenReturn(70L);
        when(worker.approximatedPowerUsageWatts()).thenReturn(68L);
        when(worker.terahashPerSecond()).thenReturn(0.000000001);
        Instant start = Instant.parse("2026-10-16T10:00:00Z");
        for (int index = 0; index < 12; index++) service.accept(List.of(worker), start.plusSeconds(index * 5L));

        var captor = org.mockito.ArgumentCaptor.forClass(MiningPerformanceProfileStore.Profile.class);
        verify(store).save(captor.capture());
        assertEquals("monero", captor.getValue().coin());
        assertEquals("LIVE", captor.getValue().source());
    }
}
