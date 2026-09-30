# 6. Redis GEO for live positions, one Lua script per update

- **Status:** Accepted
- **Date:** 2026-09-29

## Context

PROJECT_SPEC section 2 sets two numbers that decide this: 2,000 simulated online
drivers at roughly 670 location updates per second, with nearby-driver search at
p95 under 10 ms and location ingest at p95 under 20 ms.

Positions are also the least durable data in the system. A driver's position from
thirty seconds ago is worthless — the stale-driver sweep exists precisely to throw
it away. Nothing is reconciled against it afterwards, and losing the lot means
drivers reappear on their next ping a few seconds later.

Two options were real.

**PostgreSQL with PostGIS.** One datastore, real transactions, and a position
could be written in the same transaction as a trip state change. But it means
roughly 670 writes a second, each one a row update with an index to maintain, on
the same database that also has to serve the trip lifecycle, the ledger and every
booking. Postgres would do it, at the cost of continuous WAL churn and vacuum
pressure to store data we intend to discard, and it would put that load directly
behind the booking path where `POST /trips` has a 150 ms p95 budget.

**Redis GEO.** A sorted set with geohash scores, `GEOSEARCH` for radius queries,
in memory. No durability worth the name, which for this data is the point.

## Decision

Live positions live in Redis: `driver:state:{driverId}`,
`drivers:available:{type}` and `drivers:lastseen`, as PROJECT_SPEC section 7 lays
out. Postgres holds nothing about where a driver is right now.

Each accepted update is **one Lua script**, and that script performs the stateful
validation as well as the writes.

The single round trip is the part section 8 asks for, and it matters at 670
updates a second: the state hash, the GEO set and `drivers:lastseen` all have to
move together, and three round trips would be three chances to be interrupted
between them, leaving a driver in the availability set with a stale position.

Putting the validation inside it goes further than the spec asks, and is the more
important half. Four checks — sequence number, throttle interval, implied speed,
minimum move — all read the previous position that the same call is about to
overwrite. Done in Java that is a read, then a separate write, and two updates
from one driver (a reconnecting app, a duplicate client, a retry) can both pass
the sequence check before either writes. Inside the script the check and the write
are one operation, so the outcome the script reports is the outcome that occurred
— which is what makes `location_updates_total` a number worth alerting on.

Stateless checks — coordinate range, finiteness, accuracy, service area — stay in
Java, where they need no round trip and are unit testable without Redis.

Scripts are called by SHA via `EVALSHA`, with Spring's `DefaultRedisScript`
falling back to `EVAL` on `NOSCRIPT`. That fallback is not a nicety: Redis loses
its script cache on restart and on `SCRIPT FLUSH`, and without it every location
update would fail until the application was redeployed.

## Consequences

- The booking path never contends with position writes. That is the whole point.
- Losing Redis loses the live map. Drivers repopulate it within one ping cycle of
  a few seconds, and the stale sweep would have discarded that data shortly anyway.
  Nothing durable depends on it: `drivers.status` in Postgres remains the record of
  whether a driver intends to work.
- A driver's position and their trip state cannot change in one transaction. They
  do not need to — a position is an observation, not a decision.
- The nearby search returns straight-line distances. Road distance is longer, and
  matching ranks on this while pricing applies `road-factor` separately.
- Logic now lives in Lua, which the JVM cannot type-check and a debugger cannot
  step through. It is tested against a real Redis container rather than a fake, and
  the haversine in the script deliberately mirrors `GeoPoint.distanceMetersTo` so
  the two cannot disagree about whether a driver moved.
- Redis is single-threaded, so every script is a small amount of time nobody else
  gets. Both scripts are bounded: the update touches a handful of keys, and the
  stale sweep is explicitly batched rather than unbounded, because one
  `ZRANGEBYSCORE` over a post-outage backlog would stall the whole cluster while it
  ran.

## Addendum: two measurement traps, found by tests

**Redis measures with a different earth radius.** Redis GEO computes distances
with 6,372,797.56 m (`geohash_helper.c`); `GeoPoint` and `location_update.lua`
use the IUGG mean of 6,371,008.8 m. A driver `GeoPoint` places exactly 2.0 km
away is about 2,000.56 m to Redis, and falls outside a 2 km `GEOSEARCH`. Half a
metre is irrelevant to dispatch, so the two are not aligned - but it means
"exactly on the radius" is not a meaningful input. Positions are also stored as
52-bit geohashes, which moves them by up to about half a metre. The rule as
tested: the radius is inclusive, to within a couple of metres.

**`Metrics.NEUTRAL` is not "no conversion".** It divides by the earth's radius,
so `AvailableDriver.distanceMeters` was briefly a value close to radians
labelled metres. Ranking was still correct - the conversion is monotonic - which
is exactly why nothing but a test asserting the number itself found it. Matching
would have compared it against `radius-steps-km`.
