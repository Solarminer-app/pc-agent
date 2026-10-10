package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.WorkerIds;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.dto.Pools;
import de.verdox.solarminer.pcagent.lowlevel.HardwareIdentityService;
import de.verdox.solarminer.pcagent.miner.CoinMiner;
import de.verdox.solarminer.pcagent.miner.CpuMiner;
import de.verdox.solarminer.pcagent.miner.GpuCoinMiner;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Coin-independent mining orchestration: power budgets, pause/resume and worker selection are
 * applied through the {@link MinerFactory} adapters. This service never branches on which coin
 * is being mined; coin-specific parameters and APIs live inside the adapters.
 */
@Service
public class MiningService {
    private final String minerUID;
    private final String macAddress;
    private final String minerModel;
    private final HardwareIdentityService hardwareIdentityService;

    // Deliberately volatile: a process restart must not start mining from a stale target.
    private long desiredCpuPowerTarget;
    private long desiredGpuPowerTarget;
    private long desiredGlobalPowerTarget;
    private final PowerApplicationState powerApplication = new PowerApplicationState();
    private volatile boolean cpuManuallyPaused;
    private final MinerFactory miners;
    private final CpuMiner cpu;
    private final LocalGpuPowerService gpuPowerService;
    private final AgentControlSettingsService controls;
    private final AgentIdentityService agentIdentity;
    private final Path coinSelectionFile;
    private volatile String activeCoin;
    private final MinerStats.MinerIdentity minerIdentity;
    private final MinerStats.MinerStatus status = MinerStats.MinerStatus.PAUSED;

    public MiningService(MinerFactory miners, LocalGpuPowerService gpuPowerService,
                         HardwareIdentityService hardwareIdentityService,
                         AgentControlSettingsService controls, AgentIdentityService agentIdentity,
                         @Value("${solarminer.agent.coin-selection-file:./solarminer-agent/active-coin.txt}") String coinSelectionPath) {
        this.miners = miners;
        this.cpu = (CpuMiner) miners.miner(Coin.MONERO);
        this.gpuPowerService = gpuPowerService;
        this.controls = controls;
        this.agentIdentity = agentIdentity;
        this.coinSelectionFile = Path.of(coinSelectionPath).toAbsolutePath().normalize();
        this.activeCoin = readSelectedCoin();
        minerUID = "";
        macAddress = "";
        minerModel = "";
        this.minerIdentity = new MinerStats.MinerIdentity(minerUID, macAddress, minerModel);
        this.hardwareIdentityService = hardwareIdentityService;
    }

    /**
     * Allocates the legacy PV-wide budget across CPU and selected GPUs.
     */
    public synchronized boolean setTarget(long powerTarget) {
        return MinerStopContext.withDefault("Local power target", () -> setTarget(powerTarget, false));
    }

    public synchronized boolean setExternalTarget(long powerTarget) {
        return MinerStopContext.with("Remote control API", () -> {
            if (powerTarget <= 0) return pauseExternally();
            if (!controls.get().dynamicPowerScalingEnabled()) return resumeExternally();
            return setTarget(powerTarget, true);
        });
    }

