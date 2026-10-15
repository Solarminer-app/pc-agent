# Quantus / QuanPool miner integration

Status: **installer and adapter implemented; selectable on the Beta branch for live
certification** (2026-10-14).

## Reviewed release and contract

- Product: `quanpool-miner` 6.4.0, closed optimized build published by QuanPool.
- Official page and operating guide: <https://quanpool.com/>.
- Linux x86-64 artifact: `https://download.quanpool.com/quanpool-miner-6.4.0-linux-x86_64`.
- Observed `Content-Length`: `15054128`; published and independently verified SHA-256:
  `b3663cc6e7acc0a006e5d3d8c03dbce85e7c6a67cb922412a32703c18a5c4c20`.
- The 6.4.0 binary reports a 2% developer fee at startup. The adapter also checks the
  runtime `/hive-stats` fee and refuses an unexpected value so economic planning cannot
  silently use stale catalog metadata.
- Device scope: native CUDA on Linux/NVIDIA. The CLI offers only `--gpu-devices N` (the
  first N cards), not a stable UUID/PCI selector. SolarMiner solves that mismatch by running
  one supervised process per selected card and setting `CUDA_VISIBLE_DEVICES` to the stable
  UUID reported by `nvidia-smi`; inside that isolated process the miner safely uses its single
  visible GPU as device 0. Any detected NVIDIA index can therefore be assigned, and multiple
  assigned cards retain independent start, pause, metrics, console and restart state.
- Protocol: `stratum+tcp://` and `stratum+ssl://` select the Quantus Stratum dialect.
  The managed path uses the loopback `GpuStratumRelay`, which supplies the authenticated
  SolarMiner route preamble before forwarding exclusively to the configured proxy.
- Metrics: unauthenticated `/hive-stats` JSON on the configured metrics port. Parsed fields
  are `hs` + `hs_units`, `temp`, `ar`, `active_jobs`, `share_difficulty`,
  `best_share_difficulty`, `pool_rtt_ms`/`share_roundtrip_ms`, `fee_percent`, `ver` and
  `transport`. The upstream binary binds this endpoint to `0.0.0.0`; the response contains
  operational metrics but no configured payout token in the observed fixture. This exposure
  is an upstream limitation and remains part of live host review.

## QuanPool pool and payout API

- The pool catalog contains the official mainnet Stratum targets published by
  `GET https://quanpool.com/api/chains`: `global.quanpool.com:9854`,
  `us.quanpool.com:9854` and `asia.quanpool.com:9854`. They are labelled with the
  current 1% pool fee reported by `GET /api/terms`.
- The website reads a wallet without login through
  `GET https://quanpool.com/api/miner/mainnet/{qz-address}`. `QuanPoolBalanceProvider`
  recognizes only Quantus routes on `quanpool.com` or its subdomains and maps
  `balance.pending` plus `balance.paid_total` from planck (`10^12` planck = 1 QTC)
  into the generic wallet snapshot. Errors remain `UNAVAILABLE`, never a synthetic zero;
  an explicit valid `seen=false` response is the known zero-account case.
- The same response contains a wallet-specific `ledger`. Its `payout_debit` rows expose
  amount, payout id, timestamps and `payout_status` (`confirmed`, `failed`, or in flight).
  The current PC-Agent wallet strip deliberately exposes only pending and cumulative paid
  totals; a chronological history needs a separate bounded/paginated UI contract.
- This is a public website API used by QuanPool's own frontend, but no separately versioned
  API stability document was found. Parser fixtures therefore fail closed when the schema or
  integer planck units change.

## Gross earnings estimate

`MarketDataService` receives QTC network inputs through the Currency Service and applies
the same gross network-share model used for the other coins. As of 2026-10-14, the producer
pairs QTCScan's 24-hour estimated network hashrate with the observed average block interval
from the same 24-hour window. The estimate excludes pool/miner fees unless those are separately
known, and it is not a promise of pool credit; payout variance and uptime can move actual
rewards away from the projection. The producer correction affects this Agent through the
existing HTTP snapshot contract; no QTC miner or pool behavior changed.

The website still mentions a `gpu-list` command, but the shipped 6.4.0 CLI does not expose
that subcommand. The adapter is pinned to the binary's observed `serve --help` contract,
not that stale instruction.

