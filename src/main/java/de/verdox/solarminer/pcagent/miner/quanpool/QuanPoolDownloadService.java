package de.verdox.solarminer.pcagent.miner.quanpool;

import de.verdox.solarminer.pcagent.miner.engine.MinerBinaryInstaller;
import de.verdox.solarminer.pcagent.support.AtomicFileStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/** Installs the exact checksum-pinned Linux x86-64 build published by quanpool.com. */
@Service
public class QuanPoolDownloadService extends MinerBinaryInstaller {
    public static final String VERSION = "6.4.0";
    static final long EXPECTED_SIZE = 15_054_128L;
    static final String SHA256 = "b3663cc6e7acc0a006e5d3d8c03dbce85e7c6a67cb922412a32703c18a5c4c20";
    static final URI DOWNLOAD = URI.create("https://download.quanpool.com/quanpool-miner-" + VERSION + "-linux-x86_64");

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final Path directory;

    public QuanPoolDownloadService(
            @org.springframework.beans.factory.annotation.Value("${solarminer.quanpool.install-directory:./solarminer-agent/quanpool}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    public Path executablePath() { return directory.resolve("quanpool-miner"); }
    private Path versionPath() { return directory.resolve(".solarminer-quanpool-version"); }
    @Override public Path installDirectory() { return directory; }
    @Override protected String installerName() { return "QuanPool Miner"; }
    @Override protected String downloadThreadName() { return "quanpool-download"; }

    @Override public boolean binaryAvailable() {
        if (!Files.isRegularFile(executablePath())) return false;
        try { return Files.readString(versionPath()).strip().equals(VERSION); }
        catch (IOException ignored) { return false; }
    }

    @Override protected void install() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux") || !(arch.equals("amd64") || arch.equals("x86_64")))
            throw new UnsupportedOperationException("QuanPool Miner 6.4.0 is available only for Linux x86-64");
        Files.createDirectories(directory);
        Path download = Files.createTempFile(directory, "quanpool-miner-", ".part");
        try {
            HttpRequest request = HttpRequest.newBuilder(DOWNLOAD).timeout(Duration.ofMinutes(3))
                    .header("User-Agent", "SolarMiner-PC-Agent").GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (InputStream ignored = response.body()) { }
                throw new IOException("QuanPool miner download returned HTTP " + response.statusCode());
            }
            try (InputStream input = response.body()) {
                downloadAndVerify(input, download, SHA256, EXPECTED_SIZE, 90,
                        "QuanPool miner download exceeds the expected size",
                        "QuanPool miner SHA-256 or size mismatch");
            }
            setProgress(97);
            installBinary(download, executablePath(), false,
                    "Could not make QuanPool miner executable", "QuanPool miner executable is empty");
            if (!AtomicFileStore.writeText(versionPath(), VERSION + System.lineSeparator(), "quanpool-version-"))
                throw new IOException("Could not write the QuanPool miner version marker");
        } finally {
            Files.deleteIfExists(download);
        }
    }

    @Override protected String removeInstalled() throws IOException {
        Files.deleteIfExists(executablePath());
        Files.deleteIfExists(versionPath());
        return "QuanPool Miner removed; the Quantus pool configuration remains saved.";
    }

    @Override protected String removeFailureDetail(IOException failure) {
        return "Could not fully remove QuanPool Miner: " + failure.getMessage();
    }
}
