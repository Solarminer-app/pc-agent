package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class BenchmarkSharingEfficiencyTest {
    @TempDir Path temp;

    @Test
    void retainsEverySweepStepForTheConsentGatedUpload() {
        BenchmarkSharingService sharing = new BenchmarkSharingService(mock(MiningService.class), new ObjectMapper(),
                RestClient.builder(), "http://127.0.0.1:1", temp.resolve("sharing.json").toString());
        var stable = new GpuEfficiencyStore.StepResult(320, 90_000_000d, 310d, 72d, true, null);
        var unstable = new GpuEfficiencyStore.StepResult(250, null, null, 78d, false, "instabil");
        var profile = new GpuEfficiencyStore.Profile("GPU-one", "TITAN RTX", "ravencoin", "KAWPOW",
                320, 90_000_000d, 310d, 90_000_000d / 310d, Instant.now(), List.of(stable, unstable));

        BenchmarkSharingService.ReportStatus status = sharing.reportEfficiencyResults(List.of(profile));

        assertEquals("NOT_SHARED", status.status());
        assertEquals(2, status.sampleCount());
    }
}
