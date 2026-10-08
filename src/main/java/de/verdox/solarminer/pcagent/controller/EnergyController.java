package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.EnergyJournalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/local/energy")
public class EnergyController {
    private final EnergyJournalService energy;

    public EnergyController(EnergyJournalService energy) { this.energy = energy; }

    @GetMapping
    public EnergyJournalService.Overview overview() { return energy.overview(); }

    @PostMapping("/settings")
    public EnergyJournalService.Settings settings(@RequestBody EnergyJournalService.Settings settings) {
        return energy.updateSettings(settings);
    }
}
