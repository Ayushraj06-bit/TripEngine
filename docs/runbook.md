# Runbook

Operational notes for running RideFlow locally and diagnosing it when it
misbehaves.

## Prerequisites

| Tool | Version | Check |
|---|---|---|
| JDK | 21 (LTS) | `java -version` |
| Maven | 3.9+ (or use `./mvnw`) | `./mvnw -v` |
| Docker | with Compose v2 | `docker compose version` |

Docker is not optional: the integration tests use Testcontainers, and
PROJECT_SPEC section 12 rules out H2 and embedded Redis.

## Start the stack

```bash
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml ps     # all services healthy?
```

| Service | URL | Credentials |
|---|---|---|
| PostgreSQL | `localhost:5432/rideflow` | `rideflow` / `rideflow` |
| Redis | `localhost:6379` | none |
| Kafka | `localhost:9092` | none |
| Prometheus | http://localhost:9090 | none |
| Grafana | http://localhost:3000 | `admin` / `admin` |
| Jaeger | http://localhost:16686 | none |

Then run the backend:

```bash
./mvnw -pl backend spring-boot:run
```

## Build and test

```bash
./mvnw verify          # unit + integration + module verification + coverage gate
./mvnw spotless:apply  # fix formatting before committing
```

`verify` starts containers through Testcontainers. The first run pulls images
and is slow; later runs reuse them.

## Run the simulator

Full instructions: [`simulator/README.md`](../simulator/README.md). In short:

```bash
export RIDEFLOW_ADMIN_PHONE=+919999900001 RIDEFLOW_ADMIN_PASSWORD='choose-one'
SPRING_PROFILES_INCLUDE=sim ./mvnw -pl backend spring-boot:run   # terminal 1
./mvnw -pl simulator spring-boot:run                            # terminal 2
```

Watch it on http://localhost:8080/admin/live-map.html, signed in as that admin.
The simulator's metrics are on http://localhost:8081/actuator/prometheus, and
Prometheus scrapes them as `rideflow-simulator`.

## End-to-end tests

```bash
./mvnw -Pe2e verify
```

They run from the repository root, so the backend jar is packaged before the
simulator's tests use it. A run starts its own PostgreSQL, Redis and Kafka.
Nothing local needs to be running, only Docker.

## Two-instance setup: the one-command demo

Used to exercise the cross-instance real-time path, to load-test, and to show the system off:

```bash
docker compose -f infra/docker-compose.yml -f infra/docker-compose.scale.yml up
```

The first run builds both images (`infra/Dockerfile`), which takes a few minutes. Give Docker about
8 GB of memory: two backends, the simulator, Kafka, PostgreSQL, Redis, Prometheus, Grafana and
Jaeger. When everything is healthy, `demo-info` prints the URLs and the admin's login for the live
map ([ADR 0018](adr/0018-packaging-and-delivery.md)).

Both instances must share two secrets, or what one signs the other refuses:

- the RSA key pair for access tokens
- the gateway webhook secret ([ADR 0013](adr/0013-mock-payment-gateway-and-webhooks.md))

The `secrets` service generates them on first start, with the admin's password, into the `secrets`
volume. The applications read them as a Spring config tree (`SPRING_CONFIG_IMPORT`).
- **Your own values:** environment variables (`RIDEFLOW_RSA_PRIVATE_KEY`, ...) take precedence over
  the files.
- **Start from nothing:** `down -v` discards the secrets with the data.

The backends run the `sim` profile: the simulator signs everyone up and in from one address, and
tops wallets up through the mock gateway. Never in production (`docs/deploy.md`).

Behind nginx, a client's address for rate limiting comes from `X-Forwarded-For`.
Tomcat believes that header only from a trusted proxy:
`server.tomcat.remoteip.internal-proxies`, by default private and loopback
addresses, which covers nginx on the compose network. Behind a load balancer
with a public address, add its address there, or every client shares one limit.
nginx also sends `X-Forwarded-Port`: without it, Tomcat takes the port to be 80, and the live map's
WebSocket, whose origin says 8080, is refused as another site's.

