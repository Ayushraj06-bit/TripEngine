# Interview notes

The 25 questions an interviewer is most likely to ask about RideFlow, answered from the code as it
is. Paths are relative to the repository root; numbers come from [performance.md](performance.md).

## Design choices

### 1. Why a modular monolith and not microservices?

- **What it buys:** one deployable, one database, local transactions - and still boundaries.
  Eleven modules (`com.rideflow.<module>`) may call each other only through a module's top-level
  package, and react to each other through events.
- **What enforces it:** `ModularityTests` runs `ApplicationModules.verify()` on every build, so a
  boundary violation fails CI rather than surviving review.
- **What it costs:** every module scales together, and one bad deploy takes all of them down.
- **The way out:** a module can still leave when it has to - `location`, with its 670 writes a
  second, is the first candidate, and its events and public API are already the seam.
- **More:** [ADR 0001](adr/0001-modular-monolith.md),
  `backend/src/test/java/com/rideflow/ModularityTests.java`.

### 2. How do you guarantee a driver is never assigned two trips?

Three layers, the last one absolute:
- **Redis lock:** a driver holds one live offer at a time - `driver:lock:{driverId}`. In
  `backend/src/main/resources/redis/offer_create.lua`, one script checks who holds it and takes it
  only if no live offer does.
- **Atomic accept:** accepting is a compare-and-set on the offer's status in `offer_accept.lua`,
  so an expired or withdrawn offer cannot be accepted.
- **The database:** partial unique indexes make a second active trip per driver - and per rider -
  impossible: `trips_one_active_per_driver` in
  `backend/src/main/resources/db/migration/V7__trips.sql`.

The proof:
- `MatchingIntegrationTest.aHundredRidersAndTenDriversMakeExactlyTenTrips`
- after every load run, an SQL check found 0 overlapping assignments across 8,536 trips

### 3. Walk me through a booking.

1. **Idempotency:** `BookingService` claims the `Idempotency-Key` first
   (`trip/internal/IdempotencyStore.java`). A repeat with the same body gets the stored response,
   and one with a different body gets 422.
2. **The quote:** consumed by an atomic compare-and-delete Lua script (`quote_consume.lua`), so one
   quote books one trip.
3. **One transaction:** the trip row, its first audit row and a `TripRequested` event, through
   `TripService`.
4. **After commit:** the matching module's listener starts a search (`Matcher.start`), and the
   first offer reaches the nearest free driver over STOMP - p95 17.8 ms after the booking.
5. **The answer:** the HTTP request has already returned 202, at p95 16.6 ms.

### 4. Why is matching non-blocking, and what happens if an instance dies mid-match?

- **No thread waits for a driver's answer.** The search state, the offer, the driver's lock and the
  deadlines all live in Redis (`matching/internal/OfferStore.java`).
- **Every instance polls:** every 500 ms each instance reads the due offers and searches
  (`OfferPoller`), claiming each with an atomic `ZREM`, so exactly one instance handles it.
- **The recovery sweep:** every 15 s it finds trips still `REQUESTED` in PostgreSQL with no match in
  Redis, and restarts them (`RecoverySweeper`).
- **A crash loses nothing:** it costs at most a sweep interval.
- **Proof:** `FullStackChaos.assignsATripBookedBeforeTheBackendRestarted` kills the backend
  mid-matching and the trip is still assigned. See [ADR 0010](adr/0010-non-blocking-matching.md).

### 5. Why keep live positions in Redis GEO rather than PostGIS?

- **The rate:** 2,000 drivers report every 3 s, about 670 writes a second.
- **The data:** positions are minutes old by nature and worthless after a crash, so they need speed
  and no durability.
- **One write:** a Lua script validates each update and writes the state hash, the GEO set and
  `drivers:lastseen` in one round trip (`backend/src/main/resources/redis/location_update.lua`).
- **Measured:** ingest p95 2.8 ms at 2,000 drivers, nearby search p95 2.5 ms.
- **What stays in PostgreSQL:** routes and trips, which must survive.
- **More:** [ADR 0006](adr/0006-redis-geo-for-live-positions.md).

### 6. Why validate a position inside Lua?

- **What is checked:** sequence order, throttle, implied speed and minimum movement. All of them
  read the driver's previous position.
