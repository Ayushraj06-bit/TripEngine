# 10. Matching: non-blocking offers in Redis, the database as the final guard

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

Matching offers a trip to one driver at a time and waits up to
`offer-timeout-seconds` (12 s) for a human to answer, for up to
`deadline-seconds` (60 s) in all. Bookings arrive in bursts, several backend
instances run at once, and any of them can die at any moment.

The obvious design is a thread - or a task with a timer - per trip, which sends
an offer and waits for the answer. That design breaks on every one of those
conditions:

- **It blocks.** A thousand trips being matched is a thousand waiting threads.
- **It forgets.** The state lives in one instance's memory. When that instance
  dies, every trip it was matching is silently abandoned.
- **It needs affinity.** The driver's answer arrives at whichever instance their
  request reaches, which must then find the instance holding the waiting thread.

## Decision

**No thread ever waits for a driver.** Matching is a set of short steps, each
triggered by something happening: a trip requested, an offer accepted, rejected
or expired, a search falling due. Each step does a little work against Redis
and returns. Any instance can run any step, because nothing lives in an
instance's memory.

**The waiting happens in Redis**, as times in sorted sets: `offers:expiry` holds
each offer's expiry, `match:retry` each trip's next search. A poller on every
instance reads what is due every 500 ms, by the injected clock, and claims each
item with `ZREM`. Exactly one instance gets 1 back and processes it, so the
instances share the work without a lock. (`match:retry` is not in PROJECT_SPEC
section 7's list. It is the same shape as `offers:expiry`, for the same reason,
and cleaned the same way.)

**Every decision that two instances could race on is one Lua script:**

- Start matching only if it is not already running, so duplicate events - normal
  with at-least-once delivery - start it once.
- A trip has at most one offer out. A new offer is written only if the trip's
  current offer is still the one being replaced, so two instances advancing the
  same trip cannot both send one.
- An offer leaves PENDING exactly once. Accept, reject, expire, withdraw and a
  driver going stale all compete for that change; whoever makes it owns the next
  step, and the others see it already gone. This is what settles accept against
  timeout, without a lock.
- A driver's lock is `SET NX PX` and released by compare-and-delete, so a
  release never removes a lock that has since been taken for another offer.

**PostgreSQL is the final guard and the source of truth.** An accepted offer
still has to pass `assignDriver`: the trip's version and the
one-active-trip-per-driver index decide. Redis can lose data or be raced in ways
nobody foresaw, and the worst outcome is a wasted offer - never two trips for one
driver, or a trip assigned twice.

**A recovery sweep repairs from the database.** Every 15 s, under ShedLock, it
finds trips REQUESTED for more than 10 s whose matching is not in hand - no
state, an offer long past its expiry, a search long overdue - and restarts them.
A crash between any two steps, or Redis losing everything, costs a trip at most
one sweep interval plus the grace period.

### Smaller decisions

- **A lock counts only while its offer is PENDING or ACCEPTED.** One that guards
  a missing or closed offer was left by a crash and is taken over at once rather
  than waited out: nothing would ever release it, and its TTL runs on Redis's
  own clock.
- **After acceptance the driver's lock stays until it expires.** The driver
  leaves the map through two asynchronous events (assigned, then ON_TRIP); the
  lock keeps other trips off them in that moment.
- **Accept never expires an offer itself.** Past its expiry it answers 409 and
  leaves the offer to the poller, which releases the driver and moves the trip on.
- **Trip details come from the database when matching starts**, not from the
  event. A late duplicate event finds the trip no longer waiting and does nothing.
  The deadline runs from the trip's latest entry into REQUESTED, read from the
  audit log, so a trip handed back by its driver gets a full search.
- **Listeners run without a surrounding transaction.** A database connection held
  open across Redis calls would starve the pool when a hundred trips start at
  once, and section 11 keeps Redis out of transactions anyway.
- **Offers reach drivers best effort**, over the notification module's Redis
  Pub/Sub fan-out. A driver who missed the push reads `GET /api/v1/offers/current`.

## Consequences

- Throughput is bounded by Redis round trips and short database queries, not by
  threads. A step takes milliseconds.
- Matching is eventually consistent. After a crash a trip can sit unmatched for
  up to about 25 seconds before the sweep restarts it.
- An offer can be lost - pushed while the driver was disconnected, or deleted
  with Redis's data - and the trip simply moves on when it expires. No outcome
  ever leaves a driver with two trips.
- The logic is spread across five Lua scripts and the Java that sequences them.
  Each race the brief names is a test against real Redis and PostgreSQL: accept
  against timeout 200 times, accept against cancel 100 times, and a hundred
  riders booking at once against ten drivers.