    private boolean setTarget(long powerTarget, boolean external) {
        List<LocalGpuPowerService.Gpu> allowedGpus = eligibleGpus(external).stream()
                .filter(gpu -> !external || controls.workerEnabled(gpu.deviceId()))
                .toList();
        List<LocalGpuPowerService.Gpu> cards = allowedGpus.stream()
                .filter(LocalGpuPowerService.Gpu::supportsDynamicPowerScaling).toList();
        PowerBudgetPlanner.Cpu cpuCapability = cpuCapability(external);
        PowerBudgetPlanner.Plan requestedPlan = PowerBudgetPlanner.plan(powerTarget, cpuCapability, plannerGpus(cards));
        if (requestedPlan.outcome() == PowerBudgetPlanner.Outcome.PAUSE) {
            boolean paused = external ? pauseExternally() : pauseAll("Requested budget is too low.");
            if (paused) powerApplication.paused(powerTarget);
            else powerApplication.failed(requestedPlan, "Miner konnten nicht vollständig angehalten werden");
            return paused;
        }
        if (requestedPlan.outcome() == PowerBudgetPlanner.Outcome.REJECTED) {
            if (external) pauseExternally();
            else pauseAll("Power budget was rejected. Shuttding down miner");
            desiredGlobalPowerTarget = 0;
            desiredCpuPowerTarget = 0;
            desiredGpuPowerTarget = 0;
            powerApplication.rejected(requestedPlan);
            return false;
        }

        PowerBudgetPlanner.Plan appliedPlan = requestedPlan;
        String partialReason = null;
        if (requestedPlan.appliesGpuPowerLimits() && !gpuPowerService.setTotalPowerTarget(requestedPlan.gpuWatts(), cards)) {
            if (!(external ? stopGpus(allowedGpus) : stopActiveGpus()))
                return failGlobalBudget(external, requestedPlan, "GPU-Leistungsgrenzen konnten nicht gesetzt und GPU-Miner nicht sicher angehalten werden");
            appliedPlan = PowerBudgetPlanner.plan(requestedPlan.plannedWatts(), cpuCapability, List.of());
            if (appliedPlan.outcome() != PowerBudgetPlanner.Outcome.APPLY)
                return failGlobalBudget(external, requestedPlan, "GPU-Leistungsgrenzen konnten nicht gesetzt werden");
            partialReason = "GPU-Leistungsgrenzen konnten nicht gesetzt werden; nur CPU-Budget wurde angewendet";
        }
        if (appliedPlan.gpuWatts() == 0 && !(external ? stopGpus(allowedGpus) : stopActiveGpus()))
            return failGlobalBudget(external, requestedPlan, "GPU-Miner konnten nicht sicher angehalten werden");

        long cpuTarget = appliedPlan.cpuWatts();
        long gpuTarget = appliedPlan.gpuWatts();
        boolean cpuStartSucceeded = cpuTarget <= 0;
        if (!external || controls.workerEnabled(WorkerIds.CPU)) {
            cpu.setPowerCap(cpuTarget);
            if (cpuTarget > 0) {
                cpu.startAll();
                cpuStartSucceeded = cpu.running();
            }
        }
        boolean gpuStartsSucceeded = true;
        if (gpuTarget > 0) {
            if (external) {
                for (LocalGpuPowerService.Gpu gpu : cards)
                    gpuStartsSucceeded = startGpuForCoin(controls.get().coinFor(gpu.deviceId()), gpu.vendor(), gpu.index()) && gpuStartsSucceeded;
            } else gpuStartsSucceeded = startActiveGpusForBudget();
        }
        desiredGlobalPowerTarget = appliedPlan.plannedWatts();
        desiredCpuPowerTarget = cpuTarget;
        desiredGpuPowerTarget = gpuTarget;
        boolean fullyStarted = (cpuTarget <= 0 || cpuStartSucceeded) && (gpuTarget <= 0 || gpuStartsSucceeded);
        if (!fullyStarted) {
            powerApplication.partiallyApplied(requestedPlan, appliedPlan,
                    "Mindestens ein Miner-Prozess konnte nach dem Anwenden des Leistungsplans nicht gestartet werden");
            return false;
        }
        if (partialReason != null) powerApplication.partiallyApplied(requestedPlan, appliedPlan, partialReason);
        else powerApplication.applied(appliedPlan);
        return true;
    }

    private PowerBudgetPlanner.Cpu cpuCapability(boolean external) {
        boolean available = (!external || controls.workerEnabled(WorkerIds.CPU)) && !cpuManuallyPaused && cpu.readyForStart();
        return available
                ? new PowerBudgetPlanner.Cpu(true, cpu.getMinimumControllablePowerWatts(), cpu.getEstimatedMaxCpuWattage())
                : new PowerBudgetPlanner.Cpu(false, 0, 0);
    }

    private static List<PowerBudgetPlanner.Gpu> plannerGpus(List<LocalGpuPowerService.Gpu> cards) {
        return cards.stream().map(gpu -> new PowerBudgetPlanner.Gpu(gpu.deviceId(), gpu.minWatts(), gpu.maxWatts())).toList();
    }

    public synchronized boolean resumeExternally() {
        boolean attempted = false;
        boolean allStarted = true;
        if (controls.workerEnabled(WorkerIds.CPU) && !cpuManuallyPaused && cpu.readyForStart()) {
            attempted = true;
            cpu.startAll();
            allStarted = cpu.running() && allStarted;
        }
        for (LocalGpuPowerService.Gpu gpu : eligibleGpus(true)) {
            if (controls.workerEnabled(gpu.deviceId())) {
                attempted = true;
                allStarted = startGpuForCoin(controls.get().coinFor(gpu.deviceId()), gpu.vendor(), gpu.index()) && allStarted;
            }
        }
        return attempted && allStarted;
    }

