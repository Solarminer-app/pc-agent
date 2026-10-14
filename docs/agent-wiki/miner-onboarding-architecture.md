# Fast miner onboarding — HiveOS-style, SolarMiner-safe

The target is to make the common case a reviewed data package instead of a new service class,
without weakening SolarMiner's proxy, fee, process-exclusivity or installer-integrity rules.

## Proposed model

Use a versioned, signed **Miner Package Manifest** with four parts:

1. **Artifact matrix:** software ID/version, OS/architecture/vendor support, immutable URL,
   byte size, SHA-256, license/provenance and exact installed files.
2. **Command profile:** executable, fixed subcommand, typed arguments, allowed placeholders
   (`proxyUrl`, route token, device selector, metrics port) and declared capabilities. No
   arbitrary shell, templates with command substitution, or post-install scripts.
3. **Telemetry profile:** HTTP path and a small closed mapping language for hashrate units,
   shares, temperature, difficulty, latency and health. Known JSON shapes become reusable
   parser profiles (`xmrig-v1`, `srb-v3`, `hive-stats-v1`). Regex remains diagnostics-only.
4. **Certification state:** `RESEARCHED`, `INSTALLABLE`, `ASSIGNABLE`, `LIVE_VERIFIED` with
   evidence links, tested devices/pools and explicit limitations. The UI derives actions from
   this state instead of treating a catalog row as support.

A generic package installer and process supervisor would consume those manifests. A small
Java adapter is then needed only when the miner has non-declarative behavior: unstable device
mapping, a proprietary binary protocol, unusual stop semantics, or a multi-process topology.

## Workflow

1. Run an onboarding command against an official release URL. It captures headers, hash,
   `--help`, process names and a sanitized API/log fixture.
2. Generate a manifest plus contract tests from a reviewed template.
3. CI validates schema, HTTPS allowlist, hashes, extraction safety, command placeholders,
   German/English metadata, fixture parsing and GPU-exclusivity declarations.
4. Publish as `INSTALLABLE`; hardware lab evidence promotes it to `ASSIGNABLE`.
5. Real proxy fee/share/accounting evidence promotes exact device/pool combinations to
   `LIVE_VERIFIED`. Promotion is data-only and auditable; it never silently follows `latest`.

This borrows HiveOS's useful separation of miner package, flight-sheet arguments and stats
adapter, while retaining SolarMiner-specific hard gates: every route stays on the managed
proxy, every artifact is checksum-pinned, and a physical GPU cannot be claimed twice.

## Recommended implementation sequence

1. Extract `MinerPackageManifest`, `ArtifactInstaller` and certification state from the
   current XMRig/SRBMiner/QuanPool implementations.
2. Add `hive-stats-v1` and existing SRBMiner/XMRig fixture parsers to a parser registry.
3. Convert QuanPool first; its installer, command and metrics are almost entirely declarative.
4. Add a CLI generator (`miner-package scaffold`) and CI fixture runner.
5. Only then consider remotely refreshed manifests. Start with manifests bundled in the
   signed PC-Agent release; remote registries need signature rotation, revocation and rollback.
