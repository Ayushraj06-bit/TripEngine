# RideFlow — Project Specification

> **For the AI assistant:** This file is the single source of truth for the project. Read it fully before every task. If a request conflicts with this file, stop and ask. If you believe something here is wrong or can be improved, say so and explain why before changing anything.

---

## 1. What we are building

RideFlow is the backend of a ride-hailing app (like Ola, Uber or Rapido) plus a simulator that generates realistic traffic.

- **Riders** get fare quotes, book rides, track the driver live, pay, and rate.
- **Drivers** register with a vehicle, get approved by an admin, go online, stream their location, receive ride offers, and complete trips.
- **Admins** approve drivers, watch a live map, manage surge pricing, and issue refunds.
- **The simulator** runs hundreds of fake drivers and riders against the public API so the system can be demoed and load-tested.

### In scope
Auth and roles, driver onboarding, real-time location, fare quotes, surge pricing, trip lifecycle, matching engine, live tracking, wallet with a double-entry ledger, ratings, simulator with a live map, observability, load tests, CI/CD.

### Out of scope
Real payment gateway (mocked), real SMS (OTP logged in dev), turn-by-turn navigation, mobile apps (the simulator and a web map replace them), multiple cities (one configured service area).

---

## 2. Non-functional targets

These are targets to measure in Phase 10. Record real results in `docs/performance.md`. Never put made-up numbers anywhere.

| Metric | Target |
|---|---|
| Simulated online drivers | 2,000 (≈ 670 location updates/sec) |
| Location ingest latency (server side) | p95 < 20 ms |
| Nearby-driver search | p95 < 10 ms |
| Booking API (`POST /trips`) | p95 < 150 ms |
| Time from booking to first offer sent | p95 < 500 ms |
| Double-assigned drivers | Exactly 0, under any concurrency |
| Lost domain events | Exactly 0, including when Kafka is down |
| Money errors | Ledger always balances to 0 |

---

## 3. Tech stack

| Area | Choice |
|---|---|
| Language | Java 21 (LTS). Use records, sealed types, pattern matching, virtual threads. |
| Framework | Spring Boot 4.1.x (latest patch). Generate the base from start.spring.io. |
| Modularity | Spring Modulith 2.1.x (module boundaries, event publication registry, module tests) |
| Build | Maven with wrapper, multi-module repo |
| Database | PostgreSQL 17, Flyway migrations, Spring Data JPA |
| Cache and real-time state | Redis 7 (GEO, Lua scripts, Pub/Sub), Lettuce client |
| Events | Spring Modulith events internally; externalized to Kafka (KRaft, no ZooKeeper) |
| Real-time | Spring WebSocket with STOMP |
| Security | Spring Security, OAuth2 Resource Server JWT support (no hand-written JWT filters), BCrypt |
| Rate limiting | Bucket4j with Redis backend |
| Scheduled jobs | `@Scheduled` + ShedLock for jobs that must run once per cluster |
| Resilience | Resilience4j (timeouts, circuit breakers) |
| API docs | springdoc-openapi (version compatible with Boot 4) |
| Observability | Actuator, Micrometer + Prometheus, OpenTelemetry tracing, structured JSON logs, Grafana |
| Testing | JUnit 5, AssertJ, Mockito, Testcontainers 2.x with `@ServiceConnection`, Awaitility, Spring Modulith test support, jqwik (property-based), k6 (load) |
| Code quality | Spotless (google-java-format), JaCoCo, GitHub Actions, Dependabot, CodeQL |
| Map UI | Plain HTML + Leaflet + OpenStreetMap tiles |

**Version note:** Spring Boot 4 differs from Boot 3 in several places (for example, it uses Jackson 3). AI assistants often generate Boot 3 style code. Always compile after each step and check the official docs when something does not compile.

---

## 4. Repository layout

