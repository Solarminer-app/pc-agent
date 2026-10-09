package de.verdox.solarminer.pcagent.mining;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Periodic opt-in-only benchmark reporting; separate from user-triggered benchmark sessions.
 */
@Service
public class BenchmarkSharingService {
    private final MiningService mining;
    private final RestClient client;
    private final ObjectMapper json;
    private final Path identityFile;
    private final Path consentFile;
    private volatile State state;
    private volatile ReportStatus manualReportStatus = ReportStatus.idle("MANUAL");
    private volatile ReportStatus periodicReportStatus = ReportStatus.idle("PERIODIC");
    private List<Sample> retrySamples = List.of();
    private List<EfficiencySample> retryEfficiencySamples = List.of();

    public BenchmarkSharingService(MiningService mining, ObjectMapper json,
                                   RestClient.Builder client, @Value("${solarminer.agent.benchmark.url:https://portal.solarminer.app}") String url,
                                   @Value("${solarminer.agent.benchmark.state-file:./solarminer-agent/benchmark-sharing.json}") String statePath) {
        this.mining = mining;
        this.json = json;
        this.client = client.baseUrl(url.replaceAll("/+$", "")).build();
        Path stateFile = Path.of(statePath).toAbsolutePath().normalize();
        this.identityFile = stateFile.resolveSibling("benchmark-participant.id");
        this.consentFile = stateFile;
        this.state = loadState();
    }

    @Scheduled(fixedDelayString = "${solarminer.agent.benchmark.interval-ms:900000}", initialDelayString = "${solarminer.agent.benchmark.initial-delay-ms:60000}")
    public synchronized void report() {
        boolean optedIn = state.sharingEnabled();
        if (!optedIn && !state.previouslyShared()) return;
        try {
            List<Sample> samples = optedIn ? sampleWorkers(mining.getWorkerStats()) : List.of();
            send(optedIn, samples, List.of());
            periodicReportStatus = new ReportStatus("PERIODIC", "SENT",
                    optedIn ? "Regelmäßiger Benchmark-Upload erfolgreich übermittelt." : "Widerruf der Benchmark-Freigabe übermittelt.",
                    samples.size(), Instant.now());
        } catch (RuntimeException e) {
            periodicReportStatus = new ReportStatus("PERIODIC", "FAILED", "Regelmäßiger Benchmark-Upload fehlgeschlagen; der Agent versucht es beim nächsten Intervall erneut.", 0, Instant.now());
        }
    }

    public synchronized ReportStatus reportManualResults(List<MinerStats.Worker> workers) {
        // Keep the samples even when sharing is off so the post-benchmark prompt can offer them
        // for upload right after the operator grants consent.
        retrySamples = sampleWorkers(workers);
        retryEfficiencySamples = List.of();
        if (!state.sharingEnabled())
            return manualReportStatus = new ReportStatus("MANUAL", "NOT_SHARED", "Benchmarkdaten wurden nicht hochgeladen: Teilen ist deaktiviert.", retrySamples.size(), Instant.now());
        if (retrySamples.isEmpty())
            return manualReportStatus = new ReportStatus("MANUAL", "NO_DATA", "Keine geeigneten Benchmark-Messwerte zum Hochladen vorhanden.", 0, Instant.now());
        return deliverManual(retrySamples, List.of());
    }

    public synchronized ReportStatus retryManualResults() {
        if (!state.sharingEnabled())
            return manualReportStatus = new ReportStatus("MANUAL", "NOT_SHARED", "Benchmarkdaten wurden nicht hochgeladen: Teilen ist deaktiviert.", 0, Instant.now());
        if (retrySamples.isEmpty() && retryEfficiencySamples.isEmpty())
            return manualReportStatus = new ReportStatus("MANUAL", "NO_DATA", "Keine gespeicherten Benchmark-Messwerte zum erneuten Hochladen vorhanden.", 0, Instant.now());
        return deliverManual(retrySamples, retryEfficiencySamples);
    }

    public synchronized ReportStatus reportEfficiencyResults(List<GpuEfficiencyStore.Profile> profiles) {
        retrySamples = List.of();
        retryEfficiencySamples = efficiencySamples(profiles);
        if (!state.sharingEnabled())
            return manualReportStatus = new ReportStatus("MANUAL", "NOT_SHARED",
                    "Effizienzkurven wurden nicht hochgeladen: Teilen ist deaktiviert.",
                    retryEfficiencySamples.size(), Instant.now());
        if (retryEfficiencySamples.isEmpty())
            return manualReportStatus = new ReportStatus("MANUAL", "NO_DATA",
                    "Keine Effizienz-Messpunkte zum Hochladen vorhanden.", 0, Instant.now());
        return deliverManual(List.of(), retryEfficiencySamples);
    }

