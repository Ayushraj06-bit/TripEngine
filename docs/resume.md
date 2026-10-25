# Resume bullets

Four bullets in the form *action + technology + measured result*. Every number is from
[performance.md](performance.md) or the test suite. Where a number was not measured, a placeholder
in square brackets stands in its place rather than a guess.

- **Designed and built a ride-hailing backend** as a Java 21 / Spring Boot 4 modular monolith - 11
  Spring Modulith modules on PostgreSQL, Redis and Kafka - covered by 1,100+ automated tests on real
  infrastructure (Testcontainers), plus end-to-end and chaos suites run nightly in GitHub Actions.
- **Engineered a non-blocking, crash-safe matching engine** on Redis Lua scripts, with partial
  unique indexes in PostgreSQL as the final guard. It held a booking API p95 of 16.6 ms and a
  booking-to-first-offer p95 of 17.8 ms through a burst of 200 bookings in 10 seconds, with 0
  double-assigned drivers across 8,536 assigned trips.
- **Built real-time location ingest** over STOMP/WebSocket, with atomic Redis GEO updates and
  cross-instance fan-out through Redis Pub/Sub. It sustained 2,000 simulated drivers at 666
  updates/s with a p95 of 2.8 ms, and nearby-driver search held a p95 of 2.5 ms through a 30-minute
  soak.
- **Implemented a double-entry ledger and a transactional outbox** (Spring Modulith to Kafka): it
  balanced to 0 paise across 25,176 entries and lost 0 of 76 events with Kafka stopped for 60
  seconds. Load testing found connection-pool starvation under login bursts; the fix cut a
  concurrent request's p95 from 1.02 s to 182 ms.

## Alternatives

- **Packaged the services as hardened container images:** a jlink-trimmed Java runtime, non-root,
  with health checks, at 184 MiB for the backend. A one-command Docker
  Compose demo runs two instances behind a load balancer, and a staged CI/CD pipeline publishes the
  images to the GitHub Container Registry.
- **Hardened the service for failure with timeouts and circuit breakers** (Resilience4j), proven by
  chaos tests: with Redis paused, requests failed in under 5 s with 503 and threads stayed flat; with
  the backend killed mid-matching, a waiting trip was still assigned after a 23.9 s restart.