    public synchronized boolean pauseExternally() {
        return MinerStopContext.withDefault("Remote control API", this::pauseExternallyInternal);
    }

    private boolean pauseExternallyInternal() {
        desiredGlobalPowerTarget = 0;
        desiredCpuPowerTarget = 0;
        desiredGpuPowerTarget = 0;
        boolean success = true;
        if (controls.workerEnabled(WorkerIds.CPU)) {
            cpu.stopAll();
        }
        for (LocalGpuPowerService.Gpu gpu : selectedGpus(true)) {
            if (controls.workerEnabled(gpu.deviceId()))
                success = stopGpuForCoin(controls.get().coinFor(gpu.deviceId()), gpu.vendor(), gpu.index()) && success;
        }
        if (success) powerApplication.paused(0);
        return success;
    }

    private boolean stopGpus(List<LocalGpuPowerService.Gpu> cards) {
        boolean success = true;
        for (LocalGpuPowerService.Gpu gpu : cards)
            success = stopGpuForCoin(controls.get().coinFor(gpu.deviceId()), gpu.vendor(), gpu.index()) && success;
        return success;
    }

    private boolean startGpuForCoin(String coinId, String vendor, int index) {
        // Node assignments are exclusive per physical GPU: stop every other coin's miner on it first.
        Optional<GpuCoinMiner> target = miners.gpuMiner(coinId);
        if (target.isEmpty()) return false;
        boolean stopped = true;
        for (Coin other : Coin.gpuCoins())
            if (!other.id().equals(coinId)) stopped = miners.gpuMiner(other).stopGpu(vendor, index) && stopped;
        if (!stopped) return false;
        return target.get().startGpu(vendor, index);
    }

    private boolean stopGpuForCoin(String coinId, String vendor, int index) {
        return miners.gpuMiner(coinId).map(miner -> miner.stopGpu(vendor, index)).orElse(true);
    }

    private List<LocalGpuPowerService.Gpu> selectedGpus(boolean external) {
        if (!external) return selectedGpus();
        return gpuPowerService.discover().stream().filter(gpu -> controls.workerEnabled(gpu.deviceId()))
                .filter(gpu -> selectedForCoin(controls.get().coinFor(gpu.deviceId()), false).stream()
                        .anyMatch(selected -> selected.deviceId().equals(gpu.deviceId()))).toList();
    }

    private List<LocalGpuPowerService.Gpu> selectedForCoin(String coinId, boolean eligible) {
        return miners.gpuMiner(coinId)
                .map(miner -> eligible ? miner.eligibleGpus() : miner.selectedGpus())
                .orElse(List.of());
    }

    private List<LocalGpuPowerService.Gpu> eligibleGpus(boolean external) {
        if (!external) return eligibleGpus();
        return selectedGpus(true).stream().filter(gpu -> selectedForCoin(controls.get().coinFor(gpu.deviceId()), true).stream()
                .anyMatch(selected -> selected.deviceId().equals(gpu.deviceId()))).toList();
    }

    public boolean externallySelected(String deviceId) {
        return controls.workerEnabled(deviceId) && (WorkerIds.CPU.equals(deviceId) || selectedGpus(true).stream()
                .anyMatch(gpu -> gpu.deviceId().equals(deviceId)));
    }

    public synchronized boolean stopExternalWorker(String deviceId) {
        return MinerStopContext.with("Node default configuration changed", () -> {
            if (WorkerIds.CPU.equals(deviceId)) {
                cpu.stopAll();
                if (cpu.running()) return false;
                desiredGlobalPowerTarget = Math.max(0, desiredGlobalPowerTarget - desiredCpuPowerTarget);
                desiredCpuPowerTarget = 0;
                return true;
            }
            return gpuPowerService.discover().stream().filter(g -> g.deviceId().equals(deviceId)).findFirst()
                    .map(g -> stopGpuForCoin(controls.get().coinFor(deviceId), g.vendor(), g.index())).orElse(false);
        });
    }

    private List<LocalGpuPowerService.Gpu> selectedGpus() {
        return activeGpuMiner().selectedGpus();
    }

    private List<LocalGpuPowerService.Gpu> eligibleGpus() {
        return activeGpuMiner().eligibleGpus();
    }

