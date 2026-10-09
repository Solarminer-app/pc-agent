package de.verdox.solarminer.pcagent.mining;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;
import java.util.Set;

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
    void validationStartsAtTheReferenceAndMovesUpToTheCardsOriginalLimit() {
        assertEquals(List.of(155, 170, 185, 200),
                EfficiencySweepService.validationLimits(155, 100, 200, 15));
        assertEquals(List.of(200),
                EfficiencySweepService.validationLimits(220, 100, 200, 15));
    }

    @Test
    void etaCountsSiblingValidationRunsInParallel() {
        var first = run("one", "VALIDATION", List.of(155, 170));
        var second = run("two", "VALIDATION", List.of(155, 170));

        assertEquals(160, EfficiencySweepService.estimateRemainingSeconds(
                List.of(first, second), Instant.now()));
    }

    @Test
    void cohortsRequireMatchingModelAlgorithmAndPowerBounds() {
        var first = target("one", "TITAN RTX", 100, 320, "PearlHash");
        var sibling = target("two", "TITAN RTX", 100, 320, "PearlHash");
        var differentBounds = target("three", "TITAN RTX", 120, 320, "PearlHash");
        var differentAlgorithm = target("four", "TITAN RTX", 100, 320, "KAWPOW");

        List<List<EfficiencySweepService.Target>> groups = EfficiencySweepService.targetGroups(
                List.of(first, sibling, differentBounds, differentAlgorithm));

        assertEquals(List.of(2, 1, 1), groups.stream().map(List::size).toList());
    }

    @Test
    void referenceBatchesSpreadCoinsAcrossDistinctPhysicalGpus() {
        var firstCoin = List.of(target("one", "TITAN RTX", 100, 320, "PearlHash"),
                target("two", "TITAN RTX", 100, 320, "PearlHash"));
        var secondCoin = List.of(targetForCoin("one", "ravencoin", "KAWPOW"),
                targetForCoin("two", "ravencoin", "KAWPOW"));
        var thirdCoin = List.of(targetForCoin("one", "decred", "BLAKE3"),
                targetForCoin("two", "decred", "BLAKE3"));

        var batches = EfficiencySweepService.referenceBatches(List.of(firstCoin, secondCoin, thirdCoin));

        assertEquals(List.of(2, 1), batches.stream().map(List::size).toList());
        assertEquals(3, batches.stream().flatMap(List::stream).count());
        for (var batch : batches) {
            List<String> devices = batch.stream().map(group -> group.getFirst().gpu().deviceId()).toList();
            assertEquals(devices.size(), Set.copyOf(devices).size());
        }
    }

    @Test
    void etaCountsCoinReferencesInTheSameBatchInParallel() {
        var first = run("one", "FULL", List.of(200, 185), 0);
        var second = run("two", "FULL", List.of(200, 185), 0);
        var nextBatch = run("one-again", "FULL", List.of(200, 185), 1);

        assertEquals(320, EfficiencySweepService.estimateRemainingSeconds(
                List.of(first, second, nextBatch), Instant.now()));
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

    private static EfficiencySweepService.RunStatus run(String id, String mode, List<Integer> limits) {
        return run(id, mode, limits, null);
    }

    private static EfficiencySweepService.RunStatus run(String id, String mode, List<Integer> limits,
                                                         Integer referenceBatch) {
        return new EfficiencySweepService.RunStatus(id, id, "TITAN RTX", "pearl", "PearlHash", mode,
                "QUEUED", null, limits, List.of(), 0, EfficiencySweepService.SAMPLES_PER_STEP, referenceBatch, "");
    }

    private static EfficiencySweepService.Target target(String id, String model, int min, int max, String algorithm) {
        var gpu = new de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService.Gpu(
                "NVIDIA", 0, id, model, min, max, min, max, max, 25.0, "measured", true, null);
        return new EfficiencySweepService.Target("pearl", algorithm, gpu, null);
    }

    private static EfficiencySweepService.Target targetForCoin(String id, String coin, String algorithm) {
        var gpu = new de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService.Gpu(
                "NVIDIA", id.equals("one") ? 0 : 1, id, "TITAN RTX", 100, 320, 100, 320,
                320, 25.0, "measured", true, null);
        return new EfficiencySweepService.Target(coin, algorithm, gpu, null);
    }
}
