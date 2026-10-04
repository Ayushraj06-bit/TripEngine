package com.rideflow.matching.internal;

import static org.awaitility.Awaitility.await;

import com.rideflow.TestcontainersConfiguration;
import com.rideflow.driver.DriverAdministration;
import com.rideflow.driver.DriverStatus;
import com.rideflow.driver.internal.DriverService;
import com.rideflow.identity.Role;
import com.rideflow.identity.internal.AuthService;
import com.rideflow.location.internal.LocationIngestService;
import com.rideflow.location.internal.LocationUpdate;
import com.rideflow.pricing.internal.QuoteService;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.MutableClock;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.PaymentMethod;
import com.rideflow.trip.TripStatus;
import com.rideflow.trip.internal.BookingService;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Setup for the matching integration tests: real PostgreSQL, real Redis, the real location module
 * placing drivers on the real map, and a clock the test moves.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, MatchingTestConfiguration.class})
abstract class MatchingIntegrationTestBase {

  static final Instant START = MatchingTestConfiguration.START;
  static final MutableClock CLOCK = MatchingTestConfiguration.CLOCK;
  static final MatchingTestConfiguration.RecordingNotifications NOTIFICATIONS =
      MatchingTestConfiguration.NOTIFICATIONS;

  /** Roughly MG Road, Bengaluru: every trip here starts from it. */
  static final GeoPoint PICKUP = new GeoPoint(12.9752, 77.6064);

  static final UUID CAR_RATE_CARD = UUID.fromString("01940000-0000-7000-8000-000000000003");

  @Autowired Matcher matcher;
  @Autowired OfferStore store;
  @Autowired OfferPoller poller;
  @Autowired RecoverySweeper sweeper;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired AuthService auth;
  @Autowired JwtDecoder jwtDecoder;
  @Autowired DriverAdministration driverAdministration;
  @Autowired DriverService driverService;
  @Autowired LocationIngestService ingest;
  @Autowired QuoteService quotes;
  @Autowired BookingService bookings;
  @Autowired com.rideflow.location.AvailableDriverFinder finder;
  @Autowired MockMvc mockMvc;

  private final AtomicInteger sequence = new AtomicInteger();

  /** A user, and a token for them when the test goes over HTTP. */
  record User(UUID id, String token) {}

  @BeforeEach
  void resetWorld() {
    CLOCK.set(START);
    NOTIFICATIONS.clear();
    redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    truncateEverything();
  }

  /** Listeners from the previous test may still be finishing; see TripIntegrationTestBase. */
  void truncateEverything() {
    for (int attempt = 1; ; attempt++) {
      try {
        jdbc.execute(
            "TRUNCATE trip_events, trips, idempotency_keys, refresh_tokens, vehicles, drivers,"
                + " users CASCADE");
        return;
      } catch (PessimisticLockingFailureException deadlock) {
        if (attempt == 3) {
          throw deadlock;
        }
      }
    }
  }

  /** A rider, inserted directly: these tests never need a rider's token. */
  UUID rider() {
    return userRow(Role.RIDER);
  }

