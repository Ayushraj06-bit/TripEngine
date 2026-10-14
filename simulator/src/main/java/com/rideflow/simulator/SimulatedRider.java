package com.rideflow.simulator;

import static com.rideflow.simulator.SimulatedDriver.uuid;

import com.rideflow.simulator.BackendApi.ApiException;
import com.rideflow.simulator.Geo.Point;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;
import tools.jackson.databind.JsonNode;

/**
 * A rider's app: books when {@link Simulation}'s arrivals pick it, follows the trip over STOMP,
 * hands the OTP to its driver, sometimes cancels, and rates the driver at the end.
 *
 * <p>WebSocket is best effort (PROJECT_SPEC section 5), so it resyncs with {@code GET
 * /trips/active} after every reconnect and whenever its trip has been quiet for too long.
 */
final class SimulatedRider extends Actor {

  private static final Set<String> TERMINAL = Set.of("COMPLETED", "CANCELLED", "NO_DRIVER_FOUND");

  private static final Set<String> CANCELLABLE = Set.of("REQUESTED", "DRIVER_ASSIGNED");

  /** Claimed by the arrivals before a booking, released when the trip is over. */
  private final AtomicBoolean busy = new AtomicBoolean();

  /** A trip that has heard nothing for this many ticks is resynced over REST. */
  private static final int QUIET_TICKS = 10;

  private UUID tripId;
  private String status;
  private long lastSeq;
  private Instant lastHeard;

  SimulatedRider(World world, UserSession session, int index) {
    super(world, session, "rider-" + index);
  }

  @Override
  List<String> destinations() {
    return List.of("/user/queue/trip-updates", "/user/queue/driver-location");
  }

  /** True if this rider was free and is now the arrivals' to book with. */
  boolean claim() {
    return busy.compareAndSet(false, true);
  }

  void requestRide() {
    if (offline() || tripId != null) {
      busy.set(false);
      return;
    }
    try {
      book();
    } catch (ApiException e) {
      if ("ACTIVE_TRIP_EXISTS".equals(e.code())) {
        resync();
      } else {
        busy.set(tripId != null);
        throw e;
      }
    }
  }

  private void book() {
    String paymentMethod = world.properties().demand().paymentMethod();
    if ("WALLET".equals(paymentMethod)) {
      topUpIfLow();
    }
    Point pickup = world.pickup(random());
    Point drop = world.drop(pickup, random());
    String type = world.vehicleType(random());
    JsonNode quote =
        StreamSupport.stream(
                world.api().quote(session, pickup, drop).get("quotes").spliterator(), false)
            .filter(q -> type.equals(q.get("vehicleType").asString()))
            .findFirst()
            .orElseThrow();
    String key = UUID.randomUUID().toString();
    JsonNode trip;
    try {
      trip = world.api().book(session, key, quote.get("quoteId").asString(), type, paymentMethod);
    } catch (ApiException e) {
      if (!"IO".equals(e.code())) {
        throw e;
      }
      // No answer: the same request again under the same key, which books at most once.
      trip = world.api().book(session, key, quote.get("quoteId").asString(), type, paymentMethod);
    }
    follow(trip);
    if (random().nextDouble() < world.properties().demand().cancelProbability()) {
      UUID booked = tripId;
      later(
          World.within(world.properties().demand().cancelAfter(), random()), () -> cancel(booked));
    }
  }

  private void topUpIfLow() {
    var accounts = world.properties().accounts();
    if (world.api().walletBalance(session) < accounts.minBalancePaise()) {
      world.api().topUp(session, accounts.topUpPaise());
    }
  }

  @Override
  void tick() {
    if (tripId != null
        && lastHeard
            .plus(world.properties().tick().multipliedBy(QUIET_TICKS))
            .isBefore(world.clock().instant())) {
      resync();
    }
  }

  @Override
  void onMessage(String destination, JsonNode envelope) {
    JsonNode tripOfMessage = envelope.get("tripId");
    if (tripId == null
        || tripOfMessage == null
        || !tripId.toString().equals(tripOfMessage.asString())) {
      return;
    }
    lastHeard = world.clock().instant();
    if (destination.endsWith("/driver-location")) {
      world.meters().counter("simulator.rider.driver_locations").increment();
      return;
    }
    // Delivery is unordered: anything at or below the newest seen is a duplicate or stale.
    long seq = envelope.get("seq").asLong();
    if (seq <= lastSeq) {
      return;
    }
    lastSeq = seq;
    apply(envelope.get("payload"));
  }

  /** The REST view of the trip, which wins over anything pushed. */
  @Override
  void resync() {
    Optional<JsonNode> active = world.api().activeTrip(session);
    if (active.isPresent()) {
      if (tripId == null || !tripId.equals(uuid(active.get(), "id"))) {
        follow(active.get());
      } else {
        apply(active.get());
      }
    } else if (tripId != null) {
      // Over while we were not listening: how it ended.
      apply(world.api().trip(session, tripId));
    }
  }

  private void follow(JsonNode trip) {
    busy.set(true);
    tripId = uuid(trip, "id");
    lastSeq = 0;
    apply(trip);
  }

  private void apply(JsonNode trip) {
    lastHeard = world.clock().instant();
    status = trip.get("status").asString();
    if (trip.hasNonNull("otp")) {
      // Read out to the driver.
      world.otps().put(tripId, trip.get("otp").asString());
    }
    if (!TERMINAL.contains(status)) {
      return;
    }
    String outcome = status;
    world
        .meters()
        .counter("simulator.trips", "outcome", outcome.toLowerCase(Locale.ROOT))
        .increment();
    UUID finished = tripId;
    world.otps().remove(finished);
    tripId = null;
    status = null;
    busy.set(false);
    if ("COMPLETED".equals(outcome)) {
      world.api().rate(session, finished, 3 + random().nextInt(3));
    }
  }

  private void cancel(UUID booked) {
    if (booked.equals(tripId) && CANCELLABLE.contains(status)) {
      apply(world.api().cancel(session, booked, "Simulated rider changed their mind"));
    }
  }
}
