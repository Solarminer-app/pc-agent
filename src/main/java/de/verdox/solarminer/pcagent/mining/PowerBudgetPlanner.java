package de.verdox.solarminer.pcagent.mining;

import java.util.List;

/**
 * Pure allocation policy for the legacy PC-wide power target.  Process and driver calls stay in
 * {@link MiningService}; keeping this class side-effect free makes the safety policy testable.
 */
public final class PowerBudgetPlanner {
    public record Cpu(boolean available, long minimumWatts, long maximumWatts) {
        public Cpu {
            if (minimumWatts < 0 || maximumWatts < minimumWatts)
                throw new IllegalArgumentException("Invalid CPU power range");
        }
    }

    /** Only GPUs with verified dynamic limits belong in a global watt allocation. */
    public record Gpu(String deviceId, long minimumWatts, long maximumWatts) {
        public Gpu {
            if (deviceId == null || deviceId.isBlank() || minimumWatts < 0 || maximumWatts < minimumWatts)
                throw new IllegalArgumentException("Invalid GPU power range");
        }
    }

    public enum Outcome { APPLY, PAUSE, REJECTED }

    public record Plan(Outcome outcome, long requestedWatts, long plannedWatts, long cpuWatts,
                       long gpuWatts, List<Gpu> gpus, String reason) {
        public Plan {
            gpus = List.copyOf(gpus);
        }
        public boolean appliesGpuPowerLimits() { return outcome == Outcome.APPLY && gpuWatts > 0; }
    }

    private PowerBudgetPlanner() { }

    public static Plan plan(long requestedWatts, Cpu cpu, List<Gpu> gpus) {
        List<Gpu> cards = gpus == null ? List.of() : List.copyOf(gpus);
        Cpu usableCpu = cpu != null && cpu.available() ? cpu : new Cpu(false, 0, 0);
        long gpuMinimum = cards.stream().mapToLong(Gpu::minimumWatts).sum();
        long gpuMaximum = cards.stream().mapToLong(Gpu::maximumWatts).sum();
        long maximum = usableCpu.maximumWatts() + gpuMaximum;

        if (requestedWatts <= 0)
            return new Plan(Outcome.PAUSE, requestedWatts, 0, 0, 0, List.of(), "Kein Leistungsziel angefordert");
        if (maximum == 0)
            return rejected(requestedWatts, "Kein lokal freigegebener, regelbarer Worker verfügbar");

        long minimum = usableCpu.available() ? usableCpu.minimumWatts() : gpuMinimum;
        if (requestedWatts < minimum)
            return rejected(requestedWatts, "Leistungsziel liegt unter dem kleinsten sicheren Worker-Budget");

        long planned = Math.min(requestedWatts, maximum);
        long cpuWatts = Math.min(usableCpu.maximumWatts(), planned);
        long gpuWatts = 0;
        // Preserve the existing policy: as soon as the target can cover selected GPUs' minimum,
        // reserve that minimum and allocate the remaining watts to CPU first.
        if (!cards.isEmpty() && planned >= gpuMinimum) {
            cpuWatts = Math.min(usableCpu.maximumWatts(), planned - gpuMinimum);
            gpuWatts = Math.min(gpuMaximum, planned - cpuWatts);
        }
        if (cpuWatts == 0 && gpuWatts == 0)
            return rejected(requestedWatts, "Leistungsziel kann keinem Worker zugeordnet werden");
        return new Plan(Outcome.APPLY, requestedWatts, cpuWatts + gpuWatts, cpuWatts, gpuWatts,
                gpuWatts > 0 ? cards : List.of(), null);
    }

    private static Plan rejected(long requestedWatts, String reason) {
        return new Plan(Outcome.REJECTED, requestedWatts, 0, 0, 0, List.of(), reason);
    }
}