- **Why in Java would break it:** done in Java it is read, then write. Two updates from one driver -
  a reconnecting app, a duplicate client - could both pass the sequence check before either
  writes.
- **Why in Lua:** the check and the write are one operation, so the outcome counted on
  `location_updates_total` is the outcome that happened.
- **What stays in Java:** stateless checks - range, accuracy, service area - which are unit tested
  without Redis.

### 7. How does a message reach a user connected to another instance?

- **The fan-out:** every message is published on the Redis Pub/Sub channel `rt:events`. Every
  instance hears it and delivers it only to sessions connected to itself
  (`notification/internal/RedisRealtimePublisher.java`, `LocalDelivery.java`).
- **What it rules out:** sticky sessions and an external broker. Any number of instances work behind
  plain round robin.
- **Ordering:** a per-trip sequence number (`trip:seq:{tripId}`) lets a client spot a gap.
- **Resync:** WebSocket is best effort, and every client re-reads `GET /api/v1/trips/active` after
  reconnecting.
- **More:** [ADR 0011](adr/0011-realtime-fan-out-over-redis-pub-sub.md).

### 8. Why not Kafka for that fan-out?

- **Wrong delivery shape:** Kafka's consumer groups give each message to one consumer, and here
  every instance must see every message. Fanning out would take a consumer group per instance,
  created and cleaned up as instances come and go.
- **Wrong cost:** Pub/Sub is sub-millisecond, already there, and losing a message costs only a
  resync, which clients do anyway.
- **Where Kafka is used:** for what needs durability and ordering - domain events to outside
  consumers.

## Money, events and correctness

### 9. How does the ledger stay correct?

- **Double entry:** every money movement is a transaction whose entries balance - debits equal
  credits - posted through one class (`payment/internal/Ledger.java`).
- **The database enforces it**, not the code (`V11__ledger.sql`):
  - a deferred constraint trigger refuses an unbalanced transaction at commit
  - triggers refuse any change to an entry
  - a correction is a new, reversing transaction
- **Postings that share an account:** every top-up touches the platform's clearing account. Its row
  is locked in one fixed order (`Ledger.LOCK_ORDER`), so postings queue rather than deadlock.
- **Every night:** a reconciliation checks that cached balances equal their entries; the alert
  `LedgerOutOfBalance` fires on any mismatch.
- **Measured:** after the load runs, 25,176 entries summed to exactly 0 paise.

### 10. How did you test the ledger?

- **The property test** (`LedgerInvariantsTest`): 40 seeds, each driving a random sequence of 30
  operations - top-ups (some failing), wallet and cash trips, fees, refunds (some refused),
  redelivered charges - through the real services and database. After each sequence, every
  transaction balances and all balances sum to zero.
- **Why not jqwik:** it does not run on JUnit 6 yet ([ADR 0003](adr/0003-test-stack-on-junit-6-jqwik-deferred.md)),
  so this is a seeded parameterized test. A failure names its seed and replays exactly, but nothing
  shrinks it to a minimal case.
- **Concurrency:** `LedgerIntegrityTest` posts charges into one account at once.

### 11. How do you make every write idempotent?

- **Bookings and top-ups:** an `Idempotency-Key` per user, claimed before the work, with the
  response stored for 24 hours.
- **Ledger postings:** unique by type and reference. A redelivered charge inserts nothing
  (`ON CONFLICT DO NOTHING`).
- **Webhooks:** keyed by the gateway's reference. Three deliveries of one payment credit once
  (`PaymentIntegrationTest.creditsOnceWhenThreeDeliveriesArriveAtOnce`).
- **Listeners:** safe to repeat, because delivery is at least once.
  - **Matching:** ignores a `TripRequested` delivered twice.
  - **Surge:** counts each trip's demand once, with a `SET NX` per trip.
  - **Pushes:** carry sequence numbers.

### 12. How do events reach Kafka without ever being lost?

- **The outbox:** each event is stored in Spring Modulith's publication registry
  (`event_publication`, V2) in the same transaction as the state change.
- **After commit:** the listeners run asynchronously. One of them copies trip and payment events to
  Kafka, keyed by trip id, so one trip's events stay in order
  (`trip/internal/TripEventExternalization.java`).
- **When a listener or Kafka fails:** the publication stays incomplete, and `EventRedelivery`
  submits it again every minute once it is two minutes old.
