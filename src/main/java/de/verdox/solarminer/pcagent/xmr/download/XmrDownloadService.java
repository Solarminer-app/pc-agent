package de.verdox.solarminer.pcagent.xmr.download;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.stereotype.Service;
import de.verdox.solarminer.pcagent.mining.WindowsAntivirusBlock;
import java.io.*;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.*;
import java.util.zip.*;

@Service
public class XmrDownloadService {
    public static final Path MAIN_PATH = Path.of("./solarminer-agent/xmrig/");
    public static final Path CONFIG_PATH = MAIN_PATH.resolve("config.json");
    private static final Logger LOGGER = Logger.getLogger(XmrDownloadService.class.getName());
    private static final long MAX_BYTES = 300L * 1024 * 1024;
    private final XMRigApiClient apiClient;
    private final AtomicBoolean downloading = new AtomicBoolean();
    private volatile String status = "PENDING", detail = "";
    private volatile int progress;

    public XmrDownloadService(XMRigApiClient apiClient) { this.apiClient = apiClient; }
    public String status() { return status; }
    public String detail() { return detail; }
    public int progress() { return progress; }
    public Path installDirectory() { return MAIN_PATH.toAbsolutePath().normalize(); }

    public boolean remove() {
        if (!downloading.compareAndSet(false, true)) return false;
        try {
            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "xmrig.exe" : "xmrig";
            Files.deleteIfExists(MAIN_PATH.toAbsolutePath().normalize().resolve(name));
            status = "PENDING"; detail = "XMRig entfernt; die Pool-Konfiguration bleibt gespeichert."; progress = 0;
            return true;
        } catch (IOException e) {
            status = "FAILED"; detail = "XMRig konnte nicht entfernt werden: " + e.getMessage();
            return false;
        } finally { downloading.set(false); }
    }

    public boolean retry() {
        if (binaryAvailable()) { status = "READY"; progress = 100; return true; }
        if (!downloading.compareAndSet(false, true)) return false;
        status = "DOWNLOADING"; detail = ""; progress = 0;
        Thread.ofVirtual().name("xmrig-download").start(() -> {
            try { install(); status = "READY"; progress = 100; }
            catch (Exception e) {
                status = WindowsAntivirusBlock.causedBy(e) ? "BLOCKED_BY_ANTIVIRUS"
                        : e instanceof UnsupportedOperationException ? "UNSUPPORTED" : "FAILED";
                detail = "BLOCKED_BY_ANTIVIRUS".equals(status) ? WindowsAntivirusBlock.DETAIL
                        : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                LOGGER.log(Level.WARNING, "XMRig installation failed", e);
            } finally { downloading.set(false); }
        });
        return true;
    }

    private boolean binaryAvailable() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "xmrig.exe" : "xmrig";
        return Files.isRegularFile(MAIN_PATH.resolve(name));
    }

    private void install() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String platform = os.contains("win") ? "windows" : os.contains("linux") ? "linux" : os.contains("mac") ? "macos" : null;
        String cpu = arch.equals("amd64") || arch.equals("x86_64") ? "x64" : arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : null;
        if (platform == null || cpu == null) throw new UnsupportedOperationException("Unsupported XMRig operating system or architecture");
        String binaryName = platform.equals("windows") ? "xmrig.exe" : "xmrig";
        XMRigRelease release = apiClient.getLatestXmrigRelease(); progress = 5;
        if (release == null || release.assets() == null) throw new IOException("XMRig release metadata missing");
        XMRigAssetObject asset = release.assets().stream().filter(a -> (platform + "-" + cpu).equals(a.os()) || (platform + "-" + cpu).equals(a.id()))
                .filter(a -> a.name() != null && (a.name().endsWith(".zip") || a.name().endsWith(".tar.gz")))
                .findFirst().orElseThrow(() -> new UnsupportedOperationException("No XMRig archive for " + platform + "-" + cpu));
        if (asset.size() <= 0 || asset.size() > MAX_BYTES || asset.hash() == null || !asset.hash().matches("(?i)(sha256:)?[0-9a-f]{64}"))
            throw new IOException("XMRig release has no usable size or SHA-256 digest");
        URI uri = URI.create(asset.url());
        if (!"https".equals(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                || !uri.getPath().startsWith("/xmrig/xmrig/releases/download/"))
            throw new IOException("XMRig download URL must point to the official GitHub release");
        Path directory = MAIN_PATH.toAbsolutePath().normalize(); Files.createDirectories(directory);
        Path archive = Files.createTempFile(directory, "xmrig-", ".part");
        Path pending = Files.createTempFile(directory, "xmrig-bin-", ".part");
        try {
            URLConnection connection = uri.toURL().openConnection();
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            MessageDigest sha = MessageDigest.getInstance("SHA-256"); long count = 0;
            try (InputStream input = new DigestInputStream(connection.getInputStream(), sha); OutputStream output = Files.newOutputStream(archive)) {
                byte[] buffer = new byte[65536]; int read;
                while ((read = input.read(buffer)) != -1) {
                    count += read;
                    if (count > MAX_BYTES || count > asset.size()) throw new IOException("XMRig archive exceeds expected size");
                    output.write(buffer, 0, read);
                    progress = 5 + (int) Math.min(75, count * 75 / asset.size());
                }
            }
            if (count != asset.size() || !HexFormat.of().formatHex(sha.digest()).equalsIgnoreCase(asset.hash().replaceFirst("(?i)^sha256:", "")))
                throw new IOException("XMRig archive size or SHA-256 mismatch");
            extractBinary(archive, pending, asset.name(), binaryName); progress = 95;
            if (!platform.equals("windows") && !pending.toFile().setExecutable(true, false)) throw new IOException("Cannot make XMRig executable");
            Files.move(pending, directory.resolve(binaryName), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(archive); Files.deleteIfExists(pending); }
    }

    private void extractBinary(Path archive, Path target, String archiveName, String binaryName) throws IOException {
        boolean found = false;
        if (archiveName.endsWith(".zip")) {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory() && Path.of(entry.getName().replace('\\', '/')).getFileName().toString().equals(binaryName)) {
                        copyBounded(zip, target); found = true; break;
                    }
                    zip.closeEntry();
                }
            }
        } else {
            try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextEntry()) != null) {
                    if (entry.isFile() && Path.of(entry.getName().replace('\\', '/')).getFileName().toString().equals(binaryName)) {
                        copyBounded(tar, target); found = true; break;
                    }
                }
            }
        }
        if (!found) throw new IOException("XMRig executable missing from archive");
    }

    private void copyBounded(InputStream source, Path target) throws IOException {
        try (OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[65536]; long count = 0; int read;
            while ((read = source.read(buffer)) != -1) {
                count += read;
                if (count > MAX_BYTES) throw new IOException("XMRig executable exceeds size limit");
                output.write(buffer, 0, read);
            }
            if (count == 0) throw new IOException("XMRig executable is empty");
        }
    }
}
