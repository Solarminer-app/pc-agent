package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.pearl.SrbDownloadService;
import de.verdox.solarminer.pcagent.pearl.PearlMinerService;
import de.verdox.solarminer.pcagent.xmr.XmrMinerService;
import de.verdox.solarminer.pcagent.xmr.download.XmrDownloadService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Registry for user-selectable mining software.  Coin lifecycle services deliberately stay
 * separate: a catalog entry describes a concrete software adapter, not a promise that every
 * miner supports every pool, device or fee route.
 */
@Service
public class MinerCatalogService {
    private final XmrDownloadService xmrig;
    private final SrbDownloadService srb;
    private final XmrMinerService xmrMiner;
    private final PearlMinerService pearlMiner;
    private final Path selectionFile;
    private final Map<String, String> selections = new ConcurrentHashMap<>();
    private final Map<String, BooleanSupplier> installers;

    public MinerCatalogService(XmrDownloadService xmrig, SrbDownloadService srb,
                               XmrMinerService xmrMiner, PearlMinerService pearlMiner,
                               @Value("${solarminer.agent.miner-selection-file:./solarminer-agent/miner-selections.properties}") String selectionPath) {
        this.xmrig = xmrig;
        this.srb = srb;
        this.xmrMiner = xmrMiner;
        this.pearlMiner = pearlMiner;
        this.selectionFile = Path.of(selectionPath).toAbsolutePath().normalize();
        this.installers = Map.of("xmrig", xmrig::retry, "srbminer-multi", srb::retry);
        loadSelections();
    }

    /** Metadata is API data so future installers can be added without teaching the UI coin-specific facts. */
    public record MinerOption(String id, String coin, String name, String device, String algorithm,
                              Double developerFeePercent, List<String> advantages, List<String> disadvantages,
                              String projectUrl, boolean experimental, boolean installed,
                              String downloadStatus, String downloadDetail, boolean selectable,
                              String unavailableReason) { }

    public List<MinerOption> options(String coin) {
        return definitions().stream().filter(option -> option.coin().equals(coin)).map(this::state).toList();
    }

    public List<MinerOption> allOptions() { return definitions().stream().map(this::state).toList(); }

    public MinerOption selected(String coin) {
        List<MinerOption> options = options(coin);
        if (options.isEmpty()) return null;
        String selectedId = selections.get(coin);
        return options.stream().filter(option -> option.id().equals(selectedId)).findFirst().orElse(options.getFirst());
    }

    /** Selection is persisted, but changing a binary never starts mining or changes pool configuration. */
    public synchronized boolean select(String coin, String minerId) {
        MinerOption option = options(coin).stream().filter(candidate -> candidate.id().equals(minerId)).findFirst().orElse(null);
        if (option == null || !option.selectable() || !option.installed())
            return false;
        selections.put(coin, minerId);
        try {
            persistSelections();
            return true;
        } catch (IOException failure) {
            loadSelections();
            return false;
        }
    }

    public boolean selectedIsInstalled(String coin) {
        MinerOption selected = selected(coin);
        return selected != null && selected.installed();
    }

    /** Installs an explicitly requested software ID. Other installed miners are not changed or removed. */
    public boolean download(String coin, String minerId) {
        MinerOption option = options(coin).stream().filter(candidate -> candidate.id().equals(minerId)).findFirst().orElse(null);
        BooleanSupplier installer = option == null || !option.selectable() ? null : installers.get(option.id());
        return installer != null && installer.getAsBoolean();
    }

    /** Compatibility endpoint for the existing one-miner-per-coin actions. */
    public boolean downloadSelected(String coin) {
        MinerOption selected = selected(coin);
        return selected != null && download(coin, selected.id());
    }