nginx listens on 8080 and round-robins between the instances, which are also
reachable directly on 8091 and 8092. Round robin is deliberate: real-time
delivery goes through Redis Pub/Sub, so no sticky sessions are needed
([ADR 0011](adr/0011-realtime-fan-out-over-redis-pub-sub.md)).

### Proving the fan-out

1. Watch the channel: `docker exec -it rideflow-redis redis-cli SUBSCRIBE rt:events`.
2. Connect a rider's STOMP session directly to instance 1
   (`ws://localhost:8091/ws`) and subscribe to `/user/queue/trip-updates`.
3. Book and progress a trip through instance 2 (`http://localhost:8092`).
4. Each change appears once on `rt:events` and reaches the rider on instance 1.
   Instance 2 drops it, because the rider has no session there.

If a client receives messages only when it happens to land on one instance, the
fan-out is broken. `PUBSUB NUMSUB rt:events` in redis-cli should count one subscriber per instance.

### Proving the scheduled jobs run once

The stale-driver sweep, the matching recovery sweep and the silent-driver check
each run on every instance, but under ShedLock only one instance holds each
lock at a time: `SELECT name, locked_by, lock_until FROM shedlock;` shows the
holder. Stop that instance (`docker compose ... stop backend-1`), and the other
takes the lock once `lock_until` passes.

## Health and diagnostics

Actuator lives on the management port, 8090, which nothing outside the internal network may
reach (Phase 10, ADR 0016). The public port, 8080, answers only the two probes.

| What | Where |
|---|---|
| Liveness, readiness (public) | `GET :8080/livez`, `GET :8080/readyz` |
| Health with details | `GET :8090/actuator/health` |
| Metrics for Prometheus | `GET :8090/actuator/prometheus` |
| Module structure | `GET :8090/actuator/modulith` |
| Dashboards | Grafana, http://localhost:3000: *RideFlow - service* and *RideFlow - runtime* |
| Alerts | Prometheus, http://localhost:9090/alerts (rules in `infra/prometheus/alerts.yml`) |
| Traces | Jaeger, http://localhost:16686: one booking is one trace (`docs/images/booking-trace.png`) |
| API docs (not in prod) | http://localhost:8080/swagger-ui.html |

Readiness goes DOWN while PostgreSQL or Redis does not answer: every call to them gives up in a
few seconds (Redis 1 s, a database connection 3 s, a statement 10 s). Liveness does not depend on
them, so an orchestrator stops sending traffic instead of restarting a healthy process.

## Common problems

### `verify` fails with "Could not find a valid Docker environment"

Docker Desktop is not running, or the current shell cannot reach the daemon.
Start Docker Desktop and re-run. Testcontainers needs a live daemon; there is no
fallback.

### Flyway fails with a checksum mismatch

A committed migration was edited, which PROJECT_SPEC section 7 forbids. Restore
the original file and add a new migration instead. For a local database only,
`docker compose down -v` wipes the volume and replays from scratch.

### Events stop arriving

Check `event_publication` for rows with a null `completion_date`:

```sql
SELECT id, event_type, publication_date
FROM event_publication
WHERE completion_date IS NULL
ORDER BY publication_date
LIMIT 20;
```

Rows accumulating there mean a listener is failing or Kafka is unreachable. The
events are not lost — they are retried, and republished on restart. Find the
listener's exception in the logs before intervening.

### Redis is down

**What it looks like.**
- `/readyz` answers 503 within a few seconds, and the load balancer takes the instance out.
- Requests that need Redis (quotes, nearby, booking, offers) answer **503
  `SERVICE_UNAVAILABLE`** with `Retry-After`.
- Location updates are dropped at once: `location_updates_total{result="unavailable"}` rises, and
  the `redis-location` breaker opens (dashboard: *Circuit breakers open*).
- Threads do not pile up, and liveness stays UP: `FullStackChaos` checks exactly this.

