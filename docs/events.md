# Event catalog

Every domain event in RideFlow is listed here. PROJECT_SPEC section 13 makes
updating this page part of the definition of done for any phase that adds or
changes an event.

## How events work

- A module publishes with `ApplicationEventPublisher` **inside the transaction**
  that makes the state change.
- Listeners are annotated `@ApplicationModuleListener` — asynchronous, and run
  after the publishing transaction commits.
- The Spring Modulith **event publication registry** writes every event to
  `event_publication` in that same transaction. That is our transactional
  outbox: if a listener throws, or Kafka is unavailable, the event stays
  incomplete and is retried. Nothing is lost.
- Trip and payment events are **externalized to Kafka** on topic
  `rideflow.trip-events`, keyed by `tripId`, so all events for one trip land on
  one partition. The value is the event as JSON.

## What a Kafka consumer can rely on

- **At least once.** The outbox retries until the send succeeds, so an event can
  arrive twice. Every trip event carries a unique `eventId`: de-duplicate on it.
- **Per-trip order, almost always.** One trip's changes are separate
  transactions that commit one after another, and each is sent after its commit.
  But the sends are asynchronous, so two changes committed within a few
  milliseconds of each other can in principle be sent in the opposite order.
  Order by `occurredAt` when it matters rather than by arrival.
- **Never an OTP.** Events are stored in the database and copied to Kafka; the
  trip OTP is in none of them.

## Conventions

- Events are Java `record`s, named in the past tense: `TripRequested`,
  not `RequestTrip`.
- An event carries **ids and values, never entities**. A listener that needs
  more re-reads it through the owning module's public API.
- An event carries the instant it describes, taken from the injected `Clock`.
- Trip events carry plain fields (`pickupLat`, `quotedFarePaise`) rather than
  value objects, so their JSON is a stable contract for external consumers.
- Adding a field is backward compatible; removing or renaming one is not, and
  needs a new event type.

## Catalog

| Event | Published by | Consumed by | Externalized | Phase |
|---|---|---|---|---|
| `DriverStatusChanged` | `driver` | `location`, `matching` | no | 1 |
| `DriverWentStale` | `location` | `matching` | no | 2 |
| `TripRequested` | `trip` | `matching`, Kafka | yes | 4 |
| `TripReturnedToMatching` | `trip` | `driver`, `matching`, Kafka | yes | 4 |
| `DriverAssigned` | `trip` | `driver`, Kafka | yes | 4 |
| `DriverArrived` | `trip` | Kafka | yes | 4 |
| `TripStarted` | `trip` | Kafka | yes | 4 |
| `TripCompleted` | `trip` | `driver`; payment (Phase 7), Kafka | yes | 4 |
| `TripCancelled` | `trip` | `driver`, `matching`; payment (Phase 7), Kafka | yes | 4 |
| `TripNoDriverFound` | `trip` | Kafka | yes | 4 |

Each phase appends its rows here.

### DriverStatusChanged

- **Module:** driver
- **Published when:** a driver goes online or offline, is rejected while not
  already offline, changes vehicle type while on the map, or - from Phase 4 -
  starts or ends a trip.
- **Payload:** driverId, previousStatus, newStatus, vehicleType,
  previousVehicleType, occurredAt
- **Consumed by:** `location`, which adds the driver to or removes them from the
  Redis GEO set for their vehicle type; `matching`, which treats a driver going
  OFFLINE while holding an offer as having rejected it.
- **Externalized:** no. PROJECT_SPEC section 5 puts only trip and payment events
  on a Kafka topic.
- **Ordering:** not guaranteed across drivers. Per driver it follows the
  publishing transactions, which are serialised by the row lock on `drivers`.
- **Why `previousVehicleType`:** without it a listener cannot know which GEO set
  to remove the driver from when they swap vehicles, so the driver would linger
  in the old type's set and be offered the wrong kind of trip. On a status-only
  change it equals `vehicleType`.

### DriverWentStale

