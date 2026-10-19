package de.verdox.gputoolkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Read-only, process-local prerequisites. Driver write access needs a separate verified probe. */
public final class GpuPrerequisiteProbe {
    public enum Vendor { NVIDIA, AMD, INTEL }
    public enum State { READY, MISSING, UNKNOWN, NOT_APPLICABLE }
    public record Check(String code, State state, boolean required, String reasonCode) { }
    public record Report(Vendor vendor, String platform, boolean container, State state, List<Check> checks) { }

    @FunctionalInterface public interface Runner { String run(List<String> command) throws Exception; }

    private final String platform;
    private final boolean container;
    private final Runner runner;
    private final Path procStatus;

    public GpuPrerequisiteProbe() {
        this(System.getProperty("os.name", ""), Files.exists(Path.of("/.dockerenv"))
                || Files.exists(Path.of("/run/.containerenv")), GpuPrerequisiteProbe::runCommand,
                Path.of("/proc/self/status"));
    }

    public GpuPrerequisiteProbe(String osName, boolean container, Runner runner, Path procStatus) {
        this.platform = osName.toLowerCase(Locale.ROOT).contains("windows") ? "windows"
                : osName.toLowerCase(Locale.ROOT).contains("linux") ? "linux" : "other";
        this.container = container;
        this.runner = runner;
        this.procStatus = procStatus;
    }

    public Report check(Vendor vendor) {
        return check(vendor, null);
    }

    /** A known NVIDIA index keeps persistence diagnostics attached to the correct card. */
    public Report check(Vendor vendor, Integer gpuIndex) {
        List<Check> checks = new ArrayList<>();
        if ("other".equals(platform)) {
            checks.add(new Check("platform", State.MISSING, true, "gpu_prereq_platform_unsupported"));
            return report(vendor, checks);
        }
        String executable = switch (vendor) {
            case NVIDIA -> System.getProperty("gputoolkit.nvidiaSmi", "nvidia-smi");
            case AMD -> "amd-smi";
            case INTEL -> "xpu-smi";
        };
        List<String> command = switch (vendor) {
            case NVIDIA -> gpuIndex == null ? List.of(executable, "--query-gpu=index", "--format=csv,noheader,nounits")
                    : List.of(executable, "--id=" + gpuIndex, "--query-gpu=index", "--format=csv,noheader,nounits");
            case AMD -> List.of(executable, "list");
            case INTEL -> List.of(executable, "discovery", "-j");
        };
        try {
            String output = runner.run(command);
            checks.add(new Check("driver_access", output.isBlank() || "[]".equals(output.strip())
                    ? State.MISSING : State.READY, true,
                    output.isBlank() || "[]".equals(output.strip()) ? "gpu_prereq_no_device" : null));
        } catch (Exception e) {
            checks.add(new Check("driver_access", State.MISSING, true, e instanceof ProbeFailure failure
                    ? failure.reasonCode : "gpu_prereq_driver_unavailable"));
        }
        checks.add(privilege());
        if (vendor == Vendor.NVIDIA && "linux".equals(platform)) {
            // NVML and nvidia-smi do not use NV-CONTROL. Coolbits applies only to X11 tuning.
            checks.add(new Check("coolbits", State.NOT_APPLICABLE, false, "gpu_prereq_coolbits_not_required"));
            try {
                List<String> persistenceQuery = new ArrayList<>();
                persistenceQuery.add(executable);
                if (gpuIndex != null) persistenceQuery.add("--id=" + gpuIndex);
                persistenceQuery.add("--query-gpu=persistence_mode");
                persistenceQuery.add("--format=csv,noheader,nounits");
                String output = runner.run(persistenceQuery);
                boolean enabled = !output.isBlank() && output.lines().allMatch(line -> line.strip().equalsIgnoreCase("Enabled"));
                checks.add(new Check("persistence", enabled ? State.READY : State.MISSING, false,
                        enabled ? null : "gpu_prereq_persistence_off"));
            } catch (Exception e) {
                checks.add(new Check("persistence", State.UNKNOWN, false, "gpu_prereq_persistence_unknown"));
            }
        } else {
            checks.add(new Check("coolbits", State.NOT_APPLICABLE, false, "gpu_prereq_coolbits_not_required"));
            checks.add(new Check("persistence", State.NOT_APPLICABLE, false, "gpu_prereq_persistence_not_required"));
        }
        if (container) checks.add(new Check("container_runtime", State.UNKNOWN, false,
                "gpu_prereq_container_runtime_unverified"));
        return report(vendor, checks);
    }

    private Check privilege() {
        if ("windows".equals(platform)) {
            try {
                String output = runner.run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)"));
                boolean admin = output.strip().equalsIgnoreCase("True");
                return new Check("privilege", admin ? State.READY : State.MISSING, true,
                        admin ? null : "gpu_prereq_windows_admin_required");
            } catch (Exception e) {
                return new Check("privilege", State.UNKNOWN, true, "gpu_prereq_windows_admin_unknown");
            }
        }
        try {
            String status = Files.readString(procStatus);
            String[] ids = status.lines().filter(line -> line.startsWith("Uid:"))
                    .findFirst().orElseThrow().substring(4).strip().split("\\s+");
            boolean root = ids.length > 1 && "0".equals(ids[1]);
            // Container root does not establish access to host driver device nodes.
            State state = !root ? State.MISSING : container ? State.UNKNOWN : State.READY;
            return new Check("privilege", state, true, !root ? "gpu_prereq_linux_privilege_required"
                    : container ? "gpu_prereq_container_privilege_unverified" : null);
        } catch (Exception e) {
            return new Check("privilege", State.UNKNOWN, true, "gpu_prereq_linux_privilege_unknown");
        }
    }

    private Report report(Vendor vendor, List<Check> checks) {
        State state = checks.stream().anyMatch(c -> c.required() && c.state() == State.MISSING) ? State.MISSING
                : checks.stream().anyMatch(c -> c.required() && c.state() == State.UNKNOWN) ? State.UNKNOWN : State.READY;
        return new Report(vendor, platform, container, state, List.copyOf(checks));
    }

    private static final class ProbeFailure extends IOException {
        private final String reasonCode;
        private ProbeFailure(String reasonCode) { this.reasonCode = reasonCode; }
    }

    private static String runCommand(List<String> command) throws IOException, InterruptedException {
        Process process;
        try { process = new ProcessBuilder(command).redirectErrorStream(true).start(); }
        catch (IOException e) { throw new ProbeFailure("gpu_prereq_tool_missing"); }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("GPU prerequisite command timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) {
            String lower = output.toLowerCase(Locale.ROOT);
            throw new ProbeFailure(lower.contains("permission") || lower.contains("access denied")
                    || lower.contains("privilege") ? "gpu_prereq_driver_permission_denied"
                    : lower.contains("no devices") || lower.contains("no device")
                    ? "gpu_prereq_no_device" : "gpu_prereq_driver_unavailable");
        }
        return output;
    }
}