```
rideflow/
├── PROJECT_SPEC.md
├── README.md
├── pom.xml                      # parent POM
├── backend/                     # the Spring Boot application (modular monolith)
├── simulator/                   # separate Spring Boot app: fake drivers/riders + live map
├── infra/
│   ├── docker-compose.yml       # postgres, redis, kafka, prometheus, grafana, tracing
│   ├── docker-compose.scale.yml # 2 backend instances behind a load balancer
│   ├── prometheus/
│   └── grafana/                 # dashboards as code
├── load-tests/                  # k6 scripts
└── docs/
    ├── adr/                     # architecture decision records
    ├── events.md                # event catalog
    ├── performance.md           # measured results only
    └── runbook.md
```

---

## 5. Architecture

A **modular monolith**: one deployable backend, split into modules with enforced boundaries (Spring Modulith). This keeps the system simple to run while making it easy to extract a module (for example `location`) into its own service later.

### Backend modules (package `com.rideflow.<module>`)

| Module | Responsibility |
|---|---|
| `shared` | Value types (`Money`, `GeoPoint`, `VehicleType`), UUIDv7 ids, error model, `Clock` config. No business logic. |
| `identity` | Users, roles, registration, login, access/refresh tokens |
| `driver` | Driver profile, vehicle, approval, status (OFFLINE/AVAILABLE/ON_TRIP), rating aggregates |
| `location` | WebSocket location ingestion, Redis GEO, stale-driver detection, nearby search |
| `pricing` | Rate cards, route estimation, fare quotes, surge engine |
| `trip` | Trip aggregate, state machine, booking, OTP, final fare |
| `matching` | Candidate search, ranking, offers, timeouts, recovery |
| `payment` | Wallets, double-entry ledger, top-ups, charges, refunds |
| `rating` | Rider and driver ratings |
| `notification` | Real-time push to users across multiple instances |
| `admin` | Admin APIs and live-map snapshot |

### Module rules
1. A module may only call another module's **public API package** (its top-level package). Internal packages (`internal`, `persistence`, `web`) are private.
2. Modules react to each other through **domain events** wherever possible, not direct calls.
3. `ApplicationModules.of(RideFlowApplication.class).verify()` must pass in a test at all times.

### Events and Kafka
- Modules publish events with `ApplicationEventPublisher` inside the same transaction as the state change.
- Listeners use `@ApplicationModuleListener` (async, after commit). The **Spring Modulith event publication registry** stores every event in the database in the same transaction, which gives us the transactional outbox pattern: if a listener or Kafka fails, the event is retried and never lost.
- Trip and payment events are **externalized to Kafka** (topic `rideflow.trip-events`, key = `tripId` so all events of one trip stay in order). The simulator's dashboard consumes this topic as an external consumer.

### Real-time delivery across instances
- Clients connect with STOMP over WebSocket.
- The `notification` module keeps track of which users are connected to *this* instance. Messages are published to Redis Pub/Sub; every instance delivers only to its locally connected users. This works with any number of instances and adds no extra infrastructure.
- **WebSocket is best-effort. REST is the source of truth.** Every client must resync with `GET /api/v1/trips/active` after connecting or reconnecting.

---

## 6. Domain model

### Driver status
`OFFLINE` → `AVAILABLE` (go online, must be APPROVED and have a vehicle) → `ON_TRIP` (on assignment) → `AVAILABLE` (trip ends) → `OFFLINE` (go offline, not allowed while ON_TRIP).

### Trip states and allowed transitions

| From | To | Who | Rule |
|---|---|---|---|
| REQUESTED | DRIVER_ASSIGNED | system | Offer accepted and DB constraints pass |
| REQUESTED | CANCELLED | rider | Free |
| REQUESTED | NO_DRIVER_FOUND | system | Matching deadline passed |
| DRIVER_ASSIGNED | DRIVER_ARRIVED | driver | Driver within `arrival-geofence-meters` of pickup |
| DRIVER_ASSIGNED | CANCELLED | rider | Free within `rider-cancel-grace-seconds` of assignment, fee after |
| DRIVER_ASSIGNED | REQUESTED | driver | Driver cancels: trip returns to matching with priority, driver's acceptance stats drop |
| DRIVER_ARRIVED | IN_PROGRESS | driver | Correct OTP (max `otp-max-attempts`, then locked 2 min) |
| DRIVER_ARRIVED | CANCELLED | rider | Cancellation fee applies |
| DRIVER_ARRIVED | CANCELLED | driver | Only after `no-show-after-minutes` of waiting: rider charged no-show fee |
| IN_PROGRESS | COMPLETED | driver | Final fare calculated |

