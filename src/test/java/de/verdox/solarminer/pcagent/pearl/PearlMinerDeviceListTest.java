package de.verdox.solarminer.pcagent.pearl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PearlMinerDeviceListTest {
    @Test
    void mapsColoredCudaListingFromLinuxPseudoTerminal() {
        String output = "\u001b[0m\u001b[1;32mGPU0\u001b[0m  \u001b[1;33m[CUDA][0] [0000:01:00.0] : nvidia_titan_rtx\u001b[0m\r\n"
                + "\u001b[0m\u001b[1;32mGPU1\u001b[0m  \u001b[1;33m[CUDA][1] [0000:21:00.0] : nvidia_titan_rtx\u001b[0m\r\n"
                + "\u001b[0m\u001b[1;32mGPU2\u001b[0m  \u001b[1;33m[CUDA][2] [0000:44:00.0] : nvidia_titan_rtx\u001b[0m\r\n"
                + "\u001b[0m\u001b[1;32mGPU3\u001b[0m  \u001b[1;33m[CUDA][3] [0000:62:00.0] : nvidia_titan_rtx\u001b[0m\r\n";

        assertEquals(0, PearlMinerService.findNvidiaGpuId(output, "00000000:01:00.0"));
        assertEquals(1, PearlMinerService.findNvidiaGpuId(output, "00000000:21:00.0"));
        assertEquals(2, PearlMinerService.findNvidiaGpuId(output, "00000000:44:00.0"));
        assertEquals(3, PearlMinerService.findNvidiaGpuId(output, "00000000:62:00.0"));
    }

    @Test
    void keepsDisabledDeviceExcluded() {
        assertEquals(-1, PearlMinerService.findNvidiaGpuId(
                "GPU1  [CUDA][0] [0000:21:00.0] : nvidia_titan_rtx disabled by default", "00000000:21:00.0"));
    }

    @Test
    void mapsByPciAddressWhenCudaOrderDiffersFromNvidiaSmi() {
        String output = "GPU0 [CUDA][0] [0000:21:00.0] : rtx_2080\n"
                + "GPU1 [CUDA][1] [0000:01:00.0] : rtx_2080_ti\n";
        assertEquals(1, PearlMinerService.findNvidiaGpuId(output, "00000000:01:00.0"));
        assertEquals(0, PearlMinerService.findNvidiaGpuId(output, "00000000:21:00.0"));
        assertEquals(-1, PearlMinerService.findNvidiaGpuId(output, "00000000:42:00.0"));
        assertEquals(-1, PearlMinerService.findNvidiaGpuId(
                output + "GPU2 [CUDA][2] [0000:01:00.0] : duplicate\n", "00000000:01:00.0"));
    }
}
