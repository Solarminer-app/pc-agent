package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.lowlevel.GpuPrerequisiteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent/local/gpu-prerequisites")
public class GpuPrerequisiteController {
    private final GpuPrerequisiteService service;

    public GpuPrerequisiteController(GpuPrerequisiteService service) { this.service = service; }

    @GetMapping
    public List<GpuPrerequisiteService.DeviceStatus> status() { return service.snapshot(); }
}
