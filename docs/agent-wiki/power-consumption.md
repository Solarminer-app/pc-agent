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

## Readability and cost extension (2026-10-17)

- The local and peer `HistoryPoint` now add `estimatedCosts` (currency to amount) and `algorithms` to the existing five aggregate fields. Network merging adds costs within each currency and unions algorithm names. Older peer payloads without either field remain readable; a merged bucket with an older peer omits its incomplete cost or algorithm metadata, and the UI withholds the affected estimate/hashrate rather than treating missing values as zero.
- Estimated cost uses **each agent's current tariff** at read time, multiplied by measured component kWh. It is not a historic tariff ledger and excludes unmeasured intervals and whole-PC losses. The UI converts each currency to the selected display currency only when exchange rates are available; otherwise it displays a dash. Changing the display currency rerenders loaded history.
- `GET /api/agent/local/economics/history/workers?from=<ISO instant>&to=<ISO instant>` returns **local-only** period aggregates by device, coin and algorithm (device ID/model/type, coin, algorithm, kWh, runtime-weighted hashrate, runtime and measured seconds). The UI uses them for GPU average measured watts and hashes per watt. The external history endpoint still exposes no device IDs, models, worker rows or raw sessions.
- Historical hourly energy recorded from sensor intervals is no longer scaled down by an extra hour fraction when a session ran for only part of that hour. For partial range queries, recorded energy is apportioned by overlap with the session's actual hourly bucket. Legacy sessions still use runtime apportionment as disclosed by the UI.
- The chart labels both Y axes when hashrates belong to one known algorithm, switches its energy unit between kWh per hour/day, shows hourly timestamps for short ranges and leaves missing sensor periods as gaps. Hashrate is hidden when different or unknown algorithms would make the aggregate misleading. The daily table marks low measurement coverage; local GPU efficiency is withheld below 90% coverage.

Verification: `EnergyJournalServiceTest` covers partial-hour energy, tariff estimate, local GPU breakdown and old-peer JSON compatibility. Focused energy/network tests, JavaScript syntax, the closed German/English catalog and literal scan passed. A rendered browser and live multi-agent check remain open.
