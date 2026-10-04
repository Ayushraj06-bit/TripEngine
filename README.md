# RideFlow

The backend of a ride-hailing service, built as a **modular monolith**, plus a
simulator that drives realistic traffic through it.

Riders get fare quotes, book rides, track the driver live, pay from a wallet and
rate the trip. Drivers onboard with a vehicle, go online, stream their location,
receive offers and complete trips. Admins approve drivers, watch a live map and
manage surge pricing.

[`PROJECT_SPEC.md`](PROJECT_SPEC.md) is the single source of truth for scope,
architecture, domain rules and standards. This README only tells you how to run
the thing.

## Status

| Phase | State |
|---|---|
| 0 Foundation | done, tagged `phase-0` |
| 1 Identity and driver onboarding | done |
| 2 Real-time location | done |
| 3 Pricing and quotes | done |
| 4 Trip lifecycle and booking | done |
| 5 Matching engine | done |
| 6 Live trip and final fare | done |
| 7 Payments | next |

**Phase 2** adds live driver positions: STOMP over WebSocket at `/ws` with JWT
authentication on CONNECT and per-role destination rules, a validation pipeline
counted on `location_updates_total`, one atomic Lua script per update, Redis GEO
availability sets kept in step with the driver module through events, a
ShedLock-guarded stale-driver sweep, the rider's blurred "cars around you" view,
and the exact nearest-driver search matching will use.

**Phase 3** adds fares: versioned rate cards seeded from section 9 and switched on
by the clock, a pure fare calculator whose breakdown always adds up to the total,
a haversine route estimate behind an interface a real router can replace
([ADR 0007](docs/adr/0007-route-estimation.md)), and `POST /api/v1/fares/quotes`,
which prices every vehicle type at once. Quotes live five minutes in Redis and are
consumed exactly once by an atomic compare-and-delete script
([ADR 0008](docs/adr/0008-fare-quotes.md)).

**Phase 4** adds trips: `POST /api/v1/trips` books a quote exactly once per
`Idempotency-Key`, with partial unique indexes as the real guard against a second
active trip. One state machine - PROJECT_SPEC section 6's table, kept as data -
decides every change, and each change writes one audit row and one event in the
same transaction. The events reach Kafka on `rideflow.trip-events`, keyed by trip
([event catalog](docs/events.md)). Arrival is checked against the driver's live
position, the start OTP locks after three wrong codes, and drivers move between
AVAILABLE and ON_TRIP as their trips change
([ADR 0009](docs/adr/0009-trip-booking-and-lifecycle.md)). The build now fails
below 80% line coverage in `trip`, `pricing` and `shared`.

**Phase 5** adds the matching engine. Offers go to one driver at a time, nearest
first with a fairness tie-break, widening from 2 to 7 km and searching again
until the deadline. Nothing waits on a thread: offers, locks and timers live in
Redis, a poller on every instance expires offers, and a recovery sweep rebuilds
anything Redis loses from PostgreSQL - which, through the partial unique index,
is the final word on who gets a trip
([ADR 0010](docs/adr/0010-non-blocking-matching.md)). Offers reach drivers on
`/user/queue/offers` through the new notification module, which fans messages out
across instances over Redis Pub/Sub.

**Phase 6** makes the trip live. Every change reaches the rider and the driver on
`/user/queue/trip-updates`, each with their own view of the trip. While the
driver is on a trip, the rider sees their position every 2 s with an ETA to the
pickup or the drop. Every message carries a per-trip `seq` numbered by Redis, so
a client can ignore anything old, whichever instance sent it
([ADR 0011](docs/adr/0011-realtime-fan-out-over-redis-pub-sub.md)). The route is
recorded once the rider is aboard. At completion it settles the fare: the quote,
unless the distance driven differs by more than 20%, in which case the trip is
repriced with the rate card it was booked on, plus waiting beyond 3 free minutes.
GPS jumps are dropped first. Suspicious trips are flagged for review at
`GET /api/v1/admin/trips/flagged`: too short, repriced by more than half, no
route, or a driver silent for 10 minutes. A driver who loses connection does not
cancel the trip; the rider is told. WebSocket sessions close when their token
expires.

## Requirements

| Tool | Version |
|---|---|
| JDK | 21 (LTS) |
| Maven | use the bundled `./mvnw` |
| Docker | with Compose v2, running |

