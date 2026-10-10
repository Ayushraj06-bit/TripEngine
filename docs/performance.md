# Performance

> **Measured results only.** PROJECT_SPEC section 2 is explicit: never put made-up
> numbers on this page. Every figure below must be reproducible from a k6 script
> in `load-tests/` or a recorded benchmark run, with the date, the commit and the
> hardware it ran on.

## Status

One benchmark so far: the surge tick (Phase 8), below. The load-test targets
are measured in Phase 10.

## Targets

These are the targets to measure against, copied from PROJECT_SPEC section 2.
They are goals, not results.

| Metric | Target | Measured | Run |
|---|---|---|---|
| Simulated online drivers | 2,000 (≈ 670 location updates/sec) | — | — |
| Location ingest latency (server side) | p95 < 20 ms | — | — |
| Nearby-driver search | p95 < 10 ms | — | — |
| Booking API (`POST /trips`) | p95 < 150 ms | — | — |
| Booking to first offer sent | p95 < 500 ms | — | — |
| Double-assigned drivers | exactly 0 | — | — |
| Lost domain events | exactly 0 (including Kafka down) | — | — |
| Ledger balance | always 0 | — | — |

## How to record a run

Each entry gets its own section below, with:

- **Date and commit** — `git rev-parse --short HEAD` at the time of the run.
- **Hardware** — CPU, cores, RAM, and whether the stack ran on one machine.
- **Topology** — single backend or the two-instance scale profile.
- **Command** — the exact k6 invocation, so it can be repeated.
- **Raw output** — the k6 summary, unedited.
- **Reading** — what the numbers mean, including where a target was missed.

A missed target is recorded as a missed target. It is not rounded, re-run until
it passes, or quietly dropped.

## Runs

### Surge tick, 2026-10-10

- **Date and commit:** 2026-10-10, branch `phase-8-surge` as tagged `phase-8`.
- **Hardware:** Intel Core i7-13650HX (14 cores, 20 threads), 16 GB RAM,
  Windows 11. Redis 7.4 in Docker Desktop through Testcontainers, on the same
  machine.
- **Topology:** one backend, in the integration-test context.
- **Command:** `./mvnw -pl backend test -Dtest=SurgeIntegrationTest`. The test is
  `pricesThreeHundredBusyCellsWellInsideATick`, and the result is in its log
  line "Surge tick over ...".
- **Workload:**
  - 2,000 available drivers spread across Bengaluru, a third in each
    vehicle-type GEO set
  - 300 distinct geohash-6 cells with two requests each
  - 1,112 cells priced: the busy cells and their neighbours

**Raw output:**

```
Surge tick over 300 busy cells, 1112 priced, 2,000 drivers: first 94 ms, then 75 ms
```

**Reading:** one tick takes under 0.5% of its 30-second interval. The first tick
includes loading the Lua scripts. The test fails above 3,000 ms (a tenth of a
tick), which leaves a wide margin for a busier city or a slower Redis.
