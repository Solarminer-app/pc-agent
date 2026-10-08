package de.verdox.solarminer.pcagent.mining;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Adds only a known miner installation directory after interactive Windows elevation. */
@Service
public class WindowsDefenderExclusionService {
    public boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public void addMinerDirectory(String coin, Path directory) throws IOException, InterruptedException {
        if (!isWindows()) throw new IOException("Diese Funktion ist nur unter Windows verfügbar.");
        if (!("monero".equals(coin) || "pearl".equals(coin))) throw new IOException("Unbekannter Miner.");
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.toString().matches("(?i)^[a-z]:\\\\.*"))
            throw new IOException("Der Installationsordner ist kein gültiger Windows-Pfad.");

        String path = normalized.toString().replace("'", "''");
        Path script = Files.createTempFile("solarminer-defender-", ".ps1");
        Path outputFile = Files.createTempFile("solarminer-defender-", ".log");
        try {
            String body = "$ErrorActionPreference='Stop'\n"
                    + "$target='" + path + "'\n"
                    + "Add-MpPreference -ExclusionPath $target\n"
                    + "$actual=@(Get-MpPreference -ErrorAction Stop).ExclusionPath\n"
                    + "if (-not ($actual | Where-Object { $_.TrimEnd('\\') -ieq $target.TrimEnd('\\') })) { throw 'Defender hat die Ausnahme nicht bestätigt.' }\n";
            Files.writeString(script, body, StandardCharsets.UTF_8);
            String scriptPath = script.toAbsolutePath().toString().replace("'", "''");
            String command = "$ErrorActionPreference='Stop'; try { $p=Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File','\""
                    + scriptPath + "\"') -Verb RunAs -Wait -PassThru; exit $p.ExitCode } catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1223 }";
            Process launcher = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
                    .redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
            if (!launcher.waitFor(3, TimeUnit.MINUTES)) {
                launcher.destroyForcibly();
                throw new IOException("Die Windows-Freigabe hat zu lange gedauert.");
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8).strip();
            if (launcher.exitValue() != 0) {
                if (launcher.exitValue() == 1223) throw new IOException("Die Windows-Freigabe wurde abgelehnt.");
                throw new IOException(output.isBlank() ? "Defender konnte die Ausnahme nicht bestätigen." : output);
            }
        } finally {
            Files.deleteIfExists(script);
            Files.deleteIfExists(outputFile);
        }
    }
}
