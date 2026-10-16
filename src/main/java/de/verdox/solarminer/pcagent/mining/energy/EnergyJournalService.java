package de.verdox.solarminer.pcagent.mining.energy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.MinerCatalogService;
import de.verdox.solarminer.pcagent.mining.MiningService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Local, privacy-preserving energy ledger. Power readings are integrated only while a worker
 * reports MINING. Missing sensor values remain missing and never become invented zero usage.
 */
@Service
public class EnergyJournalService {
    private static final Logger LOGGER = Logger.getLogger(EnergyJournalService.class.getName());
    private static final Duration MAX_SAMPLE_GAP = Duration.ofSeconds(30);

    private final ObjectMapper json;
    private final MiningService mining;
    private final LocalGpuPowerService gpus;
    private final MinerCatalogService catalog;
    private final AgentControlSettingsService controls;
    private final Path directory;
    private final Map<String, ActiveSession> active = new LinkedHashMap<>();
    private volatile Settings settings;
    /** Historical journal I/O is materialized off the HTTP path after every sample/change. */
    private volatile Overview overviewSnapshot;
    private Instant lastCheckpoint = Instant.EPOCH;

    @org.springframework.beans.factory.annotation.Autowired
    public EnergyJournalService(ObjectMapper json, MiningService mining, LocalGpuPowerService gpus,
                                MinerCatalogService catalog, AgentControlSettingsService controls,
                                @Value("${solarminer.agent.energy-directory:./solarminer-agent/energy}") String directory) {
        this.json = json;
        this.mining = mining;
        this.gpus = gpus;
        this.catalog = catalog;
        this.controls = controls;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.settings = loadSettings();
    }

    /** Focused-test compatibility constructor. */
    public EnergyJournalService(ObjectMapper json, MiningService mining, LocalGpuPowerService gpus,
                                MinerCatalogService catalog, String directory) {
        this(json, mining, gpus, catalog, null, directory);
    }

    @PostConstruct
    synchronized void restoreInterruptedSessions() {
        try {
            Path state = directory.resolve("active-sessions.json");
            if (Files.isRegularFile(state)) {
                List<ActiveSession> interrupted = json.readValue(state.toFile(), new TypeReference<>() { });
                for (ActiveSession item : interrupted) append(item.finish(item.lastSampleAt, "AGENT_RESTART"));
                Files.deleteIfExists(state);
            }
        } catch (Exception failure) {
            LOGGER.log(Level.WARNING, "Could not restore the local energy journal", failure);
        }
        refreshOverviewSnapshot();
    }

    @Scheduled(fixedDelayString = "${solarminer.agent.energy-sample-ms:5000}",
            initialDelayString = "${solarminer.agent.energy-initial-delay-ms:5000}")
    public void collect() {
        try {
            List<MinerStats.Worker> workers = mining.getStats(gpus.discover()).workers();
            sample(workers, Instant.now());
        } catch (Exception failure) {
            LOGGER.log(Level.FINE, "Energy sample was unavailable", failure);
        }
    }

    synchronized void sample(List<MinerStats.Worker> workers, Instant now) {
        Map<String, MinerStats.Worker> running = new HashMap<>();
        for (MinerStats.Worker worker : workers) {
            if (worker.miningStatus() == MinerStats.MinerStatus.MINING) running.put(worker.deviceId(), worker);
        }

        for (String deviceId : new ArrayList<>(active.keySet())) {
            ActiveSession session = active.get(deviceId);
            MinerStats.Worker worker = running.get(deviceId);
            String coin = worker == null ? null : coin(worker.currentAlgorithm());
            String software = coin == null ? null : software(coin, deviceId);
            if (worker == null || !session.sameAssignment(coin, software, worker.currentAlgorithm())) {
                active.remove(deviceId);
                append(session.finish(session.lastSampleAt, worker == null ? "STOPPED" : "REASSIGNED"));
            }
        }

        for (MinerStats.Worker worker : running.values()) {
            String coin = coin(worker.currentAlgorithm());
            String software = software(coin, worker.deviceId());
            ActiveSession session = active.computeIfAbsent(worker.deviceId(), ignored -> ActiveSession.start(worker, coin, software, now));
            session.add(worker, now);
        }

        if (Duration.between(lastCheckpoint, now).abs().toSeconds() >= 30) checkpoint(now);
        refreshOverviewSnapshot();
    }

    /** Returns the last completed in-memory read model; it never lists or parses journal files. */
    public Overview overview() {
        Overview current = overviewSnapshot;
        return current == null ? emptyOverview() : current;
    }

