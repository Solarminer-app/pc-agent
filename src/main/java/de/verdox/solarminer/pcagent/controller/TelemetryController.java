package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.lowlevel.sensor.HardwareTelemetryService;
import de.verdox.solarminer.pcagent.lowlevel.sensor.WindowsLhmBootstrapService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local JSON interface for dashboards and other software on the miner host/LAN. */
@RestController
@RequestMapping("/api/agent/local/telemetry")
@Tag(name = "PC mining agent", description = "Local PC hardware telemetry")
public class TelemetryController {
    private final HardwareTelemetryService telemetryService;
    private final WindowsLhmBootstrapService lhmBootstrapService;

    public TelemetryController(HardwareTelemetryService telemetryService,
                               WindowsLhmBootstrapService lhmBootstrapService) {
        this.telemetryService = telemetryService;
        this.lhmBootstrapService = lhmBootstrapService;
    }

    @GetMapping
    public HardwareTelemetryService.Snapshot telemetry() {
        return telemetryService.snapshot();
    }

    @org.springframework.web.bind.annotation.PostMapping("/restart")
    public boolean restartLibreHardwareMonitor() {
        return lhmBootstrapService.restartWithElevation();
    }
}
