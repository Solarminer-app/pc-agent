package de.verdox.gputoolkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GpuPrerequisiteProbeTest {
    @TempDir Path temp;

    @Test void linuxNvidiaSeparatesRequiredAccessFromOptionalPersistence() throws Exception {
        Path status = temp.resolve("status");
        Files.writeString(status, "Uid:\t0\t0\t0\t0\n");
        GpuPrerequisiteProbe probe = new GpuPrerequisiteProbe("Linux", false, command ->
                command.stream().anyMatch(arg -> arg.contains("persistence_mode")) ? "Disabled" : "0", status);

        GpuPrerequisiteProbe.Report report = probe.check(GpuPrerequisiteProbe.Vendor.NVIDIA);

        assertEquals(GpuPrerequisiteProbe.State.READY, report.state());
        assertEquals(GpuPrerequisiteProbe.State.MISSING, check(report, "persistence").state());
        assertFalse(check(report, "persistence").required());
        assertEquals(GpuPrerequisiteProbe.State.NOT_APPLICABLE, check(report, "coolbits").state());
    }

    @Test void containerRootRemainsUnverifiedWithoutDriverWrite() throws Exception {
        Path status = temp.resolve("status");
        Files.writeString(status, "Uid:\t0\t0\t0\t0\n");
        GpuPrerequisiteProbe probe = new GpuPrerequisiteProbe("Linux", true, command -> "0", status);

        assertEquals(GpuPrerequisiteProbe.State.UNKNOWN, probe.check(GpuPrerequisiteProbe.Vendor.NVIDIA).state());
    }

    @Test void windowsNonAdminHasTargetedReason() {
        GpuPrerequisiteProbe probe = new GpuPrerequisiteProbe("Windows 11", false, command ->
                command.getFirst().equals("powershell.exe") ? "False" : "0", temp.resolve("unused"));

        GpuPrerequisiteProbe.Report report = probe.check(GpuPrerequisiteProbe.Vendor.NVIDIA);

        assertEquals(GpuPrerequisiteProbe.State.MISSING, report.state());
        assertEquals("gpu_prereq_windows_admin_required", check(report, "privilege").reasonCode());
    }

    @Test void unavailableDriverHasNoRawExternalError() {
        GpuPrerequisiteProbe probe = new GpuPrerequisiteProbe("Windows", false, command -> {
            if (command.getFirst().equals("powershell.exe")) return "True";
            throw new IllegalStateException("secret driver output");
        }, temp.resolve("unused"));

        assertEquals("gpu_prereq_driver_unavailable",
                check(probe.check(GpuPrerequisiteProbe.Vendor.AMD), "driver_access").reasonCode());
    }

    private static GpuPrerequisiteProbe.Check check(GpuPrerequisiteProbe.Report report, String code) {
        return report.checks().stream().filter(check -> check.code().equals(code)).findFirst().orElseThrow();
    }
}