  /**
   * A driver on the map: registered the real way, approved, with a car, online, and placed at
   * {@code position} by an ordinary location update.
   */
  User driverAt(GeoPoint position) {
    String token =
        auth.register(nextPhone(), "Ravi", "correct-horse-battery", Role.DRIVER).accessToken();
    UUID id = UUID.fromString(jwtDecoder.decode(token).getSubject());
    driverAdministration.approve(id);
    driverService.registerVehicle(
        id, VehicleType.CAR, "KA01MA%04d".formatted(sequence.incrementAndGet()), "Swift", "White");
    driverService.goOnline(id);
    // The location module learns of it asynchronously; a position sent before that would be
    // stored but not put on the map.
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> "AVAILABLE".equals(redis.opsForHash().get("driver:state:" + id, "status")));
    ingest.accept(
        id,
        new LocationUpdate(
            position.latitude(), position.longitude(), 10, null, null, 1, CLOCK.instant()));
    return new User(id, token);
  }

  /** A driver inserted directly, never on the map: for tests that hand offers out themselves. */
  UUID driverRow() {
    UUID id = userRow(Role.DRIVER);
    jdbc.update(
        "INSERT INTO drivers (user_id, approval_status, status, last_status_change_at)"
            + " VALUES (?, 'APPROVED', 'AVAILABLE', ?)",
        id,
        utc(CLOCK.instant()));
    return id;
  }

  /** A REQUESTED trip from {@link #PICKUP}, inserted directly. */
  UUID requestedTrip(UUID riderId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO trips (id, rider_id, vehicle_type, status, pickup_lat, pickup_lng, drop_lat,
            drop_lng, quoted_fare_paise, surge_multiplier, rate_card_id, payment_method,
            payment_status, distance_m, duration_s, requested_at)
        VALUES (?, ?, 'CAR', 'REQUESTED', ?, ?, 12.9784, 77.6408, 9900, 1, ?, 'WALLET', 'UNPAID',
            4862, 796, ?)""",
        id,
        riderId,
        PICKUP.latitude(),
        PICKUP.longitude(),
        CAR_RATE_CARD,
        utc(CLOCK.instant()));
    return id;
  }

  /** A trip booked the real way: a quote, then a booking, then TripRequested reaches matching. */
  UUID book(UUID riderId) {
    QuoteService.Quote quote =
        quotes.quote(riderId, PICKUP, new GeoPoint(12.9784, 77.6408)).stream()
            .filter(q -> q.vehicleType() == VehicleType.CAR)
            .findFirst()
            .orElseThrow();
    String trip =
        bookings.book(
            riderId,
            UUID.randomUUID().toString(),
            quote.quoteId(),
            VehicleType.CAR,
            PaymentMethod.WALLET);
    return UUID.fromString(com.jayway.jsonpath.JsonPath.read(trip, "$.id"));
  }

  /** Starts matching and hands the trip straight to {@code driverId}, bypassing the search. */
  UUID offerDirectly(UUID tripId, UUID driverId) {
    matcher.start(tripId, null);
    OfferStore.Match match = store.match(tripId).orElseThrow();
    UUID offerId = UUID.randomUUID();
    OfferStore.Creation created =
        store.createOffer(match, offerId, driverId, 2.0, CLOCK.instant().plusSeconds(12));
    if (created != OfferStore.Creation.CREATED) {
      throw new IllegalStateException("No offer to driver " + driverId + ": " + created);
    }
    return offerId;
  }

  /** The latest offer pushed to the driver, waiting for it if matching runs asynchronously. */
  OfferView awaitOffer(UUID driverId) {
    await().atMost(Duration.ofSeconds(10)).until(() -> !NOTIFICATIONS.offersTo(driverId).isEmpty());
    return NOTIFICATIONS.offersTo(driverId).getLast();
  }

  /** Expiry is the poller's job, so moving the clock is not enough on its own. */
  void advanceAndPoll(Duration by) {
    CLOCK.advance(by);
    poller.poll();
  }

  String offerStatus(UUID offerId) {
    return (String) redis.opsForHash().get("offer:" + offerId, "status");
  }

  boolean locked(UUID driverId) {
    return Boolean.TRUE.equals(redis.hasKey("driver:lock:" + driverId));
  }

  TripStatus statusOf(UUID tripId) {
    return TripStatus.valueOf(
        jdbc.queryForObject("SELECT status FROM trips WHERE id = ?", String.class, tripId));
  }

  DriverStatus driverStatus(UUID driverId) {
    return DriverStatus.valueOf(
        jdbc.queryForObject(
            "SELECT status FROM drivers WHERE user_id = ?", String.class, driverId));
  }

  int counter(UUID driverId, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM drivers WHERE user_id = ?", Integer.class, driverId);
  }

  /** A point {@code meters} due north of the pickup. */
  static GeoPoint north(double meters) {
    return new GeoPoint(
        PICKUP.latitude() + Math.toDegrees(meters / 6_371_008.8), PICKUP.longitude());
  }

  private UUID userRow(Role role) {
    UUID id = UUID.randomUUID();
    OffsetDateTime now = utc(CLOCK.instant());
    jdbc.update(
        "INSERT INTO users (id, phone, name, password_hash, role, created_at, updated_at)"
            + " VALUES (?, ?, 'Test', 'not-a-hash', ?, ?, ?)",
        id,
        nextPhone(),
        role.name(),
        now,
        now);
    return id;
  }

  private String nextPhone() {
    return "+9180000%05d".formatted(sequence.incrementAndGet());
  }

  static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
