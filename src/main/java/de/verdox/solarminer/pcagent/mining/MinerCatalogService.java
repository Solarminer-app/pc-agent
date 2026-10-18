package de.verdox.solarminer.pcagent.mining;

import de.verdox.solarminer.pcagent.miner.srb.SrbDownloadService;
import de.verdox.solarminer.pcagent.miner.quanpool.QuanPoolDownloadService;
import de.verdox.solarminer.pcagent.miner.quanpool.QuanPoolMinerService;
import de.verdox.solarminer.pcagent.miner.peak.PeakDownloadService;
import de.verdox.solarminer.pcagent.miner.peak.PeakMinerService;
import de.verdox.solarminer.pcagent.miner.wildrig.WildRigDownloadService;
import de.verdox.solarminer.pcagent.miner.wildrig.WildRigMinerService;
import de.verdox.solarminer.pcagent.miner.engine.PinnedGpuDownloadService;
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
    private final QuanPoolDownloadService quanPool;
    private final QuanPoolMinerService quanPoolMiner;
    private final PeakDownloadService peak;
    private final PeakMinerService peakMiner;
    private final WildRigDownloadService wildRig;
    private final WildRigMinerService wildRigMiner;
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
    private volatile boolean srbUpdateRequired;
    private volatile boolean quanPoolInstalled;
    private volatile boolean peakInstalled;
    private volatile boolean wildRigInstalled;

    @org.springframework.beans.factory.annotation.Autowired
    public MinerCatalogService(XmrDownloadService xmrig, SrbDownloadService srb,
                               XmrMinerService xmrMiner, PearlMinerService pearlMiner,
                               QuanPoolDownloadService quanPool, QuanPoolMinerService quanPoolMiner,
                               PeakDownloadService peak, PeakMinerService peakMiner,
                               WildRigDownloadService wildRig, WildRigMinerService wildRigMiner,
                               @Value("${solarminer.agent.miner-selection-file:./solarminer-agent/miner-selections.properties}") String selectionPath) {
        this.xmrig = xmrig;
        this.srb = srb;
        this.xmrMiner = xmrMiner;
        this.pearlMiner = pearlMiner;
        this.quanPool = quanPool;
        this.quanPoolMiner = quanPoolMiner;
        this.peak = peak; this.peakMiner = peakMiner;
        this.wildRig = wildRig; this.wildRigMiner = wildRigMiner;
        this.selectionFile = Path.of(selectionPath).toAbsolutePath().normalize();
        java.util.Map<String, BooleanSupplier> registered = new java.util.HashMap<>();
        registered.put("xmrig", xmrig::retry); registered.put("srbminer-multi", srb::retry);
        if (quanPool != null) registered.put("quanpool-miner", quanPool::retry);
        if (peak != null) registered.put("peakminer", peak::retry);
        if (wildRig != null) registered.put("wildrig-ng", wildRig::retry);
        this.installers = Map.copyOf(registered);
        loadSelections();
    }

    /** Test/backward-compatible constructor for contexts that do not instantiate the optional adapter. */
    public MinerCatalogService(XmrDownloadService xmrig, SrbDownloadService srb,
                               XmrMinerService xmrMiner, PearlMinerService pearlMiner, String selectionPath) {
        this(xmrig, srb, xmrMiner, pearlMiner, null, null, null, null, null, null, selectionPath);
    }

    public MinerCatalogService(XmrDownloadService xmrig, SrbDownloadService srb,
                               XmrMinerService xmrMiner, PearlMinerService pearlMiner,
                               QuanPoolDownloadService quanPool, QuanPoolMinerService quanPoolMiner,
                               String selectionPath) {
        this(xmrig, srb, xmrMiner, pearlMiner, quanPool, quanPoolMiner,
                null, null, null, null, selectionPath);
    }

    /** Metadata is API data so future installers can be added without teaching the UI coin-specific facts. */
    public record MinerOption(String id, String coin, String coinName, String name, String device,
                              List<String> compatibleGpuVendors, String algorithm,
                              Double developerFeePercent, String developerFeeLabel, String developerFeeSource,
                              List<String> advantages, List<String> disadvantages,
                              String projectUrl, boolean experimental, boolean installed,
                              String downloadStatus, String downloadDetail, boolean installable, boolean selectable,
                              String unavailableReason) {
        public MinerOption(String id, String coin, String coinName, String name, String device, String algorithm,
                           Double developerFeePercent, String developerFeeLabel, String developerFeeSource,
                           List<String> advantages, List<String> disadvantages, String projectUrl,
                           boolean experimental, boolean installed, String downloadStatus, String downloadDetail,
                           boolean installable, boolean selectable, String unavailableReason) {
            this(id, coin, coinName, name, device, supportedGpuVendors(id, coin), algorithm, developerFeePercent,
                    developerFeeLabel, developerFeeSource, advantages, disadvantages, projectUrl, experimental,
                    installed, downloadStatus, downloadDetail, installable, selectable, unavailableReason);
        }
        /** Static catalog definitions carry no display name; {@code state} resolves it from the coin id. */
        public MinerOption(String id, String coin, String name, String device, String algorithm,
                    Double developerFeePercent, String developerFeeLabel, String developerFeeSource,
                    List<String> advantages, List<String> disadvantages,
                    String projectUrl, boolean experimental, boolean installed,
                    String downloadStatus, String downloadDetail, boolean selectable,
                    String unavailableReason) {
            this(id, coin, null, name, device, supportedGpuVendors(id, coin), algorithm, developerFeePercent, developerFeeLabel, developerFeeSource,
                    advantages, disadvantages,
                    projectUrl, experimental, installed, downloadStatus, downloadDetail, selectable, selectable, unavailableReason);
        }

        public MinerOption(String id, String coin, String name, String device, String algorithm,
                           Double developerFeePercent, String developerFeeLabel, String developerFeeSource,
                           List<String> advantages, List<String> disadvantages,
                           String projectUrl, boolean experimental, boolean installed,
                           String downloadStatus, String downloadDetail, boolean installable, boolean selectable,
                           String unavailableReason) {
            this(id, coin, null, name, device, supportedGpuVendors(id, coin), algorithm, developerFeePercent, developerFeeLabel, developerFeeSource,
                    advantages, disadvantages, projectUrl, experimental, installed, downloadStatus, downloadDetail,
                    installable, selectable, unavailableReason);
        }
    }

    /** Closed compatibility facts used by both the catalog and per-device assignment validation. */
    public static List<String> supportedGpuVendors(String minerId, String coin) {
        if ("xmrig".equals(minerId)) return List.of();
        if ("quanpool-miner".equals(minerId)) return List.of("NVIDIA");
        if ("peakminer".equals(minerId) && "pearl".equals(coin)) return List.of("NVIDIA");
        if ("wildrig-ng".equals(minerId) && "quantus".equals(coin)) return List.of("NVIDIA");
        if ("teamredminer".equals(minerId)) return List.of("AMD");
        if (!"srbminer-multi".equals(minerId)) return List.of();
        if (de.verdox.solarminer.pcagent.coin.Coin.PEARL.id().equals(coin)
                || de.verdox.solarminer.pcagent.coin.Coin.QUANTUS.id().equals(coin)) return List.of("AMD", "NVIDIA");
        if (de.verdox.solarminer.pcagent.coin.Coin.RAVENCOIN.id().equals(coin)
                || de.verdox.solarminer.pcagent.coin.Coin.ETHEREUMCLASSIC.id().equals(coin))
            return List.of("AMD", "NVIDIA", "INTEL");
        if (de.verdox.solarminer.pcagent.coin.Coin.DECRED.id().equals(coin)) return List.of("AMD", "NVIDIA");
        return List.of();
    }

    public static boolean supportsHardware(MinerOption option, String hardwareType, String gpuVendor) {
        if ("CPU".equals(hardwareType)) return "CPU".equals(option.device());
        return "GPU".equals(hardwareType) && gpuVendor != null
                && option.compatibleGpuVendors().contains(gpuVendor.toUpperCase(java.util.Locale.ROOT));
    }

    /** Reject known unsupported NVIDIA generations before persisting an assignment. */
    public static boolean supportsGpuModel(MinerOption option, String model) {
        return supportsGpuModel(option.id(), model);
    }

    public static boolean supportsGpuModel(String minerId, String model) {
        if (model == null) return false;
        String normalized = model.toUpperCase(java.util.Locale.ROOT);
        if ("peakminer".equals(minerId))
            return !normalized.matches(".*(?:GTX|TESLA P[0-9]+|TESLA V[0-9]+|TITAN V|QUADRO P[0-9]+).*");
        if ("wildrig-ng".equals(minerId))
            return !normalized.matches(".*(?:GTX [79][0-9]{2}|TESLA M[0-9]+|TITAN X).*");
        return true;
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
        BooleanSupplier installer = option == null || !option.installable() ? null : installers.get(option.id());
        boolean started = installer != null && installer.getAsBoolean();
        if (started) refreshInstallState();
        return started;
    }

    /** Compatibility endpoint for the existing one-miner-per-coin actions. */
    public boolean downloadSelected(String coin) {
        MinerOption selected = selected(coin);
        return selected != null && download(coin, selected.id());
    }

    /** Alternative miners own independent installations and never remove SRBMiner. */
    public boolean remove(String coin, String minerId) {
        MinerOption option = options(coin).stream().filter(candidate -> candidate.id().equals(minerId)).findFirst().orElse(null);
        if (option == null) return false;
        de.verdox.solarminer.pcagent.miner.engine.MinerBinaryInstaller installer;
        de.verdox.solarminer.pcagent.miner.GpuCoinMiner miner;
        switch (option.id()) {
            case "quanpool-miner" -> { installer = quanPool; miner = quanPoolMiner; }
            case "peakminer" -> { installer = peak; miner = peakMiner; }
            case "wildrig-ng" -> { installer = wildRig; miner = wildRigMiner; }
            default -> { return false; }
        }
        if (installer == null || miner == null || installer.state() == de.verdox.solarminer.pcagent.coin.DownloadState.DOWNLOADING)
            return false;
        boolean removed = miner.stopAll() && installer.remove();
        if (removed) refreshInstallState();
        return removed;
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
                new MinerOption("srbminer-multi", de.verdox.solarminer.pcagent.coin.Coin.PEARL.id(), "SRBMiner-MULTI", "NVIDIA RTX 2000+ / AMD RDNA2", "PearlHash", 2.0,
                        "SRBMiner-MULTI Entwicklergebühr", "https://github.com/doktor83/SRBMiner-Multi",
                        List.of("Gemeinsamer GPU-Installer", "Pro-GPU-Steuerung"),
                        List.of("Developer Fee", "Treiber- und GPU-Kompatibilität erforderlich"), "https://github.com/doktor83/SRBMiner-Multi", false,
                        false, null, null, true, null),
                new MinerOption("srbminer-multi", de.verdox.solarminer.pcagent.coin.Coin.RAVENCOIN.id(), "SRBMiner-MULTI", "AMD / NVIDIA / Intel GPU", "KAWPOW", 0.85,
                        "SRBMiner-MULTI KAWPOW Entwicklergebühr", "SRBMiner-MULTI 3.7.1 --list-algorithms",
                        List.of("KAWPOW-Unterstützung", "Gemeinsamer GPU-Installer"),
                        List.of("Developer Fee", "End-to-End-Route noch experimentell"), "https://github.com/doktor83/SRBMiner-Multi", true,
                        false, null, null, true, null),
                new MinerOption("srbminer-multi", de.verdox.solarminer.pcagent.coin.Coin.ETHEREUMCLASSIC.id(), "SRBMiner-MULTI", "AMD / NVIDIA / Intel GPU", "ETCHash", 0.65,
                        "SRBMiner-MULTI ETCHash Entwicklergebühr", "SRBMiner-MULTI 3.7.1 --list-algorithms",
                        List.of("ETCHash-Unterstützung", "Gemeinsamer GPU-Installer"),
                        List.of("Developer Fee", "End-to-End-Route noch experimentell"), "https://github.com/doktor83/SRBMiner-Multi", true,
                        false, null, null, true, null),
                new MinerOption("srbminer-multi", de.verdox.solarminer.pcagent.coin.Coin.DECRED.id(), "SRBMiner-MULTI", "AMD / NVIDIA GPU", "BLAKE3 (Decred)", null,
                        "SRBMiner-MULTI BLAKE3-Decred Entwicklergebühr", "Gebühr für die gewählte SRBMiner-Version muss vor Start verifiziert werden",
                        List.of("BLAKE3-Decred-Unterstützung", "Bereits verwendeter GPU-Installer"),
                        List.of("GPU und Poolkonto erforderlich", "SolarMiner-Fee-Ziel noch nicht provisioniert"), "https://github.com/doktor83/SRBMiner-Multi", true,
                        false, null, null, true, null),
                new MinerOption("srbminer-multi", de.verdox.solarminer.pcagent.coin.Coin.QUANTUS.id(), "SRBMiner-MULTI", "AMD / NVIDIA GPU", "QPoW (Poseidon2)", 2.5,
                        "SRBMiner-MULTI QPoW Entwicklergebühr", "https://pool.kryptex.com/qtc",
                        List.of("Kryptex QTC pool support", "NVIDIA and AMD RDNA GPU path"),
                        List.of("2.5% miner fee", "SolarMiner QTC fee route and accepted-share credits are not yet verified"), "https://github.com/doktor83/SRBMiner-Multi", true,
                        false, null, null, true, null),
                new MinerOption("quanpool-miner", de.verdox.solarminer.pcagent.coin.Coin.QUANTUS.id(), "QuanPool Miner", "NVIDIA CUDA · Linux x86-64", "QPoW (Poseidon2)", 2.0,
                        "QuanPool Miner developer fee", "https://quanpool.com/",
                        List.of("Optimized native CUDA engine", "Stable NVIDIA GPU selection by driver UUID", "HiveOS-compatible JSON metrics"),
                        List.of("Closed binary with a 2% developer fee", "Real SolarMiner fee-share accounting is still pending"),
                        "https://quanpool.com/", true, false, null, null, true, true, null),
                new MinerOption("peakminer", de.verdox.solarminer.pcagent.coin.Coin.PEARL.id(), "PeakMiner", "NVIDIA RTX 2000+ · Linux/Windows x86-64", "PearlHash", 2.0,
                        "PeakMiner developer fee", "https://github.com/peakminer/peakminer",
                        List.of("Per-GPU HTTP statistics", "Independent NVIDIA worker process"),
                        List.of("Experimental proxy route", "Pool share accounting pending"),
                        "https://github.com/peakminer/peakminer", true, false, null, null, true, true, null),
                new MinerOption("wildrig-ng", de.verdox.solarminer.pcagent.coin.Coin.QUANTUS.id(), "WildRig NG", "NVIDIA CUDA · Linux/Windows x86-64", "QPoW (Poseidon2)", 2.0,
                        "WildRig NG developer fee", "https://wildrig.cc/",
                        List.of("Per-GPU JSON statistics", "Independent NVIDIA worker process"),
                        List.of("Experimental proxy route", "Metrics API listens on all network interfaces"),
                        "https://wildrig.cc/", true, false, null, null, true, true, null),
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
        boolean quan = definition.id().equals("quanpool-miner");
        boolean peakMinerOption = definition.id().equals("peakminer");
        boolean wildRigOption = definition.id().equals("wildrig-ng");
        boolean srbMiner = definition.id().equals("srbminer-multi");
        // Snapshot flags only (audit R5): the filesystem probe lives in refreshInstallState().
        boolean installed = xmr ? xmrigInstalled : quan ? quanPoolInstalled
                : peakMinerOption ? peakInstalled : wildRigOption ? wildRigInstalled : srbMiner && srbInstalled;
        String downloadStatus = xmr ? xmrig.status() : quan ? quanPool == null ? "NOT_INTEGRATED" : quanPool.status()
                : peakMinerOption ? peak == null ? "NOT_INTEGRATED" : peak.status()
                : wildRigOption ? wildRig == null ? "NOT_INTEGRATED" : wildRig.status()
                : srbMiner ? srbUpdateRequired ? "UPDATE_REQUIRED" : srb.status() : definition.downloadStatus();
        // The UI owns the localized update explanation. Do not expose an untranslated
        // backend detail string for this expected migration state.
        String downloadDetail = xmr ? xmrig.detail() : quan ? quanPool == null ? "Not technically integrated yet" : quanPool.detail()
                : peakMinerOption ? peak == null ? "Not technically integrated yet" : peak.detail()
                : wildRigOption ? wildRig == null ? "Not technically integrated yet" : wildRig.detail()
                : srbMiner ? srbUpdateRequired ? "" : srb.detail() : definition.downloadDetail();
        boolean platformSupported = quan ? quanPool != null && quanPoolPlatformSupported()
                : (peakMinerOption || wildRigOption) ? PinnedGpuDownloadService.supportedPlatform() : true;
        return new MinerOption(definition.id(), definition.coin(),
                java.util.Optional.ofNullable(de.verdox.solarminer.pcagent.coin.Coin.byIdOrNull(definition.coin()))
                        .map(de.verdox.solarminer.pcagent.coin.Coin::displayName).orElse(definition.coin()),
                definition.name(), definition.device(), definition.algorithm(),
                definition.developerFeePercent(), definition.developerFeeLabel(), definition.developerFeeSource(),
                definition.advantages(), definition.disadvantages(), definition.projectUrl(),
                definition.experimental(), installed, downloadStatus, downloadDetail,
                definition.installable() && platformSupported, definition.selectable() && platformSupported,
                platformSupported ? definition.unavailableReason() : quan
                        ? quanPoolUnsupportedReason(System.getProperty("os.name", ""))
                        : "This miner requires Linux or Windows x86-64");
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
            srbUpdateRequired = !srbInstalled && pearlMiner.binaryPresent();
            quanPoolInstalled = quanPool != null && quanPool.binaryAvailable();
            peakInstalled = peak != null && peak.binaryAvailable();
            wildRigInstalled = wildRig != null && wildRig.binaryAvailable();
        } catch (RuntimeException ignored) { /* keep the previous observation */ }
    }

    private static boolean quanPoolPlatformSupported() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    static String quanPoolUnsupportedReason(String osName) {
        return osName.toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "QuanPool Miner has no Windows build. For Quantus on Windows, use WildRig NG or SRBMiner-MULTI."
                : "QuanPool Miner 6.4.0 is available only for Linux x86-64";
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