    /**
     * One-time upload of the retained manual samples after the post-benchmark prompt.
     * The consent applies to this batch only: the periodic sharing state is deliberately
     * left untouched, so the operator's "sharing off" setting keeps suppressing future sends.
     */
    public synchronized ReportStatus uploadManualResultsOnce() {
        if (retrySamples.isEmpty() && retryEfficiencySamples.isEmpty())
            return manualReportStatus = new ReportStatus("MANUAL", "NO_DATA", "Keine gespeicherten Benchmark-Messwerte zum erneuten Hochladen vorhanden.", 0, Instant.now());
        int sampleCount = retrySamples.size() + retryEfficiencySamples.size();
        manualReportStatus = new ReportStatus("MANUAL", "UPLOADING", "Benchmarkdaten werden hochgeladen …", sampleCount, Instant.now());
        try {
            postBatches(true, retrySamples, retryEfficiencySamples);
            retrySamples = List.of();
            retryEfficiencySamples = List.of();
            return manualReportStatus = new ReportStatus("MANUAL", "SENT", "Benchmarkdaten erfolgreich ans Backend übermittelt.", sampleCount, Instant.now());
        } catch (RuntimeException e) {
            return manualReportStatus = new ReportStatus("MANUAL", "FAILED", "Upload ans Backend fehlgeschlagen. Du kannst den Upload erneut versuchen.", sampleCount, Instant.now());
        }
    }

    public UploadStatuses uploadStatuses() { return new UploadStatuses(manualReportStatus, periodicReportStatus); }

    private ReportStatus deliverManual(List<Sample> samples, List<EfficiencySample> efficiencySamples) {
        int sampleCount = samples.size() + efficiencySamples.size();
        manualReportStatus = new ReportStatus("MANUAL", "UPLOADING", "Benchmarkdaten werden hochgeladen …", sampleCount, Instant.now());
        try {
            send(true, samples, efficiencySamples);
            retrySamples = List.of();
            retryEfficiencySamples = List.of();
            return manualReportStatus = new ReportStatus("MANUAL", "SENT", "Benchmarkdaten erfolgreich ans Backend übermittelt.", sampleCount, Instant.now());
        } catch (RuntimeException e) {
            return manualReportStatus = new ReportStatus("MANUAL", "FAILED", "Upload ans Backend fehlgeschlagen. Du kannst den Upload erneut versuchen.", sampleCount, Instant.now());
        }
    }

    public synchronized boolean sharingEnabled() { return state.sharingEnabled(); }

    public synchronized boolean setSharingEnabled(boolean enabled) {
        try { writeState(new State(state.previouslyShared(), enabled)); return true; }
        catch (RuntimeException e) { return false; }
    }

    private List<Sample> sampleWorkers(List<MinerStats.Worker> workers) {
        return workers.stream()
                    .filter(w -> w.miningStatus() == MinerStats.MinerStatus.MINING)
                    .filter(w -> positive(w.terahashPerSecond()) && present(w.hardwareType()) && present(w.hardwareModel()) && present(w.currentAlgorithm()))
                    .collect(java.util.stream.Collectors.toMap(this::workerKey, this::sample, (a, b) -> b,
                            java.util.LinkedHashMap::new)).values().stream().toList();
    }

    private String workerKey(MinerStats.Worker worker) {
        return Objects.toString(worker.deviceId(), worker.hardwareModel()) + ":" + worker.currentAlgorithm();
    }

    private void send(boolean optedIn, List<Sample> samples, List<EfficiencySample> efficiencySamples) {
        postBatches(optedIn, samples, efficiencySamples);
        writeState(new State(optedIn, optedIn));
    }

    private void postBatches(boolean optedIn, List<Sample> samples, List<EfficiencySample> efficiencySamples) {
        if (efficiencySamples.isEmpty()) {
            client.post().uri("/api/telemetry/standalone-benchmarks")
                    .body(new Batch(participantId(), optedIn, samples, List.of())).retrieve().toBodilessEntity();
            return;
        }
        for (int offset = 0; offset < efficiencySamples.size(); offset += 512) {
            int end = Math.min(efficiencySamples.size(), offset + 512);
            client.post().uri("/api/telemetry/standalone-benchmarks")
                    .body(new Batch(participantId(), optedIn, offset == 0 ? samples : List.of(),
                            efficiencySamples.subList(offset, end))).retrieve().toBodilessEntity();
        }
    }

