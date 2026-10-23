package com.rideflow.trip.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rideflow.TestcontainersConfiguration;
import com.rideflow.driver.DriverAdministration;
import com.rideflow.driver.internal.DriverService;
import com.rideflow.identity.Role;
import com.rideflow.identity.internal.AuthService;
import com.rideflow.pricing.internal.QuoteService;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.MutableClock;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.TripActor;
import com.rideflow.trip.TripStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Setup shared by the trip integration tests. One context for all of them: same imports, same
 * properties, so Spring's context cache starts it - and its containers - once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TripTestConfiguration.class})
@Tag("integration")
abstract class TripIntegrationTestBase {

  static final Instant START = TripTestConfiguration.START;
  static final MutableClock CLOCK = TripTestConfiguration.CLOCK;

  /** Roughly MG Road and Indiranagar, Bengaluru: every trip here goes between them. */
  static final GeoPoint MG_ROAD = new GeoPoint(12.9752, 77.6064);

  static final GeoPoint INDIRANAGAR = new GeoPoint(12.9784, 77.6408);

  /** The CAR card V6 seeds: cancellation fee 5000 paise. */
  static final UUID CAR_RATE_CARD = UUID.fromString("01940000-0000-7000-8000-000000000003");

  static final String OTP = "4321";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired AuthService auth;
  @Autowired JwtDecoder jwtDecoder;
  @Autowired QuoteService quotes;
  @Autowired TripService trips;
  @Autowired DriverService drivers;
  @Autowired DriverAdministration driverAdministration;
  @Autowired TripTestConfiguration.PausableFees fees;
  @Autowired TripTestConfiguration.BreakableRoutes routes;

  private final AtomicInteger sequence = new AtomicInteger();

  /** A user, and a token for them when the test goes over HTTP. */
  record User(UUID id, String token) {

    Caller asRider() {
      return new Caller(id, TripActor.RIDER);
    }

    Caller asDriver() {
      return new Caller(id, TripActor.DRIVER);
    }
  }

  @BeforeEach
  void resetWorld() {
    CLOCK.set(START);
    TripTestConfiguration.POSITIONS.clear();
    TripTestConfiguration.PUSHES.clear();
    fees.reset();
    routes.reset();
    truncateEverything();
  }