    private boolean startActiveGpusForBudget() {
        return activeGpuMiner().startForBudget();
    }

    private boolean stopActiveGpus() {
        return activeGpuMiner().stopAll();
    }

    /** The GPU adapter for the active coin; the legacy default falls back to Pearl. */
    private GpuCoinMiner activeGpuMiner() {
        return miners.gpuMiner(activeCoin).orElseGet(() -> miners.gpuMiner(Coin.PEARL));
    }

    private boolean failGlobalBudget(boolean external, PowerBudgetPlanner.Plan plan, String reason) {
        desiredGlobalPowerTarget = 0;
        desiredCpuPowerTarget = 0;
        desiredGpuPowerTarget = 0;
        if (external) pauseExternally();
        else pauseAll("Failed global budget allocation. Pausing miner...");
        powerApplication.failed(plan, reason);
        return false;
    }

    public synchronized boolean setTarget(String coinId, long powerTarget) {
        return MinerStopContext.withDefault("Local " + coinId + " power target", () -> setCoinTarget(coinId, powerTarget));
    }

    private boolean setCoinTarget(String coinId, long powerTarget) {
        Optional<GpuCoinMiner> gpuMiner = miners.gpuMiner(coinId);
        if (gpuMiner.isPresent()) {
            GpuCoinMiner miner = gpuMiner.get();
            if (powerTarget <= 0) {
                desiredGpuPowerTarget = 0;
                desiredGlobalPowerTarget = desiredCpuPowerTarget;
                return miner.pauseAll();
            }
            List<LocalGpuPowerService.Gpu> cards = miner.selectedGpus();
            if (cards.isEmpty() || !miner.setPowerCap(powerTarget)) return false;
            desiredGpuPowerTarget = powerTarget;
            desiredGlobalPowerTarget = desiredCpuPowerTarget + desiredGpuPowerTarget;
            return miner.startAll();
        }
        Optional<CpuMiner> cpuWorker = miners.cpuMiner(coinId);
        if (cpuWorker.isEmpty()) return false;
        CpuMiner cpuWorkerMiner = cpuWorker.get();
        this.desiredCpuPowerTarget = Math.max(0, powerTarget);
        desiredGlobalPowerTarget = desiredCpuPowerTarget + desiredGpuPowerTarget;
        cpuWorkerMiner.setPowerCap(desiredCpuPowerTarget);
        cpuManuallyPaused = desiredCpuPowerTarget == 0;
        if (desiredCpuPowerTarget > 0) cpuWorkerMiner.startAll();
        return desiredCpuPowerTarget == 0 || cpuWorkerMiner.running();
    }

    public boolean pauseMining() {
        return pauseMining(activeCoin);
    }

    public boolean pauseMining(String coinId) {
        return MinerStopContext.withDefault("Local mining control", () -> pauseCoin(coinId));
    }

    private boolean pauseCoin(String coinId) {
        Optional<CoinMiner> miner = miners.miner(coinId);
        if (miner.isEmpty()) return false;
        if (miner.get() instanceof CpuMiner) cpuManuallyPaused = true;
        return miner.get().pauseAll();
    }

    public boolean pauseAll(String source) {
        return MinerStopContext.withDefault(source, this::pauseAllInternal);
    }

    private boolean pauseAllInternal() {
        boolean gpuStopped = true;
        for (Coin coin : Coin.gpuCoins()) gpuStopped = miners.gpuMiner(coin).stopAll() && gpuStopped;
        cpu.stopAll();
        return gpuStopped;
    }

    public boolean resumeMining() {
        return resumeMining(activeCoin);
    }

    public synchronized boolean resumeAll() {
        if (desiredGlobalPowerTarget > 0) return setTarget(desiredGlobalPowerTarget);
        return resumeMining(activeCoin);
    }

    public boolean resumeMining(String coinId) {
        Optional<CoinMiner> miner = miners.miner(coinId);
        if (miner.isEmpty()) return false;
        if (miner.get() instanceof CpuMiner) cpuManuallyPaused = false;
        return miner.get().startAll();
    }

    /** Most recent local start diagnostic for operator-facing workflows such as benchmarks. */
    public String lastStartError(String coinId) {
        return miners.miner(coinId).map(CoinMiner::lastError).orElse(null);
    }

