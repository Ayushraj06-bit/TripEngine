# Performance

> **Measured results only.** PROJECT_SPEC section 2 is explicit: never put made-up
> numbers on this page. Every figure below must be reproducible from a k6 script
> in `load-tests/` or a recorded benchmark run, with the date, the commit and the
> hardware it ran on.

## Status

**No measurements have been taken yet.** Phase 0 built the repository scaffold
only — there is no running system to measure. This page is filled in during
Phase 10.

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

_None yet._
