# 14. Surge pricing per geohash cell, recomputed every tick

- **Status:** Accepted
- **Date:** 2026-10-10

## Context

When requests outrun available drivers in an area, prices rise to draw drivers
there and ease demand. PROJECT_SPEC section 8 asks for surge "every 30 s, demand
vs supply per geohash cell (including neighbouring cells), smoothed and capped,
stored with a short TTL", and the Phase 8 brief fills in the rules. The engine
must:
- stay cheap with thousands of drivers moving
- never surprise a rider who already has a quote
- fail towards 1.0, not towards a high price

## Decision

**Cells.** A pickup's cell is its geohash at `geohash-precision` (6: about
1.2 km by 0.6 km in Bengaluru). The geohash code is ours, pure and tested
against the published reference values. It is about sixty lines, so it needs no
dependency.

**Demand.**
- Each ride request counts once in `surge:demand:{cell}:{epochMinute}` (10-minute
  TTL). A per-request marker makes a redelivered `TripRequested` count nothing.
- `trip` reports requests through `pricing`'s `SurgeDemand`. `trip` already
  depends on `pricing`, so `pricing` listening to trip events would close a
  module cycle.
- A cell's demand is the requests over the last `window-minutes`, across the
  cell **and its eight neighbours**. A rider just across a cell border is
  therefore priced much like one just inside it.
- Cells with a request in the window are kept in `surge:demand:cells`, scored by
  the minute of their latest request. The tick trims it and reads it. It never
  scans the keyspace.

**Supply.** Available drivers of every vehicle type inside the same three-by-three
block, counted with `GEOSEARCH BYBOX` from the cell centre.
- `pricing` declares the `AvailableDriverCounts` port and `location`, which owns
  the GEO sets, implements it. Again, this avoids a module cycle.
- The counting runs in a Lua script, in batches of 200 boxes, so Redis is never
  held long and no driver ids cross the wire.

**Which cells are computed.**
- Every cell whose block holds a request: the busy cells and their neighbours.
- Every cell still above 1.0, so that, with its demand gone, it steps back down
  the way it went up instead of dropping when its key lapses.
- Nothing else. A cell nobody asked about is 1.0 by default.

**The multiplier.** `SurgeCalculator` is pure and table-driven:
- The target comes from `steps` by the ratio demand ÷ max(supply, 1), with each
  step inclusive at its upper bound. Above the last step, the cap.
- The value moves towards the target by at most `max-step-per-tick`, up or down.
- It is always within 1.0 and the cap.
- The smoothed value is stored exactly (1.25, 1.5, 1.75 ...) and rounded to 0.1,
  half up, only when a fare is priced. Rounding at every tick would turn steps
  of 0.25 into an uneven 0.3, 0.3, 0.2.

**The tick.** `SurgeJob` runs every `tick-seconds` under the `surge-tick`
ShedLock lock.
- It writes `surge:{cell}` with a `ttl-seconds` TTL. If the job stops, every key
  lapses and fares fall back to 1.0 on their own. A cell back at 1.0 has its key
  deleted, since 1.0 is the default.
- The lock is held for at least `lock-at-least-for` (20 s). A second instance
  whose schedule runs a few seconds behind therefore cannot take a second step in
  the same tick.
- It writes a snapshot of every priced cell (multiplier, demand, supply) to
  `surge:cells` for the admin heatmap. The snapshot is built under a temporary
  name and renamed into place, so a reader never sees half of one.

**What a quote pays.** `RedisSurgeProvider` applies the first of these that
applies:
1. the kill switch is on: 1.0 everywhere
2. an admin override for the cell, until its expiry by our clock
3. the computed value
4. 1.0

It caches per cell for `cache-seconds` (5) with Caffeine, so a quote's three
vehicle types and every rider in the cell share one Redis read. An admin change
reaches the instance that made it at once, and the others within 5 seconds.

The cache measures age with a monotonic ticker, not the wall clock. If the
clock were stepped back an hour, cached values would otherwise live an hour
longer, and a kill switch thrown on another instance would not arrive for as
long. Age is elapsed time, not business time, so the injected `Clock` is not
the right source. Tests supply a ticker driven by their controllable clock. If Redis fails, `QuoteService` prices
at 1.0. Quotes already lock their price (Phase 3), so surge moving after a quote
never changes the fare booked with it.

**Admin levers.**
- An override is between 1.0 and the cap, ends in the future and within
  `override-max-hours`, and lives in `surge:override:{cell}` with a matching TTL.
  Overrides are listed from the `surge:overrides` index, trimmed as it is read.
- The kill switch is `surge:disabled`, the one surge key with **no TTL**: a
  switch that expired would turn surge back on by itself.
- Every change is logged with the admin who made it.

**Metrics.**
- `surge_active_cells`: cells above 1.0 after the last tick.
- `surge_multiplier`: the distribution of every priced cell's multiplier, per
  tick.
- `surge_tick`: how long a tick took. A warning is logged past half a tick.

## Consequences

- One multiplier per cell for every vehicle type, as the spec's single
  `surge:{cell}` key implies. Surge per vehicle type would need a key per type
  and supply counted per set.
- Two keys without TTL join the GEO sets and `drivers:lastseen`: the kill switch,
  on purpose, and the two indexes, which the tick and the admin view trim
  instead.
- Measured on a laptop, against Testcontainers Redis, one tick prices 300 busy
  cells - 1,112 cells with their neighbours - against 2,000 drivers in about
  80 ms (`docs/performance.md`). The ceiling is far off. The upgrade, if a city
  ever needs it, is to count supply per cell once and sum blocks in Java.
