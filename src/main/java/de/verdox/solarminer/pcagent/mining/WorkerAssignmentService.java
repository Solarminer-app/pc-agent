package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.coin.Coin;
import de.verdox.solarminer.pcagent.coin.WorkerIds;
import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.miner.GpuCoinMiner;
import de.verdox.solarminer.pcagent.miner.MinerConfig;
import de.verdox.solarminer.pcagent.miner.MinerFactory;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        List<LocalGpuPowerService.Gpu> cards = gpuPower.discover();
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
        if (!oldCoin.equals(coin.id()) && !Coin.NONE.id().equals(oldCoin) && !mining.stopExternalWorker(deviceId))
            throw new IllegalStateException("Der laufende Worker konnte nicht angehalten werden");

        if (coin != Coin.NONE) {
            MinerCatalogService.MinerOption option = catalog.options(coin.id()).stream()
                    .filter(candidate -> candidate.id().equals(request.minerSoftwareId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Miner-Software unterstützt diesen Coin nicht"));
            if (!option.installed() || !option.selectable())
                throw new IllegalArgumentException("Miner-Software ist nicht einsatzbereit");
            if (!catalog.select(coin.id(), option.id())) throw new IOException("Miner-Auswahl konnte nicht gespeichert werden");
        }

        if (!controls.setWorkerCoin(deviceId, coin.id())) throw new IOException("Worker-Zuweisung konnte nicht gespeichert werden");
        try {
            synchronizeConfiguredDevices(oldCoin, coin.id());
        } catch (IOException | RuntimeException failure) {
            controls.setWorkerCoin(deviceId, oldCoin);
            synchronizeConfiguredDevices(coin.id(), oldCoin);
            throw failure;
        }
        return workers().stream().filter(worker -> worker.deviceId().equals(deviceId)).findFirst().orElseThrow();
    }

    public synchronized boolean start(String deviceId) {
        Hardware hardware = hardware(deviceId);
        Coin coin = Coin.byIdOrNull(controls.get().coinFor(deviceId));
        if (coin == null || coin == Coin.NONE) throw new IllegalStateException("Assign a coin to this worker first");
        if (hardware.type.equals("CPU")) {
            if (!mining.resumeMining(coin.id())) throw new IllegalStateException(error(miners.miner(coin).lastError(),
                    "CPU miner could not start. Check the miner console, installation and proxy fee route."));
            return true;
        }
        GpuCoinMiner miner = miners.gpuMiner(coin);
        ensureGpuDefault(coin, miner, hardware);
        if (!miner.resumeGpu(hardware.vendor, hardware.index))
            throw new IllegalStateException(error(miner.lastError(),
                    "GPU miner could not start. Check its console, installation and proxy fee route."));
        return true;
    }

    private void ensureGpuDefault(Coin coin, GpuCoinMiner miner, Hardware hardware) {
        String assigned = assignedDevices(coin.id());
        ensureGpuDefault(coin, miner, hardware, assigned.isBlank() ? hardware.vendor + ":" + hardware.index : assigned);
    }

    private void ensureGpuDefault(Coin coin, GpuCoinMiner miner, Hardware hardware, String device) {
        MinerConfig existing = miner.configuration();
        if (coin == Coin.PEARL && existing != null) return;
        if (existing != null && !payouts.usesDefault(coin.id())) return;
        if (existing != null && payouts.resolve(coin.id()).isEmpty()) return; // Keep the last verified house route during an outage.
        PayoutDefaultsService.DefaultPayout payout = payouts.resolve(coin.id()).orElseThrow(() ->
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
    public synchronized Map<String, String> prepareBenchmarkDefaults() {
        Map<String, String> unavailable = new LinkedHashMap<>();
        List<LocalGpuPowerService.Gpu> cards = gpuPower.discover();
        if (cards.isEmpty()) return Map.of();
        String devices = cards.stream().map(gpu -> gpu.vendor() + ":" + gpu.index()).collect(java.util.stream.Collectors.joining(","));
        LocalGpuPowerService.Gpu first = cards.getFirst();
        for (Coin coin : Coin.gpuCoins()) {
            GpuCoinMiner miner = miners.gpuMiner(coin);
            if (miner.configured() || !miner.binaryAvailable()) continue;
            try {
                ensureGpuDefault(coin, miner, new Hardware("GPU", first.vendor(), first.index()), devices);
            } catch (IllegalStateException failure) {
                unavailable.put(coin.id(), failure.getMessage());
            }
        }
        return Map.copyOf(unavailable);
    }

    /** GPU benchmark phases with an installed miner and a valid local route. */
    public synchronized List<String> benchmarkGpuPhases() {
        List<String> phases = new ArrayList<>();
        for (Coin coin : Coin.gpuCoins()) {
            GpuCoinMiner miner = miners.gpuMiner(coin);
            if (miner.binaryAvailable() && miner.configured()) phases.add(coin.id());
        }
        return List.copyOf(phases);
    }

    private static String error(String detail, String fallback) {
        return detail == null || detail.isBlank() ? fallback : detail;
    }

    public synchronized boolean pause(String deviceId) {
        hardware(deviceId);
        return mining.stopExternalWorker(deviceId);
    }

    /**
     * Used only after {@link EconomicPlanningService} has validated a short-lived Node plan.
     * It keeps the same stop/persist/device-synchronisation rules as a local reassignment.
     */
    synchronized boolean applyEconomicAssignment(String deviceId, String coinId) {
        Hardware hardware = hardware(deviceId);
        Coin coin = Coin.byIdOrNull(coinId);
        if (coin == null || !coin.assignableTo(hardware.type.equals("CPU"))) return false;
        String oldCoin = controls.get().coinFor(deviceId);
        if (oldCoin.equals(coin.id())) return true;
        if (!mining.stopExternalWorker(deviceId)) return false;
        if (!controls.setWorkerCoin(deviceId, coin.id())) return false;
        try {
            synchronizeConfiguredDevices(oldCoin, coin.id());
            return true;
        } catch (IOException | RuntimeException failure) {
            controls.setWorkerCoin(deviceId, oldCoin);
            try { synchronizeConfiguredDevices(coin.id(), oldCoin); } catch (IOException | RuntimeException ignored) { }
            return false;
        }
    }

    private WorkerView view(String deviceId, String type, String model, String vendor, Integer index,
                            List<MinerStats.Worker> stats) {
        Coin coin = Coin.byIdOrNull(controls.get().coinFor(deviceId));
        String algorithm = coin == null || coin == Coin.NONE ? null : coin.assignmentAlgorithm();
        MinerStats.Worker live = stats.stream().filter(worker -> deviceId.equals(worker.deviceId())
                && (algorithm == null || algorithm.equalsIgnoreCase(worker.currentAlgorithm()))).findFirst().orElse(null);
        MinerCatalogService.MinerOption selected = coin == null || coin == Coin.NONE ? null : catalog.selected(coin.id());
        String pool = pool(coin);
        boolean configured = coin != null && (coin == Coin.NONE || coin.isCpu()
                || miners.gpuMiner(coin).configured());
        return new WorkerView(deviceId, type, model, vendor, index,
                coin == null ? Coin.NONE.id() : coin.id(), coin == null ? Coin.NONE.displayName() : coin.displayName(), algorithm,
                selected == null ? null : selected.id(), selected == null ? null : selected.name(),
                selected != null && selected.installed(), configured, pool,
                controls.get().workerEnabled(deviceId), live == null ? MinerStats.MinerStatus.STOPPED : live.miningStatus(), live);
    }

    private void synchronizeConfiguredDevices(String... changedCoins) throws IOException {
        Set<String> affected = new HashSet<>(List.of(changedCoins));
        for (String coinId : affected) {
            Coin coin = Coin.byIdOrNull(coinId);
            if (coin == null || !coin.isGpu()) continue;
            GpuCoinMiner miner = miners.gpuMiner(coin);
            String devices = assignedDevices(coin.id());
            // Existing coin credentials remain available for a later reassignment. The legacy
            // coin configuration contract requires at least one GPU, so an empty local profile
            // is represented by workerCoins rather than an invalid empty miner configuration.
            if (devices.isEmpty()) continue;
            MinerConfig config = miner.configuration();
            if (config != null) miner.applyConfig(new MinerConfig(config.poolUrl(), config.proxyUrl(),
                    config.wallet(), config.worker(), devices));
        }
    }

    private String assignedDevices(String coinId) {
        List<String> ids = new ArrayList<>();
        for (LocalGpuPowerService.Gpu gpu : gpuPower.discover()) {
            if (coinId.equals(controls.get().coinFor(gpu.deviceId()))) ids.add(gpu.vendor() + ":" + gpu.index());
        }
        return String.join(",", ids);
    }

    private String pool(Coin coin) {
        if (coin == null || !coin.isGpu()) return null;
        MinerConfig config = miners.gpuMiner(coin).configuration();
        return config == null ? null : config.poolUrl();
    }

    private Hardware hardware(String deviceId) {
        if (WorkerIds.CPU.equals(deviceId)) return new Hardware("CPU", null, null);
        return gpuPower.discover().stream().filter(gpu -> gpu.deviceId().equals(deviceId))
                .map(gpu -> new Hardware("GPU", gpu.vendor(), gpu.index())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Gerät wurde nicht erkannt"));
    }

    private record Hardware(String type, String vendor, Integer index) { }
    /** Worker setup deliberately has no remote-control flag; automation consent is centralized. */
    public record Assignment(String coin, String minerSoftwareId) { }
    public record WorkerView(String deviceId, String hardwareType, String hardwareModel, String vendor, Integer index,
                             String coin, String coinName, String algorithm, String minerSoftwareId, String minerSoftwareName,
                             boolean minerInstalled, boolean configured, String poolUrl, boolean externalControlEnabled,
                             MinerStats.MinerStatus status, MinerStats.Worker telemetry) { }
}
