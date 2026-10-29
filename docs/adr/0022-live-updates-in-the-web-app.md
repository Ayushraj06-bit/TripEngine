# 22. Live updates in the web app: a small STOMP client, REST as the source of truth

- **Status:** Accepted
- **Date:** 2026-10-30

## Context

From Stage 4 the web app has to react while it is open: a rider waits for a driver to accept, a
driver gets offers that last twelve seconds, and the driver's position has to reach the backend
as they move. The backend speaks STOMP over a plain WebSocket at `/ws` (ADR 0015). It pushes a
trip's changes to `/user/queue/trip-updates` and offers to `/user/queue/offers`, and takes a
driver's position at `/app/driver/location`. Every push is best effort: the backend's own
contract is that a client resyncs over REST after connecting.

## Decision

### A small STOMP client of our own

The app needs a small part of STOMP 1.2: CONNECT with the access token, SUBSCRIBE to its own
queues, SEND a position, and heartbeats. That is about 150 lines (`realtime/stomp.ts`), tested
frame by frame, so no new dependency was added. Reconnecting lives one level up (`realtime/live.ts`):

- **One connection while someone is signed in**, opened and closed with the session.
- **Reconnects after a drop**, waiting 1 s, 2 s, 4 s, 8 s, then 15 s at most, each a little
  randomised so a server restart is not met by every client at once.
- **A refused CONNECT** (a token that ran out while the connection was down) renews the session
  first, through the same single-flight renewal REST uses, so the two never renew at once.
- **Subscriptions are restored** after every reconnect.
- **A "Reconnecting…" notice** shows while the connection is down.

### The server stays the source of truth

- **Each trip update is the whole trip** as the server has it. It replaces what is shown, so
  applying one twice is harmless.
- **Each carries a `seq`.** The app keeps the highest it has applied per trip and drops anything
  at or below it: a duplicate, or a state already overtaken.
- **REST is read again** on opening a screen, on returning to the tab, and on every reconnect: the
  trip (`GET /trips/{id}`), and for drivers the offer (`GET /offers/current`). A message missed
  while the connection was down costs a moment, never a wrong screen.

### A driver's position

- **Sent as the driver moves**, at most every 2 s (the backend ignores anything faster), and at
  least every 10 s while they stand still, checked once a second. The backend counts 30 s of
  silence as gone.
- **A fix rougher than the backend's 100 m** is held back, and the screen says so.
- **Location refused, unavailable, or a connection down** is said in words. The screen never
  claims to be sharing while it is not.
- **The position's `seq` starts again on each connection**, as the backend expects.

### Offers and cancelling

- **An offer shows** the rounded pickup's distance from the driver, the estimated fare, and a
  countdown on the server's clock (ADR 0021). Accepting it opens the trip.
- **An offer that runs out, is withdrawn or is accepted too late** says so, and makes room for
  the next.
- **Cancelling is free while a driver is being found, and for two minutes after one is
  assigned.** The screen counts that time down. After it, the app asks first, and shows the fee
  the backend charged.

## Consequences

- **No STOMP library to keep up to date**, but the client is ours to maintain. If the app ever
  needs more of STOMP (transactions, receipts), `@stomp/stompjs` is the replacement.
- **The app, the API and `/ws` must share one origin.** The backend accepts WebSockets from its
  own origin only (`rideflow.web.allowed-origins` adds others). The Vite dev server rewrites the
  Origin header for this reason.
- **Stage 6 adds the driver's live position on the rider's map** over the same connection.
