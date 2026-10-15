package com.rideflow.simulator;

import com.rideflow.simulator.BackendApi.ApiException;
import com.rideflow.simulator.Geo.Point;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;

/**
 * A driver's app: roams between random waypoints reporting its position every tick, answers offers,
 * and drives each trip it takes from pickup to drop.
 *
 * <p>Like a careless real driver, it sometimes ignores an offer until it times out, cancels a trip
 * it accepted, loses its connection, or reports a position its GPS made up.
 */
final class SimulatedDriver extends Actor {

  static final String LOCATION = "/app/driver/location";

  /** Far enough in one tick that no car could have driven it: the backend's spoof check fires. */
  static final double SPOOF_JUMP_METERS = 3_000;

  enum Phase {
    CRUISING,
    TO_PICKUP,
    AT_PICKUP,
    TO_DROP
  }

  enum Decision {
    ACCEPT,
    REJECT,
    IGNORE
  }

  private final Movement movement;
  private Phase phase = Phase.CRUISING;
  private UUID tripId;
  private Point drop;
  private boolean cancelPlanned;
  private UUID pendingOffer;
  private boolean online;
  private long seq;

  SimulatedDriver(World world, UserSession session, int index) {
    super(world, session, "driver-" + index);
    this.movement = new Movement(Geo.anywhereIn(world.properties().serviceArea(), random()));
    movement.headFor(randomWaypoint(), world.speedKmph(random()));
  }

  @Override
  List<String> destinations() {
    return List.of("/user/queue/offers", "/user/queue/trip-updates");
  }

  /** A new session numbers its positions from zero, as the backend expects of an app. */
  @Override
  void afterConnect() {
    seq = 0;
    report();
    if (!online) {
      try {
        world.api().goOnline(session);
      } catch (ApiException e) {
        // DRIVER_ON_TRIP after a restart mid-trip: the resync picks the trip up.
        if (!"DRIVER_ON_TRIP".equals(e.code())) {
          throw e;
        }
      }
      online = true;
    }
  }

  @Override
  void tick() {
    if (phase == Phase.TO_PICKUP && cancelPlanned) {
      cancelTrip();
      return;
    }
    boolean reached = movement.advance(world.properties().tick());
    report();
    if (reached) {
      onReached();
    }
  }

  @Override
  void onMessage(String destination, JsonNode envelope) {
    JsonNode payload = envelope.get("payload");
    switch (envelope.get("type").asString()) {
      case "OFFER" -> onOffer(payload);
      case "OFFER_WITHDRAWN" -> {
        if (uuid(payload, "offerId").equals(pendingOffer)) {
          pendingOffer = null;
        }
      }
      case "TripCancelled" -> {
        if (uuid(payload, "id").equals(tripId)) {
          endTrip();
        }
      }
      case "DriverAssigned" -> {
        // Normally the accept's own answer got here first. If that answer was lost on the way,
        // this is how the app learns the trip is its.
        if (phase == Phase.CRUISING) {
          adopt(payload);
        }
      }
      default -> {
        // Other trip updates: this app drives its trip itself, and resyncs when unsure.
      }
    }
  }

  /** The trip and the offer the backend holds for this driver, whatever was missed meanwhile. */
  @Override
  void resync() {
    var active = world.api().activeTrip(session);
    if (active.isPresent()) {
      adopt(active.get());
    } else if (tripId != null) {
      endTrip();
    }
    if (phase == Phase.CRUISING) {
      world.api().currentOffer(session).ifPresent(this::onOffer);
    }
  }

  // --- Offers ------------------------------------------------------------------------------------

  private void onOffer(JsonNode offer) {
    UUID offerId = uuid(offer, "offerId");
    if (phase != Phase.CRUISING || offerId.equals(pendingOffer)) {
      return;
    }
    pendingOffer = offerId;
    Instant received = world.clock().instant();
    var behaviour = world.properties().driver();
    Decision decision = decide(random().nextDouble(), behaviour.acceptProbability());
    later(
        World.within(behaviour.responseDelay(), random()),
        () -> respond(offerId, decision, received));
  }

  /** Of the offers not accepted, half are turned down and half left to time out. */
  static Decision decide(double roll, double acceptProbability) {
    if (roll < acceptProbability) {
      return Decision.ACCEPT;
    }
    return roll < (1 + acceptProbability) / 2 ? Decision.REJECT : Decision.IGNORE;
  }

