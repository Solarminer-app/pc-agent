package de.verdox.solarminer.pcagent.miner.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinerLogDiagnosticsTest {
    @Test
    void srbMinerKernelContextFailureMapsToUnsupportedAlgorithm() {
        Optional<MinerLogDiagnostics.Diagnosis> diagnosis =
                MinerLogDiagnostics.classify(MinerLogDiagnostics.SRBMINER,
                        "GPU0 couldn't create program for ctx->algorithm");

        assertTrue(diagnosis.isPresent());
        assertEquals(MinerLogDiagnostics.Category.GPU_ALGORITHM_UNSUPPORTED, diagnosis.get().category());
        assertTrue(diagnosis.get().detail().contains("kernel"));
    }

    @Test
    void capitalisedVariantOfTheSameFailureMapsIdentically() {
        Optional<MinerLogDiagnostics.Diagnosis> diagnosis =
                MinerLogDiagnostics.classify(MinerLogDiagnostics.SRBMINER,
                        "Could not set up ctx -> algorithm pearlhash");

        assertTrue(diagnosis.isPresent());
        assertEquals(MinerLogDiagnostics.Category.GPU_ALGORITHM_UNSUPPORTED, diagnosis.get().category());
    }

    @Test
    void poolConnectionFailureMapsToPoolCategory() {
        Optional<MinerLogDiagnostics.Diagnosis> diagnosis =
                MinerLogDiagnostics.classify(MinerLogDiagnostics.SRBMINER,
                        "ERROR: could not connect to pool");

        assertTrue(diagnosis.isPresent());
        assertEquals(MinerLogDiagnostics.Category.POOL_CONNECTION, diagnosis.get().category());
    }

    @Test
    void rulesStayInsideTheirMinerFamily() {
        // The kernel rule belongs to SRBMiner; the same line must not fire for XMRig.
        assertTrue(MinerLogDiagnostics.classify(MinerLogDiagnostics.XMRIG,
                "GPU0 couldn't create program for ctx->algorithm").isEmpty());
        assertTrue(MinerLogDiagnostics.classify(MinerLogDiagnostics.XMRIG,
                "use of huge pages failed").isPresent());
    }

    @Test
    void unknownAndBlankLinesProduceNoDiagnosis() {
        assertTrue(MinerLogDiagnostics.classify(MinerLogDiagnostics.SRBMINER, "Starting miner").isEmpty());
        assertTrue(MinerLogDiagnostics.classify(MinerLogDiagnostics.SRBMINER, "").isEmpty());
        assertTrue(MinerLogDiagnostics.classify(null, "anything").isEmpty());
    }
}