Terminal states: `COMPLETED`, `CANCELLED`, `NO_DRIVER_FOUND`. Any other transition must be rejected with `409 TRIP_INVALID_TRANSITION`.

### Offer status
`PENDING` → `ACCEPTED` | `REJECTED` | `EXPIRED` | `WITHDRAWN` (rider cancelled).

---

## 7. Data design

### Conventions
- IDs: UUIDv7 (time-ordered, keeps B-tree indexes efficient).
- Time: `timestamptz`, always UTC. Code gets time only from an injected `java.time.Clock` (never `Instant.now()` directly) so tests can control time.
- Money: `long` paise (`Money` record). Never `double` or `float`. Rounding: HALF_UP to the nearest rupee for fares; HALF_EVEN for commission splits, remainder goes to the driver.
- Every schema change is a Flyway migration. Never edit a migration that has been committed.

### PostgreSQL tables (key columns and constraints)

- `users` — id, phone (unique, E.164), name, password_hash, role, failed_login_count, locked_until, timestamps
- `refresh_tokens` — id, user_id, token_hash (unique), family_id, expires_at, revoked_at, replaced_by_id
- `drivers` — user_id (PK), approval_status, status, rating_sum, rating_count, offers_received, offers_accepted, last_status_change_at, version
- `vehicles` — id, driver_id (unique), type, plate_number (unique, normalized uppercase without spaces), model, colour
- `rate_cards` — id, vehicle_type, version, base_paise, per_km_paise, per_min_paise, min_fare_paise, waiting_per_min_paise, cancellation_fee_paise, effective_from; unique (vehicle_type, version)
- `trips` — id, rider_id, driver_id, vehicle_type, status, pickup/drop lat/lng, quoted_fare_paise, surge_multiplier, rate_card_id (snapshot of rates used), payment_method (WALLET/CASH), payment_status (UNPAID/PAID/PENDING_SETTLEMENT/REFUNDED), otp, otp_attempts, otp_locked_until, final_fare_paise, distance_m, duration_s, cancelled_by, cancel_reason, cancellation_fee_paise, per-state timestamps, flagged_reason, version
  - `UNIQUE (rider_id) WHERE status IN ('REQUESTED','DRIVER_ASSIGNED','DRIVER_ARRIVED','IN_PROGRESS')` — one active trip per rider
  - `UNIQUE (driver_id) WHERE status IN ('DRIVER_ASSIGNED','DRIVER_ARRIVED','IN_PROGRESS')` — one active trip per driver
  - Index (rider_id, requested_at DESC), (driver_id, requested_at DESC), partial index on requested_at WHERE status = 'REQUESTED'
- `trip_events` — append-only audit: id, trip_id, from_status, to_status, actor_type, actor_id, occurred_at, metadata (jsonb)
- `idempotency_keys` — PK (user_id, key), request_hash, response_status, response_body (jsonb), created_at; purged after 24h
- `ledger_accounts` — id, owner_type, owner_id, account_type, balance_paise, version; unique (owner_type, owner_id, account_type)
- `ledger_transactions` — id, type, reference_id, created_at; unique (type, reference_id) for idempotency
- `ledger_entries` — id, transaction_id, account_id, direction (DEBIT/CREDIT), amount_paise (> 0). Never updated or deleted.
- `topups` — id, user_id, amount_paise, status, gateway_ref, idempotency key
- `ratings` — id, trip_id, rater_id, rater_role, ratee_id, stars (CHECK 1–5), comment (≤ 500 chars), created_at; unique (trip_id, rater_role)
- `event_publication` — managed by Spring Modulith
- `shedlock` — managed by ShedLock

