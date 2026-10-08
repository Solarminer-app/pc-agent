package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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
