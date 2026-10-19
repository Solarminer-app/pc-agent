# GPU Toolkit adapters

The `de.verdox.gputoolkit.GPUInterface` contract has two NVIDIA backends:
`NvidiaNVMLAdapter` uses the native NVML library, while `NvidiaSmiAdapter` uses
the NVIDIA driver's `nvidia-smi` command line program. The latter can be
constructed directly, or pointed at a non-PATH executable with the
`gputoolkit.nvidiaSmi` system property (or its explicit-path constructor).
Each command is passed as a separate process argument; no shell is involved.

The `nvidia-smi` backend reads name, driver version, temperature, fan speed,
GPU utilization, power, memory use and clocks. It can change/reset power limits
and lock/reset core and memory clocks when the installed utility exposes those
options. It does not expose clock-offset ranges or fan control through this
adapter; those methods report unsupported rather than claiming capability.
Driver/permission/device errors from the external command are included in
exceptions. External `nvidia-smi` output is retained verbatim because it is
produced by the installed NVIDIA driver utility; there is no user-facing UI
copy in this API package to localize.

For NVIDIA memory underclocking, the native adapter first queries the device-wide
NVML VF memory offset and its driver bounds (`nvmlDeviceGetMemClkVfOffset`,
`nvmlDeviceGetMemClkMinMaxVfOffset`). It writes with
`nvmlDeviceSetMemClkVfOffset`; the toolkit represents this scope with P-state
`-1`. This keeps one offset across P-state transitions during compute mining.
If the installed driver does not expose that interface, the Agent falls back
to `nvmlDeviceGet/SetClockOffsets` for the current P-state. Each write is read
back; calibration also checks the effective memory clock under load. Original
offset and scope are stored in the crash-recovery marker. Auto Tuning explores
at most 1500 MHz below and 100 MHz above the initial offset, clipped to the
driver bounds. This changes clock frequency, not memory voltage directly.

