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
| `DriverStatusChanged` | `driver` | `location` (from Phase 2) | no | 1 |

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
