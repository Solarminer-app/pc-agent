package de.verdox.solarminer.pcagent.controller;

import de.verdox.solarminer.pcagent.mining.measurement.BenchmarkSessionService;
import de.verdox.solarminer.pcagent.mining.measurement.BenchmarkSharingService;
import de.verdox.solarminer.pcagent.mining.measurement.MiningPerformanceProfileStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/local/benchmarks")
public class BenchmarkController {
    private final BenchmarkSessionService sessions;
    private final BenchmarkSharingService sharing;
    private final AgentStatusService status;
    private final MiningPerformanceProfileStore profiles;

    public BenchmarkController(BenchmarkSessionService sessions, BenchmarkSharingService sharing,
                               AgentStatusService status, MiningPerformanceProfileStore profiles) {
        this.sessions = sessions;
        this.sharing = sharing;
        this.status = status;
        this.profiles = profiles;
    }

    @GetMapping
    public BenchmarkSessionService.Session status() {
        return sessions.status();
    }

    @GetMapping("/profiles")
    public List<MiningPerformanceProfileStore.Profile> profiles() { return profiles.all(); }

    @GetMapping("/profiles/storage")
    public MiningPerformanceProfileStore.StorageStatus profileStorage() { return profiles.storageStatus(); }

    @GetMapping("/match")
    public Map<String, Object> comparison(@RequestParam String hardwareType,
                                          @RequestParam String hardwareModel, @RequestParam String algorithm) {
        return sharing.comparison(hardwareType, hardwareModel, algorithm);
    }

    @PostMapping
    public BenchmarkSessionService.Session start(@RequestBody StartRequest request) {
        try {
            BenchmarkSessionService.Session session = sessions.start(request.mode(), request.coins());
            // The benchmarks/efficiency channels have their own producers; wake them by name.
            status.invalidate("benchmarks", "efficiency", "overview", "power-control", "workers");
            return session;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/cancel")
    public BenchmarkSessionService.Session cancel() {
        BenchmarkSessionService.Session session = sessions.cancel();
        status.invalidate("benchmarks", "efficiency", "overview", "power-control", "workers");
        return session;
    }

    @GetMapping("/sharing")
    public boolean sharing() {
        return sharing.sharingEnabled();
    }

    @PostMapping("/sharing")
    public boolean sharing(@RequestBody SharingRequest request) {
        if (request == null || !sharing.setSharingEnabled(request.enabled()))
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save benchmark sharing choice");
        status.invalidate("benchmark-upload");
        return sharing.sharingEnabled();
    }

    @GetMapping("/sharing/upload-status")
    public BenchmarkSharingService.UploadStatuses uploadStatus() {
        return sharing.uploadStatuses();
    }

    @PostMapping("/sharing/retry-manual")
    public BenchmarkSharingService.ReportStatus retryManualReport() {
        BenchmarkSharingService.ReportStatus result = sharing.retryManualResults();
        status.invalidate("benchmark-upload");
        return result;
    }

    @PostMapping("/sharing/upload-manual")
    public BenchmarkSharingService.ReportStatus uploadManualReport() {
        BenchmarkSharingService.ReportStatus result = sharing.uploadManualResultsOnce();
        status.invalidate("benchmark-upload");
        return result;
    }

    public record StartRequest(String mode, List<String> coins) { }

    public record SharingRequest(boolean enabled) {
    }
}
