package com.rideflow.location.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rideflow.TestcontainersConfiguration;
import com.rideflow.driver.DriverStatus;
import com.rideflow.identity.Role;
import com.rideflow.identity.internal.AuthService;
import com.rideflow.location.AvailableDriver;
import com.rideflow.location.NearbyDriver;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.MutableClock;
import com.rideflow.shared.VehicleType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Reads of the live map: matching's exact search and the rider's blurred view. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, NearbyDriversIntegrationTest.Clocks.class})
@Tag("integration")
class NearbyDriversIntegrationTest {

  private static final GeoPoint RIDER = new GeoPoint(12.9752, 77.6064);
  private static final Instant START = Instant.parse("2026-10-01T10:00:00Z");
  private static final MutableClock CLOCK = new MutableClock(START);

  /** What Redis GEO uses (geohash_helper.c). Not the IUGG mean radius GeoPoint uses. */
  private static final double REDIS_EARTH_RADIUS_M = 6_372_797.560856;

  private static final double GEOPOINT_EARTH_RADIUS_M = 6_371_008.8;

  @TestConfiguration
  static class Clocks {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @Autowired private NearbyDriverService nearby;
  @Autowired private DriverStateStore store;
  @Autowired private LocationIngestService ingest;
  @Autowired private StringRedisTemplate redis;
  @Autowired private AuthService auth;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MockMvc mockMvc;

  @BeforeEach
  void reset() {
    CLOCK.set(START);
    redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    nearby.clearCache();
  }

  /** Puts an available car at {@code position}, the way a driver's first ping would. */
  private UUID carAt(GeoPoint position) {
    UUID driverId = UUID.randomUUID();
    store.applyStatusChange(driverId, DriverStatus.AVAILABLE.name(), VehicleType.CAR, null);
    ingest.accept(
        driverId,
        new LocationUpdate(
            position.latitude(), position.longitude(), 10, null, null, 1, CLOCK.instant()));
    return driverId;
  }

  /** A point due north of the rider, {@code meters} away as measured with {@code earthRadius}. */
  private static GeoPoint northOfRider(double meters, double earthRadius) {
    return new GeoPoint(RIDER.latitude() + Math.toDegrees(meters / earthRadius), RIDER.longitude());
  }

  @Nested
  class MatchingSearch {

    @Test
    void includesADriverInsideTheRadiusAndExcludesOneOutside() {
      UUID near = carAt(northOfRider(1_900, REDIS_EARTH_RADIUS_M));
      UUID far = carAt(northOfRider(2_100, REDIS_EARTH_RADIUS_M));

      List<AvailableDriver> found = nearby.findAvailableDrivers(RIDER, VehicleType.CAR, 2.0, 20);

      assertThat(found)
          .extracting(AvailableDriver::driverId)
          .containsExactly(near)
          .doesNotContain(far);
    }

    @Test
    void treatsTheRadiusAsInclusiveToWithinStoragePrecision() {
      // Redis compares with <=, so the boundary is inclusive. "Exactly 2.0 km" is not a
      // meaningful input, though: positions are stored as 52-bit geohashes, which
      // moves them by up to roughly half a metre. Two metres either side is the
      // tightest honest statement of the rule.
      UUID justInside = carAt(northOfRider(1_998, REDIS_EARTH_RADIUS_M));
      UUID justOutside = carAt(northOfRider(2_002, REDIS_EARTH_RADIUS_M));

      List<AvailableDriver> found = nearby.findAvailableDrivers(RIDER, VehicleType.CAR, 2.0, 20);

      assertThat(found)
          .extracting(AvailableDriver::driverId)
          .contains(justInside)
          .doesNotContain(justOutside);
    }

    @Test
    void measuresDistanceWithRedisEarthRadiusNotGeoPoints() {
      // A driver GeoPoint considers exactly 2 km away is about 2000.56 m to Redis, so
      // a 2 km search misses them. Harmless for dispatch - half a metre - but it is
      // why the boundary test above measures with Redis's radius.
      GeoPoint twoKmByGeoPoint = northOfRider(2_000, GEOPOINT_EARTH_RADIUS_M);
      assertThat(RIDER.distanceMetersTo(twoKmByGeoPoint)).isCloseTo(2_000, within(0.01));
      UUID driver = carAt(twoKmByGeoPoint);

      double redisMeters =
          nearby.findAvailableDrivers(RIDER, VehicleType.CAR, 5.0, 20).getFirst().distanceMeters();

      assertThat(redisMeters)
          .isCloseTo(2_000 * REDIS_EARTH_RADIUS_M / GEOPOINT_EARTH_RADIUS_M, within(1.0));
      assertThat(nearby.findAvailableDrivers(RIDER, VehicleType.CAR, 2.0, 20))
          .extracting(AvailableDriver::driverId)
          .doesNotContain(driver);
    }

