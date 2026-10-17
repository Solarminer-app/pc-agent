package de.verdox.solarminer.pcagent.miner;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.miner.srb.GpuCoinMinerService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SrbGpuCoinViewTest {
    @Test
    void failedRestorationOfPreviouslyRunningGpuIsReported() {
        GpuCoinMinerService service = mock(GpuCoinMinerService.class);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        var gpu = new LocalGpuPowerService.Gpu("NVIDIA", 0, "gpu-a", "Test GPU", 100, 300,
                100, 300, 200, 150.0, "measured", true, null);
        when(power.discover()).thenReturn(List.of(gpu));
        when(service.stop("ravencoin")).thenReturn(true);
        when(service.resumeGpu("ravencoin", "NVIDIA", 0)).thenReturn(false);

        var view = new SrbGpuCoinView(service, power, Coin.RAVENCOIN);

        assertFalse(view.restoreWorkerState(Set.of(), Set.of("gpu-a")));
    }

    @Test
    void failedStopDoesNotRestartPreviouslyRunningGpu() {
        GpuCoinMinerService service = mock(GpuCoinMinerService.class);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        var view = new SrbGpuCoinView(service, power, Coin.RAVENCOIN);

        assertFalse(view.restoreWorkerState(Set.of(), Set.of("gpu-a")));
        verify(service, never()).resumeGpu("ravencoin", "NVIDIA", 0);
    }

    @Test
    void missingPreviouslyRunningGpuFailsRestoration() {
        GpuCoinMinerService service = mock(GpuCoinMinerService.class);
        LocalGpuPowerService power = mock(LocalGpuPowerService.class);
        when(service.stop("ravencoin")).thenReturn(true);
        when(power.discover()).thenReturn(List.of());

        assertFalse(new SrbGpuCoinView(service, power, Coin.RAVENCOIN)
                .restoreWorkerState(Set.of(), Set.of("gpu-a")));
    }
}
