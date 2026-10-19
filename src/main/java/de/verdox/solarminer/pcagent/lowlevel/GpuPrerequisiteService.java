package de.verdox.solarminer.pcagent.lowlevel;

import de.verdox.gputoolkit.GpuPrerequisiteProbe;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Cached local driver readiness; HTTP reads never launch driver commands. */
@Service
public class GpuPrerequisiteService {
    public record DeviceStatus(String deviceId, String model, String vendor, GpuPrerequisiteProbe.State state,
                               List<GpuPrerequisiteProbe.Check> checks) { }

    private final LocalGpuPowerService power;
    private final GpuTuningService tuning;
    private final GpuPrerequisiteProbe probe;
    private volatile List<DeviceStatus> snapshot = List.of();

    @org.springframework.beans.factory.annotation.Autowired
    public GpuPrerequisiteService(LocalGpuPowerService power, GpuTuningService tuning) {
        this(power, tuning, new GpuPrerequisiteProbe());
    }

    GpuPrerequisiteService(LocalGpuPowerService power, GpuTuningService tuning, GpuPrerequisiteProbe probe) {
        this.power = power;
        this.tuning = tuning;
        this.probe = probe;
    }

    public List<DeviceStatus> snapshot() { return snapshot; }

    @Scheduled(fixedDelayString = "${solarminer.agent.gpu-prerequisite-refresh-ms:30000}", initialDelay = 7000)
    public void refresh() {
        List<DeviceStatus> next = new ArrayList<>();
        for (LocalGpuPowerService.Gpu gpu : power.snapshot()) {
            GpuPrerequisiteProbe.Vendor vendor;
            try { vendor = GpuPrerequisiteProbe.Vendor.valueOf(gpu.vendor().toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException e) { continue; }
            GpuPrerequisiteProbe.Report report = probe.check(vendor,
                    vendor == GpuPrerequisiteProbe.Vendor.NVIDIA ? gpu.index() : null);
            List<GpuPrerequisiteProbe.Check> checks = new ArrayList<>(report.checks());
            boolean writable = gpu.supportsDynamicPowerScaling();
            checks.add(new GpuPrerequisiteProbe.Check("power_write", writable ? GpuPrerequisiteProbe.State.READY
                    : GpuPrerequisiteProbe.State.MISSING, true,
                    writable ? null : gpu.regulationError() == null ? "gpu_prereq_power_unsupported"
                            : "gpu_prereq_power_write_failed"));
            // A successful same-value write is stronger evidence than a privilege heuristic.
            if (writable) checks.replaceAll(check -> "privilege".equals(check.code())
                    || "driver_access".equals(check.code()) || "container_runtime".equals(check.code())
                    ? new GpuPrerequisiteProbe.Check(check.code(), GpuPrerequisiteProbe.State.READY,
                    check.required(), null) : check);
            GpuTuningService.Capabilities clocks = tuning.capabilities(gpu.deviceId());
            boolean pending = "gpu_clock_probe_pending".equals(clocks.reason());
            checks.add(new GpuPrerequisiteProbe.Check("core_clock", pending ? GpuPrerequisiteProbe.State.UNKNOWN
                    : clocks.coreClockControl() ? GpuPrerequisiteProbe.State.READY : GpuPrerequisiteProbe.State.MISSING,
                    false, pending ? "gpu_prereq_clock_probe_pending" : clocks.coreClockControl() ? null
                    : "gpu_prereq_core_clock_unavailable"));
            checks.add(new GpuPrerequisiteProbe.Check("memory_clock", pending ? GpuPrerequisiteProbe.State.UNKNOWN
                    : clocks.memoryClockControl() ? GpuPrerequisiteProbe.State.READY : GpuPrerequisiteProbe.State.MISSING,
                    false, pending ? "gpu_prereq_clock_probe_pending" : clocks.memoryClockControl() ? null
                    : "gpu_prereq_memory_clock_unavailable"));
            GpuPrerequisiteProbe.State state = checks.stream().anyMatch(c -> c.required()
                    && c.state() == GpuPrerequisiteProbe.State.MISSING) ? GpuPrerequisiteProbe.State.MISSING
                    : checks.stream().anyMatch(c -> c.required() && c.state() == GpuPrerequisiteProbe.State.UNKNOWN)
                    ? GpuPrerequisiteProbe.State.UNKNOWN : GpuPrerequisiteProbe.State.READY;
            next.add(new DeviceStatus(gpu.deviceId(), gpu.model(), gpu.vendor(), state, List.copyOf(checks)));
        }
        snapshot = List.copyOf(next);
    }
}
