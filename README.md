# RideFlow

The backend of a ride-hailing service, built as a **modular monolith**, plus a
simulator that drives realistic traffic through it.

Riders get fare quotes, book rides, track the driver live, pay from a wallet and
rate the trip. Drivers onboard with a vehicle, go online, stream their location,
receive offers and complete trips. Admins approve drivers, watch a live map and
manage surge pricing. The simulator runs hundreds of fake drivers and riders
against the public API, so the system can be demonstrated and load-tested
without a mobile app.

[`PROJECT_SPEC.md`](PROJECT_SPEC.md) is the single source of truth for scope,
architecture, domain rules and standards. This README only tells you how to run
the thing.

## Status

**Phase 1 — shared kernel.** The `shared` module is in place: `Money`,
`GeoPoint`, `VehicleType`, UUIDv7 id generation, the RFC 9457 error model and
the injected `Clock`. `./mvnw verify` passes with 78 tests, and Modulith
verifies the boundary.

Phase 0 (build, infrastructure, docs, CI) is merged and tagged `phase-0`.

The `identity` module is next. It needs a running Docker daemon for its
Testcontainers integration tests.

## Requirements

| Tool | Version |
|---|---|
| JDK | 21 (LTS) |
| Maven | use the bundled `./mvnw` |
| Docker | with Compose v2 |

Docker is required, not optional — integration tests run on Testcontainers and
PROJECT_SPEC section 12 rules out H2 and embedded Redis.

## Quick start

```bash
# 1. Infrastructure: postgres, redis, kafka, prometheus, grafana, jaeger
docker compose -f infra/docker-compose.yml up -d

# 2. Build and test everything
./mvnw verify

# 3. Run the backend
./mvnw -pl backend spring-boot:run
```

| Service | URL |
|---|---|
| Backend | http://localhost:8080 |
| API docs | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |
| Grafana | http://localhost:3000 (`admin` / `admin`) |
| Prometheus | http://localhost:9090 |
| Jaeger | http://localhost:16686 |

More detail, including the two-instance setup and troubleshooting, is in
[`docs/runbook.md`](docs/runbook.md).

## Layout

```
├── backend/        Spring Boot application, split into Modulith modules
├── simulator/      separate app: fake drivers and riders, live map
├── infra/          docker compose, prometheus, grafana dashboards as code
├── load-tests/     k6 scripts
└── docs/           ADRs, event catalog, measured performance, runbook
```

The backend modules are `shared`, `identity`, `driver`, `location`, `pricing`,
`trip`, `matching`, `payment`, `rating`, `notification` and `admin`. A module may
only use another module's top-level package; `internal`, `persistence` and `web`
packages are private. `ModularityTests` enforces this on every build, so a
boundary violation breaks CI rather than surviving review.

`shared` is the kernel every other module builds on, and holds no business
logic. Note that `Money` is a count of paise in a `long` and `GeoPoint`
validates itself in its constructor, so an invalid coordinate cannot be
constructed at all. Errors carry an `ErrorCode`, which each module declares for
itself — see [ADR 0004](docs/adr/0004-error-codes-as-an-interface.md).

The simulator never depends on `backend` code. It is a client of the public HTTP
and STOMP API, and a Kafka consumer of `rideflow.trip-events`.

## Stack

Java 21 · Spring Boot 4.1.1 · Spring Modulith 2.1.1 · PostgreSQL 17 with Flyway ·
Redis 7 · Kafka 4 (KRaft) · Spring Security with OAuth2 resource server ·
WebSocket/STOMP · Testcontainers 2 · Micrometer, Prometheus, OpenTelemetry,
Grafana.

Two deliberate deviations from PROJECT_SPEC section 3, both recorded in
[ADR 0003](docs/adr/0003-test-stack-on-junit-6-jqwik-deferred.md): Spring Boot
4.1.1 manages **JUnit 6**, not 5, and **jqwik is not on the classpath** because
its current release targets the incompatible JUnit Platform 1.x.

## Development

```bash
./mvnw verify          # the full gate: tests, module boundaries, coverage
./mvnw spotless:apply  # format before committing (google-java-format)
```

Coverage is enforced by JaCoCo at 80% of lines on `trip`, `matching`, `pricing`
and `payment` (PROJECT_SPEC section 12). The rule is already configured and
activates by itself as those packages appear.

Commits follow Conventional Commits. Each phase is developed on a `phase-N`
branch and tagged `phase-N` when merged.

## Documentation

- [`PROJECT_SPEC.md`](PROJECT_SPEC.md) — scope, architecture, domain rules, standards
- [`docs/adr/`](docs/adr/) — architecture decision records
- [`docs/events.md`](docs/events.md) — event catalog
- [`docs/performance.md`](docs/performance.md) — measured results only
- [`docs/runbook.md`](docs/runbook.md) — running and troubleshooting