  /**
   * Listeners for the previous test's events may still be finishing - a driver moving back to
   * AVAILABLE, say - and their row locks can deadlock with TRUNCATE's table locks. Postgres then
   * aborts one side; when it picks the TRUNCATE, the listener finishes and a retry goes through.
   */
  private void truncateEverything() {
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

  /** A rider registered the real way, with a token for HTTP. */
  User rider() {
    String token =
        auth.register(nextPhone(), "Asha", "correct-horse-battery", Role.RIDER).accessToken();
    return new User(subjectOf(token), token);
  }

  /** A driver who could take a trip: registered, approved, with a car, online. */
  User driver() {
    String token =
        auth.register(nextPhone(), "Ravi", "correct-horse-battery", Role.DRIVER).accessToken();
    UUID id = subjectOf(token);
    driverAdministration.approve(id);
    drivers.registerVehicle(
        id, VehicleType.CAR, "KA01AB%04d".formatted(sequence.incrementAndGet()), "Swift", "White");
    drivers.goOnline(id);
    return new User(id, token);
  }

  /**
   * A user inserted directly, for tests that call the service rather than HTTP and need hundreds of
   * them: registration hashes a password, which takes most of a tenth of a second.
   */
  User userRow(Role role) {
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
    if (role == Role.DRIVER) {
      jdbc.update(
          "INSERT INTO drivers (user_id, approval_status, status, last_status_change_at)"
              + " VALUES (?, 'APPROVED', 'AVAILABLE', ?)",
          id,
          now);
    }
    return new User(id, null);
  }

  QuoteService.Quote quote(User rider, VehicleType type) {
    return quotes.quote(rider.id(), MG_ROAD, INDIRANAGAR).stream()
        .filter(quote -> quote.vehicleType() == type)
        .findFirst()
        .orElseThrow();
  }

  ResultActions book(User rider, UUID quoteId, VehicleType type, String idempotencyKey)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/trips")
            .header("Authorization", "Bearer " + rider.token())
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"quoteId":"%s","vehicleType":"%s","paymentMethod":"WALLET"}"""
                    .formatted(quoteId, type)));
  }

  /** Books a car for {@code rider} over HTTP and returns the trip id. */
  UUID bookedTrip(User rider) throws Exception {
    String body =
        book(rider, quote(rider, VehicleType.CAR).quoteId(), VehicleType.CAR, newKey())
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JsonPath.read(body, "$.id"));
  }

  /** A booked trip with {@code driver} assigned and standing at the pickup point. */
  UUID assignedTrip(User rider, User driver) throws Exception {
    UUID tripId = bookedTrip(rider);
    trips.assignDriver(tripId, driver.id());
    TripTestConfiguration.POSITIONS.place(driver.id(), MG_ROAD);
    return tripId;
  }

  String otpOf(UUID tripId) {
    return jdbc.queryForObject("SELECT otp FROM trips WHERE id = ?", String.class, tripId);
  }

  TripStatus statusOf(UUID tripId) {
    return TripStatus.valueOf(
        jdbc.queryForObject("SELECT status FROM trips WHERE id = ?", String.class, tripId));
  }

  long auditRows(UUID tripId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM trip_events WHERE trip_id = ?", Long.class, tripId);
  }

  /**
   * A trip put straight into {@code state}, with whatever that state implies: a driver once one is
   * assigned, the OTP {@link #OTP} while it is due, and arrival long enough ago that a no-show may
   * be declared.
   *
   * @param driver the assigned driver, ignored for states that have none
   */
  UUID tripIn(TripStatus state, User rider, User driver) {
    UUID id = UUID.randomUUID();
    Instant now = CLOCK.instant();
    boolean assigned = state != TripStatus.REQUESTED && state != TripStatus.NO_DRIVER_FOUND;
    boolean arrived =
        state == TripStatus.DRIVER_ARRIVED
            || state == TripStatus.IN_PROGRESS
            || state == TripStatus.COMPLETED;
    boolean started = state == TripStatus.IN_PROGRESS || state == TripStatus.COMPLETED;
    jdbc.update(
        """
        INSERT INTO trips (id, rider_id, driver_id, vehicle_type, status, pickup_lat, pickup_lng,
            drop_lat, drop_lng, quoted_fare_paise, surge_multiplier, rate_card_id, payment_method,
            payment_status, otp, distance_m, duration_s, requested_at, assigned_at, arrived_at,
            started_at, completed_at, cancelled_at, cancelled_by, no_driver_found_at)
        VALUES (?, ?, ?, 'CAR', ?, ?, ?, ?, ?, 9900, 1, ?, 'WALLET', 'UNPAID', ?, 4862, 796,
            ?, ?, ?, ?, ?, ?, ?, ?)""",
        id,
        rider.id(),
        assigned ? driver.id() : null,
        state.name(),
        MG_ROAD.latitude(),
        MG_ROAD.longitude(),
        INDIRANAGAR.latitude(),
        INDIRANAGAR.longitude(),
        CAR_RATE_CARD,
        state == TripStatus.DRIVER_ASSIGNED || state == TripStatus.DRIVER_ARRIVED ? OTP : null,
        utc(now.minus(Duration.ofMinutes(30))),
        assigned ? utc(now.minus(Duration.ofMinutes(20))) : null,
        arrived ? utc(now.minus(Duration.ofMinutes(10))) : null,
        started ? utc(now.minus(Duration.ofMinutes(5))) : null,
        state == TripStatus.COMPLETED ? utc(now) : null,
        state == TripStatus.CANCELLED ? utc(now) : null,
        state == TripStatus.CANCELLED ? "RIDER" : null,
        state == TripStatus.NO_DRIVER_FOUND ? utc(now) : null);
    return id;
  }

  static String newKey() {
    return UUID.randomUUID().toString();
  }

  UUID subjectOf(String token) {
    return UUID.fromString(jwtDecoder.decode(token).getSubject());
  }

  private String nextPhone() {
    return "+9190000%05d".formatted(sequence.incrementAndGet());
  }

  static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
