package de.verdox.solarminer.pcagent.console;

import de.verdox.solarminer.pcagent.mining.WorkerAssignmentService;
import de.verdox.solarminer.pcagent.mining.measurement.BenchmarkSessionService;
import de.verdox.solarminer.pcagent.mining.measurement.EfficiencySweepService;
import de.verdox.solarminer.pcagent.coin.SweepRunState;
import de.verdox.solarminer.pcagent.mining.agent.AgentIdentityService;
import de.verdox.solarminer.pcagent.mining.network.AgentNetworkDiscoveryService;
import de.verdox.solarminer.pcagent.mining.network.NetworkDashboardService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/** Independent console read model. HTTP reads never discover hardware or contact peers. */
@Service
public class ConsoleWorkspaceService {
    public static final long LOCAL_MAX_AGE_MS = 8_000, PEER_MAX_AGE_MS = 15_000;
    private final WorkerAssignmentService workers;
    private final AgentIdentityService identity;
    private final AgentNetworkDiscoveryService discovery;
    private final NetworkDashboardService network;
    private final BenchmarkSessionService benchmarks;
    private final EfficiencySweepService sweeps;
    private volatile LocalSnapshot local = new LocalSnapshot(null, List.of());
    private volatile long lastRead;

    public ConsoleWorkspaceService(WorkerAssignmentService workers, AgentIdentityService identity,
                                   AgentNetworkDiscoveryService discovery, NetworkDashboardService network,
                                   BenchmarkSessionService benchmarks, EfficiencySweepService sweeps) {
        this.workers = workers;
        this.identity = identity;
        this.discovery = discovery;
        this.network = network;
        this.benchmarks = benchmarks;
        this.sweeps = sweeps;
    }

    /** Sleep when the new console is unused; specialist/legacy pages keep their own collectors. */
    @Scheduled(fixedDelayString = "${solarminer.agent.console-refresh-ms:2000}")
    public synchronized void collect() {
        if (System.currentTimeMillis() - lastRead > 60_000) return;
        try {
            List<WorkerAssignmentService.WorkerView> collected = List.copyOf(workers.workers());
            local = new LocalSnapshot(Instant.now(), collected);
        } catch (RuntimeException unavailable) {
            // Keep the original timestamp; failed collections must never rejuvenate old state.
        }
    }

    private ReadSnapshot materialize() {
        lastRead = System.currentTimeMillis();
        Instant now = Instant.now();
        LocalSnapshot source = local;
        List<Agent> agents = new ArrayList<>();
        List<Worker> rows = new ArrayList<>();
        String ownId = discovery.agentId();
        agents.add(new Agent(ownId, identity.displayName(), true, true, source.at(), null,
                source.workers().size()));
        var benchmark = benchmarks.status();
        var sweep = sweeps.status();
        Set<String> benchmarkIds = benchmark.running() ? new HashSet<>(benchmark.selectedWorkers()) : Set.of();
        Set<String> sweepingIds = sweep.running() ? sweep.runs().stream()
                .filter(run -> run.status() == SweepRunState.RUNNING).map(EfficiencySweepService.RunStatus::deviceId)
                .collect(java.util.stream.Collectors.toSet()) : Set.of();
        for (var worker : source.workers()) rows.add(localWorker(ownId, worker, source.at(),
                benchmarkIds.contains(worker.deviceId()), sweepingIds.contains(worker.deviceId())));
        for (var card : network.cards()) {
            if (card.own()) continue;
            String reason = !card.reachable() ? "console_unreachable"
                    : card.error() != null ? "console_sharing_disabled" : null;
            agents.add(new Agent(card.agentId(), card.name(), false, card.reachable(), card.collectedAt(),
                    reason, card.workers() == null ? 0 : card.workers().size()));
            int legacyRow = 0;
            for (var worker : card.workers() == null ? List.<NetworkDashboardService.WorkerRow>of() : card.workers()) {
                String gate = worker.deviceId() == null || worker.deviceId().isBlank() ? "console_worker_identity_missing"
                        : reason != null ? reason : !card.controlEnabled() ? "console_remote_disabled"
                        : !Boolean.TRUE.equals(worker.controlAllowed()) ? "console_worker_disabled" : null;
                String start = gate != null ? gate : !Boolean.TRUE.equals(worker.configured()) ? "console_setup_required"
                        : !Boolean.TRUE.equals(worker.minerInstalled()) ? "console_install_required" : null;
                rows.add(new Worker(card.agentId(), worker.deviceId(), false, worker.hardwareModel(),
                        worker.hardwareType(), worker.coin(), worker.algorithm(), null, worker.status(),
                        positive(worker.hashrateHps()), positive(worker.watts()), null, null, null, null,
                        null, Boolean.TRUE.equals(worker.benchmarking()), Boolean.TRUE.equals(worker.sweeping()),
                        card.collectedAt(), start, gate, false, worker.deviceId() == null || worker.deviceId().isBlank()
                                ? "legacy:" + legacyRow++ : "device:" + worker.deviceId()));
            }
        }
        Map<String, Agent> byId = new HashMap<>();
        agents.forEach(agent -> byId.put(agent.agentId(), agent));
        List<Worker> aged = rows.stream().map(worker -> age(worker, now)).toList();
        return new ReadSnapshot(now, List.copyOf(agents), aged, byId);
    }

