package de.verdox.solarminer.pcagent.mining.measurement;

import de.verdox.solarminer.pcagent.coin.SweepMode;
import de.verdox.solarminer.pcagent.coin.SweepRunState;
import de.verdox.solarminer.pcagent.coin.Coin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;
import java.util.Set;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.GpuCoinMiner;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.miner.MinerFactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class EfficiencySweepServiceTest {
    @Test
    void recoverableFailureOnOneGpuDoesNotDiscardOtherGpuOrLaterCoins() throws Exception {
        var first = gpu("one", "NVIDIA", 0);
        var second = gpu("two", "NVIDIA", 1);
        MinerFactory miners = mock(MinerFactory.class);
        when(miners.srbBinaryAvailable()).thenReturn(true);
        GpuCoinMiner inactive = mock(GpuCoinMiner.class);
        GpuCoinMiner pearl = mock(GpuCoinMiner.class);
        GpuCoinMiner raven = mock(GpuCoinMiner.class);
        when(miners.gpuMiner(any(Coin.class))).thenReturn(inactive);
        when(miners.gpuMiner(Coin.PEARL)).thenReturn(pearl);
        when(miners.gpuMiner(Coin.RAVENCOIN)).thenReturn(raven);
        for (GpuCoinMiner miner : List.of(pearl, raven)) {
            when(miner.configuration()).thenReturn(new MinerConfig("pool", "proxy", "wallet", "worker", "NVIDIA:0,NVIDIA:1"));
            when(miner.selectedGpus()).thenReturn(List.of(first, second));
            when(miner.stopGpu(any(String.class), anyInt())).thenReturn(true);
        }
        when(inactive.stopGpu(any(String.class), anyInt())).thenReturn(true);
        when(pearl.restoreState(isNull())).thenReturn(true);
        when(pearl.reportedAlgorithm()).thenReturn("PearlHash");
        when(raven.reportedAlgorithm()).thenReturn("KAWPOW");
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(first, second));
        when(power.refresh()).thenReturn(List.of(first, second));
        when(power.restorePowerLimits(any())).thenReturn(true);
        var failedOnce = new java.util.concurrent.atomic.AtomicBoolean();
        when(power.setSweepPowerTarget(eq(200L), any(LocalGpuPowerService.Gpu.class))).thenAnswer(call -> {
            LocalGpuPowerService.Gpu card = call.getArgument(1);
            if (card.deviceId().equals("one") && failedOnce.compareAndSet(false, true))
                throw new IllegalStateException("driver readback failed");
            return false;
        });
        var service = new EfficiencySweepService(miners, power,
                mock(de.verdox.solarminer.pcagent.miner.MinerConsoleService.class),
                mock(de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService.class),
                mock(de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService.class),
                mock(GpuEfficiencyStore.class), mock(BenchmarkSharingService.class), new LocalRunLock(),
                15, 85, 0.1, 240);

        service.start(List.of("pearl", "ravencoin"), List.of("one", "two"), 2);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (service.status().running()) Thread.sleep(10);
        });

        var runs = service.status().runs();
        assertEquals(4, runs.size());
        assertTrue(service.status().phase().startsWith("Fertig"));
        assertTrue(runs.stream().allMatch(run -> run.status() == SweepRunState.FAILED));
        assertEquals(1, runs.stream().filter(run -> run.detail().contains("driver readback failed")).count());
        assertEquals(3, runs.stream().filter(run -> run.steps().size() == 1).count());
        verify(power, times(4)).setSweepPowerTarget(eq(200L), any(LocalGpuPowerService.Gpu.class));
    }

    private static LocalGpuPowerService.Gpu gpu(String id, String vendor, int index) {
        return new LocalGpuPowerService.Gpu(vendor, index, id, "GPU", 100, 320,
                200, 200, 200, 25.0, "measured", true, null, 300);
    }

    @Test
    void referenceStartRespectsUserMaximumAndUsesCurrentCapWithoutDriverDefault() {
        var capped = new LocalGpuPowerService.Gpu("NVIDIA", 0, "one", "GPU", 100, 320,
                100, 200, 180, 25.0, "measured", true, null, 300);
        var noDefault = new LocalGpuPowerService.Gpu("NVIDIA", 0, "one", "GPU", 100, 320,
                100, 200, 180, 25.0, "measured", true, null);

        assertEquals(200, EfficiencySweepService.referenceStartLimit(capped));
        assertEquals(180, EfficiencySweepService.referenceStartLimit(noDefault));
    }

    @Test
    void firstCoinStartupFailureStillRunsSecondCoinOnSameGpu() throws Exception {
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "one", "GPU", 100, 320,
                200, 200, 200, 25.0, "measured", true, null, 300);
        MinerFactory miners = mock(MinerFactory.class);
        when(miners.srbBinaryAvailable()).thenReturn(true);
        GpuCoinMiner inactive = mock(GpuCoinMiner.class);
        GpuCoinMiner pearl = mock(GpuCoinMiner.class);
        GpuCoinMiner raven = mock(GpuCoinMiner.class);
        when(miners.gpuMiner(any(Coin.class))).thenReturn(inactive);
        when(miners.gpuMiner(Coin.PEARL)).thenReturn(pearl);
        when(miners.gpuMiner(Coin.RAVENCOIN)).thenReturn(raven);
        for (GpuCoinMiner miner : List.of(pearl, raven)) {
            when(miner.configuration()).thenReturn(new MinerConfig("pool", "proxy", "wallet", "worker", "NVIDIA:0"));
            when(miner.selectedGpus()).thenReturn(List.of(gpu));
            when(miner.stopGpu("NVIDIA", 0)).thenReturn(true);
        }
        when(inactive.stopGpu("NVIDIA", 0)).thenReturn(true);
        when(pearl.restoreState(isNull())).thenReturn(true);
        when(pearl.reportedAlgorithm()).thenReturn("PearlHash");
        when(raven.reportedAlgorithm()).thenReturn("KAWPOW");
        when(pearl.resumeGpu("NVIDIA", 0)).thenReturn(false);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(power.discover()).thenReturn(List.of(gpu));
        when(power.refresh()).thenReturn(List.of(gpu));
        when(power.restorePowerLimits(any())).thenReturn(true);
        when(power.setSweepPowerTarget(eq(200L), eq(gpu))).thenReturn(true, false);
        var service = new EfficiencySweepService(miners, power, mock(de.verdox.solarminer.pcagent.miner.MinerConsoleService.class),
                mock(de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService.class),
                mock(de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService.class),
                mock(GpuEfficiencyStore.class), mock(BenchmarkSharingService.class), new LocalRunLock(),
                15, 85, 0.1, 240);

        service.start(List.of("pearl", "ravencoin"), List.of("one"), 1);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (service.status().running()) Thread.sleep(10);
        });

        var runs = service.status().runs();
        assertTrue(service.status().phase().startsWith("Fertig"));
        assertEquals(List.of("pearl", "ravencoin"), runs.stream().map(EfficiencySweepService.RunStatus::coin).toList());
        assertTrue(runs.stream().allMatch(run -> run.status() == SweepRunState.FAILED && run.steps().size() == 1));
        assertTrue(runs.getFirst().steps().getFirst().note().contains("Miner konnte nicht gestartet werden"));
        verify(pearl).resumeGpu("NVIDIA", 0);
        verify(power, times(2)).setSweepPowerTarget(eq(200L), eq(gpu));
    }

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
        var first = run("one", SweepMode.VALIDATION, List.of(155, 170));
        var second = run("two", SweepMode.VALIDATION, List.of(155, 170));

        assertEquals(210, EfficiencySweepService.estimateRemainingSeconds(
                List.of(first, second), Instant.now()));
    }

    @Test
    void identicalModelsStillGetIndependentFullCurves() {
        var first = target("one", "TITAN RTX", 100, 320, "PearlHash");
        var sibling = target("two", "TITAN RTX", 100, 320, "PearlHash");
        var differentBounds = target("three", "TITAN RTX", 120, 320, "PearlHash");
        var differentAlgorithm = target("four", "TITAN RTX", 100, 320, "KAWPOW");

        List<List<EfficiencySweepService.Target>> groups = EfficiencySweepService.targetGroups(
                List.of(first, sibling, differentBounds, differentAlgorithm));

        assertEquals(List.of(1, 1, 1, 1), groups.stream().map(List::size).toList());
    }

    @Test
    void referenceBatchesSpreadCoinsAcrossDistinctPhysicalGpus() {
        var firstCoin = List.of(target("one", "TITAN RTX", 100, 320, "PearlHash"),
                target("two", "TITAN RTX", 100, 320, "PearlHash"));
        var secondCoin = List.of(targetForCoin("one", "ravencoin", "KAWPOW"),
                targetForCoin("two", "ravencoin", "KAWPOW"));
        var thirdCoin = List.of(targetForCoin("one", "decred", "BLAKE3"),
                targetForCoin("two", "decred", "BLAKE3"));

        var batches = EfficiencySweepService.referenceBatches(EfficiencySweepService.targetGroups(
                java.util.stream.Stream.of(firstCoin, secondCoin, thirdCoin).flatMap(List::stream).toList()));

        assertEquals(List.of(2, 2, 2), batches.stream().map(List::size).toList());
        assertEquals(6, batches.stream().flatMap(List::stream).count());
        for (var batch : batches) {
            List<String> devices = batch.stream().map(group -> group.getFirst().gpu().deviceId()).toList();
            assertEquals(devices.size(), Set.copyOf(devices).size());
        }
    }

    @Test
    void etaCountsCoinReferencesInTheSameBatchInParallel() {
        var first = run("one", SweepMode.FULL, List.of(200, 185), 0);
        var second = run("two", SweepMode.FULL, List.of(200, 185), 0);
        var nextBatch = run("one-again", SweepMode.FULL, List.of(200, 185), 1);

        assertEquals(420, EfficiencySweepService.estimateRemainingSeconds(
                List.of(first, second, nextBatch), Instant.now()));
    }

    @Test
    void schedulerPrefersUnlockedSameCoinValidationBeforeAnotherReference() {
        var validation = task(targetForCoin("one", "pearl", "PearlHash"), SweepMode.VALIDATION);
        var otherCoin = task(targetForCoin("two", "ravencoin", "KAWPOW"), SweepMode.FULL);
        List<EfficiencySweepService.SweepTask> validations = new java.util.ArrayList<>(List.of(validation));
        List<EfficiencySweepService.SweepTask> references = new java.util.ArrayList<>(List.of(otherCoin));

        var selected = EfficiencySweepService.takeRunnable(validations, references, Set.of());

        assertSame(validation, selected);
        assertTrue(validations.isEmpty());
        assertEquals(List.of(otherCoin), references);
    }

    @Test
    void schedulerUsesOtherCoinWorkWhileValidationDeviceIsBusy() {
        var blockedValidation = task(targetForCoin("one", "pearl", "PearlHash"), SweepMode.VALIDATION);
        var otherCoin = task(targetForCoin("two", "ravencoin", "KAWPOW"), SweepMode.FULL);
        List<EfficiencySweepService.SweepTask> validations = new java.util.ArrayList<>(List.of(blockedValidation));
        List<EfficiencySweepService.SweepTask> references = new java.util.ArrayList<>(List.of(otherCoin));

        var selected = EfficiencySweepService.takeRunnable(validations, references, Set.of("one"));

        assertSame(otherCoin, selected);
        assertEquals(List.of(blockedValidation), validations);
        assertTrue(references.isEmpty());
        assertNull(EfficiencySweepService.takeRunnable(validations, references, Set.of("one")));
    }

    @Test
    void schedulerKeepsSecondCoinQueuedUntilTheSameGpuIsFree() {
        var firstCoin = task(targetForCoin("one", "pearl", "PearlHash"), SweepMode.FULL);
        var secondCoin = task(targetForCoin("one", "ravencoin", "KAWPOW"), SweepMode.FULL);
        List<EfficiencySweepService.SweepTask> references = new java.util.ArrayList<>(List.of(firstCoin, secondCoin));
        List<EfficiencySweepService.SweepTask> validations = new java.util.ArrayList<>();

        assertSame(firstCoin, EfficiencySweepService.takeRunnable(validations, references, Set.of()));
        assertNull(EfficiencySweepService.takeRunnable(validations, references, Set.of("one")));
        assertEquals(List.of(secondCoin), references);
        assertSame(secondCoin, EfficiencySweepService.takeRunnable(validations, references, Set.of()));
        assertTrue(references.isEmpty());
    }

    @Test
    void resumeReusesOnlyFullyCompletedRunsAndNotCancelledPartialResults() {
        var complete = withStatus(identifiedRun("one", SweepMode.FULL, List.of(200, 185)), SweepRunState.COMPLETE);
        var cancelled = withStatus(identifiedRun("two", SweepMode.FULL, List.of(200, 185)), SweepRunState.CANCELLED);
        var completeProfile = profile("one", "pearl", "PearlHash", 185);
        var partialProfile = profile("two", "pearl", "PearlHash", 200);
        var previous = new EfficiencySweepService.Session(false, "Abgebrochen", Instant.now(), 2, 2,
                List.of(completeProfile, partialProfile), List.of(complete, cancelled), 0L);

        var checkpoint = EfficiencySweepService.reusableProfiles(previous);

        assertEquals(Set.of("one|pearl|PearlHash"), checkpoint.keySet());
        var resumed = EfficiencySweepService.applyCheckpoint(
                List.of(identifiedRun("one", SweepMode.FULL, List.of(200, 185)),
                        identifiedRun("two", SweepMode.FULL, List.of(200, 185))),
                previous.runs(), checkpoint);
        assertEquals(SweepRunState.COMPLETE, resumed.getFirst().status());
        assertEquals(SweepRunState.QUEUED, resumed.get(1).status());
    }

    @Test
    void storeKeySeparatesDevicesAndAlgorithms() {
        assertEquals("GPU-abc|kawpow", GpuEfficiencyStore.key("GPU-abc", "kawpow"));
    }

    @Test
    void workerLookupMatchesCanonicalQuantusTelemetryAgainstDisplayAlgorithm() {
        var target = targetForCoin("one", "quantus", "QPoW (Poseidon2)");

        assertTrue(EfficiencySweepService.matchesTargetWorker(target, Coin.QUANTUS, "one", "quantus"));
        assertFalse(EfficiencySweepService.matchesTargetWorker(target, Coin.QUANTUS, "two", "quantus"));
        assertFalse(EfficiencySweepService.matchesTargetWorker(target, Coin.QUANTUS, "one", "etchash"));
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

    private static de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService.Gpu gpu(boolean writable, String error) {
        return new de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService.Gpu(
                "NVIDIA", 0, "GPU-abc", "TITAN RTX", 100, 320, 100, 320,
                200, 25.0, "measured", writable, error);
    }

    private static EfficiencySweepService.RunStatus run(String id, SweepMode mode, List<Integer> limits) {
        return run(id, mode, limits, null);
    }

    private static EfficiencySweepService.RunStatus run(String id, SweepMode mode, List<Integer> limits,
                                                         Integer referenceBatch) {
        return new EfficiencySweepService.RunStatus(id, id, "TITAN RTX", "pearl", "PearlHash", mode,
                SweepRunState.QUEUED, null, limits, List.of(), 0, EfficiencySweepService.SAMPLES_PER_STEP, referenceBatch, "");
    }

    private static EfficiencySweepService.Target target(String id, String model, int min, int max, String algorithm) {
        var gpu = new de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService.Gpu(
                "NVIDIA", 0, id, model, min, max, min, max, max, 25.0, "measured", true, null);
        return new EfficiencySweepService.Target("pearl", algorithm, gpu, null);
    }

    private static EfficiencySweepService.Target targetForCoin(String id, String coin, String algorithm) {
        var gpu = new de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService.Gpu(
                "NVIDIA", id.equals("one") ? 0 : 1, id, "TITAN RTX", 100, 320, 100, 320,
                320, 25.0, "measured", true, null);
        return new EfficiencySweepService.Target(coin, algorithm, gpu, null);
    }

    private static EfficiencySweepService.SweepTask task(EfficiencySweepService.Target target, SweepMode mode) {
        return new EfficiencySweepService.SweepTask(target, target.coin() + "|" + target.algorithm(), mode, null);
    }

    private static EfficiencySweepService.RunStatus withStatus(EfficiencySweepService.RunStatus run, SweepRunState status) {
        return new EfficiencySweepService.RunStatus(run.id(), run.deviceId(), run.model(), run.coin(), run.algorithm(),
                run.mode(), status, run.limitWatts(), run.plannedLimits(), run.steps(), run.samples(),
                run.samplesRequired(), run.referenceBatch(), run.detail());
    }

    private static EfficiencySweepService.RunStatus identifiedRun(String device, SweepMode mode, List<Integer> limits) {
        var run = run(device, mode, limits);
        return new EfficiencySweepService.RunStatus(device + "|pearl|PearlHash", device, run.model(), run.coin(),
                run.algorithm(), run.mode(), run.status(), run.limitWatts(), run.plannedLimits(), run.steps(),
                run.samples(), run.samplesRequired(), run.referenceBatch(), run.detail());
    }

    private static GpuEfficiencyStore.Profile profile(String device, String coin, String algorithm, int watts) {
        var step = new GpuEfficiencyStore.StepResult(watts, 1_000d, (double) watts, 60d, true, null);
        return new GpuEfficiencyStore.Profile(device, "TITAN RTX", coin, algorithm, watts, 1_000d,
                (double) watts, 1_000d / watts, Instant.now(), List.of(step));
    }
}