    public Map<String, Object> comparison(String hardwareType, String hardwareModel, String algorithm) {
        try {
            return client.get().uri(uri -> uri.path("/api/public/benchmarks/match")
                            .queryParam("hardwareType", hardwareType).queryParam("hardwareModel", hardwareModel)
                            .queryParam("algorithm", algorithm).build())
                    .retrieve().body(new ParameterizedTypeReference<>() {
                    });
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Sample sample(MinerStats.Worker worker) {
        String identity = worker.deviceId() == null || worker.deviceId().isBlank() ? worker.workerDisplayName() : worker.deviceId();
        String deviceKey = deviceKey(identity);
        return new Sample(deviceKey, worker.hardwareType(), worker.hardwareModel(), worker.currentAlgorithm(),
                System.getProperty("os.name", "unknown"), System.getProperty("os.arch", "unknown"),
                worker.terahashPerSecond() * 1_000_000_000_000d, positive(worker.approximatedPowerUsageWatts()) ? (double) worker.approximatedPowerUsageWatts() : null,
                positive(worker.powerTargetWatts()) ? (double) worker.powerTargetWatts() : null);
    }

    private List<EfficiencySample> efficiencySamples(List<GpuEfficiencyStore.Profile> profiles) {
        if (profiles == null) return List.of();
        Map<String, EfficiencySample> points = new java.util.LinkedHashMap<>();
        for (GpuEfficiencyStore.Profile profile : profiles) {
            if (profile == null || !present(profile.deviceId()) || !present(profile.model())
                    || !present(profile.coin()) || !present(profile.algorithm()) || profile.steps() == null) continue;
            String pseudonym = deviceKey(profile.deviceId());
            for (GpuEfficiencyStore.StepResult step : profile.steps()) {
                if (step == null || step.limitWatts() <= 0) continue;
                EfficiencySample sample = new EfficiencySample(pseudonym, "GPU", profile.model(), profile.coin(),
                        profile.algorithm(), step.limitWatts(), positiveOrNull(step.medianHashrateHs()),
                        positiveOrNull(step.avgPowerWatts()), finiteOrNull(step.maxTemperatureC()),
                        step.stable(), step.note());
                points.put(String.join("|", pseudonym, profile.coin(), profile.algorithm(),
                        Integer.toString(step.limitWatts())), sample);
            }
        }
        return List.copyOf(points.values());
    }

    private String deviceKey(String identity) {
        return UUID.nameUUIDFromBytes((participantId() + ":" + identity).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Double positiveOrNull(Double value) {
        return value != null && positive(value) ? value : null;
    }

    private static Double finiteOrNull(Double value) {
        return value != null && Double.isFinite(value) ? value : null;
    }

    private String participantId() {
        try {
            if (Files.isRegularFile(identityFile)) return Files.readString(identityFile).trim();
            Files.createDirectories(identityFile.getParent());
            String id = UUID.randomUUID().toString();
            Files.writeString(identityFile, id);
            return id;
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist anonymous benchmark identity", e);
        }
    }

    private State loadState() {
        try {
            return json.readValue(Files.readString(consentFile), State.class);
        } catch (Exception ignored) {
            return new State(false, false);
        }
    }

    private void writeState(State value) {
        try {
            Files.createDirectories(consentFile.getParent());
            Path temp = Files.createTempFile(consentFile.getParent(), "benchmark-", ".json");
            try {
                json.writeValue(temp.toFile(), value);
                Files.move(temp, consentFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, consentFile, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            state = value;
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist benchmark consent state", e);
        }
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank() && !"-".equals(value);
    }

    public record Batch(String uuid, boolean telemetryOptIn, List<Sample> benchmarks,
                        List<EfficiencySample> efficiencySweeps) {
    }

    public record Sample(String deviceKey, String hardwareType, String hardwareModel, String algorithm,
                         String minerOs, String processorArchitecture, double hashrateHs,
                         Double powerWatts, Double powerTargetWatts) {
    }

    public record EfficiencySample(String deviceKey, String hardwareType, String hardwareModel, String coin,
                                   String algorithm, int powerLimitWatts, Double hashrateHs, Double powerWatts,
                                   Double maxTemperatureC, boolean stable, String note) {
    }

    public record State(boolean previouslyShared, boolean sharingEnabled) {
    }

    public record ReportStatus(String source, String status, String message, int sampleCount, Instant updatedAt) {
        static ReportStatus idle(String source) { return new ReportStatus(source, "IDLE", "", 0, null); }
    }
    public record UploadStatuses(ReportStatus manual, ReportStatus periodic) { }
}
