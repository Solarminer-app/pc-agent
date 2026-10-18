# PeakMiner and WildRig NG on Beta

Windows operator follow-up (2026-10-18): A PeakMiner 210 W sweep step failed
with repeated `Another miner already controls NVIDIA:0` while the worker was
assigned to a different miner; assigning PeakMiner manually let a later run
start. The sweep now retains the adapter start reason, retries one managed
handoff collision, and suppresses watchdog spam for ownership conflicts.
The PeakMiner 2.19.1 API hashrate is currently treated as H/s after the
operator observed an implausible PH/s value with the former ×1,000 conversion.
The upstream README describes kH/s; capture raw Windows `/summary` and the
miner-console rate before considering the unit contract hardware verified.
See BUG-011 for the remaining reproduction.

Status: installed, selectable GPU adapters on `beta` (2026-10-18). These are
experimental mining routes. The operator can install and assign them; release
promotion still requires credited user, house and referral shares. The PC-Agent
never treats a positive local hashrate as pool credit.

| Software | Coin / algorithm | Pinned official release | Initial device scope | Miner fee |
| --- | --- | --- | --- | --- |
| PeakMiner | Pearl / PearlHash | `peakminer/peakminer` 2.19.1, Linux direct binary and Windows ZIP | NVIDIA RTX 2000+ and compatible data center GPUs, x86-64 | 2% |
| WildRig NG | Quantus / QPoW | `andru-kun/wildrig-ng` 1.0.0k Beta, Linux TAR.GZ and Windows ZIP | NVIDIA Pascal or newer, x86-64 | 2% |

WildRig Multi 0.51.5 does not list `quantus`. The operator chose WildRig NG,
whose official [algorithm matrix](https://wildrig.cc/) does list it. AMD and
Intel QTC are documented by WildRig NG but remain outside this first managed
adapter: its `--gpu-list` uses miner-local indices, and no stable mapping to the
PC-Agent's AMD/Intel physical device IDs has been verified. PeakMiner's Pearl
scope excludes NVIDIA GTX, Pascal/Volta Tesla and Quadro P models. Hardware
unknown to the closed catalog still needs a real driver/kernel test.

Both installers select only the requested software ID, require HTTPS, pin the
GitHub release asset byte count and SHA-256, extract behind the shared traversal
and link guard, install just the executable, and remove only their own binary
and version marker. Neither download overwrites SRBMiner or QuanPool.

Each selected NVIDIA GPU runs in its own supervised process. The Agent sets
`CUDA_VISIBLE_DEVICES` to the discovered driver UUID and selects miner device
`0` inside that isolated process. A local probe with two TITAN RTX cards showed
PeakMiner `/summary` PCI bus `21:00.0` and WildRig NG `/2/summary` bus ID `33`
when the UUID for NVIDIA index 1 was selected. The adapter collects per-worker
hashrate, temperature, accepted/rejected shares and pool quality; it keeps
console output and diagnoses kernel/driver/pool failures. It stops on missing
metrics, absent jobs, an unexpected pinned version or an unexpected developer
fee. A local process and API response alone do not mark the pool healthy.

PeakMiner uses the existing Pearl proxy's semicolon login: `--url` points only
to the SolarMiner Pearl listener and `--user` carries
`<upstream>;<PRL-wallet>/<worker>;x`. A captured PeakMiner `mining.authorize`
object showed this login verbatim and an empty separate worker field. The
existing proxy rewrites both fields before upstream login. Its separate-worker
shape is also accepted by the newer proxy contract test. WildRig NG uses the
PC-Agent's loopback relay and QTC `wallet.sm1.BASE64URL_POOL.worker` envelope;
the captured `login` object matches the existing Quantus proxy dialect. Neither
adapter has a direct pool fallback. Both check the proxy and loaded fee target
again at the last start path.

The efficiency sweep now iterates every installed registered GPU adapter per
selected compatible card, retaining software ID in profile/cohort keys. It
restores the saved miner and power limit after the run. Missing binaries are
skipped; route/fee failures appear as failed or unavailable measurements. The
regular sequential benchmark already iterates adapter batches. The operator's
later power sweeps are still required to compare actual efficiency.

## Verification and open gates

- Official GitHub release API returned all four pinned sizes/digests; Linux
  and Windows assets were downloaded and independently SHA-256 checked. ZIP
  contents contain the expected standalone `.exe` files.
- The actual Linux binaries supplied captured loopback API and Stratum login
  fixtures on an NVIDIA host; no valid pool job or submitted share was created
  during these connection-refusal probes.
- `sh gradlew test standaloneJar --offline` and `node scripts/check-i18n.cjs`
  passed on Java 21. The Pearl proxy's captured combined-login contract test
  passed in its repository.
- Live gates: install/start/stop/restart with a real Pearl/QTC pool and managed
  proxy, sustained positive hash, accepted and rejected share comparison,
  user/house/referral pool credit, payout/fee accounting, Windows device test,
  and a multi-GPU power sweep. WildRig NG binds its unauthenticated metrics API
  to all interfaces; host firewall exposure needs operator review.

Rollback: pause affected workers, reassign them to SRBMiner-MULTI or QuanPool
Miner, then remove only the selected alternative package. Pool and wallet
configuration remains saved. No coin, payout currency, fee-backend schema,
Currency Service collector, Node coin enum or portal contract changed; the
workspace new-coin guide's unrelated steps are not applicable to these added
miners. Its existing Pearl/Quantus accounting gates remain open.
