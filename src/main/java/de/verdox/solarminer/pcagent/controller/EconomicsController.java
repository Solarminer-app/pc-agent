package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.energy.EnergyJournalService;
import de.verdox.solarminer.pcagent.mining.economics.MarketDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The single economics read/write resource (refactoring Phase 5d, owner decision O3): the
 * former micro-controllers {@code EnergyController} and {@code FiatRateController} were
 * merged here when the dashboard moved to these paths. Payload shapes are unchanged — only
 * the route namespace consolidated. Local dashboard contract only.
 */
@RestController
@RequestMapping("/api/agent/local/economics")
public class EconomicsController {
    private final EnergyJournalService energy;
    private final MarketDataService rates;
    private final AgentStatusService status;
    private final de.verdox.solarminer.pcagent.mining.network.NetworkDashboardService network;

    @org.springframework.beans.factory.annotation.Autowired
    public EconomicsController(EnergyJournalService energy, MarketDataService rates,
                               @org.springframework.context.annotation.Lazy AgentStatusService status,
                               de.verdox.solarminer.pcagent.mining.network.NetworkDashboardService network) {
        this.energy = energy;
        this.rates = rates;
        this.status = status;
        this.network = network;
    }

    EconomicsController(EnergyJournalService energy, MarketDataService rates,
                        @org.springframework.context.annotation.Lazy AgentStatusService status) {
        this(energy, rates, status, null);
    }

    /** Energy journal overview (today, 7 days, active sessions, tariff settings). */
    @GetMapping
    public EnergyJournalService.Overview overview() { return energy.overview(); }

    @GetMapping("/history")
    public EnergyJournalService.History history(@org.springframework.web.bind.annotation.RequestParam java.time.Instant from,
                                                 @org.springframework.web.bind.annotation.RequestParam java.time.Instant to) {
        return network == null ? energy.history(from, to) : network.networkEnergyHistory(from, to);
    }

    @PostMapping("/settings")
    public EnergyJournalService.Settings settings(@RequestBody EnergyJournalService.Settings settings) {
        EnergyJournalService.Settings saved = energy.updateSettings(settings);
        if (status != null) status.invalidate("energy");
        return saved;
    }

    /** Fiat exchange rates for the currency preference layer (TTL-cached upstream). */
    @GetMapping("/rates")
    public MarketDataService.Snapshot rates() { return rates.rates(); }
}
