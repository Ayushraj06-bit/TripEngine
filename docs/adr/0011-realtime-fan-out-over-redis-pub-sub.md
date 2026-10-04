# 11. Real-time fan-out over Redis Pub/Sub, not a RabbitMQ STOMP relay

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

Riders and drivers hold a STOMP-over-WebSocket session with one backend
instance, whichever nginx's round robin handed them. The message for a user is
produced wherever the cause happens: a driver's position arrives on the
driver's instance, a trip change commits on whichever instance served the
request, an offer is sent by whichever instance's poller claimed the step. The
rider is often connected somewhere else.

Spring's simple in-memory broker only reaches sessions on its own instance. So
something has to carry each message to the instance where its user is
connected. PROJECT_SPEC section 5 names Redis Pub/Sub. This ADR records why,
against the usual alternative.

## Options

**A. Redis Pub/Sub fan-out.** Every message is published to one channel,
`rt:events`. Every instance subscribes. Each one delivers to the users connected
to it and drops the rest. The simple broker stays in each instance.

**B. RabbitMQ STOMP broker relay.** `enableStompBrokerRelay` replaces the
in-memory broker. Every subscription and every message goes through RabbitMQ,
which tracks sessions and routes `/user/` destinations across instances.

| | A. Redis Pub/Sub | B. RabbitMQ relay |
|---|---|---|
| New infrastructure | none: Redis is already here | a RabbitMQ cluster with the STOMP plugin, to run, monitor and upgrade |
| Spec | section 5's choice; nothing to ask | a dependency the spec does not list |
| Cost per message | one `PUBLISH`, then N instances each parse it and most drop it | routed to exactly one instance |
| Ceiling | every instance receives every message: fine for a few instances, wasteful at dozens | scales with the broker |
| Delivery | at most once; a message to an unreachable user is gone | at most once for STOMP subscriptions too, unless durable queues are set up per user |
| Session tracking | per instance, by Spring's `SimpUserRegistry` | in the broker, cluster-wide |
| Failure | Redis down: no real-time messages, the REST API still answers | RabbitMQ down: no WebSocket subscriptions at all |

## Decision

**Option A.** At RideFlow's scale - a handful of instances - broadcasting every
message to every instance costs nothing that matters, and it needs no new
system. The relay solves a problem we do not have yet and adds one we would
have every day: another stateful cluster.

How it works:

- `RealtimePublisher.toUser(userId, destination, message)` is the one way any
  module sends a real-time message.
- It wraps the message in an envelope, `{type, tripId, seq, sentAt, payload}`,
  serialises it once, and publishes `{userId, destination, envelope}` to
  `rt:events`.
- On every instance, `LocalDelivery` receives it, asks `SimpUserRegistry`
  whether the user has a session here, and if so calls `convertAndSendToUser`.
  Spring delivers to every session of that user, so a phone and a laptop both
  receive. If the user has no session here, the message is dropped. That is
  normal: it is being delivered somewhere else, or nowhere.
- Messages about a trip carry `seq`, from `INCR trip:seq:{tripId}` (24 h TTL,
  set in the same Lua script). Redis numbers them, not an instance, so they are
  strictly increasing even when two instances send for the same trip. Pub/Sub
  does not guarantee order across publishers, and a client may reconnect and
  re-read. A client keeps the highest `seq` it has seen per trip and ignores
  anything at or below it.
- Messages not about a trip (offers) carry `seq: null`. An offer has its own id,
  and the driver acts on it at most once anyway.

### Real-time is a hint, the REST API is the truth

Pub/Sub keeps nothing. A message published while the user is disconnected, or
while their instance is restarting, is lost, and nothing tries again. That is
acceptable because every message only reflects state the API already serves:

- `GET /api/v1/trips/active` returns the current trip after a reconnect.
- `GET /api/v1/offers/current` returns the open offer.
- A missed position is replaced by the next one, two seconds later.

A client re-reads those when it connects. It never relies on having seen every
message.

### Smaller decisions

- **No hand-written session tracker.** `SimpUserRegistry` already follows
  connect and disconnect events per instance, keeps several sessions per user,
  and is what `convertAndSendToUser` itself consults. A second copy could only
  disagree with it.
- **A user can subscribe only to their own queues.** `/user/queue/...` resolves
  to the authenticated principal. An explicit `/user/{someoneElse}/...` is
  refused at SUBSCRIBE (Phase 2's interceptor).
- **Sessions end with their token.** The access token is checked once, at
  CONNECT. Every 15 s, each instance closes sessions whose token has expired, with
  close status 1008. The client refreshes its token and reconnects.

## Consequences

- One Redis round trip (`INCR`) is added per trip message, and one `PUBLISH`
  per message. The location update's own hot path is unchanged for available
  drivers. Forwarding happens only for drivers on a trip, and at most every 2 s.
- With N instances, every message is parsed N times. If the cluster grows to
  dozens of instances, the next step is a channel per instance and a
  `user -> instance` map in Redis, or the relay. That decision can wait for
  numbers.
- Losing Redis stops real-time delivery but not the product. Bookings, matching
  recovery and the REST reads all still work.
- `infra/docker-compose.scale.yml` runs two instances behind nginx with plain
  round robin and no sticky sessions. The runbook shows how to watch a message
  cross from one to the other.
