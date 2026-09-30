# 7. Route estimation: haversine now, a real router later behind a circuit breaker

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

A quote needs a distance and a duration before any driver is involved: the fare
formula bills per kilometre and per minute, and the trip-length limits (300 m to
60 km, PROJECT_SPEC section 9) are checked against the same distance.

There are two ways to get them.

**A routing engine** such as OSRM gives real road distance and a duration that
knows about one-way streets and rivers. It is also an external service: it can be
slow, it can be down, and it needs map data and a server that the compose stack
does not have today.

**Geometry.** The great-circle (haversine) distance, stretched by the section 9
`road-factor` of 1.3 because roads are never straight, with the duration taken
from an average city speed. It needs nothing, costs microseconds and cannot fail.

## Decision

Pricing depends on a `RouteEstimator` interface, not on either answer:

```java
RouteEstimate estimate(GeoPoint from, GeoPoint to, VehicleType type);
```

The only implementation today is `HaversineRouteEstimator`: distance is the
haversine distance times `road-factor`, and duration is that distance at
`average-speed-kmph` for the vehicle type (bike 25, auto 20, car 22), rounded up
to the second.

When a router is added, it becomes a second implementation:

- `OsrmRouteEstimator`, calling `GET /route/v1/driving/{lng},{lat};{lng},{lat}?overview=false`.
- Wrapped in a Resilience4j `TimeLimiter` (a few hundred milliseconds; a quote
  must not wait on a slow router) and a `CircuitBreaker`, so a failing router
  stops being called at all instead of adding its timeout to every quote.
- Falling back to `HaversineRouteEstimator` on a timeout, an error or an open
  circuit. The fallback is why the haversine implementation stays even after the
  router arrives: it is the one estimate that is always available.
- Chosen by a property, so the router can be switched off without a deploy.

Resilience4j is already in the PROJECT_SPEC section 3 stack, so this adds no
dependency the spec does not list.

## Consequences

- Estimates are rough. Locally the road factor can be badly wrong: across a river
  with one bridge, a trip can be twice its straight-line distance. The rider is
  protected by repricing the finished trip against the actual route (Phase 6,
  `reprice-deviation`), which caps what a bad estimate can cost them.
- Duration ignores traffic, because city averages cannot see a rush hour. Surge is
  the lever for demand; duration is not trying to be one.
- The trip-length limits move when the router arrives, because they are checked
  against the estimated road distance. That is intended: it is the distance the
  rider is billed for.
- Numeric care, found by a test: duration is computed as
  `distance x 3600 / (speed x 1000)`, multiplying before dividing. Dividing by a
  pre-computed 6.111... m/s put 11 km at 22 km/h a hair over 1,800 s, and rounding
  up made it 1,801.
