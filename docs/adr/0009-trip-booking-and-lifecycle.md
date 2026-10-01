# 9. Trip booking and lifecycle

- **Status:** Accepted
- **Date:** 2026-10-01

## Context

Phase 4 turns a quote into a trip and takes it through PROJECT_SPEC section 6's
state machine. Three things make that harder than it looks: retries (a client
that times out books again), races (a rider cancels while the driver arrives),
and a secret (the OTP) that must reach exactly one person.

## Decisions

### 1. Claim the Idempotency-Key before doing anything

The Phase 4 brief says to look the key up (read only), then consume the quote,
then insert the key with the trip. Two concurrent requests with one key both
find nothing in that first read, and both try to consume the quote. Only one can
get it, so the other answers `410 QUOTE_EXPIRED` - to a booking that in fact
succeeded. A client retrying a timed-out request would be told its booking
failed.

Instead the key is **claimed first**, with `INSERT ... ON CONFLICT DO NOTHING`
in its own short transaction. Exactly one request inserts the row and does the
work. Any other request with that key sees the row and gets one of three
answers:

- the stored response, if the first request finished with the same body;
- `422 IDEMPOTENCY_KEY_REUSED`, if the body differs;
- `409 IDEMPOTENCY_KEY_IN_FLIGHT` with `Retry-After: 1`, if the first request
  is still running.

The response - 2xx or 4xx - is stored with the key, the 2xx inside the same
transaction as the trip. A 5xx releases the key instead, because a failure that
was never the client's fault should not be replayed at them.

The "same request" is a SHA-256 of the endpoint and the parsed body, so JSON
field order or whitespace cannot make one request look like two.

**Known gap:** a process that dies between claiming a key and answering leaves
the key in flight until the 24-hour purge, and the consumed quote is lost.
Retries with that key are refused until then. Taking over claims older than any
request can take would fix it, and is not worth the complexity until it is seen.

### 2. The database decides "one active trip"

`trips_one_active_per_rider` and `trips_one_active_per_driver` are partial
unique indexes, and they are the only real guard. A check in code first would
race: twenty concurrent bookings would all see no active trip. The trip module
translates a violation **by index name** into `409 ACTIVE_TRIP_EXISTS` or
`409 DRIVER_HAS_ACTIVE_TRIP`, rather than leaving it to the generic
constraint-violation handler, so the client learns which rule it hit.

### 3. The OTP is stored as plaintext, and lives briefly

A hash was considered and rejected. The rider must be able to read the OTP again
after reconnecting (`GET /trips/active`), which a hash cannot give back, and a
four-digit code has 10,000 values: anyone holding the database could reverse a
hash of it instantly anyway.

What protects it instead:

- It exists only between assignment and start. A database `CHECK` rejects an OTP
  on a trip in any other state, so no code path can leave one behind.
- Only the rider's own view contains it. The driver's view, every event and
  every audit row leave it out, and `TripView.toString()` omits it in case a
  view is ever logged.
- It is compared in constant time, and after `otp-max-attempts` wrong codes it
  locks for `otp-lock-minutes` - rejecting even the right code - which caps
  guessing at three tries every two minutes.

If a database leak ever needs covering, encrypting the column with AES-GCM and
a server-held key is the upgrade; hashing is not.

### 4. A wrong OTP is counted in a transaction that commits

The attempt counter is updated, the transaction commits, and only then is the
wrong code rejected. Rejecting it from inside the transaction would roll the
counter back with it, and the lock would never engage - the trap Phase 1's
account lockout fell into.

### 5. Concurrent changes: tell users, retry for the system

Every trip has an optimistic-lock `version`. When two changes race, the loser's
write fails at commit.

- **User actions** answer `409 TRIP_CONCURRENT_UPDATE` with the state the trip
  is now in, so the app can redraw rather than guess.
- **System transitions** (assigning a driver, giving up on finding one) are
  idempotent and reload the trip, so they retry once: the retry either finds its
  work already done or is refused by the state machine.
- Cancelling an already-cancelled trip succeeds and changes nothing, so a retried
  cancel is not an error.

### 6. `trip` owns the position port; `driver` follows trips by events

`driver` reacts to trip events (ON_TRIP on assignment, AVAILABLE when the trip
ends) and `location` reacts to driver events. If `trip` also depended on
`location` for the driver's position, the three would form a cycle, which Spring
Modulith's verification rejects. So `trip` declares the small interface it needs,
`DriverPositions`, and `location` implements it. Later phases point the same way:
recording a trip's route will make `location` listen to trip events.

A driver's status follows their trip a moment after the trip commits. The
partial unique index, not the driver's status, is what stops a driver holding
two trips in that moment.

**The status is derived, not replayed.** Each trip event only prompts `driver`
to ask `trip` whether the driver has an active trip right now
(`DriverTrips.hasActiveTrip`) and to set ON_TRIP or AVAILABLE from the answer.
Acting on the events themselves breaks when they are handled out of order, which
asynchronous delivery allows: a `DriverAssigned` handled after its trip had
already been handed back would leave the driver ON_TRIP with no trip - unable to
go offline, never offered work again. Reading the current state makes every
handler converge on the right status, in any order. The review of this phase
caught it; a test pins it.

### 7. Smaller calls

- **The booking body includes `vehicleType`.** The brief lists `quoteId` and
  `paymentMethod`. The type is redundant with the quote on purpose: Phase 3's
  mismatch check needs something to compare, and a client sending the wrong
  quote id for the type on screen gets a 422 instead of the wrong vehicle.
- **The arrival geofence reads the position before the transaction opens**, as a
  Redis call must (PROJECT_SPEC section 11). A position older than
  `stale-after-seconds` counts as unknown, `422 POSITION_UNKNOWN`: a stale fix
  inside the geofence is not an arrival.
- **`trip_events` is append-only in the database**: a trigger rejects any update
  or delete. An audit log that can be edited proves nothing.
- **History uses a keyset cursor** on `(requested_at, id)`, so every page is an
  index range scan however deep the rider scrolls.

## Consequences

- A booking costs three short database round trips before the main transaction:
  claiming the key, and on the way out storing or releasing it. Cheap next to
  the 150 ms budget, and the price of never double-booking.
- Kafka consumers get at-least-once delivery and must de-duplicate by `eventId`
  (see `docs/events.md`).
- The OTP is readable by anyone with database access while a trip is waiting to
  start. Accepted for now, with AES-GCM as the recorded upgrade path.