### Redis keys

Every key has a TTL, except the GEO sets and `drivers:lastseen`, which the stale-driver job cleans.

| Key | Type | Purpose | TTL |
|---|---|---|---|
| `driver:state:{driverId}` | Hash | status, vehicleType, lat, lng, ts, seq, lastGeoWriteTs | 24 h, refreshed |
| `drivers:available:{type}` | GEO | Positions of AVAILABLE drivers | none (cleaned) |
| `drivers:lastseen` | ZSET | score = last ping epoch ms | none (cleaned) |
| `driver:lock:{driverId}` | String | offerId currently holding the driver | offer timeout + 3 s |
| `match:{tripId}` | Hash | matching progress (radius step, current offer, started at) | 10 min |
| `match:{tripId}:tried` | Set | drivers already offered this trip | 10 min |
| `offer:{offerId}` | Hash | tripId, driverId, status, expiresAt | 10 min |
| `offers:expiry` | ZSET | score = expiresAt (from injected Clock) | none (cleaned) |
| `quote:{quoteId}` | String (JSON) | quote bound to rider and vehicle type | 5 min |
| `surge:{cell}` | String | current multiplier | 90 s (fails safe to 1.0) |
| `surge:demand:{cell}:{epochMinute}` | Counter | ride requests per minute | 10 min |
| `surge:override:{cell}` | String | admin override | until override expiry |
| `trip:route:{tripId}` | List | route points during a trip (capped with LTRIM) | 6 h |
| `trip:seq:{tripId}` | Counter | increasing sequence number for real-time messages of a trip | 24 h |
| `rt:events` | Pub/Sub channel | cross-instance real-time fan-out | n/a |

---

## 8. Key flows (summary)

1. **Location:** driver sends a STOMP message every ~3 s → validated (range, accuracy, order, speed sanity, service area) → throttled → one Lua script updates the state hash, the GEO set for the driver's status, and `drivers:lastseen` in a single round trip.
2. **Quote:** rider asks for a quote → fares for all vehicle types returned at once → each stored in Redis, bound to the rider, valid 5 min.
3. **Booking:** `POST /trips` with `Idempotency-Key` → quote consumed with `GETDEL` (single use) → trip saved as REQUESTED + `TripRequested` event in one transaction → `202 Accepted`.
4. **Matching:** event-driven and non-blocking. Offers are stored in Redis with an expiry index; a poller handles timeouts; accept/timeout races are settled atomically by Lua scripts; the database is the final guard (optimistic locking + partial unique indexes). A recovery sweeper re-triggers matching for stuck trips.
5. **Trip:** arrive (geofence) → OTP start → live tracking → complete → final fare (quoted fare unless the route deviates more than the threshold).
6. **Payment:** `TripCompleted` → ledger transaction (rider wallet → driver earnings + platform commission), idempotent by trip id.
7. **Surge:** every 30 s, demand vs supply per geohash cell (including neighbouring cells), smoothed and capped, stored with a short TTL.

---

## 9. Business constants (`rideflow.*` in `application.yml`, bound with `@ConfigurationProperties`)

```yaml
rideflow:
  location:
    min-interval-ms: 1000
    min-move-meters: 10
    force-write-seconds: 15
    stale-after-seconds: 30
    max-speed-kmph: 150
    max-accuracy-meters: 100
  matching:
    radius-steps-km: [2, 4, 7]
    candidates-per-search: 20
    offer-timeout-seconds: 12
    deadline-seconds: 60
    research-interval-seconds: 5
    fairness-bucket-meters: 300
    min-driver-rating: 3.5
  pricing:
    road-factor: 1.3
    quote-ttl-minutes: 5
    min-trip-meters: 300
    max-trip-km: 60
    reprice-deviation: 0.20
  trip:
    arrival-geofence-meters: 150
    free-wait-minutes: 3
    no-show-after-minutes: 5
    otp-max-attempts: 3
    rider-cancel-grace-seconds: 120
  surge:
    geohash-precision: 6
    window-minutes: 5
    tick-seconds: 30
    max-step-per-tick: 0.25
    cap: 2.5
    ttl-seconds: 90
  payment:
    commission-percent: 20
```