  private void respond(UUID offerId, Decision decision, Instant received) {
    if (!offerId.equals(pendingOffer) || phase != Phase.CRUISING) {
      // Withdrawn by the rider, or overtaken by a resync.
      return;
    }
    pendingOffer = null;
    world
        .meters()
        .counter("simulator.offers", "decision", decision.name().toLowerCase(Locale.ROOT))
        .increment();
    if (decision == Decision.IGNORE) {
      return;
    }
    world
        .meters()
        .timer("simulator.offer.response", "decision", decision.name().toLowerCase(Locale.ROOT))
        .record(world.clock().millis() - received.toEpochMilli(), TimeUnit.MILLISECONDS);
    if (decision == Decision.REJECT) {
      world.api().rejectOffer(session, offerId);
      return;
    }
    UUID accepted = uuid(world.api().acceptOffer(session, offerId), "tripId");
    adopt(world.api().trip(session, accepted));
    cancelPlanned = random().nextDouble() < world.properties().driver().cancelProbability();
  }

  // --- The trip ----------------------------------------------------------------------------------

  /** Takes up a trip where the backend says it stands. */
  private void adopt(JsonNode trip) {
    tripId = uuid(trip, "id");
    drop = point(trip.get("drop"));
    switch (trip.get("status").asString()) {
      case "DRIVER_ASSIGNED" -> {
        phase = Phase.TO_PICKUP;
        movement.headFor(point(trip.get("pickup")), world.speedKmph(random()));
      }
      case "DRIVER_ARRIVED" -> {
        phase = Phase.AT_PICKUP;
        movement.headFor(movement.position(), 0);
      }
      case "IN_PROGRESS" -> {
        phase = Phase.TO_DROP;
        movement.headFor(drop, world.speedKmph(random()));
      }
      default -> endTrip();
    }
  }

  /** At the waypoint: what comes next depends on where in the trip the driver is. */
  private void onReached() {
    try {
      switch (phase) {
        case CRUISING -> movement.headFor(randomWaypoint(), world.speedKmph(random()));
        case TO_PICKUP -> {
          world.api().arrive(session, tripId);
          phase = Phase.AT_PICKUP;
        }
        case AT_PICKUP -> {
          // The rider reads the code out; until then, the driver waits.
          String otp = world.otps().get(tripId);
          if (otp != null) {
            world.api().start(session, tripId, otp);
            phase = Phase.TO_DROP;
            movement.headFor(drop, world.speedKmph(random()));
          }
        }
        case TO_DROP -> {
          UUID finished = tripId;
          world.api().complete(session, finished);
          endTrip();
          world.api().rate(session, finished, 3 + random().nextInt(3));
        }
      }
    } catch (ApiException e) {
      if (e.status() == 409 || e.status() == 404) {
        // The trip moved without us - the rider cancelled, say. Ask what it is now.
        resync();
      } else if (e.status() != 422) {
        // 422 is the arrival geofence ahead of the last position: next tick tries again.
        throw e;
      }
    }
  }

  private void cancelTrip() {
    cancelPlanned = false;
    try {
      world.api().cancel(session, tripId, "Simulated driver changed their mind");
      world.meters().counter("simulator.driver.cancels").increment();
      endTrip();
    } catch (ApiException e) {
      resync();
    }
  }

  private void endTrip() {
    tripId = null;
    drop = null;
    cancelPlanned = false;
    phase = Phase.CRUISING;
    movement.headFor(randomWaypoint(), world.speedKmph(random()));
  }

  // --- Positions ---------------------------------------------------------------------------------

  /** The position every tick: the GPS's view of the truth, or now and then a made-up one. */
  private void report() {
    Point truth = movement.position();
    boolean spoof = random().nextDouble() < world.properties().driver().spoofProbability();
    Point reported =
        spoof
            ? Geo.offset(truth, random().nextDouble(360), SPOOF_JUMP_METERS)
            : Geo.noisy(truth, world.properties().gpsNoiseMeters(), random());
    Map<String, Object> update = new LinkedHashMap<>();
    update.put("lat", reported.lat());
    update.put("lng", reported.lng());
    update.put("accuracyMeters", 5 + world.properties().gpsNoiseMeters());
    update.put("headingDegrees", movement.headingDegrees());
    update.put("speedKmph", movement.speedKmph());
    update.put("seq", ++seq);
    update.put("clientTime", world.clock().instant().toString());
    if (link.send(LOCATION, update)) {
      world.meters().counter("simulator.positions", "spoofed", Boolean.toString(spoof)).increment();
    }
  }

  private Point randomWaypoint() {
    return Geo.anywhereIn(world.properties().serviceArea(), random());
  }

  Phase phase() {
    return phase;
  }

  static UUID uuid(JsonNode node, String field) {
    return UUID.fromString(node.get(field).asString());
  }

  static Point point(JsonNode node) {
    return new Point(node.get("lat").asDouble(), node.get("lng").asDouble());
  }
}