Docker is required, not optional: integration tests run on Testcontainers and
PROJECT_SPEC section 12 rules out H2 and embedded Redis.

## Quick start

On the `local` profile, Spring Boot starts the Compose stack itself and wires the
connection details, so there is nothing to configure by hand:

```bash
./mvnw -pl backend spring-boot:run
```

To manage the infrastructure yourself instead:

```bash
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml ps     # all six healthy?
```

| Service | URL |
|---|---|
| Backend | http://localhost:8080 |
| API docs | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |
| Liveness / readiness | `/actuator/health/liveness`, `/actuator/health/readiness` |
| Metrics | http://localhost:8080/actuator/prometheus |
| Module structure | http://localhost:8080/actuator/modulith |
| Grafana | http://localhost:3000 (`admin` / `admin`) |
| Prometheus | http://localhost:9090 |
| Jaeger | http://localhost:16686 |

More detail and troubleshooting: [`docs/runbook.md`](docs/runbook.md).

## Profiles

| Profile | Purpose |
|---|---|
| `local` (default) | Boot manages Compose, plain-text logs, every request traced |
| `test` | Testcontainers supplies all connection details; no fixed hosts |
| `prod` | Everything from the environment, no defaults, ECS JSON logs |

## Testing

```bash
./mvnw verify           # unit + integration + module boundaries + coverage report
./mvnw spotless:apply   # format before committing (google-java-format)
```

Integration tests share one set of containers through
[`TestcontainersConfiguration`](backend/src/test/java/com/rideflow/TestcontainersConfiguration.java).
Time-dependent behaviour uses
[`MutableClock`](backend/src/test/java/com/rideflow/shared/MutableClock.java) rather
than sleeping, per PROJECT_SPEC section 12.

JaCoCo reports coverage; the 80% gate on `trip`, `matching`, `pricing` and
`payment` switches on in Phase 4, once there is business code to measure.

## Layout

```
├── backend/        Spring Boot application, split into Modulith modules
├── infra/          docker compose, prometheus, grafana dashboards as code
├── load-tests/     k6 scripts (Phase 10)
└── docs/           ADRs, event catalog, measured performance, runbook
```

Modules: `shared`, `identity`, `driver`, `location`, `pricing`, `trip`,
`matching`, `payment`, `rating`, `notification`, `admin`. A module may only use
another module's top-level package; `internal`, `persistence` and `web` packages
are private. `ModularityTests` enforces this on every build and writes the module
documentation to `target/spring-modulith-docs`, so a boundary violation breaks CI
rather than surviving review.

## Stack

Java 21 · Spring Boot 4.1.1 · Spring Modulith 2.1.1 · PostgreSQL 17 with Flyway ·
Redis 7 · Kafka 4 (KRaft) · Spring Security with OAuth2 resource server ·
WebSocket/STOMP · Testcontainers 2 · Micrometer, Prometheus, OpenTelemetry, Grafana.

### Notes on Spring Boot 4

Boot 4 splits autoconfiguration into per-technology modules and renames several
things. The traps this project hit, all of which fail *silently*:

- `flyway-core` alone does nothing — Boot's Flyway autoconfiguration lives in
  `spring-boot-flyway`. Without it no migration runs and there is no warning.
- `@AutoConfigureMockMvc` moved to `spring-boot-starter-webmvc-test`.
- `HttpStatus.UNPROCESSABLE_ENTITY` is deprecated in favour of
  `UNPROCESSABLE_CONTENT` (RFC 9110 renamed 422).
- Boot 4.1.1 manages **JUnit 6**, not 5, which rules out jqwik — see
  [ADR 0003](docs/adr/0003-test-stack-on-junit-6-jqwik-deferred.md).
- Testcontainers 2.x renamed every module to `testcontainers-*` and dropped its
  Redis module; Redis comes from `com.redis:testcontainers-redis`.

## Documentation

- [`PROJECT_SPEC.md`](PROJECT_SPEC.md) — scope, architecture, domain rules, standards
- [`docs/adr/`](docs/adr/) — architecture decision records
- [`docs/events.md`](docs/events.md) — event catalog
- [`docs/performance.md`](docs/performance.md) — measured results only
- [`docs/runbook.md`](docs/runbook.md) — running and troubleshooting
