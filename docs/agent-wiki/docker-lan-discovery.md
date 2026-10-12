# Docker networking and LAN discovery

Reviewed on 2026-10-11 against the PC-Agent compose profiles, Dockerfile and
`AgentNetworkDiscoveryService`. This is a deployment constraint, not evidence
that peer discovery has been exercised between two physical hosts.

## Host-network ports

The supported Compose profile (`docker-compose.pc-agent.yml`) uses
`network_mode: host`. It therefore has no `ports:` mappings: the Agent binds
these host ports directly, and the trusted-LAN firewall must allow them:

| Port | Protocol | Purpose | Required from LAN |
| --- | --- | --- | --- |
| 8084 | TCP | Agent UI, Node identity/control contract, peer snapshot and gated worker start/pause | Yes |
| 8092 | UDP | Receive an external Stratum-proxy heartbeat | Yes, for proxy auto-discovery |
| 8093 | UDP | PC-Agent peer-dashboard heartbeat and query/reply protocol | Yes, for peer auto-discovery |

`8091/udp` is deliberately not published by the Agent: it belongs to the
proxy's legacy query listener. The bundled proxy is loopback-only inside the
container, so its API (`8090/tcp`) and Stratum listeners (`3334` onward) must
not be published by the PC-Agent profile. They are not part of the Agent's LAN
or peer-dashboard contract.

The AMD, Intel and NVIDIA compose overlays add hardware access only and retain
the base profile's host networking. `docker-compose.pc-agent.standalone.yml`
is a legacy filename alias with the same host-network behaviour.

## Discovery scope

`AgentNetworkDiscoveryService` sends `SOLARMINER_AGENT_DISCOVER_V1` heartbeats
and probes to each broadcast address visible **inside its own network
namespace** on UDP 8093. With host networking, those packets use the host's
physical interfaces rather than a Docker bridge namespace. The same applies to
proxy discovery on UDP 8091/8092.

Broadcast discovery works only within one Layer-2 broadcast domain. It does
not cross a router into another IP subnet or VLAN. A direct, known external
proxy address remains usable because the Agent initiates an ordinary outbound
unicast connection to that proxy; ensure the proxy's API and required Stratum
listeners are reachable. There is currently no configured-peer fallback for
the peer dashboard itself.

## Deployment choices

For Linux installations, allow TCP 8084 plus UDP 8092 and UDP 8093 on the
host's trusted-LAN firewall and verify the ports are unused before start.
`network_mode: host` is not a portable remedy for Docker Desktop/VM-based
Docker; test the actual host-to-LAN path there.

Docker bridge mode is unsupported for the PC-Agent. A routed-subnet deployment
needs an explicit product change, such as a host/LAN discovery relay or
authenticated configured peers; port mappings cannot bridge the broadcast
boundary. Any such change needs a two-host physical-LAN verification including
snapshot access on TCP 8084 and firewall rules.
