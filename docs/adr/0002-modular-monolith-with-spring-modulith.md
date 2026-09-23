# 2. Modular monolith with Spring Modulith

- **Status:** Accepted
- **Date:** 2026-09-23

## Context

RideFlow has eleven bounded areas (PROJECT_SPEC section 5), several of which
have genuinely different runtime profiles: `location` absorbs roughly 670 writes
per second at 2,000 simulated drivers, while `payment` is low-volume and needs
strict transactional correctness.

Microservices would let those scale separately, at the cost of distributed
transactions, network failure modes and an operational burden out of proportion
to a system that has to be demonstrable on one laptop.

A single unstructured application would be simple to run, but nothing would stop
`payment` reaching into `matching` internals, and the option to split later
would quietly disappear.

## Decision

Build one deployable backend, split into Spring Modulith application modules
with enforced boundaries.

- Each module exposes only its top-level package. `internal`, `persistence` and
  `web` packages are private to the module.
- Modules communicate through domain events rather than direct calls wherever
  the interaction is not a synchronous query.
- `ApplicationModules.of(RideFlowApplication.class).verify()` runs as a test
  (`ModularityTests`), so a boundary violation fails the build rather than
  review.
- Events are published in the same transaction as the state change. The Modulith
  event publication registry persists them, giving a transactional outbox: no
  event is lost if a listener or Kafka fails.

## Consequences

- Boundaries are checked mechanically and continuously, not by convention.
- Extracting a module later is a mechanical job: its events already cross a
  real boundary, so the call sites do not change shape.
- The whole system still runs with one `java -jar`, which keeps the simulator
  and the load tests honest.
- We pay for it in indirection: an event round trip is harder to follow in a
  debugger than a method call, and the event catalog (`docs/events.md`) has to
  be kept current or the flow becomes hard to reason about.
- One module misbehaving (a memory leak, a hot loop) still affects the whole
  process. Module-level isolation is logical, not physical.
