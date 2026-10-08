package de.verdox.solarminer.pcagent.mining;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PowerBudgetPlannerTest {
    private static final PowerBudgetPlanner.Cpu CPU = new PowerBudgetPlanner.Cpu(true, 10, 100);
    private static final PowerBudgetPlanner.Gpu GPU = new PowerBudgetPlanner.Gpu("GPU-1", 80, 200);

    @Test void allocatesCpuOnlyAndCapsAtCapacity() {
        var plan = PowerBudgetPlanner.plan(120, CPU, List.of());
        assertEquals(PowerBudgetPlanner.Outcome.APPLY, plan.outcome());
        assertEquals(100, plan.plannedWatts());
        assertEquals(100, plan.cpuWatts());
        assertEquals(0, plan.gpuWatts());
    }

    @Test void allocatesGpuOnlyWhenCpuIsManuallyPaused() {
        var plan = PowerBudgetPlanner.plan(120, new PowerBudgetPlanner.Cpu(false, 0, 0), List.of(GPU));
        assertEquals(PowerBudgetPlanner.Outcome.APPLY, plan.outcome());
        assertEquals(0, plan.cpuWatts());
        assertEquals(120, plan.gpuWatts());
        assertEquals(List.of(GPU), plan.gpus());
    }

    @Test void reservesGpuMinimumBeforeAllocatingMixedBudget() {
        var plan = PowerBudgetPlanner.plan(150, CPU, List.of(GPU));
        assertEquals(PowerBudgetPlanner.Outcome.APPLY, plan.outcome());
        assertEquals(70, plan.cpuWatts());
        assertEquals(80, plan.gpuWatts());
    }

    @Test void rejectsTargetBelowSafeMinimumAndRepresentsExternalOptOutAsUnavailable() {
        var belowMinimum = PowerBudgetPlanner.plan(9, CPU, List.of(GPU));
        assertEquals(PowerBudgetPlanner.Outcome.REJECTED, belowMinimum.outcome());
        var optedOut = PowerBudgetPlanner.plan(100, new PowerBudgetPlanner.Cpu(false, 0, 0), List.of());
        assertEquals(PowerBudgetPlanner.Outcome.REJECTED, optedOut.outcome());
    }

    @Test void representsExplicitPauseSeparatelyFromRejectedTarget() {
        var plan = PowerBudgetPlanner.plan(0, CPU, List.of(GPU));
        assertEquals(PowerBudgetPlanner.Outcome.PAUSE, plan.outcome());
    }

    @Test void newApplicationStateIsSafeAfterRestartAndDoesNotReplayPriorTarget() {
        var beforeRestart = new PowerApplicationState();
        beforeRestart.applied(PowerBudgetPlanner.plan(120, CPU, List.of(GPU)));
        assertEquals(120, beforeRestart.snapshot().appliedWatts());

        var afterRestart = new PowerApplicationState();
        assertEquals(PowerApplicationState.Status.IDLE, afterRestart.snapshot().status());
        assertEquals(0, afterRestart.snapshot().requestedWatts());
        assertEquals(0, afterRestart.snapshot().appliedWatts());
    }

    @Test void applicationStateMakesGpuFallbackAndFailureVisible() {
        var requested = PowerBudgetPlanner.plan(150, CPU, List.of(GPU));
        var cpuFallback = PowerBudgetPlanner.plan(150, CPU, List.of());
        var state = new PowerApplicationState();
        state.partiallyApplied(requested, cpuFallback, "GPU limit write failed");

        assertEquals(150, state.snapshot().requestedWatts());
        assertEquals(100, state.snapshot().appliedWatts());
        assertEquals(PowerApplicationState.Status.PARTIALLY_APPLIED, state.snapshot().status());
        assertEquals("GPU limit write failed", state.snapshot().failureReason());
    }
}
