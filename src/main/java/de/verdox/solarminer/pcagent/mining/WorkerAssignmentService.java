package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.dto.MinerStats;
import de.verdox.solarminer.pcagent.pearl.GpuCoinMinerService;
import de.verdox.solarminer.pcagent.pearl.LocalGpuPowerService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One local contract for assigning and controlling a physical CPU/GPU worker. */
@Service
public class WorkerAssignmentService {
    private static final Set<String> GPU_COINS = Set.of("pearl", "ravencoin", "ethereumclassic", "decred", "quantus");

    private final AgentControlSettingsService controls;
    private final MiningService mining;
    private final LocalGpuPowerService gpuPower;
    private final MinerCatalogService catalog;
    private final PearlMinerService pearl;
    private final GpuCoinMinerService gpuCoins;

    public WorkerAssignmentService(AgentControlSettingsService controls, MiningService mining,
                                   LocalGpuPowerService gpuPower, MinerCatalogService catalog,
                                   PearlMinerService pearl, GpuCoinMinerService gpuCoins) {
        this.controls = controls;
        this.mining = mining;
        this.gpuPower = gpuPower;
        this.catalog = catalog;
        this.pearl = pearl;
        this.gpuCoins = gpuCoins;
    }

    public synchronized List<WorkerView> workers() {
        List<LocalGpuPowerService.Gpu> cards = gpuPower.discover();
        List<MinerStats.Worker> stats = mining.getStats(cards).workers();
        List<WorkerView> result = new ArrayList<>();
        MinerStats.Worker cpu = stats.stream().filter(worker -> "cpu".equals(worker.deviceId())).findFirst().orElse(null);
        result.add(view("cpu", "CPU", cpu == null ? "CPU" : cpu.hardwareModel(), null, null, stats));
        for (LocalGpuPowerService.Gpu gpu : cards)
            result.add(view(gpu.deviceId(), "GPU", gpu.model(), gpu.vendor(), gpu.index(), stats));
        return result;
    }