- **Delivery is at least once:** consumers de-duplicate on `eventId` ([events.md](events.md)).
- **Proof:** Kafka stopped for 60 s while trips complete: 76 of 76 events reached the topic.

### 13. How does surge pricing avoid flapping?

- **The tick:** every 30 seconds, under a cluster-wide lock (ShedLock), a job counts each geohash-6
  cell's requests in the last 5 minutes against its available drivers.
- **The target:** a step table maps the ratio to a target multiplier (1.0 / 1.2 / 1.5 / 2.0, capped
  at 2.5).
- **The smoothing:** the price moves at most 0.25 a tick towards the target, so it never jumps.
- **Fail-safe:** a multiplier the job stops refreshing expires in 90 s and fares fall back to 1.0.
- **Admin control:** a kill switch and per-cell overrides win over the computed value.
- **More:** `pricing/internal/SurgeJob.java`, `SurgeCalculator.java`,
  [ADR 0014](adr/0014-surge-engine.md).

## Security and failure

### 14. How are users authenticated, including on WebSocket?

- **Access tokens:** JWTs signed with RS256, valid 15 minutes, verified by Spring's resource
  server. "Who am I" always comes from the token's subject, never the request body.
- **Refresh tokens:** opaque, stored hashed, in families. Reusing a spent one revokes the family.
- **STOMP:** clients authenticate in their CONNECT frame, may send only to their role's
  destinations, and may subscribe only to their own `/user/queue`. The session closes when its
  token expires.
- **Another user's trip:** answers 404, which does not reveal that it exists.
- **Proof:** `SecurityMatrixTest` checks every endpoint against every kind of caller.
- **More:** [ADR 0017](adr/0017-authentication.md).

### 15. How do you stop password guessing?

- **Lockout:** ten wrong passwords lock the account for 15 minutes. Failures are counted under a
  row lock, so ten sent at once count as ten
  (`AuthIntegrationTest.locksAfterTenFailuresEvenWhenTheyArriveTogether`).
- **Rate limits:** per phone and per address, Bucket4j buckets in Redis shared by every instance.
- **Timing:** an unknown phone costs the same BCrypt work as a known one, so response time does not
  reveal which numbers have accounts.

### 16. What happens when Redis goes down?

- **It fails fast:** commands give up after 1 s.
- **Location ingest:** a circuit breaker drops positions at once rather than queueing them, since
  the next arrives in 3 s anyway.
- **Readiness:** goes DOWN, so the load balancer stops sending traffic.
- **Requests that need Redis:** answer 503 with `Retry-After`. Liveness stays UP, so nothing
  restarts.
- **Recovery:** when Redis returns, everything resumes with no intervention.
- **Proof:** `FullStackChaos.failsFastWhileRedisIsPausedAndRecoversOnItsOwn` - threads stayed flat
  at 101 during the pause.

### 17. And when PostgreSQL is slow?

- **Bounded waits:**
  - a connection from the pool within 3 s
  - a statement within 10 s
  - a row lock within 2 s, with `statement_timeout` and `lock_timeout` set by PostgreSQL itself on
    every connection
- **An unreachable or slow database:** 503 `SERVICE_UNAVAILABLE`.
- **Contention** - a lock timeout, a deadlock, a version conflict: 409 `CONCURRENT_UPDATE` with
  `Retry-After: 1`, because the retry usually goes through.
- **Never:** a 500 for either.

## Performance and scale

### 18. What did load testing find?

Four things, each fixed and measured ([performance.md](performance.md)), and a fifth that CI found:
1. **The test itself:** it started drivers kilometres from where the last run had left them, and the
   spoof check rightly refused the jumps.
2. **A flag storm:** a spoofer was flagged again on every position past the threshold - 111,318 log
   lines in ten minutes.
3. **BCrypt in a transaction:** logins hashed while holding a database connection. At 200 logins a
   second they starved the pool. Moving the hash out cut a canary request's p95 from 1.02 s to
   182 ms.
4. **A bigger pool was slower:** 30 connections lost to 10 - more transactions contending inside
   one PostgreSQL.
5. **A lost update, found by CI:** a driver was occasionally never freed after a cancellation.
   `DriverService.followTrips` read the trips, then set the status; handled at once, the assigned
   and cancelled events interleaved and ON_TRIP committed last. It now locks the driver's row
   first, and `DriverOnboardingIntegrationTest` holds that row from another connection to prove
   the wait. I first blamed virtual-thread pinning and moved the listeners to platform threads;
   the flake came back until the real cause was fixed.

