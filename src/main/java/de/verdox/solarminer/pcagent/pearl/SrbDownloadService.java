package de.verdox.solarminer.pcagent.pearl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.stereotype.Service;
import de.verdox.solarminer.pcagent.mining.WindowsAntivirusBlock;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Locale;
import java.util.List;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs the official, checksum-verified SRBMiner release when the binary is absent. */
@Service
public class SrbDownloadService {
    private static final Logger LOGGER = Logger.getLogger(SrbDownloadService.class.getName());
    private static final String WINDOWS_VERSION = "3.7.0";
    private static final URI LINUX_RELEASE_API = URI.create("https://api.github.com/repos/doktor83/SRBMiner-Multi/releases/latest");
    private static final long MAX_ARCHIVE_BYTES = 300L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 1024L * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper mapper;
    private final PearlMinerService miner;
    private final AtomicBoolean downloading = new AtomicBoolean();
    private volatile String status = "PENDING";
    private volatile String detail = "";
    private volatile int progress = 0;

    public SrbDownloadService(ObjectMapper mapper, PearlMinerService miner) {
        this.mapper = mapper;
        this.miner = miner;
    }

    public boolean retry() {
        if (miner.binaryAvailable()) {
            status = "READY";
            detail = "";
            progress = 100;
            return true;
        }
        if (!downloading.compareAndSet(false, true)) return false;
        status = "DOWNLOADING";
        detail = "";
        progress = 0;
        Thread.ofVirtual().name("srbminer-download").start(() -> {
            try {
                install();
                progress = 100;
                status = "READY";
                detail = "";
            } catch (Exception e) {
                status = WindowsAntivirusBlock.causedBy(e) ? "BLOCKED_BY_ANTIVIRUS"
                        : e instanceof UnsupportedOperationException ? "UNSUPPORTED" : "FAILED";
                detail = "BLOCKED_BY_ANTIVIRUS".equals(status) ? WindowsAntivirusBlock.DETAIL
                        : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                LOGGER.log(Level.WARNING, "SRBMiner-MULTI installation failed", e);
            } finally {
                downloading.set(false);
            }
        });
        return true;
    }

    public String status() { return status; }
    public String detail() { return detail; }
    public int progress() { return progress; }
    public Path installDirectory() { return miner.executablePath().getParent().toAbsolutePath().normalize(); }