    /** Bounded historical aggregates for the Power Consumption detail page and LAN peers. */
    public synchronized History history(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from) || Duration.between(from, to).toDays() > 366)
            throw new IllegalArgumentException("Invalid energy history range");
        List<Session> sessions = new ArrayList<>(readSessions());
        Instant now = Instant.now();
        sessions.addAll(active.values().stream().map(value -> value.snapshot(now)).toList());
        ZoneId zone = ZoneId.systemDefault();
        Map<Instant, Bucket> hours = new java.util.TreeMap<>();
        for (Instant cursor = from.truncatedTo(java.time.temporal.ChronoUnit.HOURS); cursor.isBefore(to); cursor = cursor.plus(Duration.ofHours(1)))
            hours.put(cursor, new Bucket());
        for (Session session : sessions) {
            Instant start = session.startedAt().isAfter(from) ? session.startedAt() : from;
            Instant end = session.endedAt().isBefore(to) ? session.endedAt() : to;
            if (!end.isAfter(start)) continue;
            long total = Math.max(1, Duration.between(session.startedAt(), session.endedAt()).toSeconds());
            for (Instant cursor = start.truncatedTo(java.time.temporal.ChronoUnit.HOURS); cursor.isBefore(end); cursor = cursor.plus(Duration.ofHours(1))) {
                Instant a = cursor.isAfter(start) ? cursor : start, b = cursor.plus(Duration.ofHours(1)).isBefore(end) ? cursor.plus(Duration.ofHours(1)) : end;
                long seconds = Duration.between(a, b).toSeconds();
                Bucket bucket = hours.computeIfAbsent(cursor, ignored -> new Bucket());
                Double recordedWh = session.hourlyWattHours() == null ? null : session.hourlyWattHours().get(cursor.toString());
                if (recordedWh == null) bucket.wattHours += session.wattHours() * seconds / total;
                else bucket.wattHours += recordedWh * seconds / 3600d;
                bucket.runtime += seconds;
                bucket.measured += Math.round(session.measuredSeconds() * seconds / (double) total);
                bucket.hashrateWeighted += session.averageHashrateHs() * seconds;
            }
        }
        Map<LocalDate, Bucket> days = new java.util.TreeMap<>();
        hours.forEach((instant, bucket) -> days.computeIfAbsent(instant.atZone(zone).toLocalDate(), ignored -> new Bucket()).add(bucket));
        return new History(from, to, hours.entrySet().stream().map(e -> e.getValue().point(e.getKey().toString())).toList(),
                days.entrySet().stream().map(e -> e.getValue().point(e.getKey().toString())).toList());
    }

    private static final class Bucket {
        double wattHours, hashrateWeighted; long runtime, measured;
        void add(Bucket other) { wattHours += other.wattHours; runtime += other.runtime; measured += other.measured; hashrateWeighted += other.hashrateWeighted; }
        HistoryPoint point(String at) { return new HistoryPoint(at, wattHours / 1000d, runtime == 0 ? 0 : hashrateWeighted / runtime, runtime, measured); }
    }

    /** Called only by lifecycle/sample/write paths, never by a dashboard GET. */
    private synchronized void refreshOverviewSnapshot() {
        overviewSnapshot = buildOverview();
    }

    private Overview buildOverview() {
        Instant now = Instant.now();
        List<Session> persisted = readSessions();
        List<Session> recent = persisted.stream()
                .sorted(Comparator.comparing(Session::startedAt).reversed()).limit(100).toList();
        List<Session> current = active.values().stream().map(value -> value.snapshot(now)).toList();
        List<Session> all = new ArrayList<>(persisted);
        all.addAll(current);
        ZoneId zone = ZoneId.systemDefault();
        Instant today = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        return new Overview(settings, aggregate(all, today, now),
                aggregate(all, now.minus(Duration.ofDays(7)), now),
                aggregate(all, now.minus(Duration.ofDays(30)), now), current, recent);
    }

    private Overview emptyOverview() {
        Aggregate empty = new Aggregate(BigDecimal.ZERO.setScale(5), BigDecimal.ZERO.setScale(2), 0, 0, 0);
        return new Overview(settings, empty, empty, empty, List.of(), List.of());
    }

    public synchronized Settings updateSettings(Settings next) {
        if (next == null || next.pricePerKwh() == null || next.pricePerKwh().signum() < 0
                || next.pricePerKwh().compareTo(new BigDecimal("100")) > 0
                || !java.util.Set.of("EUR", "USD", "CHF").contains(next.currency()))
            throw new IllegalArgumentException("Ungültiger Stromtarif");
        settings = new Settings(next.pricePerKwh().setScale(4, RoundingMode.HALF_UP), next.currency());
        writeAtomic(directory.resolve("settings.json"), settings);
        refreshOverviewSnapshot();
        return settings;
    }

    private Aggregate aggregate(List<Session> sessions, Instant from, Instant to) {
        double wattHours = 0;
        long runtimeSeconds = 0;
        long measuredSeconds = 0;
        for (Session session : sessions) {
            if (!session.endedAt().isAfter(from) || !session.startedAt().isBefore(to)) continue;
            Instant overlapStart = session.startedAt().isAfter(from) ? session.startedAt() : from;
            Instant overlapEnd = session.endedAt().isBefore(to) ? session.endedAt() : to;
            long overlap = Math.max(0, Duration.between(overlapStart, overlapEnd).toSeconds());
            double fraction = session.runtimeSeconds() <= 0 ? 0 : Math.min(1, overlap / (double) session.runtimeSeconds());
            wattHours += session.wattHours() * fraction;
            runtimeSeconds += overlap;
            measuredSeconds += Math.round(session.measuredSeconds() * fraction);
        }
        BigDecimal kwh = BigDecimal.valueOf(wattHours / 1000.0).setScale(5, RoundingMode.HALF_UP);
        BigDecimal cost = kwh.multiply(settings.pricePerKwh()).setScale(2, RoundingMode.HALF_UP);
        return new Aggregate(kwh, cost, runtimeSeconds, measuredSeconds,
                runtimeSeconds == 0 ? 0 : Math.min(1, measuredSeconds / (double) runtimeSeconds));
    }

    private String software(String coin, String deviceId) {
        MinerCatalogService.MinerOption selected = catalog.selected(coin);
        String fallback = selected == null ? "unknown" : selected.id();
        return controls == null ? fallback : controls.get().minerFor(deviceId, fallback);
    }

    private static String coin(String algorithm) {
        return Coin.byAlgorithm(algorithm).map(Coin::id).orElse("unknown");
    }

    private void checkpoint(Instant now) {
        writeAtomic(directory.resolve("active-sessions.json"), new ArrayList<>(active.values()));
        lastCheckpoint = now;
    }

    private void append(Session session) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("sessions-" + YearMonth.from(session.startedAt().atZone(ZoneId.systemDefault())) + ".jsonl");
            Files.writeString(file, json.writeValueAsString(session) + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException failure) {
            LOGGER.log(Level.WARNING, "Could not persist an energy session", failure);
        }
    }

    private List<Session> readSessions() {
        if (!Files.isDirectory(directory)) return List.of();
        List<Session> result = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("sessions-\\d{4}-\\d{2}\\.jsonl"))
                    .sorted(Comparator.reverseOrder()).limit(13).toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    try { result.add(json.readValue(line, Session.class)); }
                    catch (Exception ignored) { }
                }
            }
        } catch (IOException failure) {
            LOGGER.log(Level.FINE, "Could not read energy sessions", failure);
        }
        return result;
    }

    private Settings loadSettings() {
        try { return json.readValue(directory.resolve("settings.json").toFile(), Settings.class); }
        catch (Exception ignored) { return new Settings(new BigDecimal("0.3000"), "EUR"); }
    }

    private void writeAtomic(Path target, Object value) {
        if (!de.verdox.solarminer.pcagent.support.AtomicFileStore.writeJson(target, value, json,
                target.getFileName().toString())) {
            LOGGER.log(Level.WARNING, "Could not checkpoint the energy journal");
        }
    }

    @PreDestroy
    synchronized void shutdown() {
        checkpoint(Instant.now());
    }

    public record Settings(BigDecimal pricePerKwh, String currency) { }
    public record Aggregate(BigDecimal kilowattHours, BigDecimal cost, long runtimeSeconds,
                            long measuredSeconds, double measurementCoverage) { }
    public record Overview(Settings settings, Aggregate today, Aggregate last7Days, Aggregate last30Days,
                           List<Session> activeSessions, List<Session> recentSessions) { }
    public record History(Instant from, Instant to, List<HistoryPoint> hourly, List<HistoryPoint> daily) { }
    public record HistoryPoint(String at, double kilowattHours, double averageHashrateHs, long runtimeSeconds, long measuredSeconds) { }
    public record Session(String id, String deviceId, String hardwareType, String hardwareModel,
                          String coin, String minerSoftwareId, String algorithm,
                          Instant startedAt, Instant endedAt, long runtimeSeconds, long measuredSeconds,
                          double wattHours, double averageWatts, double maximumWatts,
                          double averageHashrateHs, Long acceptedShares, Long rejectedShares,
                          String measurementSource, String endReason, Map<String, Double> hourlyWattHours) {
        public Session(String id, String deviceId, String hardwareType, String hardwareModel,
                       String coin, String minerSoftwareId, String algorithm,
                       Instant startedAt, Instant endedAt, long runtimeSeconds, long measuredSeconds,
                       double wattHours, double averageWatts, double maximumWatts,
                       double averageHashrateHs, Long acceptedShares, Long rejectedShares,
                       String measurementSource, String endReason) {
            this(id, deviceId, hardwareType, hardwareModel, coin, minerSoftwareId, algorithm, startedAt, endedAt,
                    runtimeSeconds, measuredSeconds, wattHours, averageWatts, maximumWatts, averageHashrateHs,
                    acceptedShares, rejectedShares, measurementSource, endReason, null);
        }
    }

    static final class ActiveSession {
        public String id;
        public String deviceId;
        public String hardwareType;
        public String hardwareModel;
        public String coin;
        public String minerSoftwareId;
        public String algorithm;
        public Instant startedAt;
        public Instant lastSampleAt;
        public double lastWatts;
        public boolean lastMeasured;
        public double wattHours;
        public long measuredSeconds;
        public double maximumWatts;
        public double weightedHashrate;
        public long hashrateSeconds;
        public Long acceptedAtStart;
        public Long rejectedAtStart;
        public Long acceptedLast;
        public Long rejectedLast;
        public String measurementSource;
        public Map<String, Double> hourlyWattHours = new LinkedHashMap<>();

        public ActiveSession() { }

        static ActiveSession start(MinerStats.Worker worker, String coin, String software, Instant now) {
            ActiveSession session = new ActiveSession();
            session.id = UUID.randomUUID().toString();
            session.deviceId = worker.deviceId();
            session.hardwareType = worker.hardwareType();
            session.hardwareModel = worker.hardwareModel();
            session.coin = coin;
            session.minerSoftwareId = software;
            session.algorithm = worker.currentAlgorithm();
            session.startedAt = now;
            session.lastSampleAt = now;
            session.lastWatts = Math.max(0, worker.approximatedPowerUsageWatts());
            session.lastMeasured = worker.approximatedPowerUsageWatts() > 0;
            session.maximumWatts = session.lastWatts;
            session.acceptedAtStart = worker.acceptedShares();
            session.rejectedAtStart = worker.rejectedShares();
            session.acceptedLast = worker.acceptedShares();
            session.rejectedLast = worker.rejectedShares();
            session.measurementSource = "CPU".equals(worker.hardwareType()) ? "CPU_PACKAGE" : "GPU_BOARD";
            return session;
        }

        boolean sameAssignment(String nextCoin, String nextSoftware, String nextAlgorithm) {
            return java.util.Objects.equals(coin, nextCoin) && java.util.Objects.equals(minerSoftwareId, nextSoftware)
                    && java.util.Objects.equals(algorithm, nextAlgorithm);
        }

        void add(MinerStats.Worker worker, Instant now) {
            long seconds = Math.max(0, Duration.between(lastSampleAt, now).toSeconds());
            boolean measured = worker.approximatedPowerUsageWatts() > 0;
            double watts = measured ? worker.approximatedPowerUsageWatts() : 0;
            if (seconds > 0 && seconds <= MAX_SAMPLE_GAP.toSeconds()) {
                if (lastMeasured && measured) {
                    double intervalWh = ((lastWatts + watts) / 2.0) * seconds / 3600.0;
                    wattHours += intervalWh;
                    Instant cursor = lastSampleAt;
                    while (cursor.isBefore(now)) {
                        Instant bucketStart = cursor.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
                        Instant next = bucketStart.plus(Duration.ofHours(1));
                        Instant segmentEnd = next.isBefore(now) ? next : now;
                        long segmentSeconds = Duration.between(cursor, segmentEnd).toSeconds();
                        hourlyWattHours.merge(bucketStart.toString(), intervalWh * segmentSeconds / seconds, Double::sum);
                        cursor = segmentEnd;
                    }
                    measuredSeconds += seconds;
                    maximumWatts = Math.max(maximumWatts, watts);
                }
                double hashrate = Math.max(0, worker.terahashPerSecond() * 1_000_000_000_000.0);
                weightedHashrate += hashrate * seconds;
                hashrateSeconds += seconds;
            }
            lastSampleAt = now;
            lastWatts = watts;
            lastMeasured = measured;
            acceptedLast = worker.acceptedShares();
            rejectedLast = worker.rejectedShares();
        }

        Session snapshot(Instant now) { return finish(now, "RUNNING"); }

        Session finish(Instant endedAt, String reason) {
            long runtime = Math.max(0, Duration.between(startedAt, endedAt).toSeconds());
            double average = measuredSeconds == 0 ? 0 : wattHours * 3600.0 / measuredSeconds;
            return new Session(id, deviceId, hardwareType, hardwareModel, coin, minerSoftwareId, algorithm,
                    startedAt, endedAt, runtime, measuredSeconds, wattHours, average, maximumWatts,
                    hashrateSeconds == 0 ? 0 : weightedHashrate / hashrateSeconds,
                    delta(acceptedAtStart, acceptedLast), delta(rejectedAtStart, rejectedLast), measurementSource, reason,
                    Map.copyOf(hourlyWattHours));
        }

        private static Long delta(Long start, Long end) {
            if (start == null || end == null || end < start) return null;
            return end - start;
        }
    }
}
