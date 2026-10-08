# SolarMiner PC-Agent container for Linux

`verdox/solar-miner-pc-agent` always includes the Stratum proxy. In the local
Mining UI choose whether to use that local proxy or a reachable external
SolarMiner proxy. The choice is stored under the persistent data volume and
switching mode pauses active miners first. The local proxy binds only inside the
container, so it is not exposed to the LAN. On the first start the agent first
searches the LAN for an external proxy and falls back to the local proxy when
none responds. The prominent Proxy card on the Overview switches modes later.

The image contains no miner and does not mine automatically. Install XMRig or
SRBMiner from the local Mining UI after startup; the agent downloads only the
official release and verifies its release metadata.

The image is Linux `amd64` only. This is intentional: the current official
SRBMiner Linux package used for Pearl is x64. Start from this directory. The
default reserves 2-MiB HugeTLB pages until the next reboot; it does not change
the bootloader or kernel command line:

```sh
sudo sh ./setup-xmr-linux.sh --temporary
docker compose -f ../docker-compose.pc-agent.yml up -d
```

Use `--persistent` for a persistent 2-MiB reservation; this writes the managed
`/etc/sysctl.d/99-solarminer-xmrig.conf` setting. Add `--one-gb` to also
request 1-GiB pages at runtime. That pool is optional and may fail if the
CPU/kernel does not support it or suitable memory is unavailable. The default
target is 1280 x 2-MiB pages per NUMA node and, when requested, 3 x 1-GiB pages
per NUMA node. Both pools reserve host RAM, so enable them only when sufficient
memory is available. XMRig can use ordinary huge pages without 1-GiB pages.

The Compose service grants `IPC_LOCK` and an unlimited `memlock` limit so the
containerized miner can lock/use host memory. The pools belong to the host
kernel. After startup, enable `huge-pages` in the agent's RandomX settings;
enable `1gb-pages` only if the host was prepared with `--one-gb`. Confirm
XMRig's log reports `huge pages 100%` and successful 1-GiB allocation; host
reservation alone does not prove the miner used those pages.

The setup script also creates `/opt/solarminer-pc-agent/data`. Run the compose command from
`/opt/solarminer-pc-agent` after copying the compose files there, or replace
`./data` in the base compose file with `/opt/solarminer-pc-agent/data`.

For NVIDIA add the NVIDIA overlay after installing NVIDIA Container Toolkit:

```sh
docker compose -f docker-compose.pc-agent.yml -f docker-compose.pc-agent.nvidia.yml up -d
```

For AMD add the AMD overlay. The host needs a working AMDGPU/ROCm OpenCL stack:

```sh
docker compose -f docker-compose.pc-agent.yml -f docker-compose.pc-agent.amd.yml up -d
```

The Intel overlay only exposes `/dev/dri` to the agent. It is useful for host
hardware discovery, but must not be used to claim Pearl mining: SRBMiner's
current PearlHash support matrix lists AMD and NVIDIA, not Intel. A verified
Intel PearlHash miner and end-to-end pool test are required before enabling
that route.

The agent UI on port `8084` must not be exposed to the public internet. When
using an external proxy, enter its LAN host/IP in the UI; Docker bridge networks
do not forward LAN UDP discovery broadcasts. The retained
`docker-compose.pc-agent.standalone.yml` is a host-network compatibility
profile for existing deployments; it uses the same image and the same UI mode
selection.