    private List<MinerOption> definitions() {
        // Registering a future miner is intentionally one self-contained entry plus its adapter.
        return List.of(
                new MinerOption("xmrig", "monero", "XMRig", "CPU", "RandomX", 1.0,
                        List.of("Sehr verbreitet", "RandomX-Optimierungen und Huge Pages"),
                        List.of("Developer Fee", "Nur CPU-Pfad im PC-Agent"), "https://xmrig.com/", false,
                        false, null, null, true, null),
                new MinerOption("srbminer-multi", "pearl", "SRBMiner-MULTI", "GPU", "PearlHash", 2.0,
                        List.of("Gemeinsamer GPU-Installer", "Pro-GPU-Steuerung"),
                        List.of("Developer Fee", "Treiber- und GPU-Kompatibilität erforderlich"), "https://github.com/doktor83/SRBMiner-Multi", false,
                        false, null, null, true, null),
                new MinerOption("srbminer-multi", "ravencoin", "SRBMiner-MULTI", "GPU", "KAWPOW", 0.85,
                        List.of("KAWPOW-Unterstützung", "Gemeinsamer GPU-Installer"),
                        List.of("Developer Fee", "End-to-End-Route noch experimentell"), "https://github.com/doktor83/SRBMiner-Multi", true,
                        false, null, null, true, null),
                new MinerOption("srbminer-multi", "ethereumclassic", "SRBMiner-MULTI", "GPU", "ETCHash", 0.65,
                        List.of("ETCHash-Unterstützung", "Gemeinsamer GPU-Installer"),
                        List.of("Developer Fee", "End-to-End-Route noch experimentell"), "https://github.com/doktor83/SRBMiner-Multi", true,
                        false, null, null, true, null),
                new MinerOption("srbminer-multi", "decred", "SRBMiner-MULTI", "GPU", "BLAKE3 (Decred)", null,
                        List.of("BLAKE3-Decred-Unterstützung", "Bereits verwendeter GPU-Installer"),
                        List.of("GPU und Poolkonto erforderlich", "SolarMiner-Fee-Ziel noch nicht provisioniert"), "https://github.com/doktor83/SRBMiner-Multi", true,
                        false, null, null, true, null),
                new MinerOption("srbminer-multi", "quantus", "SRBMiner-MULTI", "GPU", "QPoW (Poseidon2)", 2.5,
                        List.of("Kryptex QTC pool support", "NVIDIA and AMD RDNA GPU path"),
                        List.of("2.5% miner fee", "SolarMiner QTC fee route and accepted-share credits are not yet verified"), "https://github.com/doktor83/SRBMiner-Multi", true,
                        false, null, null, true, null),
                new MinerOption("teamredminer", "ravencoin", "TeamRedMiner", "AMD GPU", "KAWPOW", 2.0,
                        List.of("Auf AMD-GPUs spezialisiert", "Dokumentierte lokale API"),
                        List.of("Nur AMD", "ETC-Dev-Fee ist auf Polaris niedriger", "Adapter und reale Fee-/Share-Prüfung stehen aus"), "https://github.com/todxx/teamredminer", true,
                        false, "NOT_INTEGRATED", "Noch nicht technisch integriert", false,
                        "Noch nicht integrierter Adapter: kein Download, Start oder Fee-Pfad verfügbar."),
                new MinerOption("teamredminer", "ethereumclassic", "TeamRedMiner", "AMD GPU", "ETCHash", 1.0,
                        List.of("Auf AMD-GPUs spezialisiert", "Dokumentierte lokale API"),
                        List.of("Nur AMD", "Adapter und reale Fee-/Share-Prüfung stehen aus"), "https://github.com/todxx/teamredminer", true,
                        false, "NOT_INTEGRATED", "Noch nicht technisch integriert", false,
                        "Noch nicht integrierter Adapter: kein Download, Start oder Fee-Pfad verfügbar."));
    }

    private MinerOption state(MinerOption definition) {
        boolean xmr = definition.id().equals("xmrig");
        boolean installed = xmr ? xmrMiner.binaryAvailable() : pearlMiner.binaryAvailable();
        return new MinerOption(definition.id(), definition.coin(), definition.name(), definition.device(), definition.algorithm(),
                definition.developerFeePercent(), definition.advantages(), definition.disadvantages(), definition.projectUrl(),
                definition.experimental(), installed, xmr ? xmrig.status() : srb.status(), xmr ? xmrig.detail() : srb.detail(),
                definition.selectable(), definition.unavailableReason());
    }

    private void loadSelections() {
        selections.clear();
        try {
            for (String line : Files.readAllLines(selectionFile)) {
                int separator = line.indexOf('=');
                if (separator > 0) selections.put(line.substring(0, separator), line.substring(separator + 1));
            }
        } catch (IOException ignored) { }
    }

    private void persistSelections() throws IOException {
        Files.createDirectories(selectionFile.getParent());
        String content = selections.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue()).reduce("", (left, right) -> left + right + "\n");
        Path temporary = Files.createTempFile(selectionFile.getParent(), "miner-selection-", ".tmp");
        try {
            Files.writeString(temporary, content);
            try { Files.move(temporary, selectionFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, selectionFile, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
