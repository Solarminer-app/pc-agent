package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.economics.CoinHoppingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/agent/local/coin-hopping")
public class CoinHoppingController {
    private final CoinHoppingService hopping;

    public CoinHoppingController(CoinHoppingService hopping) { this.hopping = hopping; }

    @GetMapping
    public CoinHoppingService.View view(@RequestParam(required = false) String deviceId,
                                       @RequestParam(required = false) Integer targetWatts) {
        return deviceId == null || targetWatts == null ? hopping.view() : hopping.view(deviceId, targetWatts);
    }

    @PostMapping
    public CoinHoppingService.View configure(@RequestBody CoinHoppingService.Settings settings) {
        try {
            hopping.configure(settings);
            return hopping.view();
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, failure.getMessage());
        } catch (IllegalStateException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, failure.getMessage());
        }
    }
}
