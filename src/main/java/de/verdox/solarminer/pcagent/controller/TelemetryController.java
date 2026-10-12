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
    private final AgentStatusService status;

    public TelemetryController(HardwareTelemetryService telemetryService,
                               WindowsLhmBootstrapService lhmBootstrapService,
                               @org.springframework.context.annotation.Lazy AgentStatusService status) {
        this.telemetryService = telemetryService;
        this.lhmBootstrapService = lhmBootstrapService;
        this.status = status;
    }

    @GetMapping
    public HardwareTelemetryService.Snapshot telemetry() {
        return telemetryService.snapshot();
    }

    @org.springframework.web.bind.annotation.PostMapping("/restart")
    public boolean restartLibreHardwareMonitor() {
        boolean restarted = lhmBootstrapService.restartWithElevation();
        if (restarted && status != null) status.invalidate("telemetry", "overview");
        return restarted;
    }
}
