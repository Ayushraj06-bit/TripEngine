package com.rideflow.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rideflow.admin.internal.LiveMapPublisher.LiveMap;
import com.rideflow.identity.Role;
import com.rideflow.location.LiveDrivers.LiveDriver;
import com.rideflow.payment.internal.PaymentIntegrationTestBase;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.TripStatus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * The live map's snapshot from real Redis and PostgreSQL, and its trip through the fan-out. Shares
 * the payment tests' context, and so their controllable clock.
 */
class LiveMapIntegrationTest extends PaymentIntegrationTestBase {

  private static final Duration EVENTUALLY = Duration.ofSeconds(10);

  @Autowired LiveMapPublisher publisher;
  @Autowired StringRedisTemplate redis;
  @Autowired RedisMessageListenerContainer realtimeListener;

  @BeforeEach
  void clearTheMap() {
    Set<String> keys = new HashSet<>(redis.keys("driver:state:*"));
    keys.add("drivers:lastseen");
    redis.delete(keys);
  }

  private void driverState(UUID id, Duration quietFor, Map<String, String> state) {
    if (!state.isEmpty()) {
      redis.opsForHash().putAll("driver:state:" + id, state);
    }
    redis
        .opsForZSet()
        .add("drivers:lastseen", id.toString(), CLOCK.instant().minus(quietFor).toEpochMilli());
  }

  @Test
  void showsEveryDriverHeardFromWithinTheStaleWindow() {
    UUID available = UUID.randomUUID();
    UUID onTrip = UUID.randomUUID();
    UUID stale = UUID.randomUUID();
    UUID lapsed = UUID.randomUUID();
    driverState(
        available,
        Duration.ZERO,
        Map.of("status", "AVAILABLE", "vehicleType", "CAR", "lat", "12.9752", "lng", "77.6064"));
    driverState(
        onTrip,
        Duration.ofSeconds(10),
        Map.of(
            "status", "ON_TRIP",
            "vehicleType", "BIKE",
            "lat", "12.97",
            "lng", "77.6",
            "tripPhase", "TO_DROP",
            "targetLat", "12.9784",
            "targetLng", "77.6408"));
    driverState(
        stale, Duration.ofSeconds(31), Map.of("status", "AVAILABLE", "lat", "12.9", "lng", "77.6"));
    // Its state hash expired; only the last-seen entry is left.
    driverState(lapsed, Duration.ZERO, Map.of());

    List<LiveDriver> drivers = publisher.snapshot().drivers();

    assertThat(drivers)
        .containsExactlyInAnyOrder(
            new LiveDriver(
                available, "AVAILABLE", VehicleType.CAR, 12.9752, 77.6064, null, null, null),
            new LiveDriver(
                onTrip, "ON_TRIP", VehicleType.BIKE, 12.97, 77.6, "TO_DROP", 12.9784, 77.6408));
  }

  @Test
  void roundsPositionsToAboutAMetre() {
    UUID driver = UUID.randomUUID();
    driverState(
        driver,
        Duration.ZERO,
        Map.of("status", "AVAILABLE", "lat", "12.975234567891234", "lng", "77.606445678912345"));

    LiveDriver live = publisher.snapshot().drivers().getFirst();

    assertThat(live.lat()).isEqualTo(12.97523);
    assertThat(live.lng()).isEqualTo(77.60645);
  }

  @Test
  void measuresTheTripsRequestedInTheLastFiveMinutes() {
    trip(TripStatus.COMPLETED, Duration.ofMinutes(4), Duration.ofSeconds(30));
    trip(TripStatus.IN_PROGRESS, Duration.ofMinutes(3), Duration.ofSeconds(10));
    trip(TripStatus.REQUESTED, Duration.ofMinutes(1), null);
    trip(TripStatus.NO_DRIVER_FOUND, Duration.ofMinutes(2), null);
    trip(TripStatus.CANCELLED, Duration.ofMinutes(2), null);
    // Before the window: active numbers only.
    trip(TripStatus.COMPLETED, Duration.ofMinutes(10), Duration.ofSeconds(100));

    var kpis = publisher.snapshot().kpis();

    assertThat(kpis.activeTrips()).isEqualTo(2);
    assertThat(kpis.requested()).isEqualTo(5);
    assertThat(kpis.tripsPerMinute()).isEqualTo(1.0);
    assertThat(kpis.averageSecondsToAssign()).isCloseTo(20.0, within(0.001));
    assertThat(kpis.noDriverRate()).isEqualTo(0.2);
    assertThat(kpis.cancelRate()).isEqualTo(0.2);
  }

  @Test
  void reportsNoTimeToAssignWhenNoTripGotADriver() {
    trip(TripStatus.NO_DRIVER_FOUND, Duration.ofMinutes(1), null);

    assertThat(publisher.snapshot().kpis().averageSecondsToAssign()).isNull();
  }

  @Test
  void carriesTheSurgeHeatmapAndItsKillSwitch() {
    redis.opsForValue().set("surge:disabled", "1");
    try {
      LiveMap map = publisher.snapshot();

      assertThat(map.surgeOff()).isTrue();
      assertThat(map.at()).isEqualTo(CLOCK.instant());
    } finally {
      redis.delete("surge:disabled");
    }
  }

  /** One message through Redis Pub/Sub, so every instance hands it to its own admins. */
  @Test
  void broadcastsTheSnapshotThroughTheFanOut() {
    List<String> published = new CopyOnWriteArrayList<>();
    MessageListener listener =
        (message, pattern) -> published.add(new String(message.getBody(), StandardCharsets.UTF_8));
    realtimeListener.addMessageListener(listener, new ChannelTopic("rt:events"));
    try {
      publisher.publish();

      await()
          .atMost(EVENTUALLY)
          .until(
              () ->
                  published.stream()
                      .anyMatch(
                          json ->
                              json.contains("\"userId\":null")
                                  && json.contains("\"destination\":\"/topic/admin/live-map\"")
                                  && json.contains("LIVE_MAP")));
    } finally {
      realtimeListener.removeMessageListener(listener);
    }
  }

  /** The page holds no data, so it needs no token; the topic it reads is what is guarded. */
  @Test
  void servesTheLiveMapPageWithoutAToken() throws Exception {
    mockMvc
        .perform(get("/admin/live-map.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("/topic/admin/live-map")));
  }

  private void trip(TripStatus status, Duration requestedAgo, Duration assignedAfter) {
    User rider = userRow(Role.RIDER);
    boolean hasDriver =
        status != TripStatus.REQUESTED
            && status != TripStatus.NO_DRIVER_FOUND
            && status != TripStatus.CANCELLED;
    Instant requested = CLOCK.instant().minus(requestedAgo);
    jdbc.update(
        """
        INSERT INTO trips (id, rider_id, driver_id, vehicle_type, status, pickup_lat, pickup_lng,
            drop_lat, drop_lng, quoted_fare_paise, surge_multiplier, rate_card_id, payment_method,
            payment_status, distance_m, duration_s, requested_at, assigned_at)
        VALUES (?, ?, ?, 'CAR', ?, 12.9752, 77.6064, 12.9784, 77.6408, 9900, 1,
            (SELECT id FROM rate_cards WHERE vehicle_type = 'CAR' LIMIT 1), 'CASH', 'UNPAID',
            4862, 796, ?, ?)""",
        UUID.randomUUID(),
        rider.id(),
        hasDriver ? userRow(Role.DRIVER).id() : null,
        status.name(),
        utc(requested),
        assignedAfter == null ? null : utc(requested.plus(assignedAfter)));
  }
}
