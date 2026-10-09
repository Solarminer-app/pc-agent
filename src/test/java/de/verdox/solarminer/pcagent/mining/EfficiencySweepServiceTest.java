package de.verdox.solarminer.pcagent.mining;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EfficiencySweepServiceTest {
    @Test
    void candidateLimitsSweepDownwardFromCurrentAndAlwaysEndAtTheEffectiveMinimum() {
        List<Integer> limits = EfficiencySweepService.candidateLimits(65, 120, 15);

        assertEquals(List.of(120, 105, 90, 75, 65), limits);
    }

    @Test
    void candidateLimitsNeverUndercutTheEffectiveMinimum() {
        List<Integer> limits = EfficiencySweepService.candidateLimits(100, 120, 15);

        assertEquals(List.of(120, 105, 100), limits);
        assertTrue(limits.stream().allMatch(limit -> limit >= 100));
    }

    @Test
    void candidateLimitsNeverRaiseAThrottledCardToItsConfiguredMaximum() {
        List<Integer> limits = EfficiencySweepService.candidateLimits(80, 170, 15);

        assertEquals(List.of(170, 155, 140, 125, 110, 95, 80), limits);
        assertTrue(limits.stream().allMatch(limit -> limit <= 170));
    }

    @Test
    void storeKeySeparatesDevicesAndAlgorithms() {
        assertEquals("GPU-abc|kawpow", GpuEfficiencyStore.key("GPU-abc", "kawpow"));
    }

    @Test
    void unavailableReasonExplainsMissingDriverPermissionInsteadOfBlamingMinerConfiguration() {
        var gpu = gpu(false, "Power-Cap nicht schreibbar (Administrator/root und Treiber prüfen): Insufficient Permissions\nDetails");

        String reason = EfficiencySweepService.unavailableReason(List.of(gpu), true);

        assertTrue(reason.contains("Keine GPU-Leistungsgrenze ist schreibbar"));
        assertTrue(reason.contains("Administrator-/root-Rechten"));
        assertTrue(reason.contains("Insufficient Permissions"));
        assertFalse(reason.contains("\n"));
    }

    @Test
    void unavailableReasonDistinguishesMissingMinerFromMissingGpu() {
        assertEquals("Power-Limit-Test kann nicht gestartet werden: Keine GPU wurde erkannt",
                EfficiencySweepService.unavailableReason(List.of(), true));
        assertEquals("Power-Limit-Test kann nicht gestartet werden: SRBMiner-MULTI ist nicht installiert",
                EfficiencySweepService.unavailableReason(List.of(gpu(true, null)), false));
    }

    private static de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService.Gpu gpu(boolean writable, String error) {
        return new de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService.Gpu(
                "NVIDIA", 0, "GPU-abc", "TITAN RTX", 100, 320, 100, 320,
                200, 25.0, "measured", writable, error);
    }
}
