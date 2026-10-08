package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.FiatRateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/local/fiat-rates")
public class FiatRateController {
    private final FiatRateService rates;

    public FiatRateController(FiatRateService rates) { this.rates = rates; }

    @GetMapping
    public FiatRateService.Snapshot rates() { return rates.rates(); }
}
