package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.measurement.TuningCurveService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.List;

@RestController
@RequestMapping("/api/agent/local/measurements/tuning-curve")
public class TuningCurveController {
    private final TuningCurveService curves;
    public TuningCurveController(TuningCurveService curves) { this.curves = curves; }
    @GetMapping public List<TuningCurveService.Point> points(@RequestParam String deviceId) {
        if (deviceId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gpu_tuning_request_invalid");
        return curves.points(deviceId);
    }
}