**What to do.**
1. Find out why Redis is down: `docker compose ps`, Redis logs, memory.
2. Nothing in RideFlow needs restarting. When Redis answers, readiness comes back, the breaker
   closes after a probe, and positions are taken again.
3. What was lost is short-lived state:
   - Driver positions return with the next report, 3 s later.
   - Offers in flight expire, and the recovery sweep re-matches their trips from PostgreSQL.
   - Quotes made before the outage must be asked for again.

### Kafka is down

**What it looks like.**
- `KafkaConsumerLagGrowing` or `EventPublicationBacklog` fires. The *Event publication backlog*
  panel climbs.
- The lag alert watches whoever reads `rideflow.trip-events` and reports Kafka client metrics
  here. The backend only writes to the topic, so locally that is the simulator. Without such a
  consumer it cannot fire: production needs the downstream consumers scraped, or a broker-side
  exporter.
- Bookings, trips and payments carry on as normal. Only the copy of events to Kafka waits.

**What to do.**
1. Bring Kafka back. Nothing else is needed: every unsent event is in `event_publication`, and
   `EventRedelivery` sends it again, every minute, once it is two minutes old (ADR 0012).
2. Watch the backlog fall to 0. A send waits at most 5 s for Kafka (`max.block.ms`), so no
   listener thread is held while it is away.
3. Consumers may see an event twice: delivery is at least once. They de-duplicate on `eventId`
   (`docs/events.md`). `FullStackChaos.losesNoEventWhileKafkaIsDown` proves none is lost.

If the backlog stays up after Kafka returns, look for the Kafka listener's exception in the
logs. An `InvalidTimestampException` means the broker's clock is far behind the backend's. On a
laptop, this follows a sleep; restart Docker Desktop to resync it.

### Matching is slow

**What it looks like.** `BookingLatencyHigh` fires, or *Booking to first offer* or *Time to
assign* climbs.

**What to check.**
1. **Is it the booking request itself?** Look at the *Booking API latency* panel. If it is slow,
   check *HikariCP connections*: requests waiting for a connection (`pending` above 0, acquire
   time rising) mean the pool is short or a query is slow. Then check *Redis command latency*.
2. **Is it the time to the first offer?** Look at `matching_first_offer_seconds`. Matching runs
   in event listeners: check the *Event publication backlog*. A backlog means listeners are
   behind.
3. **Pick one slow booking and open its trace in Jaeger.** The trace shows which step - Redis,
   the database, the listener - took the time.

**What to do.** Scale out, which the Redis fan-out allows. Do not reach for a bigger pool
(`spring.datasource.hikari.maximum-pool-size`) first. In `docs/performance.md`, 30 connections
were slower than 10, because more transactions at once only contend inside PostgreSQL. First find
what holds the connections, as the BCrypt-in-a-transaction fault did.

### The no-driver rate spikes

**What it looks like.** `NoDriverRateHigh`: more than 20% of the last 10 minutes' bookings found
no driver.

**What to check.**
1. **Are there drivers?** *Available drivers by vehicle type*. A sudden drop with ingest still
   healthy means drivers are being taken off the map: check the stale sweep, and
   `location_updates_total` by result. A rise in `unavailable` means Redis; a rise in
   `rejected_spoof` means bad GPS, or a client sending stale positions.
2. **Are drivers turning offers down?** *Offer results per minute*: many `expired` or `rejected`
   against few `accepted`.
3. **Is demand concentrated?** *Surge: cells above 1.0*. Demand above supply in a few cells is a
   business event, not a fault: surge is meant to answer it.

**What to do.** If it is a fault, fix its cause above. If it is demand, the surge override
(`POST /api/v1/admin/surge/overrides`) can raise prices in the affected cells to bring drivers in.

### Drivers linger on the map after disconnecting

The stale-driver job removes drivers idle beyond
`rideflow.location.stale-after-seconds`. It runs under ShedLock, so exactly one
instance performs it. If it has stopped, inspect the `shedlock` table for a lock
held by a dead instance:

```sql
SELECT name, lock_until, locked_at, locked_by FROM shedlock;
```