    public synchronized WorkerView assign(String deviceId, Assignment request) throws IOException {
        Hardware hardware = hardware(deviceId);
        if (request == null || request.coin() == null) throw new IllegalArgumentException("Zuweisung fehlt");
        String coin = request.coin();
        if (hardware.type.equals("CPU") ? !Set.of("none", "monero").contains(coin) : !Set.of("none", "pearl", "ravencoin", "ethereumclassic", "decred", "quantus").contains(coin))
            throw new IllegalArgumentException("Coin passt nicht zur Hardware");

        String oldCoin = controls.get().coinFor(deviceId);
        if (!oldCoin.equals(coin) && !"none".equals(oldCoin) && !mining.stopExternalWorker(deviceId))
            throw new IllegalStateException("Der laufende Worker konnte nicht angehalten werden");

        if (!"none".equals(coin)) {
            MinerCatalogService.MinerOption option = catalog.options(coin).stream()
                    .filter(candidate -> candidate.id().equals(request.minerSoftwareId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Miner-Software unterstützt diesen Coin nicht"));
            if (!option.installed() || !option.selectable())
                throw new IllegalArgumentException("Miner-Software ist nicht einsatzbereit");
            if (!catalog.select(coin, option.id())) throw new IOException("Miner-Auswahl konnte nicht gespeichert werden");
        }

        if (!controls.setWorkerCoin(deviceId, coin)) throw new IOException("Worker-Zuweisung konnte nicht gespeichert werden");
        if (!controls.setWorkerEnabled(deviceId, request.externalControlEnabled())) {
            controls.setWorkerCoin(deviceId, oldCoin);
            throw new IOException("Worker-Steuerungsmodus konnte nicht gespeichert werden");
        }
        try {
            synchronizeConfiguredDevices(oldCoin, coin);
        } catch (IOException | RuntimeException failure) {
            controls.setWorkerCoin(deviceId, oldCoin);
            synchronizeConfiguredDevices(coin, oldCoin);
            throw failure;
        }
        return workers().stream().filter(worker -> worker.deviceId().equals(deviceId)).findFirst().orElseThrow();
    }

    public synchronized boolean start(String deviceId) {
        Hardware hardware = hardware(deviceId);
        String coin = controls.get().coinFor(deviceId);
        if ("none".equals(coin)) return false;
        if (hardware.type.equals("CPU")) return mining.resumeMining("monero");
        if ("pearl".equals(coin)) return pearl.resumeGpuManually(hardware.vendor, hardware.index);
        return gpuCoins.resumeGpu(coin, hardware.vendor, hardware.index);
    }

    public synchronized boolean pause(String deviceId) {
        hardware(deviceId);
        return mining.stopExternalWorker(deviceId);
    }

    private WorkerView view(String deviceId, String type, String model, String vendor, Integer index,
                            List<MinerStats.Worker> stats) {
        String coin = controls.get().coinFor(deviceId);
        String algorithm = algorithm(coin);
        MinerStats.Worker live = stats.stream().filter(worker -> deviceId.equals(worker.deviceId())
                && (algorithm == null || algorithm.equalsIgnoreCase(worker.currentAlgorithm()))).findFirst().orElse(null);
        MinerCatalogService.MinerOption selected = "none".equals(coin) ? null : catalog.selected(coin);
        String pool = pool(coin);
        boolean configured = switch (coin) {
            case "none", "monero" -> true; // XMRig ensures its default route independently.
            case "pearl" -> pearl.configuration() != null;
            default -> gpuCoins.configuration(coin) != null;
        };
        return new WorkerView(deviceId, type, model, vendor, index, coin, coinName(coin), algorithm,
                selected == null ? null : selected.id(), selected == null ? null : selected.name(),
                selected != null && selected.installed(), configured, pool,
                controls.get().workerEnabled(deviceId), live == null ? MinerStats.MinerStatus.STOPPED : live.miningStatus(), live);
    }

    private void synchronizeConfiguredDevices(String... changedCoins) throws IOException {
        Set<String> affected = new java.util.HashSet<>(List.of(changedCoins));
        for (String coin : affected) {
            if (!GPU_COINS.contains(coin)) continue;
            String devices = assignedDevices(coin);
            // Existing coin credentials remain available for a later reassignment. The legacy
            // coin configuration contract requires at least one GPU, so an empty local profile
            // is represented by workerCoins rather than an invalid empty miner configuration.
            if (devices.isEmpty()) continue;
            if ("pearl".equals(coin)) {
                PearlMinerService.Config config = pearl.configuration();
                if (config != null) pearl.configure(new PearlMinerService.Config(config.poolUrl(), config.proxyUrl(),
                        config.wallet(), config.worker(), devices));
            } else {
                GpuCoinMinerService.Config config = gpuCoins.configuration(coin);
                if (config != null) gpuCoins.configure(coin, new GpuCoinMinerService.Config(config.poolUrl(), config.proxyUrl(),
                        config.wallet(), config.worker(), devices));
            }
        }
    }

    private String assignedDevices(String coin) {
        List<String> ids = new ArrayList<>();
        for (LocalGpuPowerService.Gpu gpu : gpuPower.discover()) {
            if (coin.equals(controls.get().coinFor(gpu.deviceId()))) ids.add(gpu.vendor() + ":" + gpu.index());
        }
        return String.join(",", ids);
    }

    private String pool(String coin) {
        if ("pearl".equals(coin)) return pearl.configuration() == null ? null : pearl.configuration().poolUrl();
        if (GpuCoinMinerService.supported(coin)) return gpuCoins.configuration(coin) == null ? null : gpuCoins.configuration(coin).poolUrl();
        return null;
    }

    private Hardware hardware(String deviceId) {
        if ("cpu".equals(deviceId)) return new Hardware("CPU", null, null);
        return gpuPower.discover().stream().filter(gpu -> gpu.deviceId().equals(deviceId))
                .map(gpu -> new Hardware("GPU", gpu.vendor(), gpu.index())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Gerät wurde nicht erkannt"));
    }

    private static String algorithm(String coin) {
        return switch (coin) {
            case "monero" -> "RandomX";
            case "pearl" -> "PearlHash";
            case "ravencoin", "ethereumclassic", "decred", "quantus" -> GpuCoinMinerService.algorithm(coin);
            default -> null;
        };
    }

    private static String coinName(String coin) {
        return switch (coin) {
            case "monero" -> "Monero";
            case "pearl" -> "Pearl";
            case "ravencoin" -> "Ravencoin";
            case "ethereumclassic" -> "Ethereum Classic";
            case "decred" -> "Decred";
            case "quantus" -> "Quantus";
            default -> "Nicht zugewiesen";
        };
    }

    private record Hardware(String type, String vendor, Integer index) { }
    public record Assignment(String coin, String minerSoftwareId, boolean externalControlEnabled) { }
    public record WorkerView(String deviceId, String hardwareType, String hardwareModel, String vendor, Integer index,
                             String coin, String coinName, String algorithm, String minerSoftwareId, String minerSoftwareName,
                             boolean minerInstalled, boolean configured, String poolUrl, boolean externalControlEnabled,
                             MinerStats.MinerStatus status, MinerStats.Worker telemetry) { }
}
