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
  `rideflow.trip-events`, keyed by `tripId` so all events for one trip keep
  their order within a partition.

## Conventions

- Events are Java `record`s, named in the past tense: `TripRequested`,
  not `RequestTrip`.
- An event carries **ids and values, never entities**. A listener that needs
  more re-reads it through the owning module's public API.
- An event carries the instant it describes, taken from the injected `Clock`.
- Adding a field is backward compatible; removing or renaming one is not, and
  needs a new event type.

## Catalog

| Event | Published by | Consumed by | Externalized | Phase |
|---|---|---|---|---|
| `DriverStatusChanged` | `driver` | `location` | no | 1 |
| `DriverWentStale` | `location` | `trip` (from Phase 4) | no | 2 |

Each phase appends its rows here.

### DriverStatusChanged

- **Module:** driver
- **Published when:** a driver goes online or offline, is rejected while not
  already offline, or changes vehicle type while on the map.
- **Payload:** driverId, previousStatus, newStatus, vehicleType,
  previousVehicleType, occurredAt
- **Consumed by:** `location`, which adds the driver to or removes them from the
  Redis GEO set for their vehicle type.
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
- **Consumed by:** `trip` from Phase 4, to notice a driver who vanished mid-trip.
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

### Template for a new entry

```
### TripRequested

- **Module:** trip
- **Published when:** a rider's booking is persisted as REQUESTED.
- **Payload:** tripId, riderId, vehicleType, pickup, drop, quotedFarePaise,
  surgeMultiplier, requestedAt
- **Consumed by:** matching (starts the search), surge (counts demand)
- **Externalized:** yes, `rideflow.trip-events`, key `tripId`
- **Ordering:** guaranteed per trip, not across trips
```
