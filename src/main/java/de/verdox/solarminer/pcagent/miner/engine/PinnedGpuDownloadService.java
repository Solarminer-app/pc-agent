package de.verdox.solarminer.pcagent.miner.engine;

import de.verdox.solarminer.pcagent.support.AtomicFileStore;

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

/** A package-local, checksum-pinned installer for an official GPU miner release. */
public abstract class PinnedGpuDownloadService extends MinerBinaryInstaller {
    public record Asset(URI url, long size, String sha256, boolean archive, boolean zip) { }

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final Path directory;
    private final String name;
    private final String version;
    private final String executable;
    private final Asset linux;
    private final Asset windows;

    protected PinnedGpuDownloadService(String directory, String name, String version,
                                      String executable, Asset linux, Asset windows) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.name = name;
        this.version = version;
        this.executable = executable;
        this.linux = linux;
        this.windows = windows;
    }

    public String version() { return version; }
    public Path executablePath() { return directory.resolve(executable + (isWindows() ? ".exe" : "")); }
    private Path versionPath() { return directory.resolve(".solarminer-version"); }
    @Override public Path installDirectory() { return directory; }
    @Override protected String installerName() { return name; }
    @Override protected String downloadThreadName() { return executable + "-download"; }

    @Override public boolean binaryAvailable() {
        if (!Files.isRegularFile(executablePath())) return false;
        try { return version.equals(Files.readString(versionPath()).strip()); }
        catch (IOException ignored) { return false; }
    }

    @Override protected void install() throws Exception {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!(arch.equals("amd64") || arch.equals("x86_64")) || !(isWindows() || isLinux()))
            throw new UnsupportedOperationException(name + " requires Linux or Windows x86-64");
        Asset asset = isWindows() ? windows : linux;
        if (!"https".equals(asset.url().getScheme())) throw new IOException(name + " release URL is not HTTPS");
        Files.createDirectories(directory);
        Path downloaded = Files.createTempFile(directory, executable + "-", ".part");
        Path staging = Files.createTempDirectory(directory, executable + "-unpack-");
        try {
            HttpRequest request = HttpRequest.newBuilder(asset.url()).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "SolarMiner-PC-Agent").GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (InputStream ignored = response.body()) { }
                throw new IOException(name + " download returned HTTP " + response.statusCode());
            }
            try (InputStream input = response.body()) {
                downloadAndVerify(input, downloaded, asset.sha256(), asset.size(), 85,
                        name + " download exceeds the expected size", name + " SHA-256 or size mismatch");
            }
            Path source = downloaded;
            if (asset.archive()) {
                extractArchive(downloaded, staging, asset.zip(), name);
                try (var entries = Files.walk(staging)) {
                    String fileName = executable + (isWindows() ? ".exe" : "");
                    source = entries.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().equalsIgnoreCase(fileName))
                            .findFirst().orElseThrow(() -> new IOException(name + " archive has no executable"));
                }
            }
            setProgress(97);
            installBinary(source, executablePath(), isWindows(),
                    "Could not make " + name + " executable", name + " executable is empty");
            if (!AtomicFileStore.writeText(versionPath(), version + System.lineSeparator(), executable + "-version-"))
                throw new IOException("Could not write " + name + " version marker");
        } finally {
            Files.deleteIfExists(downloaded);
            deleteTree(staging);
        }
    }

    @Override protected String removeInstalled() throws IOException {
        Files.deleteIfExists(executablePath());
        Files.deleteIfExists(versionPath());
        return name + " removed; pool and wallet configuration remains saved.";
    }

    @Override protected String removeFailureDetail(IOException failure) {
        return "Could not fully remove " + name + ": " + failure.getMessage();
    }

    public static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
    public static boolean isLinux() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"); }
    public static boolean supportedPlatform() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return (isLinux() || isWindows()) && (arch.equals("amd64") || arch.equals("x86_64"));
    }
}