    @Test
    void returnsNearestFirstAndHonoursTheLimit() {
      UUID third = carAt(northOfRider(1_500, REDIS_EARTH_RADIUS_M));
      UUID first = carAt(northOfRider(300, REDIS_EARTH_RADIUS_M));
      UUID second = carAt(northOfRider(900, REDIS_EARTH_RADIUS_M));

      assertThat(nearby.findAvailableDrivers(RIDER, VehicleType.CAR, 2.0, 20))
          .extracting(AvailableDriver::driverId)
          .containsExactly(first, second, third);
      assertThat(nearby.findAvailableDrivers(RIDER, VehicleType.CAR, 2.0, 2))
          .extracting(AvailableDriver::driverId)
          .containsExactly(first, second);
    }

    @Test
    void searchesOnlyTheRequestedVehicleType() {
      carAt(northOfRider(500, REDIS_EARTH_RADIUS_M));

      assertThat(nearby.findAvailableDrivers(RIDER, VehicleType.BIKE, 2.0, 20)).isEmpty();
    }
  }

  @Nested
  class RiderView {

    @Test
    void carriesNoDriverIdentity() {
      // Structural, so a field added later fails here rather than in production.
      assertThat(Arrays.stream(NearbyDriver.class.getRecordComponents()).map(c -> c.getName()))
          .containsExactly("lat", "lng");
    }

    @Test
    void blursPositionsToAboutAHundredMetres() {
      carAt(new GeoPoint(12.976_789, 77.607_123));

      List<NearbyDriver> view = nearby.nearby(RIDER, VehicleType.CAR);

      assertThat(view)
          .singleElement()
          .satisfies(
              driver -> {
                assertThat(driver.lat()).isEqualTo(12.977);
                assertThat(driver.lng()).isEqualTo(77.607);
              });
    }

    @Test
    void servesACachedAnswerForTheCacheLifetimeThenRefreshes() {
      carAt(northOfRider(300, REDIS_EARTH_RADIUS_M));
      assertThat(nearby.nearby(RIDER, VehicleType.CAR)).hasSize(1);

      carAt(northOfRider(600, REDIS_EARTH_RADIUS_M));
      // Same area, same moment: served from cache. nearby-cache-seconds is 2.
      assertThat(nearby.nearby(RIDER, VehicleType.CAR)).hasSize(1);

      // The cache expires on the injected clock, so time can be moved, not waited for.
      CLOCK.advance(Duration.ofSeconds(3));
      assertThat(nearby.nearby(RIDER, VehicleType.CAR)).hasSize(2);
    }

    @Test
    void returnsOnlyCoordinatesOverHttp() throws Exception {
      jdbc.execute("TRUNCATE refresh_tokens, vehicles, drivers, users CASCADE");
      String riderToken =
          auth.register("+919000006666", "Asha", "correct-horse-battery", Role.RIDER).accessToken();
      carAt(northOfRider(400, REDIS_EARTH_RADIUS_M));

      mockMvc
          .perform(
              get("/api/v1/drivers/nearby")
                  .param("lat", String.valueOf(RIDER.latitude()))
                  .param("lng", String.valueOf(RIDER.longitude()))
                  .param("type", "CAR")
                  .header("Authorization", "Bearer " + riderToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(1))
          .andExpect(jsonPath("$[0].lat").exists())
          .andExpect(jsonPath("$[0].lng").exists())
          .andExpect(jsonPath("$[0].driverId").doesNotExist())
          .andExpect(jsonPath("$[0].id").doesNotExist());
    }

    @Test
    void refusesAnAreaOutsideTheServiceArea() throws Exception {
      jdbc.execute("TRUNCATE refresh_tokens, vehicles, drivers, users CASCADE");
      String riderToken =
          auth.register("+919000007777", "Asha", "correct-horse-battery", Role.RIDER).accessToken();

      mockMvc
          .perform(
              get("/api/v1/drivers/nearby")
                  .param("lat", "19.0760")
                  .param("lng", "72.8777")
                  .param("type", "CAR")
                  .header("Authorization", "Bearer " + riderToken))
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.code").value("OUTSIDE_SERVICE_AREA"));
    }
  }
}