- **Module:** location
- **Published when:** the stale sweep finds a driver whose last ping is older
  than `stale-after-seconds`, and has already removed them from every
  availability set.
- **Payload:** driverId, lastSeenAt, detectedAt
- **Consumed by:** `matching` (Phase 5): a driver who goes stale while holding
  an offer is treated as having rejected it, at once, rather than after the full
  timeout. Phase 2 had planned for `trip` to consume it, but `trip` cannot depend
  on `location` without closing a module cycle.
- **Externalized:** no.
- **Published once per cluster:** the sweep holds a ShedLock, so two instances
  cannot both declare the same driver gone.
- **Published inside a transaction, on purpose.** Every
  `@ApplicationModuleListener` is a transactional event listener, and Spring
  skips those entirely for an event published with no transaction active. The
  sweep first published bare, and the event reached nobody; a test with a real
  module listener caught it. The Redis sweep itself stays outside the
  transaction, which holds only the outbox rows.
- **Not the same as going offline:** the driver's stored status is untouched, so
  their next ping restores them without a round trip through `driver`.

### Trip events

All eight share a sealed interface, `TripEvent`, and three fields:

- `eventId` - unique per event, for de-duplication
- `occurredAt` - the instant of the change, from the injected `Clock`
- `tripId` - also the Kafka key

Each one is published by `TripService` together with exactly one
`trip_events` audit row, in the transaction that made the change.

#### TripRequested

- **Published when:** a booking is saved as REQUESTED.
- **Payload:** riderId, vehicleType, pickupLat, pickupLng, dropLat, dropLng,
  quotedFarePaise, surgeMultiplier, paymentMethod
- **Consumed by:** `matching`, which starts looking for a driver - reading the
  trip from the database, so a late duplicate for a trip since assigned does
  nothing. Surge (Phase 8) may count it as demand.

#### TripReturnedToMatching

- **Published when:** the assigned driver cancels before arriving. The trip goes
  back to REQUESTED instead of ending (PROJECT_SPEC section 6).
- **Payload:** riderId, previousDriverId, vehicleType, pickupLat, pickupLng
- **Consumed by:** `driver`, which moves the previous driver back to AVAILABLE
  and counts the back-out against their acceptance rate; `matching`, which
  restarts at once, with a fresh deadline, never offering the trip back to the
  driver who left it.

#### DriverAssigned

- **Published when:** matching assigns a driver: REQUESTED to DRIVER_ASSIGNED.
- **Payload:** riderId, driverId. Deliberately not the OTP.
- **Consumed by:** `driver`, which moves the driver to ON_TRIP. `location`
  follows through `DriverStatusChanged`, taking them off the map.
- **How `driver` uses trip events:** as a prompt to ask `trip` whether the
  driver has an active trip now, never as the answer itself. That keeps the
  driver's status right even when events are handled out of order.

#### DriverArrived

- **Published when:** the driver arrives inside `arrival-geofence-meters` of
  the pickup point.
- **Payload:** riderId, driverId

#### TripStarted

- **Published when:** the driver enters the right OTP.
- **Payload:** riderId, driverId

#### TripCompleted

- **Published when:** the driver completes the trip.
- **Payload:** riderId, driverId, finalFarePaise, paymentMethod. Until Phase 6
  adds repricing, the final fare is the quoted fare.
- **Consumed by:** `driver` (back to AVAILABLE); payment (Phase 7) charges it,
  idempotently by trip id.

#### TripCancelled

- **Published when:** the rider cancels, or the driver declares a no-show after
  `no-show-after-minutes`.
- **Payload:** riderId, driverId (null if none was assigned), cancelledBy,
  previousStatus, cancellationFeePaise (zero when free), paymentMethod
- **Consumed by:** `driver` (frees the driver, if there was one); `matching`,
  which withdraws the offer that was out if the trip was still waiting, and tells
  that driver; payment (Phase 7) charges a non-zero fee.

#### TripNoDriverFound

- **Published when:** matching's deadline passes with no driver: REQUESTED to
  NO_DRIVER_FOUND.
- **Payload:** riderId