    /** GPU coin workers (excluding Pearl, tracked separately) that were running before a benchmark took ownership. */
    public List<String> runningGpuCoins() {
        return Coin.gpuCoins().stream().filter(coin -> coin != Coin.PEARL)
                .filter(coin -> miners.gpuMiner(coin).running())
                .map(Coin::id).toList();
    }

    public synchronized void usePearl(MinerConfig config) throws IOException {
        miners.gpuMiner(Coin.PEARL).applyConfig(config);
        if (!switchCoin(Coin.PEARL.id())) throw new IOException("Could not select Pearl after configuration");
    }

    public synchronized boolean useMonero() {
        return switchCoin(Coin.MONERO.id());
    }

    public String activeCoin() {
        return activeCoin;
    }

    public boolean cpuManuallyPaused() {
        return cpuManuallyPaused;
    }

    public synchronized void restoreCpuPauseState(boolean manuallyPaused) {
        if (manuallyPaused) cpu.stopAll();
        cpuManuallyPaused = manuallyPaused;
    }

    public synchronized boolean switchCoin(String coinId) {
        Coin coin = Coin.byIdOrNull(coinId);
        if (coin == null || coin == Coin.NONE || !miners.supports(coin.id())) return false;
        if (coin.id().equals(activeCoin)) return true;
        try {
            persistSelectedCoin(coin.id());
        } catch (IOException e) {
            return false;
        }
        activeCoin = coin.id();
        return true;
    }

    private String readSelectedCoin() {
        try {
            String selected = Files.readString(coinSelectionFile).strip();
            if (miners.supports(selected)) return selected;
        } catch (IOException ignored) {
        }
        return miners.gpuMiner(Coin.PEARL).configured() ? Coin.PEARL.id() : Coin.MONERO.id();
    }