### A booking answers 409 IDEMPOTENCY_KEY_IN_FLIGHT and never recovers

The request that claimed that `Idempotency-Key` died before answering - a crash
or a kill between claiming the key and storing the response. The claim stays
until the 24-hour purge (ADR 0009 records this gap).

1. Confirm no trip was created for it: the rider has no trip with a
   `requested_at` near the key's `created_at`.
2. Release the key, and the client's retry will go through:

   ```sql
   DELETE FROM idempotency_keys
   WHERE user_id = '<rider id>' AND key = '<key>' AND response_status IS NULL;
   ```

The quote that request consumed is gone; the rider needs a new one.

### A trip sits in REQUESTED and no driver is offered it

Matching state lives in Redis and is rebuilt from PostgreSQL by the recovery
sweep every 15 s, so a trip should never wait unattended for long (ADR 0010).

1. Is anyone available nearby? `GET /api/v1/drivers/nearby` as a rider at the
   pickup, or `location_drivers_available` in Grafana.
2. What does Redis hold? `HGETALL match:<tripId>` shows the deadline and the
   current offer; `ZSCORE match:retry <tripId>` the next search.
3. Is the sweep running? Look for `Restarting matching for trip` in the logs and
   for `match_restarts_total` rising. The sweep holds the `matching-recovery`
   ShedLock; a row in `shedlock` with a `lock_until` far in the future means an
   instance died holding it.

A driver lock left behind by a crash (`driver:lock:<driverId>` pointing at an
offer that no longer exists) needs no action: the next search takes it over.

### A rider stops seeing their driver move

Positions are forwarded only while the driver has an active trip, and at most
every 2 s.

1. `HGETALL driver:state:<driverId>`. `tripId`, `riderId` and `tripPhase` should
   be set. If they are missing, the trip-context listener has not caught up.
   Look for incomplete rows in `event_publication`.
2. Is the driver still sending? `ts` in the same hash is their last accepted
   update. Silence for 30 s makes them stale, and the rider is told
   `DRIVER_CONNECTION_LOST`. The trip carries on.
3. Is the rider connected? A message to a user with no session anywhere is
   dropped by design. The app re-reads `GET /api/v1/trips/active` on reconnect.

### A trip is flagged

`GET /api/v1/admin/trips/flagged` lists flagged trips, newest first, with
every reason found, oldest first, separated by ` | `. Flags never change a fare or a status. They ask a human to
look.

- `Completed in N s over M m`: a trip that barely happened, charged the minimum
  fare.
- `Repriced from X to Y paise...`: the route driven was much longer than
  estimated. Compare with the quote.
- `No route was recorded...`: the phone sent nothing during the ride, or Redis
  lost the list. The quote was charged.
- `Driver silent for over 10 minutes...`: the driver's phone stopped reporting
  mid-ride.

### A rider cannot book: 422 OUTSTANDING_BALANCE

A trip of theirs was charged to a wallet that could not cover it, so their
wallet is below zero ([ADR 0012](adr/0012-double-entry-ledger.md)).

- `GET /api/v1/wallet`, as the rider, shows the balance and the charge.
- `SELECT id, payment_status FROM trips WHERE rider_id = '<id>' AND payment_status = 'PENDING_SETTLEMENT';`
  lists what waits.

A top-up that brings the wallet to zero or above settles every one of them.
So does a refund that does. Nothing needs doing by hand.

### A top-up stays PENDING

Only the gateway's webhook settles a top-up.

1. Did it arrive? Look for `INVALID_SIGNATURE` 401s on
   `/api/v1/webhooks/payment-gateway`. That means the secret differs between
   the gateway and this instance (every instance must share
   `RIDEFLOW_GATEWAY_WEBHOOK_SECRET`), or the clocks are more than 5 minutes
   apart.
2. A 404 means the webhook names a payment we never started. A 422
   `AMOUNT_MISMATCH` means the amount paid differs from the top-up; it is
   logged and not applied.