Official alternatives evaluated: NVIDIA's `nvidia-smi -lmc` sets an absolute
memory clock, but command success did not change the observed 6800 MHz clock
on the operator's Windows RTX 2080 Ti. NVIDIA's Linux `nvidia-settings`
offers `GPUMemoryTransferRateOffsetAllPerformanceLevels` for Pascal and newer
GeForce when an X server and Coolbits bit 8 are configured. It is a possible
fallback for a device where NVML offsets are unsupported, but the Agent does
not download or configure an X server automatically. NVML already ships with
the installed NVIDIA driver and needs no extra downloaded executable.
Sources: [NVML offset API](https://docs.nvidia.com/deploy/nvml-api/latest/api/group__nvmlDeviceQueries.html),
[NVML offset write API](https://docs.nvidia.com/deploy/nvml-api/latest/api/group__nvmlDeviceCommands.html),
[NVIDIA Linux Coolbits](https://download.nvidia.com/XFree86/Linux-x86_64/530.30.02/README/xconfigoptions.html),
[nvidia-settings attributes](https://github.com/NVIDIA/nvidia-settings/blob/main/src/parse.c).

Deutsch: Für NVIDIA wird zuerst der geräteweite NVML-Speicher-Offset mit
Treibergrenzen, Rücklesen und Wiederherstellung verwendet. Falls diese API
fehlt, bleibt der Offset des aktiven P-State als Rückfall. Die Kalibrierung
prüft bis zu 1500 MHz unter dem ursprünglichen Offset und höchstens 100 MHz
darüber, stets innerhalb der Treibergrenzen. Der Speichertakt wird damit
gesenkt; eine direkte Absenkung der Speicherspannung ist nicht nachgewiesen.
Unter Linux ist `nvidia-settings` mit X-Server und Coolbits 8 eine mögliche
Alternative, wird vom Agenten aber nicht automatisch installiert oder
konfiguriert. Ein wirksamer Taktwechsel muss unter Mining-Last gemessen werden.

## Optional Linux X11 fallback packaging / Optionaler Linux-X11-Rückfall

The Agent can ship a Coolbits 8 Xorg example and an opt-in host installer that
inspects existing X configuration, writes a separate file with backup, and
requires an X-server restart. The NVIDIA X server must read that host file;
placing it only in the PC-Agent container has no effect on the host X server.
A headless host can use NVIDIA Xorg with `AllowEmptyInitialConfiguration` when
supported. Wayland or Xwayland alone does not establish NVIDIA X11 NV-CONTROL.

For Docker, an optional Compose overlay would additionally need
`NVIDIA_DRIVER_CAPABILITIES=compute,utility,graphics,display`, the host X11
socket, a scoped Xauthority credential and `DISPLAY`, plus `nvidia-settings`
in the image. It must map X GPU targets to NVIDIA GPU UUIDs and verify both
offset readback and the effective clock. The current NVIDIA overlay exposes
only `compute,utility` for the X-free NVML path. Mounting host `/etc/X11` into
the container does not configure the host X server.

Deutsch: Ein Coolbits-8-Beispiel und ein optionaler Host-Installer können
mitgeliefert werden. Die Einstellung muss der NVIDIA-X-Server auf dem Host
lesen; eine Datei nur im Container ändert ihn nicht. Docker bräuchte für den
optionalen X11-Pfad zusätzlich Display-Treiberfunktionen, den X11-Socket,
eine gezielte Xauthority-Berechtigung, `DISPLAY` und `nvidia-settings`.
Der bestehende NVML-Pfad benötigt all dies nicht.

References / Quellen: [NVIDIA Coolbits X option](https://download.nvidia.com/XFree86/Linux-x86_64/530.30.02/README/xconfigoptions.html),
[NVIDIA headless X option](https://download.nvidia.com/XFree86/Linux-x86_64/580.82.09/README/xconfigoptions.html),
[NVIDIA Container Toolkit driver capabilities](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/docker-specialized.html),
[NVIDIA nvidia-settings target attributes](https://github.com/NVIDIA/nvidia-settings/blob/main/src/parse.c).

Both adapters share the Java API but have different requirements: NVML needs a
loadable driver library, while the CLI adapter needs a compatible executable
and process permissions. PC-Agent Auto Tuning uses the NVML adapter for
Windows NVIDIA core targets and Windows/Linux memory-offset calibration when
per-device NVML offset controls are available; the CLI adapter remains
available as an API option and is not selected for that offset path. Hardware-specific command support
and writes still depend on the GPU, driver, operating system and process
privileges.

## English summary

`NvidiaSmiAdapter` is the command-line implementation of `GPUInterface`, beside
the native NVML implementation. It preserves NVIDIA utility diagnostics,
supports the telemetry and controls exposed by `nvidia-smi`, and reports
offset tuning and fan control as unsupported. PC-Agent selects the NVML adapter
for Windows/Linux NVIDIA offset calibration when NVML reports a valid per-device
range; it does not select the CLI adapter for that path.

## Driver prerequisites and local dashboard

`GpuPrerequisiteProbe.check(Vendor)` is a read-only toolkit utility for NVIDIA,
AMD and Intel on Linux/Windows. It returns named checks with `READY`, `MISSING`,
`UNKNOWN` or `NOT_APPLICABLE`, required/optional flags and stable reason codes.
It checks the vendor CLI, process privilege and (on Linux NVIDIA) Persistence
Mode. The overload with an NVIDIA index associates Persistence Mode with the
correct GPU. The probe never changes driver state and does not expose raw command
output to the UI. Other operating systems receive an unsupported-platform
reason. In Docker, root inside the container alone is `UNKNOWN` until a driver
write is verified.

`GpuPrerequisiteService` joins this report to the existing cached power snapshot.
`LocalGpuPowerService` already checks power control with an unchanged-value write
and readback. That successful test settles process privilege and driver access
for power control, including a container. The service also reports the existing
asynchronous core/memory clock capability probes as optional features. It does
not claim clock write permission merely because ranges are readable. A scheduled
refresh owns driver processes; the local `GET /api/agent/local/gpu-prerequisites`
endpoint returns only its cached per-GPU rows. The dashboard displays localized
reasons, and an empty inventory tells the operator to check drivers or Docker
device mapping. GPU absence alone cannot identify which vendor driver failed.

Coolbits is `NOT_APPLICABLE` for the current NVML/nvidia-smi path: it enables
features through NVIDIA's X11 NV-CONTROL extension, not NVML. Persistence Mode
is reported as an optional Linux NVIDIA recommendation, not a write-permission
gate. Windows administrator status comes from the current process token. The
Docker runtime still needs the relevant GPU device and vendor utility/library;
for NVIDIA, `compute,utility` exposes CUDA plus NVML/nvidia-smi. A live driver
write, clock application and readback on each target OS/vendor remains required
before claiming full clock-control operation.

References: [NVIDIA X config Coolbits](https://download.nvidia.com/XFree86/Linux-x86_64/550.54.14/README/xconfigoptions.html),
[NVIDIA SMI power/persistence](https://docs.nvidia.com/deploy/nvidia-smi/index.html),
[NVIDIA Container Toolkit driver capabilities](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/docker-specialized.html),
[NVML privileged commands](https://docs.nvidia.com/deploy/nvml-api/latest/api/group__nvmlDeviceCommands.html).