    public Workspace view(String query, String agentId, String state, int page, int size) {
        ReadSnapshot read = materialize();
        List<Worker> aged = read.workers();
        Map<String, Agent> byId = read.byId();
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<Worker> filtered = aged.stream()
                .filter(worker -> agentId == null || agentId.isBlank() || agentId.equals(worker.agentId()))
                .filter(worker -> state == null || state.isBlank() || state.equals(worker.status())
                        || "attention".equals(state) && needsAttention(worker))
                .filter(worker -> q.isEmpty() || String.join(" ", text(worker.model()), text(worker.deviceId()),
                        text(worker.coin()), text(worker.algorithm()), text(worker.miner()),
                        text(byId.get(worker.agentId()).name())).toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing(Worker::agentId).thenComparing(worker -> text(worker.rowId())))
                .toList();
        int boundedSize = Math.clamp(size, 1, 50);
        int pages = Math.max(1, (filtered.size() + boundedSize - 1) / boundedSize);
        int boundedPage = Math.clamp(page, 0, pages - 1);
        int offset = boundedPage * boundedSize;
        long active = aged.stream().filter(worker -> !worker.stale() && "MINING".equals(worker.status())).count();
        double watts = aged.stream().filter(worker -> !worker.stale() && "MINING".equals(worker.status()))
                .filter(worker -> worker.watts() != null).mapToDouble(Worker::watts).sum();
        long measured = aged.stream().filter(worker -> !worker.stale() && "MINING".equals(worker.status()) && worker.watts() != null).count();
        return new Workspace(read.at(), read.agents(),
                new Summary(aged.size(), active, aged.stream().filter(ConsoleWorkspaceService::needsAttention).count(),
                        watts, measured),
                filtered.size(), boundedPage, boundedSize, pages,
                filtered.subList(offset, Math.min(offset + boundedSize, filtered.size())));
    }

    public Optional<Worker> find(String agentId, String deviceId) {
        return materialize().workers().stream().filter(worker -> Objects.equals(agentId, worker.agentId())
                && Objects.equals(deviceId, worker.deviceId())).findFirst();
    }

    private static Worker localWorker(String agentId, WorkerAssignmentService.WorkerView worker, Instant at, boolean benchmarking, boolean sweeping) {
        var telemetry = worker.telemetry();
        String blocked = !worker.configured() ? "console_setup_required"
                : !worker.minerInstalled() ? "console_install_required" : null;
        return new Worker(agentId, worker.deviceId(), true, worker.hardwareModel(), worker.hardwareType(),
                worker.coin(), telemetry != null && worker.status() == de.verdox.solarminer.pcagent.dto.MinerStats.MinerStatus.MINING
                ? telemetry.currentAlgorithm() : worker.algorithm(), worker.minerSoftwareName(), worker.status().name(),
                telemetry == null ? null : positive(telemetry.terahashPerSecond() * 1e12),
                telemetry == null ? null : positive(telemetry.approximatedPowerUsageWatts()),
                telemetry == null ? null : positive(telemetry.temperatureCelsius()),
                telemetry == null ? null : telemetry.acceptedShares(), telemetry == null ? null : telemetry.rejectedShares(),
                worker.powerMode(), worker.poolUrl(), benchmarking, sweeping, at, blocked, null);
    }

    private static Worker age(Worker row, Instant now) {
        boolean stale = row.collectedAt() == null || row.collectedAt().isAfter(now.plusSeconds(5))
                || now.toEpochMilli() - row.collectedAt().toEpochMilli() > (row.own() ? LOCAL_MAX_AGE_MS : PEER_MAX_AGE_MS);
        String block = stale ? "console_stale" : null;
        return new Worker(row.agentId(), row.deviceId(), row.own(), row.model(), row.hardwareType(), row.coin(),
                row.algorithm(), row.miner(), row.status(), row.hashrateHps(), row.watts(), row.temperature(),
                row.acceptedShares(), row.rejectedShares(), row.powerMode(), row.pool(), row.benchmarking(), row.sweeping(),
                row.collectedAt(), block != null ? block : row.startBlocked(), block != null ? block : row.pauseBlocked(), stale, row.rowId());
    }

    private static boolean needsAttention(Worker worker) {
        return worker.stale() || "ERROR".equals(worker.status()) || worker.startBlocked() != null;
    }
    private static Double positive(double value) { return Double.isFinite(value) && value > 0 ? value : null; }
    private static String text(String value) { return value == null ? "" : value; }
    private record ReadSnapshot(Instant at, List<Agent> agents, List<Worker> workers, Map<String, Agent> byId) { }
    private record LocalSnapshot(Instant at, List<WorkerAssignmentService.WorkerView> workers) { }
    public record Agent(String agentId, String name, boolean own, boolean reachable, Instant collectedAt,
                        String issue, int workerCount) { }
    public record Summary(long total, long active, long attention, double reportedWatts, long reportingWorkers) { }
    public record Workspace(Instant generatedAt, List<Agent> agents, Summary summary, int total,
                            int page, int size, int pages, List<Worker> workers) { }
    public record Worker(String agentId, String deviceId, boolean own, String model, String hardwareType,
                         String coin, String algorithm, String miner, String status, Double hashrateHps,
                         Double watts, Double temperature, Long acceptedShares, Long rejectedShares,
                         String powerMode, String pool, boolean benchmarking, boolean sweeping,
                         Instant collectedAt, String startBlocked, String pauseBlocked, boolean stale, String rowId) {
        public Worker(String agentId, String deviceId, boolean own, String model, String hardwareType,
                      String coin, String algorithm, String miner, String status, Double hashrateHps,
                      Double watts, Double temperature, Long acceptedShares, Long rejectedShares,
                      String powerMode, String pool, boolean benchmarking, boolean sweeping,
                      Instant collectedAt, String startBlocked, String pauseBlocked) {
            this(agentId, deviceId, own, model, hardwareType, coin, algorithm, miner, status, hashrateHps, watts,
                    temperature, acceptedShares, rejectedShares, powerMode, pool, benchmarking, sweeping,
                    collectedAt, startBlocked, pauseBlocked, false, "device:" + deviceId);
        }
    }
}
