package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.miner.srb.SrbDownloadService;
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
    /**
     * REST audit R5: binary availability is a filesystem check, so it is collected on the
     * scheduler (and after every install/remove) instead of on every catalog read. The two
     * booleans are volatile and only ever replaced wholesale; a catalog GET reads the last
     * completed observation.
     */
    private volatile boolean xmrigInstalled;
    private volatile boolean srbInstalled;

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
    public record MinerOption(String id, String coin, String coinName, String name, String device, String algorithm,
                              Double developerFeePercent, String developerFeeLabel, String developerFeeSource,
                              List<String> advantages, List<String> disadvantages,
                              String projectUrl, boolean experimental, boolean installed,
                              String downloadStatus, String downloadDetail, boolean selectable,
                              String unavailableReason) {
        /** Static catalog definitions carry no display name; {@code state} resolves it from the coin id. */
        public MinerOption(String id, String coin, String name, String device, String algorithm,
                    Double developerFeePercent, String developerFeeLabel, String developerFeeSource,
                    List<String> advantages, List<String> disadvantages,
                    String projectUrl, boolean experimental, boolean installed,
                    String downloadStatus, String downloadDetail, boolean selectable,
                    String unavailableReason) {
            this(id, coin, null, name, device, algorithm, developerFeePercent, developerFeeLabel, developerFeeSource,
                    advantages, disadvantages,
                    projectUrl, experimental, installed, downloadStatus, downloadDetail, selectable, unavailableReason);
        }
    }

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
        boolean started = installer != null && installer.getAsBoolean();
        if (started) refreshInstallState();
        return started;
    }

    /** Compatibility endpoint for the existing one-miner-per-coin actions. */
    public boolean downloadSelected(String coin) {
        MinerOption selected = selected(coin);
        return selected != null && download(coin, selected.id());
    }

    /**
     * The single truth for per-miner developer fees. {@link FeeTransparencyService} reads the
     * selected entry here instead of keeping its own coin switch; a null percent means the fee
     * of the currently shipped miner version still has to be verified before it is claimed.
     */
    private List<MinerOption> definitions() {
        // Registering a future miner is intentionally one self-contained entry plus its adapter.
        return List.of(
                new MinerOption("xmrig", de.verdox.solarminer.pcagent.coin.Coin.MONERO.id(), "XMRig", "CPU", "RandomX", 1.0,
                        "XMRig Entwickler-Spende", "https://xmrig.com/docs/miner/config/network",
                        List.of("Sehr verbreitet", "RandomX-Optimierungen und Huge Pages"),
                        List.of("Developer Fee", "Nur CPU-Pfad im PC-Agent"), "https://xmrig.com/", false,
                        false, null, null, true, null),
                new MinerOption("srbminer-multi", de.verdox.solarminer.pcagent.coin.Coin.PEARL.id(), "SRBMiner-MULTI", "GPU", "PearlHash", 2.0,
                        "SRBMiner-MULTI Entwicklergebühr", "https://github.com/doktor83/SRBMiner-Multi",
                        List.of("Gemeinsamer GPU-Installer", "Pro-GPU-Steuerung"),
                        List.of("Developer Fee", "Treiber- und GPU-Kompatibilität erforderlich"), "https://github.com/doktor83/SRBMiner-Multi", false,
                        false, null, null, true, null),
                new MinerOption("srbminer-multi", de.verdox.solarminer.pcagent.coin.Coin.RAVENCOIN.id(), "SRBMiner-MULTI", "GPU", "KAWPOW", 0.85,
                        "SRBMiner-MULTI KAWPOW Entwicklergebühr", "SRBMiner-MULTI 3.7.1 --list-algorithms",
                        List.of("KAWPOW-Unterstützung", "Gemeinsamer GPU-Installer"),
                        List.of("Developer Fee", "End-to-End-Route noch experimentell"), "https://github.com/doktor83/SRBMiner-Multi", true,
                        false, null, null, true, null),
                new MinerOption("srbminer-multi", de.verdox.solarminer.pcagent.coin.Coin.ETHEREUMCLASSIC.id(), "SRBMiner-MULTI", "GPU", "ETCHash", 0.65,
                        "SRBMiner-MULTI ETCHash Entwicklergebühr", "SRBMiner-MULTI 3.7.1 --list-algorithms",
                        List.of("ETCHash-Unterstützung", "Gemeinsamer GPU-Installer"),
                        List.of("Developer Fee", "End-to-End-Route noch experimentell"), "https://github.com/doktor83/SRBMiner-Multi", true,
                        false, null, null, true, null),
                new MinerOption("srbminer-multi", de.verdox.solarminer.pcagent.coin.Coin.DECRED.id(), "SRBMiner-MULTI", "GPU", "BLAKE3 (Decred)", null,
                        "SRBMiner-MULTI BLAKE3-Decred Entwicklergebühr", "Gebühr für die gewählte SRBMiner-Version muss vor Start verifiziert werden",
                        List.of("BLAKE3-Decred-Unterstützung", "Bereits verwendeter GPU-Installer"),
                        List.of("GPU und Poolkonto erforderlich", "SolarMiner-Fee-Ziel noch nicht provisioniert"), "https://github.com/doktor83/SRBMiner-Multi", true,
                        false, null, null, true, null),
                new MinerOption("srbminer-multi", de.verdox.solarminer.pcagent.coin.Coin.QUANTUS.id(), "SRBMiner-MULTI", "GPU", "QPoW (Poseidon2)", 2.5,
                        "SRBMiner-MULTI QPoW Entwicklergebühr", "https://pool.kryptex.com/qtc",
                        List.of("Kryptex QTC pool support", "NVIDIA and AMD RDNA GPU path"),
                        List.of("2.5% miner fee", "SolarMiner QTC fee route and accepted-share credits are not yet verified"), "https://github.com/doktor83/SRBMiner-Multi", true,
                        false, null, null, true, null),
                new MinerOption("teamredminer", de.verdox.solarminer.pcagent.coin.Coin.RAVENCOIN.id(), "TeamRedMiner", "AMD GPU", "KAWPOW", 2.0,
                        "TeamRedMiner Entwicklergebühr", "https://github.com/todxx/teamredminer",
                        List.of("Auf AMD-GPUs spezialisiert", "Dokumentierte lokale API"),
                        List.of("Nur AMD", "ETC-Dev-Fee ist auf Polaris niedriger", "Adapter und reale Fee-/Share-Prüfung stehen aus"), "https://github.com/todxx/teamredminer", true,
                        false, "NOT_INTEGRATED", "Noch nicht technisch integriert", false,
                        "Noch nicht integrierter Adapter: kein Download, Start oder Fee-Pfad verfügbar."),
                new MinerOption("teamredminer", de.verdox.solarminer.pcagent.coin.Coin.ETHEREUMCLASSIC.id(), "TeamRedMiner", "AMD GPU", "ETCHash", 1.0,
                        "TeamRedMiner Entwicklergebühr", "https://github.com/todxx/teamredminer",
                        List.of("Auf AMD-GPUs spezialisiert", "Dokumentierte lokale API"),
                        List.of("Nur AMD", "Adapter und reale Fee-/Share-Prüfung stehen aus"), "https://github.com/todxx/teamredminer", true,
                        false, "NOT_INTEGRATED", "Noch nicht technisch integriert", false,
                        "Noch nicht integrierter Adapter: kein Download, Start oder Fee-Pfad verfügbar."));
    }

    private MinerOption state(MinerOption definition) {
        boolean xmr = definition.id().equals("xmrig");
        // Snapshot flags only (audit R5): the filesystem probe lives in refreshInstallState().
        boolean installed = xmr ? xmrigInstalled : srbInstalled;
        return new MinerOption(definition.id(), definition.coin(),
                java.util.Optional.ofNullable(de.verdox.solarminer.pcagent.coin.Coin.byIdOrNull(definition.coin()))
                        .map(de.verdox.solarminer.pcagent.coin.Coin::displayName).orElse(definition.coin()),
                definition.name(), definition.device(), definition.algorithm(),
                definition.developerFeePercent(), definition.developerFeeLabel(), definition.developerFeeSource(),
                definition.advantages(), definition.disadvantages(), definition.projectUrl(),
                definition.experimental(), installed, xmr ? xmrig.status() : srb.status(), xmr ? xmrig.detail() : srb.detail(),
                definition.selectable(), definition.unavailableReason());
    }

    /**
     * Re-probes both miner installations (file checks against the install directories). Runs
     * at startup, after install/remove actions, and on the fixed interval below, so a catalog
     * read never touches the filesystem itself.
     */
    public void refreshInstallState() {
        try {
            xmrigInstalled = xmrMiner.binaryAvailable();
            srbInstalled = pearlMiner.binaryAvailable();
        } catch (RuntimeException ignored) { /* keep the previous observation */ }
    }

    /** Scheduler entry point: the probe runs on a virtual thread to keep the shared scheduler free. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${solarminer.agent.miner-install-refresh-ms:10000}")
    public void refreshInstallStateScheduled() {
        Thread.startVirtualThread(this::refreshInstallState);
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmInstallState() {
        refreshInstallState();
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
        String content = selections.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue()).reduce("", (left, right) -> left + right + "\n");
        if (!de.verdox.solarminer.pcagent.support.AtomicFileStore.writeText(selectionFile, content, "miner-selection-")) {
            throw new IOException("Could not persist miner selections");
        }
    }
}