    private void persistSelectedCoin(String coinId) throws IOException {
        Files.createDirectories(coinSelectionFile.getParent());
        Path temp = Files.createTempFile(coinSelectionFile.getParent(), "coin-", ".tmp");
        try {
            Files.writeString(temp, coinId);
            try {
                Files.move(temp, coinSelectionFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, coinSelectionFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public boolean increasePowerTarget(long powerTarget) {
        return setTarget(desiredGlobalPowerTarget + powerTarget);
    }

    public boolean decreasePowerTarget(long powerTarget) {
        return setTarget(desiredGlobalPowerTarget - powerTarget);
    }

    public long calculateMinPowerTargetFromComponents() {
        long cpuWatts = controls.workerEnabled(WorkerIds.CPU) && !cpuManuallyPaused && cpu.readyForStart()
                ? cpu.getMinimumControllablePowerWatts() : 0;
        long gpuWatts = eligibleGpus(true).stream().filter(g -> controls.workerEnabled(g.deviceId())).filter(LocalGpuPowerService.Gpu::supportsDynamicPowerScaling)
                .mapToLong(LocalGpuPowerService.Gpu::minWatts).sum();
        return cpuWatts > 0 ? cpuWatts : gpuWatts;
    }

    public long calculateMaxPowerTargetFromComponents() {
        long cpuWatts = controls.workerEnabled(WorkerIds.CPU) && !cpuManuallyPaused && cpu.readyForStart() ? cpu.getEstimatedMaxCpuWattage() : 0;
        long gpuWatts = eligibleGpus(true).stream().filter(g -> controls.workerEnabled(g.deviceId())).filter(LocalGpuPowerService.Gpu::supportsDynamicPowerScaling)
                .mapToLong(LocalGpuPowerService.Gpu::maxWatts).sum();
        return cpuWatts + gpuWatts;
    }

    public long desiredGlobalPowerTarget() {
        return desiredGlobalPowerTarget;
    }

    public long desiredCpuPowerTarget() {
        return desiredCpuPowerTarget;
    }

    public PowerApplicationState.Snapshot powerApplication() {
        return powerApplication.snapshot();
    }

    public long approximatePowerUsageSystem() {
        return 0;
    }

    public long getHotspotTemperature() {
        return 0;
    }

    public List<Pools> collectPoolsForAllWorkers() {
        return List.of();
    }

    public List<MinerStats.Worker> getWorkerStats() {
        return getWorkerStats(null);
    }

    public List<MinerStats.Worker> getExternallyVisibleWorkerStats() {
        return getWorkerStats().stream().filter(worker -> externallySelected(worker.deviceId()))
                .filter(worker -> {
                    Coin coin = Coin.byIdOrNull(controls.get().coinFor(worker.deviceId()));
                    return coin != null && coin.matchesAlgorithm(worker.currentAlgorithm());
                }).toList();
    }

    private List<MinerStats.Worker> getWorkerStats(List<LocalGpuPowerService.Gpu> discoveredGpus) {
        List<LocalGpuPowerService.Gpu> cards = discoveredGpus == null ? gpuPowerService.discover() : discoveredGpus;
        List<MinerStats.Worker> workers = new ArrayList<>();
        workers.add(cpu.getWorkerStats());
        AgentControlSettingsService.Settings owner = controls.get();
        for (Coin coin : Coin.gpuCoins()) {
            GpuCoinMiner miner = miners.gpuMiner(coin);
            String coinId = coin.id();
            // Pearl always reports its state (external-process detection included); other GPU
            // coins only contribute while active, running or assigned to a worker.
            if (coin == Coin.PEARL || coinId.equals(activeCoin) || miner.running()
                    || (owner.workerCoins() != null && owner.workerCoins().containsValue(coinId)))
                workers.addAll(miner.workerStats(cards));
        }
        return workers;
    }

    public MinerStats getStats() {
        return getStats(null);
    }

    public MinerStats getExternalStats() {
        List<MinerStats.Worker> visible = controls.get().externalControlEnabled()
                ? getExternallyVisibleWorkerStats() : List.of();
        return getStatsFromWorkers(visible);
    }

    public MinerStats getStats(List<LocalGpuPowerService.Gpu> discoveredGpus) {
        List<MinerStats.Worker> workers = new ArrayList<>();
        workers.addAll(getWorkerStats(discoveredGpus));
        return getStatsFromWorkers(workers);
    }

    private MinerStats getStatsFromWorkers(List<MinerStats.Worker> workers) {
        long totalPowerTarget = 0;
        long totalMinPowerTarget = 0;
        long totalDefaultPowerTarget = 0;
        long totalMaxPowerTarget = 0;
        long totalApproximatedPowerUsage = 0;
        double totalTerahashPerSecond = 0.0;
        double maxTemperature = 0.0;
        List<Pools> allPools = new ArrayList<>();

        boolean isAnyMining = false;
        boolean isAnyError = false;
        boolean isAnyPaused = false;

        for (MinerStats.Worker worker : workers) {
            totalPowerTarget += worker.powerTargetWatts();
            totalMinPowerTarget += worker.minPowerTarget();
            totalDefaultPowerTarget += worker.defaultPowerTarget();
            totalMaxPowerTarget += worker.maxPowerTarget();
            if (worker.miningStatus() == MinerStats.MinerStatus.MINING) {
                totalApproximatedPowerUsage += worker.approximatedPowerUsageWatts();
            }
            totalTerahashPerSecond += worker.terahashPerSecond();

            if (worker.temperatureCelsius() > maxTemperature) {
                maxTemperature = worker.temperatureCelsius();
            }

            allPools.addAll(worker.pools());

            switch (worker.miningStatus()) {
                case MINING -> isAnyMining = true;
                case ERROR -> isAnyError = true;
                case PAUSED -> isAnyPaused = true;
            }
        }

        MinerStats.MinerStatus globalStatus = MinerStats.MinerStatus.STOPPED;
        if (isAnyMining) {
            globalStatus = MinerStats.MinerStatus.MINING;
        } else if (isAnyError) {
            globalStatus = MinerStats.MinerStatus.ERROR;
        } else if (isAnyPaused) {
            globalStatus = MinerStats.MinerStatus.PAUSED;
        }

        List<Pools> distinctPools = allPools.stream().distinct().toList();

        MinerStats.MinerIdentity identity = new MinerStats.MinerIdentity(
                hardwareIdentityService.getDeterministicUuid().toString(),
                hardwareIdentityService.getMacAddress(),
                "SolarMiner-PC-Agent"
        );

        return new MinerStats(
                identity,
                // The Node lists this agent under its own label, so the operator name travels with
                // every status read. minerModel stays fixed: it feeds pool worker naming.
                agentIdentity.displayName(),
                globalStatus,
                totalPowerTarget,
                totalMinPowerTarget,
                totalDefaultPowerTarget,
                totalMaxPowerTarget,
                totalApproximatedPowerUsage,
                totalTerahashPerSecond,
                maxTemperature,
                distinctPools,
                workers
        );
    }
}
