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
| `TripRequested` | `trip` | `matching`, `trip` (push), `location`, Kafka | yes | 4 |
| `TripReturnedToMatching` | `trip` | `driver`, `matching`, `trip` (push), `location`, Kafka | yes | 4 |
| `DriverAssigned` | `trip` | `driver`, `trip` (push), `location`, Kafka | yes | 4 |
| `DriverArrived` | `trip` | `trip` (push), `location`, Kafka | yes | 4 |
| `TripStarted` | `trip` | `trip` (push), `location`, Kafka | yes | 4 |
| `TripCompleted` | `trip` | `driver`, `trip` (push), `location`; payment (Phase 7), Kafka | yes | 4 |
| `TripCancelled` | `trip` | `driver`, `matching`, `trip` (push), `location`; payment (Phase 7), Kafka | yes | 4 |
| `TripNoDriverFound` | `trip` | `trip` (push), `location`, Kafka | yes | 4 |

Each phase appends its rows here. From Phase 6, every trip event also has two
consumers that react to it the same way whatever its type. They are described
once, under [Trip events](#trip-events), rather than eight times.

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
- **The rider is told by the sweep itself** (Phase 6), not by a listener. For a
  stale driver who is on a trip, the sweep sends the rider
  `DRIVER_CONNECTION_LOST` on `/user/queue/trip-updates`, using the trip context
  it already holds in Redis. The trip is not cancelled. If the driver stays
  silent through the ride, `trip`'s own scheduled check flags the trip after
  `driver-silence-flag-minutes` for an admin to review.
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

Every trip event, whatever its type, also goes to two listeners (Phase 6):

- **`trip`'s `TripUpdatePusher`** sends the rider and the current driver each
  their own view of the trip, as it is now, on `/user/queue/trip-updates`. The
  message type is the event's name. The payload is the same as
  `GET /api/v1/trips/{id}` would return to that person, so the OTP appears only
  in the rider's copy. A driver who backed out is no longer on the trip and gets
  nothing. Because the payload is a state, not a change, a repeat is harmless.
  The envelope's `seq`, reserved before the trip is read, lets the client drop
  anything older than what it already shows on that destination ([ADR 0011](adr/0011-realtime-fan-out-over-redis-pub-sub.md)).
- **`location`'s `TripContextListener`** finds the trip's driver in the event and
  asks `trip` for that driver's active trip, now. It stores the answer in the
  driver's Redis state: the rider to show the driver's position to, the point
  they are heading for (the pickup until the trip starts, then the drop), and
  whether to record the route. Nothing is taken from the event itself, so events
  handled out of order still leave the right answer, as with `driver` in Phase 4.

Both run without a surrounding transaction: one database read, then Redis.

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
- **Payload:** riderId, driverId, finalFarePaise, paymentMethod. From Phase 6
  the final fare is settled at completion: the quoted fare, or a reprice for the
  route actually driven, plus any waiting charge (PROJECT_SPEC section 8).
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

## Real-time messages

Not domain events: these are messages to a person's WebSocket session, sent
through `notification`'s `RealtimePublisher` and fanned out across instances
over Redis Pub/Sub ([ADR 0011](adr/0011-realtime-fan-out-over-redis-pub-sub.md)).
They are delivered at most once. A client that reconnects re-reads the REST API
rather than expecting to have seen every message.

Every message has the same envelope:

```json
{"type": "TripStarted", "tripId": "...", "seq": 7, "sentAt": "2026-10-03T10:15:00Z", "payload": {}}
```

`seq` increases strictly per trip, across instances. It is null for messages
that are not about a trip.

| Destination | Type | Sent by | To | Payload |
|---|---|---|---|---|
| `/user/queue/offers` | `OFFER` | `matching` | the driver offered a trip | the offer |
| `/user/queue/offers` | `OFFER_WITHDRAWN` | `matching` | that driver, once the offer is gone | offerId |
| `/user/queue/trip-updates` | each trip event's name | `trip` | the rider and the current driver | the recipient's view of the trip |
| `/user/queue/trip-updates` | `DRIVER_CONNECTION_LOST` | `location` | the rider | driverId, lastSeenAt, message |
| `/user/queue/driver-location` | `DRIVER_LOCATION` | `location` | the rider | lat, lng, phase (`TO_PICKUP` or `TO_DROP`), etaSeconds |

`DRIVER_LOCATION` is sent at most every `driver-location-forward-ms` (2 s) per
driver, and only while the driver has an active trip.