3. With the mock gateway, nothing happens until someone pays at the top-up's
   `paymentUrl`. The page exists only with
   `RIDEFLOW_PAYMENT_MOCKGATEWAY_ENABLED=true`; without it, the URL answers 404.

Nothing expires a PENDING top-up yet. The rider can start another with a new
`Idempotency-Key`.

### The ledger does not reconcile

The `LedgerOutOfBalance` alert fires as soon as a reconciliation finds a mismatch
(`ledger_reconciliation_mismatches` above 0).

`ledger_reconciliation_mismatches` above zero, or an ERROR line starting
"Ledger reconciliation found problems", means one of these no longer holds:
- every transaction balances
- every account's balance equals its entries
- all balances total zero

`GET /api/v1/admin/ledger/reconcile` lists the first 100 of each.

The database refuses unbalanced transactions and edited entries, so a mismatch
means something went around it: a manual UPDATE of `ledger_accounts`, or a
restore. Find what changed before touching anything. Never edit an entry: the
trigger refuses it, and the fix is a reversing transaction.

### Fares are not surging, or will not stop surging

Check these in order: they are the precedence quotes apply
([ADR 0014](adr/0014-surge-engine.md)).

1. `GET /api/v1/admin/surge`, as an admin. `killSwitchOn: true` prices
   everything at 1.0. Turn it off with
   `POST /api/v1/admin/surge/kill-switch {"on": false}`.
2. In the same response, an override for the cell beats the computed value until
   it expires. Remove it with `DELETE /api/v1/admin/surge/overrides/{cell}`.
3. Is the job running? `surge_tick` should count up every 30 seconds. The
   `surge-tick` row in `shedlock` shows who ran it last. With the job stopped,
   `surge:{cell}` keys lapse within 90 seconds and every fare is 1.0. That is by
   design.
4. Is demand arriving? `ZRANGE surge:demand:cells 0 -1 WITHSCORES` lists cells
   with requests in the last 5 minutes. Requests are counted from
   `TripRequested`; check `event_publication` for incomplete
   `SurgeDemandRecorder` rows.

A price falls 0.25 per tick once its demand eases, so a cell at 2.5 takes three
minutes to return to 1.0. That is smoothing, not a fault.

### The simulator stops at setup

- **"rate-limits registrations and logins per address"**: the backend is not on
  the `sim` profile. Every simulated user comes from one address, and the
  normal limits are 3 registrations an hour and 20 logins a minute.
- **"Drivers need approving"**: set `RIDEFLOW_ADMIN_PHONE` and
  `RIDEFLOW_ADMIN_PASSWORD` for the simulator, to the backend's bootstrap admin.
- **Riders never get money**: `WALLET` top-ups need the mock gateway, which
  only the `sim` profile turns on. Use `--simulator.demand.payment-method=CASH`
  otherwise.

### The live map stays empty

1. Signed in, but "refused": the account is not an ADMIN. Only admins may
   subscribe to `/topic/admin/live-map`.
2. Connected, but no time shown: no snapshot is arriving. The `live-map` row in
   `shedlock` shows when one was last built. A snapshot that fails to build is
   logged by the scheduler.
3. Snapshots arrive with no drivers: nobody has reported a position in the last
   30 seconds. `ZRANGE drivers:lastseen 0 -1 WITHSCORES` lists who has, and
   when.

### A reconnected driver's positions are ignored

Each new STOMP session numbers its positions from zero. The backend forgets the
stored `seq` when a driver's CONNECT arrives, so positions from an app that
reconnects are accepted again. If `location_updates_total{result="ignored_out_of_order"}`
keeps climbing for a driver who reconnected, the app is reusing old numbers
inside one session. That is a client fault.

### Port already in use

Defaults: backend 8080, simulator 8081, Postgres 5432, Redis 6379, Kafka 9092,
Grafana 3000, Prometheus 9090, Jaeger 16686. Override the backend with
`SERVER_PORT`, or change the host-side port mapping in the compose file.

## Reset everything

```bash
docker compose -f infra/docker-compose.yml down -v   # -v also drops the data volumes
./mvnw clean
```
