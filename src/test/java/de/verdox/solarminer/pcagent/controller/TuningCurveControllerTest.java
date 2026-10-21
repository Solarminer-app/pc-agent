package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.measurement.TuningCurveService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TuningCurveControllerTest {
    @Test void requiresDeviceScopeAndReturnsOnlyTheReadOnlyProjection() throws Exception {
        var service = mock(TuningCurveService.class);
        when(service.points("GPU:one")).thenReturn(List.of());
        var api = standaloneSetup(new TuningCurveController(service)).build();
        api.perform(get("/api/agent/local/measurements/tuning-curve")).andExpect(status().isBadRequest());
        api.perform(get("/api/agent/local/measurements/tuning-curve").param("deviceId", " ")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        api.perform(get("/api/agent/local/measurements/tuning-curve").param("deviceId", "GPU:one"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).points("GPU:one");
    }
}
