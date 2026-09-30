package com.rideflow.location.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rideflow.TestcontainersConfiguration;
import com.rideflow.driver.DriverAdministration;
import com.rideflow.driver.internal.DriverService;
import com.rideflow.identity.Role;
import com.rideflow.identity.internal.AuthService;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.MutableClock;
import com.rideflow.shared.VehicleType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The live map following the driver module, through the real event path.
 *
 * <p>Nothing here is called directly on the listener. The driver goes online through {@code
 * DriverService}, which publishes {@code DriverStatusChanged} inside its transaction; the Modulith
 * registry stores it, and the listener runs after commit. So these tests prove the whole chain -
 * transactional publish, outbox, asynchronous delivery, Redis - rather than the last link in it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, DriverStatusListenerIntegrationTest.Clocks.class})
class DriverStatusListenerIntegrationTest {

  private static final GeoPoint MG_ROAD = new GeoPoint(12.9752, 77.6064);
  private static final Instant START = Instant.parse("2026-10-01T10:00:00Z");
  private static final MutableClock CLOCK = new MutableClock(START);
  private static final Duration EVENTUALLY = Duration.ofSeconds(10);

  @TestConfiguration
  static class Clocks {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @Autowired private AuthService auth;
  @Autowired private DriverService drivers;
  @Autowired private DriverAdministration administration;
  @Autowired private LocationIngestService ingest;
  @Autowired private StringRedisTemplate redis;
  @Autowired private JdbcTemplate jdbc;

  private UUID driverId;
  private long seq;

  @BeforeEach
  void anApprovedCarDriver() {
    CLOCK.set(START);
    seq = 0;
    redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    jdbc.execute("TRUNCATE refresh_tokens, vehicles, drivers, users CASCADE");

    auth.register("+919000005555", "Ravi", "correct-horse-battery", Role.DRIVER);
    driverId = jdbc.queryForObject("SELECT user_id FROM drivers", UUID.class);
    drivers.registerVehicle(driverId, VehicleType.CAR, "KA01AB1234", "Swift", "White");
    administration.approve(driverId);
  }

  private String storedStatus() {
    return (String) redis.opsForHash().get("driver:state:" + driverId, "status");
  }

  private boolean isIn(VehicleType type) {
    return redis.opsForZSet().score("drivers:available:" + type.name(), driverId.toString())
        != null;
  }

  /** One position, a plausible distance on and far enough in time not to be throttled. */
  private String ping() {
    CLOCK.advance(Duration.ofSeconds(5));
    seq++;
    double lat = MG_ROAD.latitude() + seq * 0.0001; // about 11 m per ping
    return ingest.accept(
        driverId,
        new LocationUpdate(lat, MG_ROAD.longitude(), 10, null, null, seq, CLOCK.instant()));
  }

  @Test
  void placesADriverOnTheMapAfterTheyGoOnline() {
    drivers.goOnline(driverId);

    // Asynchronous, after commit: the listener catches up, it does not run inline.
    await().atMost(EVENTUALLY).until(() -> "AVAILABLE".equals(storedStatus()));
    assertThat(redis.opsForHash().get("driver:state:" + driverId, "vehicleType")).isEqualTo("CAR");

    assertThat(ping()).isEqualTo("accepted");
    assertThat(isIn(VehicleType.CAR)).isTrue();
  }

  @Test
  void takesADriverOffTheMapWhenTheyGoOffline() {
    drivers.goOnline(driverId);
    await().atMost(EVENTUALLY).until(() -> "AVAILABLE".equals(storedStatus()));
    ping();
    assertThat(isIn(VehicleType.CAR)).isTrue();

    drivers.goOffline(driverId);

    await().atMost(EVENTUALLY).until(() -> !isIn(VehicleType.CAR));
    assertThat(storedStatus()).isEqualTo("OFFLINE");
  }

  @Test
  void keepsAnOfflineDriverOffTheMapWhateverTheyReport() {
    // Never went online: status is unknown to location, so a ping must not add them.
    assertThat(ping()).isEqualTo("accepted");
    assertThat(isIn(VehicleType.CAR)).isFalse();
  }

  @Test
  void movesADriverBetweenSetsWhenTheirVehicleTypeChanges() {
    drivers.goOnline(driverId);
    await().atMost(EVENTUALLY).until(() -> "AVAILABLE".equals(storedStatus()));
    ping();
    assertThat(isIn(VehicleType.CAR)).isTrue();

    drivers.registerVehicle(driverId, VehicleType.BIKE, "KA02CD5678", "Activa", "Grey");

    // Out of the car set at once, so no car trip can be offered to a motorbike.
    await().atMost(EVENTUALLY).until(() -> !isIn(VehicleType.CAR));
    assertThat(redis.opsForHash().get("driver:state:" + driverId, "vehicleType")).isEqualTo("BIKE");

    // Into the bike set on the next position, which is the only thing that knows
    // where they are.
    assertThat(ping()).isEqualTo("accepted");
    assertThat(isIn(VehicleType.BIKE)).isTrue();
    assertThat(isIn(VehicleType.CAR)).isFalse();
  }

  @Test
  void removesARejectedDriverFromTheMap() {
    drivers.goOnline(driverId);
    await().atMost(EVENTUALLY).until(() -> "AVAILABLE".equals(storedStatus()));
    ping();

    administration.reject(driverId, "Complaint upheld");

    await().atMost(EVENTUALLY).until(() -> !isIn(VehicleType.CAR));
  }
}