### 19. How did you measure latency?

- **The server's own histograms,** read from Prometheus as percentiles over each run's window.
- **Why not k6's numbers:** they include Docker's port forwarding on the laptop, which is not the
  service.
- **The setup:** k6, the backend and every store shared one laptop, so the numbers are a floor for
  that machine, not a server's ceiling.
- **Every run is recorded:** with its hardware, command and raw output. That includes the runs a
  Windows standby spoiled, which are kept and marked discarded rather than quietly re-run.

### 20. How would you scale to ten times the traffic?

20,000 drivers, about 6,700 position updates a second:
- **Backends:** more instances. They are stateless; real-time fan-out and every lock already work
  across instances, and the autoscaler in `infra/k8s/` adds them on CPU.
- **Redis:** handles that write rate on one node. Ingest used 4% CPU at 670 a second.
- **PostgreSQL:** watch the connection budget, which grows with instances times pool size. It may
  need PgBouncer.
- **Kafka:** more partitions for `rideflow.trip-events`; the key stays the trip id.

### 21. And a hundred times?

At 67,000 updates a second, one Redis node and one Pub/Sub channel become the limits:
- **Partition by city or region:** a Redis per region for positions and matching. This also
  shortens searches.
- **Extract `location`:** into its own service, along the module boundary that already exists.
- **Fan-out:** replace the single broadcast channel with a channel per instance, routed by where a
  user is connected.
- **The database:** partition `trips` by region, and give reads replicas.

The modules and the events between them are what make each step a cut along an existing line
rather than a rewrite.

## Practice and reflection

### 22. How do you test time and concurrency without flakiness?

- **Time:** business code never reads the system clock; it takes the injected `Clock`. Tests move a
  `MutableClock`, so expiry, lockouts and surge ticks are exact.
- **No sleeps:** there is no `Thread.sleep`. Async results are awaited with Awaitility.
- **Races:** threads are started together with an `ExecutorService` and a `CountDownLatch`.
  Examples:
  - ten concurrent bookings, one quote
  - an accept racing the rider's cancel
  - ten simultaneous wrong passwords
- **Real infrastructure:** integration tests run on real PostgreSQL, Redis and Kafka through
  Testcontainers - no H2, no embedded Redis.

### 23. How do you keep secrets and personal data safe?

- **Secrets:** never in the repository.
  - **Production:** files mounted as a config tree.
  - **The demo:** a container generates them on first start.
  - **Development:** without keys, an instance generates ephemeral ones and warns.
- **Logs:** phone numbers are masked to the last four digits, and tokens, passwords and OTPs never
  appear. `LogMaskingIntegrationTest` runs a whole trip and searches the log for each.
- **Redis:** rate-limit keys are hashed, so phone numbers are not readable there either.
- **More:** [security.md](security.md).

### 24. How would you debug a slow booking in production?

1. **Grafana:** booking p95 on the service dashboard; the `BookingLatencyHigh` alert fires at 300 ms
   for 5 minutes.
2. **The cause, roughly:**
   - **The database:** HikariCP's acquire time.
   - **Redis:** command latency.
   - **Listeners behind:** the event publication backlog.
3. **One trace in Jaeger:** a booking is one trace from the HTTP request through the matching
   listener to the offer's push and the Kafka send, so the slow step shows directly.
4. **The runbook:** [runbook.md](runbook.md) has a section for exactly this.

### 25. What would you do differently?

- **Rate limits in Redis from the first day:** they started in memory per instance, which quietly
  doubled every limit once two instances ran.
- **Load testing earlier:** the BCrypt-in-a-transaction pool starvation was in the code from Phase 1
  and surfaced only in Phase 10.
- **Lock every read-then-write on the first day:** `followTrips` was designed to converge however
  events arrived, but two at once still lost an update. A CI flake found it, and I misdiagnosed it
  once before a test held the row and proved it.
- **Contract tests on the events:** consumers outside this repository rely on their shape;
  `docs/events.md` documents it, but no test pins it.
- **Known limits:**
  - the payment gateway is a mock
  - routes are straight lines times a road factor
  - one city
  - the first offer misses its target when demand outruns the cars nearby - which is supply, and
    the surge engine's job, not the matcher's
