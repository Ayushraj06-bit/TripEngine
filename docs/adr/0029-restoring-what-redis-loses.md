# 29. Restoring what Redis loses of a driver, when their next position finds it missing

- **Status:** Accepted
- **Date:** 2026-11-24

## Context

Redis keeps nothing on disk in production or in the demo (`docs/deploy.md`). A driver's status and
trip reach it only as events, when they change (ADRs 0006 and 0011). Restarting Redis changes
neither, and going online again publishes nothing for a driver PostgreSQL already has as available.

Measured in the demo with an empty Redis: 200 drivers reporting, none with a status, none offerable.
Of 73 bookings in four minutes, none was assigned. The state hash's own 24-hour expiry does the same
to a demo left off for a day.

## Decision

- **The position that finds no status restores it.** `location_update.lua` already reads the status
  on every accepted position, so it says whether there was one, at no extra round trip.
  `DriverStateRepair` then writes the driver's status, vehicle type and version, and their trip, from
  PostgreSQL. A sweep would cost work every few seconds for something that almost never happens.
- **The status is written last.** It is what says the state is whole, so a failure before it is
  tried again by the next position, 3 s later.
- **The row's version goes with it**, so an older change still on its way cannot undo it.
- **Two at once per instance, and the rest turned away.** After a restart every driver needs it at
  once, on the threads that take positions. Queued, they would wait on PostgreSQL together.
- **A route whose start is missing counts as no route.** A ride under way has lost its route so far,
  and is recorded again from here on. Fares reprice downwards too, so pricing it on what is left
  would underpay the driver. If nothing was recorded within `stale-after-seconds` of the start, the
  quote stands and the trip is flagged. A trip under `short-trip-flag-seconds` is still measured,
  so staying quiet is no way to charge the whole quote for one.

## Consequences

- With the same empty Redis, all 200 drivers had a status again within a minute.
- A driver whose phone is quiet for the first 30 s of a ride is charged the quote rather than the
  route, and flagged for a human.
- A route long enough to be trimmed (`route-max-points`) has lost its start too, and is treated alike.
- **Not restored:** the surge kill switch and overrides (ADR 0014), and each trip's message numbers
  (ADR 0011). The runbook says what to do about each.
