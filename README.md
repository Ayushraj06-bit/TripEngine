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

**Phase 0 — Foundation. Complete.** `./mvnw verify` passes with 117 tests against
real PostgreSQL, Redis and Kafka.

In place: the multi-module build, all eleven module packages with enforced
boundaries, the `shared` kernel (`Money`, `GeoPoint`, `VehicleType`, UUIDv7 ids,
the RFC 9457 error model, an injected UTC `Clock`), `RideFlowProperties` bound and
validated so bad configuration fails startup, Flyway with ShedLock and the
Modulith outbox table, the `local`/`test`/`prod` profiles, observability, the
pinned Docker Compose stack, and CI.

No business logic yet: every module package beyond `shared` is an empty boundary
waiting for its phase. The `simulator` module arrives in Phase 9.

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
