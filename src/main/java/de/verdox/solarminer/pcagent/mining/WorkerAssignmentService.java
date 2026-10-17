package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.WorkerIds;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.miner.GpuCoinMiner;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.lowlevel.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.mining.agent.AgentControlSettingsService;
import de.verdox.solarminer.pcagent.mining.wallet.PayoutDefaultsService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import de.verdox.solarminer.pcagent.mining.proxy.ProxyConfigurationService;

/**
 * One local contract for assigning and controlling a physical CPU/GPU worker. Coin identity
 * comes from the {@link Coin} enum and coin behaviour from the {@link MinerFactory} adapters;
 * this service never branches on a specific coin.
 */
@Service
public class WorkerAssignmentService {
    private final AgentControlSettingsService controls;
    private final MiningService mining;
    private final LocalGpuPowerService gpuPower;
    private final MinerCatalogService catalog;
    private final MinerFactory miners;
    private final PayoutDefaultsService payouts;
    private final ProxyConfigurationService proxy;
    private de.verdox.solarminer.pcagent.mining.power.WorkerPowerModeService workerPowerModes;
    private de.verdox.solarminer.pcagent.mining.economics.WorkerCoinRevenueModeService coinRevenueMode;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWorkerPowerModes(de.verdox.solarminer.pcagent.mining.power.WorkerPowerModeService workerPowerModes) {
        this.workerPowerModes = workerPowerModes;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCoinRevenueMode(@org.springframework.context.annotation.Lazy de.verdox.solarminer.pcagent.mining.economics.WorkerCoinRevenueModeService service) {
        this.coinRevenueMode = service;
    }

    public WorkerAssignmentService(AgentControlSettingsService controls, MiningService mining,
                                   LocalGpuPowerService gpuPower, MinerCatalogService catalog,
                                   MinerFactory miners, PayoutDefaultsService payouts,
                                   ProxyConfigurationService proxy) {
        this.controls = controls;
        this.mining = mining;
        this.gpuPower = gpuPower;
        this.catalog = catalog;
        this.miners = miners;
        this.payouts = payouts;
        this.proxy = proxy;
    }

    public synchronized List<WorkerView> workers() {
        // Listing workers is a read model: do not execute driver commands on its REST path.
        List<LocalGpuPowerService.Gpu> cards = gpuPower.snapshot();
        List<MinerStats.Worker> stats = mining.getStats(cards).workers();
        List<WorkerView> result = new ArrayList<>();
        MinerStats.Worker cpu = stats.stream().filter(worker -> WorkerIds.CPU.equals(worker.deviceId())).findFirst().orElse(null);
        result.add(view(WorkerIds.CPU, "CPU", cpu == null ? "CPU" : cpu.hardwareModel(), null, null, stats));
        for (LocalGpuPowerService.Gpu gpu : cards)
            result.add(view(gpu.deviceId(), "GPU", gpu.model(), gpu.vendor(), gpu.index(), stats));
        return result;
    }

    public synchronized WorkerView assign(String deviceId, Assignment request) throws IOException {
        Hardware hardware = hardware(deviceId);
        if (request == null || request.coin() == null) throw new IllegalArgumentException("Zuweisung fehlt");
        Coin coin = Coin.byIdOrNull(request.coin());
        if (coin == null || !coin.assignableTo(hardware.type.equals("CPU")))
            throw new IllegalArgumentException("Coin passt nicht zur Hardware");

        String oldCoin = controls.get().coinFor(deviceId);
        Coin oldCoinValue = Coin.byIdOrNull(oldCoin);
        String oldMinerId = oldCoinValue == null || oldCoinValue == Coin.NONE ? null
                : miners.minerSoftwareForWorker(oldCoinValue, deviceId);
        MinerCatalogService.MinerOption selectedOption = null;
        if (coin != Coin.NONE) {
            selectedOption = catalog.options(coin.id()).stream()
                    .filter(candidate -> candidate.id().equals(request.minerSoftwareId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Miner-Software unterstützt diesen Coin nicht"));
            if (!MinerCatalogService.supportsHardware(selectedOption, hardware.type, hardware.vendor))
                throw new IllegalArgumentException("Miner-Software ist mit diesem GPU-Hersteller nicht kompatibel");
            if (!selectedOption.installed() || !selectedOption.selectable())
                throw new IllegalArgumentException("Miner-Software ist nicht einsatzbereit");
        }

        if ((!oldCoin.equals(coin.id()) || !java.util.Objects.equals(oldMinerId, request.minerSoftwareId()))
                && !Coin.NONE.id().equals(oldCoin) && !mining.stopExternalWorker(deviceId,
                        "Worker-Zuweisung geändert / Worker assignment changed"))
            throw new IllegalStateException("Der laufende Worker konnte nicht angehalten werden");

        if (!controls.setWorkerAssignment(deviceId, coin.id(), selectedOption == null ? null : selectedOption.id()))
            throw new IOException("Worker-Zuweisung konnte nicht gespeichert werden");
        try {
            synchronizeConfiguredDevices(oldCoinValue, oldMinerId, coin,
                    selectedOption == null ? null : selectedOption.id());
        } catch (IOException | RuntimeException failure) {
            controls.setWorkerAssignment(deviceId, oldCoin, oldMinerId);
            synchronizeConfiguredDevices(coin, selectedOption == null ? null : selectedOption.id(), oldCoinValue, oldMinerId);
            throw failure;
        }
        return workers().stream().filter(worker -> worker.deviceId().equals(deviceId)).findFirst().orElseThrow();
    }

    public synchronized boolean start(String deviceId) {
        return startWithMode(deviceId, false);
    }

    /** Economic plans already hold the local run lock while applying on/off actions. */
    public synchronized boolean startUnderEconomicPlan(String deviceId) {
        return startWithMode(deviceId, true);
    }

    /** A completed benchmark still owns the measurement lock while it starts configured workers. */
    public synchronized boolean startWithHeldRunLock(String deviceId) {
        return startWithMode(deviceId, true);
    }

    private boolean startWithMode(String deviceId, boolean lockHeld) {
        String mode = controls.get().powerModeFor(deviceId);
        if (!WorkerIds.isCpu(deviceId) && ("REVENUE_PER_KWH".equals(mode) || "COIN_REVENUE_PER_KWH".equals(mode))) {
            if (coinRevenueMode == null) throw new IllegalStateException("power_mode_unavailable");
            var selected = coinRevenueMode.applyBestStartPoint(deviceId, controls.get().coinFor(deviceId), mode, true, lockHeld);
            if (!selected.success()) throw new IllegalStateException(selected.error());
            return true;
        }
        return start(deviceId, true);
    }

    /** Starts with the already selected power cap; revenue modes choose that point upstream. */
    public synchronized boolean startAtCurrentPowerTarget(String deviceId) {
        return start(deviceId, false);
    }

    private boolean start(String deviceId, boolean applyPowerMode) {
        Hardware hardware = hardware(deviceId);
        Coin coin = Coin.byIdOrNull(controls.get().coinFor(deviceId));
        if (coin == null || coin == Coin.NONE) throw new IllegalStateException("Assign a coin to this worker first");
        if (hardware.type.equals("CPU")) {
            if (!mining.resumeMining(coin.id())) throw new IllegalStateException(error(miners.miner(coin).lastError(),
                    "CPU miner could not start. Check the miner console, installation and proxy fee route."));
            return true;
        }
        String minerId = miners.minerSoftwareForWorker(coin, deviceId);
        MinerCatalogService.MinerOption option = catalog.options(coin.id()).stream()
                .filter(candidate -> candidate.id().equals(minerId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Miner-Software unterstützt diesen Coin nicht"));
        if (!MinerCatalogService.supportsHardware(option, hardware.type, hardware.vendor))
            throw new IllegalStateException("Miner-Software ist mit diesem GPU-Hersteller nicht kompatibel");
        GpuCoinMiner miner = miners.gpuMiner(coin, minerId);
        ensureGpuDefault(coin, miner, hardware, assignedDevices(coin.id(), minerId));
        if (applyPowerMode && workerPowerModes != null) {
            var selected = workerPowerModes.apply(deviceId, coin.id());
            if (selected.error() != null) throw new IllegalStateException(selected.error());
        }
        if (!miner.resumeGpu(hardware.vendor, hardware.index))
            throw new IllegalStateException(error(miner.lastError(),
                    "GPU miner could not start. Check its console, installation and proxy fee route."));
        return true;
    }

    private void ensureGpuDefault(Coin coin, GpuCoinMiner miner, Hardware hardware, String device) {
        MinerConfig existing = miner.configuration();
        if (coin == Coin.PEARL && existing != null) return;
        if (existing != null && !payouts.usesDefault(coin.id())) return;
        // REST audit R3: assignment runs on the request thread, so it reads the pre-warmed
        // cache instead of calling the fee backend itself. The scheduled pre-warm marks a
        // target missing within its TTL during an outage, which preserves the old behavior.
        if (existing != null && payouts.resolveCached(coin.id()).isEmpty()) return; // Keep the last verified house route during an outage.
        PayoutDefaultsService.DefaultPayout payout = payouts.resolveCached(coin.id()).orElseThrow(() ->
                new IllegalStateException("SolarMiner default payout for " + coin.id() + " is unavailable. Check the proxy and fee target."));
        String proxyUrl = proxy.coinUrl(coin);
        if (proxyUrl == null) throw new IllegalStateException("SolarMiner proxy route for " + coin.id() + " is unavailable");
        String worker = error(payout.workerPart(), "solarminer");
        if (existing != null) {
            if (existing.poolUrl().equals(payout.poolUrl()) && existing.wallet().equals(payout.walletPart())
                    && existing.worker().equals(worker) && existing.devices().equals(device)
                    && existing.proxyUrl().equals(proxyUrl)) return;
        }
        try {
            miner.applyConfig(new MinerConfig(payout.poolUrl(), proxyUrl, payout.walletPart(), worker, device));
            payouts.markDefault(coin.id(), true);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("SolarMiner default payout could not be configured: " + failure.getMessage(), failure);
        }
    }

    /**
     * Prepares every installed GPU miner with the SolarMiner house route, without starting it.
     * A benchmark must not require a prior Worker assignment or a manually saved payout route.
     */
    public synchronized Map<String, String> prepareBenchmarkDefaults() { return prepareBenchmarkDefaults(Set.of()); }

    /**
     * Selection-aware variant: only coins the operator opted into are prepared with the house
     * route. An empty selection prepares every installed GPU miner, matching the previous
     * behaviour. Coins the operator configured themselves are never touched either way.
     */
    public synchronized Map<String, String> prepareBenchmarkDefaults(Set<String> selectedCoinIds) {
        Map<String, String> unavailable = new LinkedHashMap<>();
        List<LocalGpuPowerService.Gpu> cards = gpuPower.discover();
        if (cards.isEmpty()) return Map.of();
        for (Coin coin : Coin.gpuCoins()) {
            if (!selectedCoinIds.isEmpty() && !selectedCoinIds.contains(coin.id())) continue;
            for (MinerCatalogService.MinerOption option : catalog.options(coin.id())) {
                if (!option.installed() || !option.selectable()) continue;
                List<LocalGpuPowerService.Gpu> compatible = cards.stream()
                        .filter(gpu -> MinerCatalogService.supportsHardware(option, "GPU", gpu.vendor())).toList();
                if (compatible.isEmpty()) continue;
                GpuCoinMiner miner = miners.gpuMiner(coin, option.id());
                if (miner.configured() || !miner.binaryAvailable()) continue;
                String devices = compatible.stream().map(gpu -> gpu.vendor() + ":" + gpu.index())
                        .collect(java.util.stream.Collectors.joining(","));
                LocalGpuPowerService.Gpu first = compatible.getFirst();
                try {
                    ensureGpuDefault(coin, miner, new Hardware("GPU", first.vendor(), first.index()), devices);
                } catch (IllegalStateException failure) {
                    unavailable.put(coin.id() + "/" + option.id(), failure.getMessage());
                }
            }
        }
        return Map.copyOf(unavailable);
    }

    /** GPU benchmark phases with an installed miner and a valid local route. */
    public synchronized List<String> benchmarkGpuPhases() {
        List<String> phases = new ArrayList<>();
        for (Coin coin : Coin.gpuCoins()) {
            if (catalog.options(coin.id()).stream().anyMatch(option -> option.installed() && option.selectable()
                    && miners.gpuMiner(coin, option.id()).configured()
                    && miners.gpuMiner(coin, option.id()).selectedGpus().stream().anyMatch(gpu ->
                    MinerCatalogService.supportsHardware(option, "GPU", gpu.vendor())))) phases.add(coin.id());
        }
        return List.copyOf(phases);
    }

    private static String error(String detail, String fallback) {
        return detail == null || detail.isBlank() ? fallback : detail;
    }

    public synchronized boolean pause(String deviceId) {
        hardware(deviceId);
        return mining.stopExternalWorker(deviceId, "Worker pausiert / Worker paused");
    }

    /**
     * Used only after {@link de.verdox.solarminer.pcagent.mining.economics.EconomicPlanningService}
     * has validated a short-lived Node plan.
     * It keeps the same stop/persist/device-synchronisation rules as a local reassignment.
     */
    public synchronized boolean applyEconomicAssignment(String deviceId, String coinId) {
        Hardware hardware = hardware(deviceId);
        Coin coin = Coin.byIdOrNull(coinId);
        if (coin == null || !coin.assignableTo(hardware.type.equals("CPU"))) return false;
        String oldCoin = controls.get().coinFor(deviceId);
        if (oldCoin.equals(coin.id())) return true;
        if (!mining.stopExternalWorker(deviceId, "Automatischer Coin-Wechsel / Automatic coin switch: "
                + oldCoin + " → " + coin.id())) return false;
        Coin old = Coin.byIdOrNull(oldCoin);
        String oldMiner = old == null || old == Coin.NONE ? null : miners.minerSoftwareForWorker(old, deviceId);
        String minerId = coin == Coin.NONE ? null : miners.minerSoftwareForWorker(coin, deviceId);
        if (!controls.setWorkerAssignment(deviceId, coin.id(), minerId)) return false;
        try {
            synchronizeConfiguredDevices(old, oldMiner, coin, minerId);
            return true;
        } catch (IOException | RuntimeException failure) {
            controls.setWorkerAssignment(deviceId, oldCoin, oldMiner);
            try { synchronizeConfiguredDevices(coin, minerId); } catch (IOException | RuntimeException ignored) { }
            return false;
        }
    }

    /** Selects the measured miner as well as its coin, using normal assignment validation. */
    public synchronized boolean applyEconomicAssignment(String deviceId, String coinId, String minerSoftwareId) {
        if (minerSoftwareId == null || minerSoftwareId.isBlank()) return applyEconomicAssignment(deviceId, coinId);
        try {
            assign(deviceId, new Assignment(coinId, minerSoftwareId));
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private WorkerView view(String deviceId, String type, String model, String vendor, Integer index,
                            List<MinerStats.Worker> stats) {
        Coin coin = Coin.byIdOrNull(controls.get().coinFor(deviceId));
        String algorithm = coin == null || coin == Coin.NONE ? null : coin.assignmentAlgorithm();
        // Runtime ownership is authoritative for occupancy and telemetry. The saved assignment
        // remains the configuration target and may legitimately differ from a running process.
        MinerStats.Worker live = stats.stream().filter(worker -> deviceId.equals(worker.deviceId())
                && worker.miningStatus() == MinerStats.MinerStatus.MINING).findFirst()
                .orElseGet(() -> stats.stream().filter(worker -> deviceId.equals(worker.deviceId())
                        && (algorithm == null || algorithm.equalsIgnoreCase(worker.currentAlgorithm()))).findFirst().orElse(null));
        String selectedId = coin == null || coin == Coin.NONE ? null : miners.minerSoftwareForWorker(coin, deviceId);
        MinerCatalogService.MinerOption selected = coin == null || coin == Coin.NONE ? null : catalog.options(coin.id()).stream()
                .filter(option -> option.id().equals(selectedId)).findFirst().orElse(null);
        String pool = pool(coin, selectedId);
        boolean configured = coin != null && (coin == Coin.NONE || coin.isCpu()
                || miners.gpuMiner(coin, selectedId).configured());
        if (live != null && "GPU".equals(type) && !Double.isFinite(live.temperatureCelsius())) {
            LocalGpuPowerService.Gpu gpu = gpuPower.snapshot().stream()
                    .filter(card -> deviceId.equals(card.deviceId())).findFirst().orElse(null);
            Double temperature = gpu == null ? null : gpuPower.readTemperatureC(gpu);
            if (temperature != null) live = new MinerStats.Worker(live.miningStatus(), live.workerDisplayName(),
                    live.currentAlgorithm(), live.terahashPerSecond(), temperature, live.powerTargetWatts(),
                    live.minPowerTarget(), live.defaultPowerTarget(), live.maxPowerTarget(),
                    live.approximatedPowerUsageWatts(), live.pools(), live.hardwareType(), live.hardwareModel(),
                    live.deviceId(), live.acceptedShares(), live.rejectedShares(), live.pool());
        }
        return new WorkerView(deviceId, type, model, vendor, index,
                coin == null ? Coin.NONE.id() : coin.id(), coin == null ? Coin.NONE.displayName() : coin.displayName(), algorithm,
                selected == null ? null : selected.id(), selected == null ? null : selected.name(),
                selected != null && selected.installed(), configured, pool,
                controls.get().workerEnabled(deviceId), live == null ? MinerStats.MinerStatus.STOPPED : live.miningStatus(), live,
                controls.get().powerModeFor(deviceId));
    }

    private void synchronizeConfiguredDevices(Object... changedAssignments) throws IOException {
        Set<AssignmentKey> affected = new HashSet<>();
        for (int i = 0; i + 1 < changedAssignments.length; i += 2) {
            Coin coin = (Coin) changedAssignments[i];
            String minerId = (String) changedAssignments[i + 1];
            if (coin != null && coin.isGpu() && minerId != null) affected.add(new AssignmentKey(coin, minerId));
        }
        for (AssignmentKey assignment : affected) {
            GpuCoinMiner miner = miners.gpuMiner(assignment.coin(), assignment.minerId());
            String devices = assignedDevices(assignment.coin().id(), assignment.minerId());
            // Existing coin credentials remain available for a later reassignment. The legacy
            // coin configuration contract requires at least one GPU, so an empty local profile
            // is represented by workerCoins rather than an invalid empty miner configuration.
            if (devices.isEmpty()) continue;
            MinerConfig config = miner.configuration();
            if (config != null) miner.applyConfig(new MinerConfig(config.poolUrl(), config.proxyUrl(),
                    config.wallet(), config.worker(), devices));
        }
    }

    private String assignedDevices(String coinId, String minerId) {
        List<String> ids = new ArrayList<>();
        for (LocalGpuPowerService.Gpu gpu : gpuPower.discover()) {
            Coin coin = Coin.byIdOrNull(coinId);
            if (coinId.equals(controls.get().coinFor(gpu.deviceId()))
                    && minerId.equals(miners.minerSoftwareForWorker(coin, gpu.deviceId())))
                ids.add(gpu.vendor() + ":" + gpu.index());
        }
        return String.join(",", ids);
    }

    private String pool(Coin coin, String minerId) {
        if (coin == null || !coin.isGpu()) return null;
        MinerConfig config = miners.gpuMiner(coin, minerId).configuration();
        return config == null ? null : config.poolUrl();
    }

    private Hardware hardware(String deviceId) {
        if (WorkerIds.CPU.equals(deviceId)) return new Hardware("CPU", null, null);
        return gpuPower.discover().stream().filter(gpu -> gpu.deviceId().equals(deviceId))
                .map(gpu -> new Hardware("GPU", gpu.vendor(), gpu.index())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Gerät wurde nicht erkannt"));
    }

    private record Hardware(String type, String vendor, Integer index) { }
    private record AssignmentKey(Coin coin, String minerId) { }
    /** Worker setup deliberately has no remote-control flag; automation consent is centralized. */
    public record Assignment(String coin, String minerSoftwareId) { }
    public record WorkerView(String deviceId, String hardwareType, String hardwareModel, String vendor, Integer index,
                             String coin, String coinName, String algorithm, String minerSoftwareId, String minerSoftwareName,
                             boolean minerInstalled, boolean configured, String poolUrl, boolean externalControlEnabled,
                             MinerStats.MinerStatus status, MinerStats.Worker telemetry, String powerMode) {
        public WorkerView(String deviceId, String hardwareType, String hardwareModel, String vendor, Integer index,
                          String coin, String coinName, String algorithm, String minerSoftwareId, String minerSoftwareName,
                          boolean minerInstalled, boolean configured, String poolUrl, boolean externalControlEnabled,
                          MinerStats.MinerStatus status, MinerStats.Worker telemetry) {
            this(deviceId, hardwareType, hardwareModel, vendor, index, coin, coinName, algorithm, minerSoftwareId,
                    minerSoftwareName, minerInstalled, configured, poolUrl, externalControlEnabled, status, telemetry, "MANUAL");
        }
    }
}
