package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REST/SSE loading plan P2 — dirty-flag infrastructure for AgentStatusService.
 * Proves the two halves of the event-driven model:
 *  1. invalidate(name) forces the next read of that section to re-run its producer
 *     even inside the TTL (push &lt; 1 s after a local mutation), and
 *  2. a clean section stays cached until its TTL (no tick-driven re-computation).
 * Also pins the publisher contract: isDirty/clearDirty wake non-section channels
 * (workers, power-control, ...) without touching a section cache.
 */
class AgentStatusServiceTest {

    private static MiningController.AgentOverview overviewRow(String marker) {
        // Same shape the wire pinning test builds; the marker rides in activeCoin so two
        // collections are distinguishable without touching the pinned field set.
        return new MiningController.AgentOverview(MinerStats.DEFAULT, marker, "linux", "amd64",
                List.of(), List.of(), List.of(),
                new MiningController.ProxyOverview(null, false, "external", "random", "external", "", "v0", List.of()),
                new MiningController.MoneroConfiguration("stratum+tcp://proxy:3335;w;x", "wallet", "worker"),
                new MinerConfig("pool", "proxy", "wallet", "worker", "NVIDIA:0"),
                new MiningController.DownloadReadiness("READY", "", 100, null, "/dir"),
                new MiningController.PearlReadiness(true, true, "READY", "", 100, null, "", false, true, List.of(), "/dir"),
                List.of(), new MiningController.ReferralOverview(null), List.of(), Map.of());
    }

    private final MiningController mining = mock(MiningController.class);
    private final AtomicInteger overviewCalls = new AtomicInteger();
    private final AtomicInteger walletsCalls = new AtomicInteger();

    private AgentStatusService service() {
        when(mining.overview()).thenAnswer(invocation -> overviewRow("v" + overviewCalls.incrementAndGet()));
        when(mining.walletSnapshot()).thenAnswer(invocation -> {
            walletsCalls.incrementAndGet();
            return new MiningController.WalletsSnapshot(List.of(), List.of(), Map.of());
        });
        return new AgentStatusService(mining, mock(AgentPowerController.class),
                mock(TelemetryController.class), mock(NodeAssessmentController.class),
                mock(EconomicsController.class));
    }

    @Test
    void aCleanSectionStaysCachedUntilItsTtl() {
        AgentStatusService status = service();

        Object first = status.payload("overview");
        Object second = status.payload("overview");

        assertSame(first, second, "cached payload must be reused");
        assertEquals(1, overviewCalls.get(), "a clean section must not re-run its producer");
        assertEquals(1, status.evaluationCount("overview"), "counter tracks producer invocations");
    }

    @Test
    void invalidateForcesTheNextReadToReEvaluateEvenInsideTheTtl() {
        AgentStatusService status = service();

        status.payload("overview");
        assertFalse(status.isDirty("overview"));

        status.invalidate("overview");
        assertTrue(status.isDirty("overview"), "mutation must mark the section dirty");

        Object refreshed = status.payload("overview");
        assertEquals("v2", ((MiningController.AgentOverview) refreshed).activeCoin(),
                "dirty section must re-run its producer immediately");
        assertEquals(2, overviewCalls.get());
        // The publisher clears the flag after its snapshot; a follow-up read is cached again.
        status.clearDirty("overview");
        status.payload("overview");
        assertEquals(2, overviewCalls.get(), "after the wake, the TTL cache applies again");
    }

    @Test
    void invalidatingOneSectionLeavesOthersCached() {
        AgentStatusService status = service();

        status.payload("overview");
        status.payload("wallets");
        status.invalidate("overview");

        status.payload("overview");
        status.payload("wallets");

        assertEquals(2, overviewCalls.get(), "the reported section re-evaluates");
        assertEquals(1, walletsCalls.get(), "an untouched section stays cached until its TTL");
    }

    @Test
    void nonSectionChannelNamesWakeThePublisherWithoutASectionCache() {
        AgentStatusService status = service();

        // The workers/power-control/miner-options channels have no section producer;
        // invalidating them only sets the wake flag the SSE publisher consumes.
        status.invalidate("workers", "power-control", "miner-options");
        assertTrue(status.isDirty("workers"));
        assertTrue(status.isDirty("power-control"));
        assertTrue(status.isDirty("miner-options"));
        status.clearDirty("workers");
        assertFalse(status.isDirty("workers"));

        status.payload("overview");
        assertEquals(1, overviewCalls.get(), "a channel wake must not touch unrelated sections");
    }

    @Test
    void theAggregateHonoursDirtyFlagsAndTheCounterCountsFailures() {
        // Single stub (a second when() would re-invoke the mock through the first answer):
        // the first collection throws, the invalidation-driven retry recovers.
        MiningController failing = mock(MiningController.class);
        AtomicInteger calls = new AtomicInteger();
        when(failing.overview()).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("first read fails");
            return overviewRow("recovered");
        });
        when(failing.walletSnapshot()).thenReturn(new MiningController.WalletsSnapshot(List.of(), List.of(), Map.of()));
        AgentStatusService status = new AgentStatusService(failing, mock(AgentPowerController.class),
                mock(TelemetryController.class), mock(NodeAssessmentController.class),
                mock(EconomicsController.class));

        // A failed section degrades to stale in the aggregate and caches the failure briefly.
        AgentStatusService.Aggregate degraded = status.aggregate();
        AgentStatusService.Section overviewSection = degraded.sections().stream()
                .filter(s -> s.name().equals("overview")).findFirst().orElseThrow().section();
        assertTrue(overviewSection.stale());

        // The failure cache would hold for 2 s; invalidate forces an immediate retry.
        status.invalidate("overview");
        AgentStatusService.Aggregate healed = status.aggregate();
        AgentStatusService.Section healedSection = healed.sections().stream()
                .filter(s -> s.name().equals("overview")).findFirst().orElseThrow().section();
        assertFalse(healedSection.stale());
        assertEquals("recovered", healedSection.payload() instanceof MiningController.AgentOverview o ? o.activeCoin() : null);
        assertEquals(2, calls.get());
        assertEquals(2, status.evaluationCount("overview"), "failed collections count as work");
    }

    @Test
    void selectiveAggregateAvoidsUnrequestedColdSections() {
        AgentStatusService status = service();

        AgentStatusService.Aggregate aggregate = status.aggregate(List.of("overview", "ui", "unknown"));

        assertEquals(List.of("overview", "ui"), aggregate.sections().stream()
                .map(AgentStatusService.Sectioned::name).toList());
        assertEquals(1, overviewCalls.get());
        assertEquals(0, walletsCalls.get(), "wallet/market collection is not on this page's critical path");
    }
}
