package de.verdox.solarminer.pcagent.mining.energy;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.Map;

class EnergyJournalServiceTest {
    @TempDir Path directory;

    @Test
    void integratesMeasuredPowerAndPersistsStoppedSession() {
        EnergyJournalService journal = journal();
        Instant start = Instant.parse("2026-10-06T10:00:00Z");
        journal.sample(List.of(worker(360)), start);
        journal.sample(List.of(worker(360)), start.plusSeconds(10));
        journal.sample(List.of(), start.plusSeconds(11));

        EnergyJournalService.Session session = journal.overview().recentSessions().getFirst();
        assertEquals(1.0, session.wattHours(), 0.0001);
        assertEquals(10, session.measuredSeconds());
        assertEquals("STOPPED", session.endReason());
        assertTrue(session.measurementSource().contains("GPU"));
    }

    @Test
    void missingPowerIsNotRecordedAsZeroMeasurement() {
        EnergyJournalService journal = journal();
        Instant start = Instant.parse("2026-10-06T10:00:00Z");
        journal.sample(List.of(worker(0)), start);
        journal.sample(List.of(worker(0)), start.plusSeconds(10));

        EnergyJournalService.Session session = journal.overview().activeSessions().getFirst();
        assertEquals(0, session.wattHours());
        assertEquals(0, session.measuredSeconds());
        assertTrue(session.runtimeSeconds() >= 10);
    }

    @Test
    void recordsTheMinerAssignedToThePhysicalWorker() {
        MinerCatalogService catalog = mock(MinerCatalogService.class);
        MinerCatalogService.MinerOption fallback = mock(MinerCatalogService.MinerOption.class);
        when(fallback.id()).thenReturn("srbminer-multi");
        when(catalog.selected("quantus")).thenReturn(fallback);
        AgentControlSettingsService controls = mock(AgentControlSettingsService.class);
        when(controls.get()).thenReturn(new AgentControlSettingsService.Settings(true, false, Map.of(),
                Map.of("gpu-0", "quantus"), Map.of(), true, false, Map.of("gpu-0", "quanpool-miner")));
        EnergyJournalService journal = new EnergyJournalService(new ObjectMapper().findAndRegisterModules(),
                mock(MiningService.class), mock(LocalGpuPowerService.class), catalog, controls, directory.toString());
        MinerStats.Worker quantus = new MinerStats.Worker(MinerStats.MinerStatus.MINING, "Demo GPU", "quantus",
                1, 70, 200, 100, 200, 300, 200, List.of(), "GPU", "RTX Demo", "gpu-0",
                1L, 0L, MinerStats.PoolTelemetry.unavailable());

        journal.sample(List.of(quantus), Instant.parse("2026-10-14T10:00:00Z"));

        assertEquals("quanpool-miner", journal.overview().activeSessions().getFirst().minerSoftwareId());
    }

    private EnergyJournalService journal() {
        return new EnergyJournalService(new ObjectMapper().findAndRegisterModules(), mock(MiningService.class),
                mock(LocalGpuPowerService.class), mock(MinerCatalogService.class), directory.toString());
    }

    private MinerStats.Worker worker(long watts) {
        return new MinerStats.Worker(MinerStats.MinerStatus.MINING, "Demo GPU", "PearlHash",
                0.0000415, 70, 200, 100, 200, 300, watts, List.of(), "GPU", "RTX Demo", "gpu-0",
                10L, 1L, MinerStats.PoolTelemetry.unavailable());
    }
}
