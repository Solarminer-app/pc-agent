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
