# Power Consumption history

The `/power.html` detail page shows mining hashrate and measured energy for a selected date range (up to 366 days). One or two calendar days use hourly chart buckets; longer ranges use daily chart buckets. The table always contains daily aggregates. Hashrate is weighted by mining runtime, and measurement coverage is shown so missing sensor readings are not presented as zero consumption.

New energy journal sessions persist hourly watt-hour buckets from the actual sensor sample intervals (normally five seconds; integration stops when samples are more than 30 seconds apart). Sessions written before this schema addition have only a total; their energy is uniformly apportioned over their runtime in the timeline and the UI discloses this limitation. Missing sensor intervals remain unmeasured and do not add energy.

## API and LAN boundary

- Local dashboard: `GET /api/agent/local/economics/history?from=<ISO instant>&to=<ISO instant>`.
- Peer contract: `GET /api/agent/external/energy-history?from=<ISO instant>&to=<ISO instant>`.
- The peer route is allowed only while the existing `networkSharingActive()` consent is enabled. It stays independent of `externalControlEnabled`, matching the read-only network snapshot.
- The external response contains only hourly/daily buckets: timestamp, kWh, weighted average hashrate, runtime seconds and measured seconds. It does not expose device IDs, worker names, raw sessions, accepted/rejected shares or pool configuration.
- The local detail endpoint queries currently discovered peers concurrently and adds their buckets to this agent's buckets. A peer that is offline or has sharing disabled contributes no rows. The UI states this limitation and does not estimate missing power.
- Energy is the journal's measured worker-board/package energy while mining. It is not whole-PC wall power. Daily boundaries use each agent's system time zone; typical same-LAN deployments use the same zone.

The journal is retained as monthly JSONL files (the existing reader scans up to 13 months). Requests are bounded to 366 days. This endpoint is on demand and does not change the existing compact network snapshot payload.
