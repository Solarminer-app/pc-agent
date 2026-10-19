package de.verdox.solarminer.pcagent.lowlevel;

import de.verdox.gputoolkit.GPUInterface;
import de.verdox.gputoolkit.nvidia.NvidiaNVMLAdapter;

/** Keeps the agent's verified clock writes behind the shared GPU toolkit API. */
final class ToolkitClockDriver implements GpuTuningService.MemoryOffsetDriver {
    private volatile GPUInterface toolkit;

    private GPUInterface toolkit() {
        GPUInterface current = toolkit;
        if (current == null) {
            synchronized (this) {
                if (toolkit == null) toolkit = new NvidiaNVMLAdapter();
                current = toolkit;
            }
        }
        return current;
    }

    @Override
    public GpuTuningService.MemoryOffsetRange probe(int gpuIndex, int pstate) {
        GPUInterface.ClockOffsetRange range = toolkit().getClockOffsetRange(gpuIndex, true, pstate);
        return new GpuTuningService.MemoryOffsetRange(range.pstate(), range.minMhz(),
                range.maxMhz(), range.currentMhz());
    }

    @Override
    public void set(int gpuIndex, int pstate, int offsetMhz) {
        toolkit().setMemoryClockOffsetMhz(gpuIndex, pstate, offsetMhz);
    }

    @Override
    public void setCoreTarget(int gpuIndex, int coreMhz) {
        toolkit().setCoreClockTargetMhz(gpuIndex, coreMhz);
    }
}
