package de.verdox.solarminer.pcagent.miner.xmr.download;

import de.verdox.solarminer.pcagent.miner.engine.MinerBinaryInstaller;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Installs the official XMRig release for the CPU path. The shared install mechanic
 * (state block, retry, size-capped SHA-256 download, archive wall, atomic placement)
 * lives in {@link MinerBinaryInstaller}; this class keeps only the XMRig release query
 * and the single-binary install layout.
 */
@Service
public class XmrDownloadService extends MinerBinaryInstaller {
    public static final Path MAIN_PATH = Path.of("./solarminer-agent/xmrig/");
    public static final Path CONFIG_PATH = MAIN_PATH.resolve("config.json");
    private final XMRigApiClient apiClient;

    public XmrDownloadService(XMRigApiClient apiClient) { this.apiClient = apiClient; }

    @Override public Path installDirectory() { return MAIN_PATH.toAbsolutePath().normalize(); }

    @Override protected String installerName() { return "XMRig"; }
    @Override protected String downloadThreadName() { return "xmrig-download"; }

    @Override
    protected boolean binaryAvailable() {
        return Files.isRegularFile(MAIN_PATH.resolve(binaryName()));
    }

    @Override
    protected String removeInstalled() throws IOException {
        Files.deleteIfExists(MAIN_PATH.toAbsolutePath().normalize().resolve(binaryName()));
        return "XMRig entfernt; die Pool-Konfiguration bleibt gespeichert.";
    }

    @Override
    protected String removeFailureDetail(IOException failure) {
        return "XMRig konnte nicht entfernt werden: " + failure.getMessage();
    }

    private static String binaryName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "xmrig.exe" : "xmrig";
    }

    @Override
    protected void install() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String platform = os.contains("win") ? "windows" : os.contains("linux") ? "linux" : os.contains("mac") ? "macos" : null;
        String cpu = arch.equals("amd64") || arch.equals("x86_64") ? "x64" : arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : null;
        if (platform == null || cpu == null) throw new UnsupportedOperationException("Unsupported XMRig operating system or architecture");
        boolean windows = platform.equals("windows");
        String binaryName = windows ? "xmrig.exe" : "xmrig";
        XMRigRelease release = apiClient.getLatestXmrigRelease(); setProgress(5);
        if (release == null || release.assets() == null) throw new IOException("XMRig release metadata missing");
        XMRigAssetObject asset = release.assets().stream().filter(a -> (platform + "-" + cpu).equals(a.os()) || (platform + "-" + cpu).equals(a.id()))
                .filter(a -> a.name() != null && (a.name().endsWith(".zip") || a.name().endsWith(".tar.gz")))
                .findFirst().orElseThrow(() -> new UnsupportedOperationException("No XMRig archive for " + platform + "-" + cpu));
        if (asset.size() <= 0 || asset.size() > MAX_ARCHIVE_BYTES || asset.hash() == null || !asset.hash().matches("(?i)(sha256:)?[0-9a-f]{64}"))
            throw new IOException("XMRig release has no usable size or SHA-256 digest");
        URI uri = URI.create(asset.url());
        if (!"https".equals(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                || !uri.getPath().startsWith("/xmrig/xmrig/releases/download/"))
            throw new IOException("XMRig download URL must point to the official GitHub release");
        Path directory = MAIN_PATH.toAbsolutePath().normalize(); Files.createDirectories(directory);
        Path archive = Files.createTempFile(directory, "xmrig-", ".part");
        Path staging = Files.createTempDirectory(directory, "xmrig-stage-");
        try {
            URLConnection connection = uri.toURL().openConnection();
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            try (InputStream input = connection.getInputStream()) {
                downloadAndVerify(input, archive, asset.hash().replaceFirst("(?i)^sha256:", ""),
                        asset.size(), 75,
                        "XMRig archive exceeds expected size", "XMRig archive size or SHA-256 mismatch");
            }
            extractArchive(archive, staging, windows, "XMRig"); setProgress(95);
            Path binary;
            try (var files = Files.walk(staging)) {
                binary = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals(binaryName))
                        .findFirst().orElseThrow(() -> new IOException("XMRig executable missing from archive"));
            }
            installBinary(binary, directory.resolve(binaryName), windows,
                    "Cannot make XMRig executable", "XMRig executable is empty");
        } finally {
            Files.deleteIfExists(archive);
            deleteTree(staging);
        }
    }
}
