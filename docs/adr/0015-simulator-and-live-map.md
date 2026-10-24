# 15. A black-box simulator, and a live map pushed as one snapshot

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

Phase 9 needs traffic: hundreds of drivers and riders behaving like real apps,
so that matching, surge, payments and recovery can be demonstrated and, in
Phase 10, load-tested. It also needs a live map for admins.

The simulator is only worth anything if it uses the system the way real
clients do. A simulator that shares the backend's classes or database would
pass even when the public API is broken.

## Decision

### The simulator is a separate application that knows only the public API

`simulator/` is its own Spring Boot application with no dependency on
`rideflow-backend`. It speaks REST and STOMP and reads Kafka, as a mobile
app and an external consumer would. Requests are plain maps and answers plain
JSON.

**Setup is idempotent.** User *i* always has the same phone number and plate.
Registering an existing number logs in instead. A vehicle is added only if
missing, approval is asked for only if not yet given, and a wallet is topped up
only when low. A restarted simulator carries on with the same accounts.

**One actor per user.** Each simulated app has a mailbox served by a single
virtual thread. Everything an app does happens on that thread, one thing at a
time: its tick, an offer arriving, a lost connection. Its state therefore needs
no locks. One small scheduler wakes every app on its tick and fires the delays.
Blocking HTTP calls run on the apps' own virtual threads, so they cost no
platform threads.

**Behaviour is configured, not coded.**
- Every probability, delay range, speed range, hotspot and the vehicle mix
  comes from `simulator.*` and can be overridden on the command line.
- Ride requests are a Poisson process: exponential gaps at the configured
  rate.
- Movement is straight-line between random waypoints at 20 to 35 km/h,
  reported with Gaussian GPS noise.
- Four presets apply on top: `normal`, `rush-hour` (three times the demand
  around hotspots), `driver-shortage` (a quarter of the drivers) and `chaos`
  (ten times the disconnects and spoofs, more cancellations).

**The OTP takes a shortcut.** A rider reads it out to the driver. The
simulated rider puts the code from its own trip view into an in-memory map,
and its driver takes it from there. The backend still sees the real exchange:
the rider's view has the code, the driver's never does.

### The live map is a public page reading an admin-only topic

A browser opening a URL cannot send a bearer token, so a static page cannot be
what is protected.
- `/admin/live-map.html` is public and holds no data. An admin signs in on it
  through the normal login API.
- The page connects over STOMP with that token and subscribes to
  `/topic/admin/live-map`.
- `StompAuthInterceptor` now allows `/topic/admin/**` to ADMIN tokens only.
  Every other subscription rule is unchanged.
- The token stays in the page's memory, never in storage.

**One snapshot every two seconds, not every ping.** At 2,000 drivers,
forwarding pings would send a browser about 670 messages a second.
`LiveMapPublisher` instead builds one snapshot every `live-map-interval`:
- every driver seen within the stale window, with status, trip phase and where
  they are heading. All from Redis: one range read, then every state hash in
  one pipelined round trip.
- the surge heatmap the last tick wrote.
- the KPIs, from two aggregate queries. Active trips use the
  one-active-trip-per-rider index. The window uses a new index on
  `requested_at` (V14).

**Built once, delivered everywhere.** The job holds a ShedLock, so one
instance builds the snapshot. It broadcasts it through the notification
module's Redis fan-out: `RealtimePublisher.toTopic`, a delivery with no user.
Every instance hands it to the admins connected there. The lock is held at
least a second, so an instance whose schedule runs a little behind skips the
round instead of repeating it.

**The browser side has no build step.** It is one HTML file, Leaflet with
OpenStreetMap tiles (pinned, with subresource integrity), and a STOMP client
of about thirty lines over the browser's own WebSocket.

### Kafka events carry their type in a header

`DriverArrived` and `TripStarted` have the same JSON fields, so a consumer
could not tell them apart. Each trip event now carries an `eventType` header
with its simple name. The value is unchanged, so existing consumers are
unaffected.

The simulator's KPI consumer counts trips per minute and the completion rate
over five minutes, de-duplicating on `eventId`.
- A message that is not a readable trip event goes to
  `rideflow.trip-events.DLT` at once, since reading it again would not change
  it.
- Any other failure is retried twice first.

### What the simulator found in the backend

Four changes, each covered by a test that failed without it.

**Sequence numbers restart with the session.** `LocationUpdate` has always said
an app numbers its positions from zero after reconnecting. But the stored
`seq` survived the reconnect, so every position a reconnected driver sent was
dropped as out of order until it passed the old number. A driver's STOMP
CONNECT now clears the stored number, before the client can send anything.

**Per-address limits are configuration.** The login and registration limits
per address (20 a minute, 3 an hour) were hard-coded. The simulator registers
hundreds of users from one address, so they are now
`rideflow.security.*-per-address-*`. The new `sim` profile raised them and
turns the mock gateway on. It is never meant for production. (Phase 11: once
the limits moved to Redis, one huge bucket shared by every simulated user
queued past its timeout, so `sim` now turns the per-address limits off.)

**Top-ups arriving together no longer fail.** Every top-up also posts to the
platform's one clearing account. Sixty at once used up the optimistic-lock
retries on that row and answered 500. A posting now locks the accounts it
touches, in one fixed order, so postings sharing a row wait their turn (ADR
0012, amended).

**Pushes travel as text.** The notification module passed the JSON content type
as a plain map. Spring treats a plain map as a STOMP native header, so every
push went out as `application/octet-stream`, and Spring sends those as binary
WebSocket frames. A browser receives a binary frame as a Blob, so the map never
drew. Java clients decode either kind of frame, which is why nothing had shown
it before. The type is now a real message header.

### End-to-end tests treat the backend as a black box

`./mvnw -Pe2e verify` starts PostgreSQL, Redis and Kafka on a Testcontainers
network, and runs the backend jar this build packaged on a plain JRE beside
them. Building an image would add minutes and test nothing more. The tests
reach the backend only through its port, and check its database and logs from
outside.
- One ride, by hand: sign-up to ratings.
- Twenty drivers and ten riders for a minute, then four checks:
  - at least one trip completed
  - no driver ever held two trips at once, checked over every instant
  - every completed trip has a balanced ledger transaction
  - no ERROR line in the backend's log

## Consequences

- The simulator can only do what the public API allows. A missing endpoint
  shows up as something the simulator cannot simulate, which is the point.
- The live map's snapshot is built every two seconds whether or not anyone is
  watching. It costs two indexed queries and one pipelined Redis read. If it
  ever shows in a profile, the upgrade is to skip it while no admin holds a
  subscription.
- The `sim` profile is a second way to loosen production safeguards. It is
  opt-in, named for what it is, and documented as never for production.
- The E2E tests need the backend jar. Run them from the repository root, so
  the reactor packages the backend first.
