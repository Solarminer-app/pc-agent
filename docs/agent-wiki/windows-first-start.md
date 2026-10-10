# Windows first-start analysis (2026-10-09)

## Failure surfaces and ownership

| Surface | What the Agent can establish | Action and remaining limit |
| --- | --- | --- |
| Downloaded JAR and private JRE | Launcher verifies SHA-256 before replacing either asset | A matching hash establishes transfer integrity, not antivirus approval or publisher trust. A quarantine can still occur after extraction or later execution. |
| XMRig and SRBMiner extraction | Installers classify Windows Defender/PUA file-access failures as `BLOCKED_BY_ANTIVIRUS` | Existing local Mining UI offers a coin-specific UAC exclusion after a detected block. Its retry remains explicit. |
| Startup Defender state | `Get-MpPreference` reports exact path exclusions; `Add-MpPreference -ExclusionPath` adds one after a UAC prompt | The launcher checks the PC-Agent install folder on every start and offers the exclusion whenever it is missing, then reads back the setting. Enterprise policy or another antivirus can still prevent or supersede it. A folder exclusion reduces scanning of every file placed there. |
| GPU power caps | Launcher requires an Administrator token and checks NVIDIA CLI presence | Without the token, the launcher stops before any download or Agent start. The running Agent must still perform `LocalGpuPowerService`'s same-value write and readback for every device. A readable watt limit is not evidence of write permission. AMD Windows needs an ADLX-backed helper; it is not advertised as dynamically controllable. |
| CPU and GPU sensors | The Agent's LibreHardwareMonitor helper has a separate opt-in UAC flow | Sensor elevation is not a mining prerequisite. The monitor disables storage/SMART discovery to avoid unrelated disk prompts. |
| LAN control | Native launcher binds HTTP port 8084 to all interfaces for Node discovery | Windows Private-profile firewall permission must be reviewed on the host. The external-control gate remains separate from local browser access. |
| Docker on Windows | Container cannot administer host Defender or UAC | Use the native launcher for host security setup; container starts must keep host configuration outside the container. |

Microsoft's [Defender exclusion guidance](https://learn.microsoft.com/en-us/defender-endpoint/configure-contextual-file-folder-exclusions-microsoft-defender-antivirus) says exclusions reduce protection and recommends using them sparingly. Its [PowerShell documentation](https://learn.microsoft.com/powershell/module/defender/add-mppreference) describes `Add-MpPreference -ExclusionPath` and its effect on scheduled and real-time scanning. The launcher therefore checks the state at every start, explains the tradeoff and asks explicitly whenever the exclusion is missing. It uses an in-memory encoded command for UAC (no writable temporary script) and verifies the resulting preference. The marker records the most recent completed check; it does not suppress later checks. No silent AV bypass is attempted.

Startup-check output is English-only and is deliberately not inferred from the
operating-system locale. Windows may still return localized system exception
messages, which the launcher presents unchanged as external operating-system
text.

The first-run status is diagnostic, not a hardware certification. A Windows acceptance pass still needs: Defender enabled and disabled-policy hosts; approval and rejection of UAC; fresh and existing install directories; native NVIDIA same-value and changed-value writes with readback; optional sensor UAC; and an actual XMRig/SRBMiner install and start. Those checks cannot be completed on this Linux workspace. The Windows RTX 2080 Ti RVN/ETC epoch failure remains separately tracked in [the integration record](rvn-etc-integration.md).