    public boolean remove() {
        if (!downloading.compareAndSet(false, true)) return false;
        try {
            Path executable = miner.executablePath().toAbsolutePath().normalize();
            Path target = executable.getParent();
            Path manifest = target.resolve(".solarminer-srbminer-files.json");
            if (Files.isRegularFile(manifest)) {
                List<String> files = mapper.readValue(manifest.toFile(), new TypeReference<>() { });
                Path realTarget = target.toRealPath();
                for (String relative : files) {
                    Path file = target.resolve(relative).normalize();
                    if (!file.startsWith(target) || file.equals(miner.configurationPath())) continue;
                    Path parent = file.getParent();
                    if (Files.exists(parent) && !parent.toRealPath().startsWith(realTarget))
                        throw new IOException("Tracked package path escapes the SRBMiner directory");
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(executable);
            Files.deleteIfExists(manifest);
            status = "PENDING"; detail = "SRBMiner entfernt; die Pearl-Konfiguration bleibt gespeichert."; progress = 0;
            return true;
        } catch (IOException e) {
            status = "FAILED"; detail = "SRBMiner konnte nicht vollständig entfernt werden: " + e.getMessage();
            return false;
        } finally { downloading.set(false); }
    }

    private void install() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!arch.equals("amd64") && !arch.equals("x86_64"))
            throw new UnsupportedOperationException("SRBMiner-MULTI supports only x64 on this agent");
        boolean windows = os.contains("win");
        if (!windows && !os.contains("linux"))
            throw new UnsupportedOperationException("No official SRBMiner package for this operating system");

        URI releaseApi = windows
                ? URI.create("https://api.github.com/repos/doktor83/SRBMiner-Multi/releases/tags/" + WINDOWS_VERSION)
                : LINUX_RELEASE_API;
        HttpRequest releaseRequest = HttpRequest.newBuilder(releaseApi).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SolarMiner-PC-Agent").GET().build();
        HttpResponse<InputStream> releaseResponse = http.send(releaseRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (releaseResponse.statusCode() != 200) {
            try (InputStream ignored = releaseResponse.body()) { }
            throw new IOException("GitHub release API returned HTTP " + releaseResponse.statusCode());
        }
        JsonNode release;
        progress = 5;
        try (InputStream stream = releaseResponse.body()) {
            byte[] json = stream.readNBytes(1024 * 1024 + 1);
            if (json.length > 1024 * 1024) throw new IOException("Release metadata too large");
            release = mapper.readTree(json);
        }
        if (release.path("prerelease").asBoolean() || release.path("draft").asBoolean())
            throw new IOException("SRBMiner release is not stable");
        String tag = release.path("tag_name").asText();
        if (windows ? !tag.equals(WINDOWS_VERSION) : !tag.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
            throw new IOException("Unexpected SRBMiner release tag");
        String name = "SRBMiner-Multi-" + tag.replace('.', '-') + (windows ? "-win64.zip" : "-Linux.tar.gz");
        JsonNode asset = null;
        for (JsonNode candidate : release.path("assets"))
            if (name.equals(candidate.path("name").asText())) { asset = candidate; break; }
        if (asset == null) throw new IOException("No SRBMiner asset for " + name);
        String digest = asset.path("digest").asText();
        long size = asset.path("size").asLong();
        String url = asset.path("browser_download_url").asText();
        if (!digest.matches("sha256:[0-9a-fA-F]{64}") || size < 1 || size > MAX_ARCHIVE_BYTES
                || !url.equals("https://github.com/doktor83/SRBMiner-Multi/releases/download/" + tag + "/" + name))
            throw new IOException("SRBMiner release asset metadata is invalid");

        Path executable = miner.executablePath();
        Path target = executable.getParent();
        Files.createDirectories(target);
        Path archive = Files.createTempFile(target, "srbminer-", windows ? ".zip" : ".tar.gz");
        Path staging = Files.createTempDirectory(target, "srbminer-stage-");
        try {
            download(URI.create(url), archive, digest.substring(7), size);
            progress = 85;
            extract(archive, staging, windows);
            progress = 95;
            Path binary;
            try (var files = Files.walk(staging)) {
                binary = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals(executable.getFileName().toString()))
                        .findFirst().orElseThrow(() -> new IOException("SRBMiner executable missing from archive"));
            }
            Path packageRoot = binary.getParent();
            List<String> installedFiles = new ArrayList<>();
            try (var files = Files.walk(packageRoot)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path destination = target.resolve(packageRoot.relativize(file)).normalize();
                    if (!destination.startsWith(target)) throw new IOException("Invalid package path");
                    if (destination.equals(executable) || destination.getFileName().toString().equals("solarminer-config.json"))
                        continue;
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    installedFiles.add(target.relativize(destination).toString());
                }
            }
            if (!miner.binaryAvailable()) {
                Path pending = Files.createTempFile(target, "srbminer-binary-", ".part");
                try {
                    Files.copy(binary, pending, StandardCopyOption.REPLACE_EXISTING);
                    if (!windows && !pending.toFile().setExecutable(true, false))
                        throw new IOException("Could not make SRBMiner executable");
                    try {
                        Files.move(pending, executable, StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(pending, executable);
                    }
                } finally {
                    Files.deleteIfExists(pending);
                }
            }
            installedFiles.add(target.relativize(executable).toString());
            Path manifest = target.resolve(".solarminer-srbminer-files.json");
            Path manifestTemp = Files.createTempFile(target, "srbminer-manifest-", ".tmp");
            try {
                mapper.writeValue(manifestTemp.toFile(), installedFiles);
                try {
                    Files.move(manifestTemp, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(manifestTemp, manifest, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(manifestTemp); }
            LOGGER.info("SRBMiner-MULTI " + tag + " installed at " + executable);
        } finally {
            Files.deleteIfExists(archive);
            try (var files = Files.walk(staging)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private void download(URI uri, Path archive, String expectedDigest, long expectedSize) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5))
                .header("User-Agent", "SolarMiner-PC-Agent").GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) { }
            throw new IOException("SRBMiner download returned HTTP " + response.statusCode());
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (InputStream input = new DigestInputStream(response.body(), sha);
             var output = Files.newOutputStream(archive)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > MAX_ARCHIVE_BYTES || count > expectedSize) throw new IOException("SRBMiner archive exceeds expected size");
                output.write(buffer, 0, read);
                progress = 5 + (int) Math.min(79, count * 79 / expectedSize);
            }
        }
        if (count != expectedSize || !HexFormat.of().formatHex(sha.digest()).equalsIgnoreCase(expectedDigest))
            throw new IOException("SRBMiner archive SHA-256 or size mismatch");
    }

    private void extract(Path archive, Path staging, boolean windows) throws IOException {
        long total = 0;
        if (windows) {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory()) total = copyEntry(zip, staging, entry.getName(), total);
                    zip.closeEntry();
                }
            }
        } else {
            try (TarArchiveInputStream tar = new TarArchiveInputStream(
                    new GZIPInputStream(Files.newInputStream(archive)))) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextEntry()) != null) {
                    if (entry.isFile()) total = copyEntry(tar, staging, entry.getName(), total);
                    else if (entry.isSymbolicLink() || entry.isLink())
                        throw new IOException("SRBMiner archive contains a link");
                }
            }
        }
    }

    private long copyEntry(InputStream input, Path staging, String name, long total) throws IOException {
        Path relative = Path.of(name.replace('\\', '/')).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || name.contains(":"))
            throw new IOException("Unsafe SRBMiner archive entry: " + name);
        Path output = staging.resolve(relative).normalize();
        if (!output.startsWith(staging)) throw new IOException("Unsafe SRBMiner archive entry");
        Files.createDirectories(output.getParent());
        try (var target = Files.newOutputStream(output)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_EXTRACTED_BYTES) throw new IOException("SRBMiner archive expands beyond limit");
                target.write(buffer, 0, read);
            }
        }
        return total;
    }
}