## Implemented seams

- `miner/quanpool/QuanPoolDownloadService`: independent direct-binary installer, exact size
  and SHA-256 verification, version marker, atomic placement and isolated removal.
- `miner/quanpool/QuanPoolMinerService`: Quantus-only lifecycle, safe stop, bounded restart,
  process ownership, proxy/fee readiness, default-payout refresh, console redaction and
  per-worker telemetry. Each selected NVIDIA worker is isolated by driver UUID in its own
  miner process, removing any dependency on global CUDA enumeration order. QuanPool owns a
  separate `solarminer-agent/quanpool/solarminer-quantus.json`; SRBMiner retains its own
  Quantus file, so mixed assignments cannot overwrite each other's GPU set.
- `miner/quanpool/QuanPoolStats`: closed parser for the HiveOS JSON shape. A captured
  disconnected response and a populated GPU fixture pin units and missing-value semantics.
- `MinerProcessRegistry`: recognizes external and managed QuanPool processes and includes
  them in physical-GPU exclusion.
- `MinerCatalogService`: independent `quanpool-miner` package with software-ID-scoped install/remove, current version, fee,
  provenance, platform and limitation metadata. It is directly installable and selectable on
  the current `beta` branch; no additional runtime channel flag duplicates the branch rollout.
- Frontend: the Software page can install the package; Worker assignment exposes it as
  an experimental option. All SolarMiner-authored status and failure text is present in
  the closed German/English catalog. Verbatim external miner output remains untranslated.
- Assignment is persisted per physical worker as coin plus software ID. Selecting QuanPool
  for one Quantus GPU therefore does not select it for another Quantus GPU: QuanPool and
  SRBMiner-MULTI can run simultaneously on different cards. Start, pause, Node dispatch and
  telemetry resolve the adapter from that worker profile. Profiles written by older builds
  have no software ID and intentionally fall back to the former coin-wide selection.
- Pool/wallet frontend: the QuanPool global, US and Asia destinations are offered for QTC;
  pending balance and cumulative confirmed payouts are displayed through the generic wallet
  snapshot without counting historical payouts as current holdings.

## Verification and open release gates

Completed without mining hardware:

- Download headers, byte size and SHA-256 checked against the live official artifact.
- The real binary's root/`serve` help, non-TTY logs and `/hive-stats` response were captured.
- A loopback connection-refusal run proved stable reconnect output and metrics availability.
- Unit tests cover metrics parsing, units, shares, pool quality, command construction,
  route-token encoding, arbitrary/multiple NVIDIA selections, UUID visibility isolation and
  pinned release metadata.

Still required before promotion to the release path and claiming certified support:

1. Linux x86-64 host with supported NVIDIA GPU: install, start, positive hash rate, graceful
   stop, crash restart and no stale process; confirm the reported worker maps to the selected
   driver UUID. On a multi-GPU host, start two non-zero indices independently and pause one
   without interrupting the other.
2. Direct diagnostic pool run and managed SolarMiner-proxy run: subscribe/login, job,
   difficulty, submit, reconnect and fee switch for the Quantus Stratum dialect.
3. Compare `/hive-stats` accepted/rejected values to the miner console and pool account.
4. Prove credited user, SolarMiner house and referral shares plus pool credit/payout.
5. Review the all-interface metrics listener on a real PC firewall/LAN and record exposure.
6. Verify removal preserves the Quantus pool/wallet configuration.

Rollback is package-local: pause the Quantus worker, uninstall `quanpool-miner`, and keep or
reselect the existing SRBMiner-MULTI option. No SRBMiner files are overwritten or removed.

## Workspace new-coin guide applicability

This change adds a second miner implementation and a read-only QuanPool account provider to
the existing Quantus/QPoW coin; it does not add a coin, algorithm, wire identifier or currency
collector. Therefore the Currency Service, fee-backend schema, Stratum-proxy coin registry, Node coin
enum, admin/referrer portal and documentation-site coin catalog are unchanged. Their existing
Quantus rollout gates are not waived. The PC-Agent miner integration guide applies in full;
the Beta assignment exists specifically to collect the still-missing real share/accounting evidence.
