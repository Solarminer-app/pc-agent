package de.verdox.solarminer.pcagent.pearl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SrbMinerVersionTest {
    @TempDir Path directory;

    @Test
    void acceptsOnlyTheReviewedArchitectureAwareRelease() throws Exception {
        Path executable = directory.resolve("SRBMiner-MULTI");
        Files.writeString(executable, "binary");

        assertFalse(PearlMinerService.compatibleBinary(executable), "legacy unversioned installs require an explicit update");
        Files.writeString(PearlMinerService.versionPath(executable), "3.7.0\n");
        assertFalse(PearlMinerService.compatibleBinary(executable));
        Files.writeString(PearlMinerService.versionPath(executable), SrbDownloadService.SRBMINER_VERSION + "\n");
        assertTrue(PearlMinerService.compatibleBinary(executable));
    }

    @Test
    void delegatesArchitectureKernelSelectionToSrbMinerAndPinsTheMappedGpu() {
        List<String> command = PearlMinerService.buildCommand(Path.of("SRBMiner-MULTI"),
                URI.create("stratum+tcp://127.0.0.1:3334"), "wallet", "worker", 12007, 7);

        assertTrue(command.contains("pearlhash"));
        assertTrue(command.contains("--gpu-id"));
        assertTrue(command.contains("7"));
        assertFalse(command.contains("--pearl-k1"));
        assertFalse(command.contains("--pearl-k2"));
    }
}
