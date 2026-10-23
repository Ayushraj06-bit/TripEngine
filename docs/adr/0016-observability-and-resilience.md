# 16. Observability on an internal port, timeouts everywhere, and breakers at the edges

- **Status:** Accepted
- **Date:** 2026-10-22

## Context

Phase 10 asks for dashboards, alerts and tracing, load tests against PROJECT_SPEC section 2, and a
backend that fails fast and recovers on its own when PostgreSQL, Redis or Kafka do not answer.
Building it found that parts of what earlier phases took for granted were not working at all:
- **Metrics:** Prometheus could not scrape them. `/actuator/prometheus` answered 401 by design.
- **Tracing:** no span was ever exported.
- **Async listeners:** they ran on a fallback executor, without the trace.

## Decision

### Actuator on its own port

Actuator moves to a management port, 8090, which no load balancer or ingress publishes.
- **Management port:** Prometheus scrapes it without credentials; a security chain matching only
  that port's requests opens it.
- **Public port:** keeps `/livez` and `/readyz` for the orchestrator, and nothing else of
  actuator.
- **Shared port:** where the two share a port (the tests), metrics stay closed.

The alternative, a password for Prometheus, would be one more credential to rotate, for an
endpoint that should not be reachable from outside in the first place.

### Tracing that works

Three things stood between a booking and one trace across its steps.
- **The tracer itself:** Spring Boot 4 moved tracing's auto-configuration into its own modules.
  With only the bridge and the exporter on the classpath, nothing created a tracer. The
  `spring-boot-starter-opentelemetry` starter does. Its OTLP metrics and log exporters stay off:
  Prometheus scrapes metrics, and logs go to the console.
- **Listener threads:** Boot creates its application executor only when no other `Executor` bean
  exists, and the WebSocket broker defines several. `@ApplicationModuleListener` methods
  therefore ran on Spring's fallback, a new platform thread per event with no context.
  - **Now:** `ListenerExecutorConfig` defines `taskExecutor`: 32 platform threads, with a decorator
    that carries the trace (`BookingTraceIntegrationTest`).
  - **Why not virtual threads:** Boot's own executor would have been virtual. Phase 10 first used
    that, until a CI run saw every listener stall for ten seconds at once. On Java 21 a virtual
    thread blocked in `synchronized` code pins its carrier, and a two-core runner has two.
- **Spans with names:** `spring-modulith-observability-core` adds a span per call into a module.
  A `realtime.publish` span marks the push to a user, and the Kafka template's observation the
  send.

One booking is now one trace: HTTP → booking → the matching listener → the offer's publish → the
Kafka send (`docs/images/booking-trace.png`).

### Every call has a deadline

| Dependency | Before | Now |
|---|---|---|
| Redis command, connect | 60 s (Lettuce default) | 1 s |
| Database connection from the pool | 30 s | 3 s |
| Database statement, lock wait | none | 10 s, 2 s (`statement_timeout`, `lock_timeout`, set by PostgreSQL per connection) |
| Kafka send blocking, delivery | 60 s, 120 s | 5 s, 15 s |
| Mock gateway webhook (HTTP) | none | 5 s connect and read |

- **Exceptions:** migrations and the nightly reconciliation, which reads the whole ledger, lift
  the statement timeout for themselves.
- **When a store does not answer:** the request gets **503 `SERVICE_UNAVAILABLE`** with
  `Retry-After`, not a 500. That includes a transaction that cannot begin because no connection
  came within Hikari's timeout. Spring reports it as `CannotCreateTransactionException`, which
  is not a `DataAccessException`, and the login storm showed it answering 500.
- **Readiness:** it already checked PostgreSQL and Redis. With these timeouts it actually goes
  DOWN within seconds.

### Breakers at the edges

Resilience4j circuit breakers, configured in `application.yml`.
- **`redis-location`, around location ingest's Redis writes.** When it opens, positions are
  dropped at once. They are counted as `unavailable`, not queued behind timeouts, because the
  next one arrives in 3 s anyway.
- **`payment-gateway`, around starting a payment.** When it opens, top-ups answer the gateway's
  own 503 at once.
- **`route-estimator`.** It falls back to the straight-line estimate. Today's estimator *is* the
  straight line and cannot fail; the breaker marks the seam where a remote router would go
  (ADR 0007).

### Proved by breaking things

`./mvnw -Pchaos verify` runs `FullStackChaos` against the containerised stack:
- **Redis paused.** Requests fail within seconds, threads stay flat, readiness goes DOWN, and
  everything recovers on unpause.
- **Kafka stopped for 60 s.** Every event reaches the topic after it returns, and every completed
  trip is charged once.
- **Backend restarted mid-matching.** The waiting trip is assigned.

### What the load tests changed

Each change is measured before and after in `docs/performance.md`.
- **A reconnect does not reset the speed check.** The first load test rejected one position in
  five as a spoof. The script had started each driver at a new random point, a real teleport.
  Exempting a session's first fix, tried first, would let a spoofing app teleport by
  reconnecting, and was reverted. The script now starts each driver at the same point every run.
- **A spoofer is flagged at the threshold, then only at each multiple of it.** Every strike past
  the threshold used to flag again: a transaction and a warning per position. The repeats remain
  because flagging is idempotent and an attempt can fail, for example on a connection timeout.
  A failure is logged and never escapes the STOMP handler.
- **BCrypt runs outside the login and registration transactions.** Held across the hash, a
  connection was lost to everyone else for a tenth of a second per login. Two hundred logins a
  second starved the pool. An unknown phone now gets a password check too, so timing no longer
  reveals which numbers are registered.
- **The connection pool stays at 10.** Thirty made the same storm slower: more transactions at
  once contending inside one small PostgreSQL.
- **History pages after the first stop after a page.** The keyset condition, written as one
  `OR`, was planned as a bitmap scan of the user's whole older history; page 2 of a 5,000-trip
  history read 4,980 rows. With the bound stated on its own it reads 22, from the same index.
- **The WebSocket session gauge counts session ids.** It was a counter decremented on every
  `SessionDisconnectEvent`. Spring may raise that event twice for a session, and raises it for a
  socket whose CONNECT was refused, so after an hour of reconnects it read -89,567.
- **Redis pipelines keep Spring Data's default flush.** Buffering 256 commands per write made the
  live map's snapshot read 2.4 times faster and doubled the location-ingest p99, which is the
  latency that matters. Measured as an A/B, then reverted.

## Consequences

- **A second port to secure.** It must never be published. The compose files, the Dockerfile and
  the runbook say so.
- **The OpenTelemetry starter brings an OTLP metrics registry.** It stays switched off.
- **Chaos tests take minutes** and run only with `-Pchaos`, like the E2E tests with `-Pe2e`.