Sample rate cards (seeded by Flyway, values in paise, adjust freely):

| Type | Base | Per km | Per min | Min fare | Waiting/min | Cancel fee |
|---|---|---|---|---|---|---|
| BIKE | 2000 | 700 | 100 | 3000 | 150 | 3000 |
| AUTO | 3000 | 1300 | 150 | 4000 | 200 | 4000 |
| CAR | 5000 | 1600 | 200 | 8000 | 300 | 5000 |

---

## 10. API conventions

- Base path `/api/v1`. JSON only.
- Errors use RFC 9457 Problem Details (`ProblemDetail`) with an extra `code` field, e.g. `TRIP_INVALID_TRANSITION`, `QUOTE_EXPIRED`.
- Status codes: 400 validation, 401 unauthenticated, 403 wrong role, **404 when a resource does not exist or does not belong to the caller** (never reveal that another user's trip exists), 409 state conflict or duplicate, 422 business rule violation, 429 rate limited.
- Every `POST` that creates something or moves money requires an `Idempotency-Key` header. Same key + same body → same stored response. Same key + different body → 422.
- Lists use cursor pagination (`?cursor=&limit=`, max 50).
- Validate every input with Bean Validation. Coordinates: lat ∈ [-90, 90], lng ∈ [-180, 180], finite numbers.
- Never trust ids from the request body for "who am I". Always use the authenticated principal.
- STOMP: clients may only SEND to allowed `/app/**` destinations for their role, and may only SUBSCRIBE to their own `/user/queue/**` destinations. No shared per-trip topics (anyone could subscribe to them).

---

## 11. Coding standards

- Constructor injection only. No field injection.
- Records for DTOs, events, and value objects. Lombok is not used.
- Controllers are thin: validate, call one service method, map the result.
- `@Transactional` only on service methods, never on controllers. Keep transactions short; never call Redis/Kafka/HTTP inside a DB transaction unless it is part of the outbox mechanism.
- No `Instant.now()`, `LocalDateTime.now()` or `System.currentTimeMillis()` in business code. Use the injected `Clock`.
- No swallowed exceptions. No `catch (Exception e) {}`.
- Logs: structured, include trace id; never log passwords, tokens, OTPs, or full phone numbers (mask to last 4 digits).
- Config values come from `@ConfigurationProperties`, never hard-coded magic numbers.
- Do not add a dependency that is not in this spec without asking first.

---

## 12. Testing standards

- **Unit tests** for pure logic: state machine, fare calculator, surge calculator, geo math, validators.
- **Integration tests** with Testcontainers (PostgreSQL, Redis, Kafka) using `@ServiceConnection`. No H2, no embedded Redis.
- **Module tests** with `@ApplicationModuleTest` and the Modulith `Scenario` API for event flows.
- **Concurrency tests** for every race condition listed in a phase (use `ExecutorService` + `CountDownLatch` to start threads together).
- **No `Thread.sleep` in tests.** Use Awaitility for async results and a controllable `Clock` for time.
- **No `@Disabled` tests** and never weaken an assertion to make a test pass.
- Test names describe behaviour: `rejectsBookingWhenQuoteBelongsToAnotherRider()`.
- Coverage (JaCoCo): ≥ 80% line coverage on `trip`, `matching`, `pricing`, `payment`. The build fails below this.

---

## 13. Definition of done (every phase)

1. `./mvnw verify` passes locally (unit + integration + module verification + coverage gate).
2. CI is green.
3. All edge cases listed in the phase prompt have tests.
4. OpenAPI docs updated for any new endpoint.
5. README and relevant `docs/` pages updated.
6. An ADR written for any significant decision (`docs/adr/NNNN-title.md`).
7. The assistant has run the standard review prompt and fixed all blocker and major findings.
8. Changes committed on a phase branch with conventional commit messages, merged, and tagged `phase-N`.
