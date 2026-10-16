package de.verdox.solarminer.pcagent.standalone;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsLauncherLifecycleTest {

    @Test
    void stableAndBetaLaunchersPutTheAgentInAKillOnCloseJob() throws IOException {
        assertProcessTreeGuard("start-agent.ps1");
        assertProcessTreeGuard("start-agent-beta.ps1");
    }

    @Test
    void elevatedLaunchersDoNotRequestElevationAgainForDefender() throws IOException {
        for (String launcher : new String[]{"start-agent.ps1", "start-agent-beta.ps1"}) {
            String script = Files.readString(Path.of("standalone", launcher));
            assertTrue(script.contains("IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)"), launcher);
            assertTrue(script.contains("Add-MpPreference -ExclusionPath $installDir"), launcher);
            assertFalse(script.contains("-Verb RunAs"), launcher);
        }
    }

    @Test
    void elevatedInstallerStartsLauncherWithoutAnotherRunAs() throws IOException {
        String script = Files.readString(Path.of("standalone", "install-windows.ps1"));
        assertTrue(script.contains("IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)"));
        assertTrue(script.contains("Start-Process -FilePath $launcher\n"));
        assertTrue(script.contains("Start-Process -FilePath $launcher -Verb RunAs"));
        assertTrue(script.indexOf("IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)")
                < script.indexOf("Start-Process -FilePath $launcher\n"));
    }

    private static void assertProcessTreeGuard(String launcher) throws IOException {
        String script = Files.readString(Path.of("standalone", launcher));
        int create = script.lastIndexOf("[SolarMinerProcessJob]::CreateKillOnClose()");
        int start = script.lastIndexOf("Start-Process -FilePath $javaExe");
        int assign = script.lastIndexOf("[SolarMinerProcessJob]::Assign($jobHandle, $agentProcess.Handle)");
        int wait = script.lastIndexOf("$agentProcess.WaitForExit()");
        int close = script.lastIndexOf("[SolarMinerProcessJob]::Close($jobHandle)");

        assertTrue(script.contains("JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE"), launcher);
        assertTrue(create >= 0 && create < start, launcher);
        assertTrue(start < assign && assign < wait, launcher);
        assertTrue(wait < close, launcher);
        assertFalse(script.contains("& $javaExe -jar"), launcher);
    }
}
