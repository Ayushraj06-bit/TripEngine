package com.rideflow.payment.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.rideflow.TestcontainersConfiguration;
import com.rideflow.identity.Role;
import com.rideflow.identity.internal.AuthService;
import com.rideflow.shared.MutableClock;
import com.rideflow.shared.RideFlowProperties.Payment.Outcome;
import com.rideflow.trip.PaymentMethod;
import com.rideflow.trip.PaymentStatus;
import com.rideflow.trip.TripActor;
import com.rideflow.trip.TripCancelled;
import com.rideflow.trip.TripCompleted;
import com.rideflow.trip.TripStatus;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Setup shared by the payment and rating integration tests: one context, on a real port so the mock
 * gateway can deliver its webhooks over HTTP the way a real one would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, PaymentTestConfiguration.class})
public abstract class PaymentIntegrationTestBase {

  protected static final MutableClock CLOCK = PaymentTestConfiguration.CLOCK;

  /** The CAR card V6 seeds. */
  private static final UUID CAR_RATE_CARD = UUID.fromString("01940000-0000-7000-8000-000000000003");

  protected static final String PASSWORD = "correct-horse-battery";

  @Autowired protected MockMvc mockMvc;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected AuthService auth;
  @Autowired protected JwtDecoder jwtDecoder;
  @Autowired protected ApplicationEventPublisher events;
  @Autowired protected PlatformTransactionManager transactionManager;
  @Autowired WebhookSignatures signatures;
  @Autowired PaymentTestConfiguration.SwitchableGateway gateway;

  private static final AtomicInteger PHONES = new AtomicInteger();

  /** A user, and a token for them when the test goes over HTTP. */
  public record User(UUID id, String token) {}

  @BeforeEach
  void resetWorld() {
    CLOCK.set(PaymentTestConfiguration.START);
    gateway.reset();
    truncateEverything();
  }

  /**
   * Listeners for the previous test's events may still be finishing, and their row locks can
   * deadlock with TRUNCATE's; a retry goes through once they have.
   */
  private void truncateEverything() {
    for (int attempt = 1; ; attempt++) {
      try {
        jdbc.execute(
            "TRUNCATE ledger_entries, ledger_transactions, ledger_accounts, topups, ratings,"
                + " trip_events, trips, idempotency_keys, refresh_tokens, vehicles, drivers, users"
                + " CASCADE");
        return;
      } catch (PessimisticLockingFailureException deadlock) {
        if (attempt == 3) {
          throw deadlock;
        }
      }
    }
  }

  // --- People ----------------------------------------------------------------------------------

  protected User rider() {
    return registered(Role.RIDER);
  }

  protected User driver() {
    return registered(Role.DRIVER);
  }

  protected User admin() {
    return registered(Role.ADMIN);
  }

  private User registered(Role role) {
    String token = auth.register(nextPhone(), "Test", PASSWORD, role).accessToken();
    return new User(UUID.fromString(jwtDecoder.decode(token).getSubject()), token);
  }

  /** A user inserted directly, for tests that need many and never go over HTTP. */
  protected User userRow(Role role) {
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

  private static String nextPhone() {
    return "+9191000%05d".formatted(PHONES.incrementAndGet());
  }

  // --- Trips -----------------------------------------------------------------------------------

  /** A trip that ended just now at {@code farePaise}, as the trip module leaves it. */
  protected UUID completedTrip(User rider, User driver, long farePaise, PaymentMethod method) {
    return tripRow(rider, driver, TripStatus.COMPLETED, method, farePaise, null);
  }

  /** A trip the rider cancelled just now with a fee. */
  protected UUID cancelledTrip(User rider, User driver, long feePaise, PaymentMethod method) {
    return tripRow(rider, driver, TripStatus.CANCELLED, method, null, feePaise);
  }

  private UUID tripRow(
      User rider,
      User driver,
      TripStatus status,
      PaymentMethod method,
      Long finalFarePaise,
      Long feePaise) {
    UUID id = UUID.randomUUID();
    Instant now = CLOCK.instant();
    boolean completed = status == TripStatus.COMPLETED;
    jdbc.update(
        """
        INSERT INTO trips (id, rider_id, driver_id, vehicle_type, status, pickup_lat, pickup_lng,
            drop_lat, drop_lng, quoted_fare_paise, surge_multiplier, rate_card_id, payment_method,
            payment_status, distance_m, duration_s, final_fare_paise, cancellation_fee_paise,
            requested_at, assigned_at, arrived_at, started_at, completed_at, cancelled_at,
            cancelled_by)
        VALUES (?, ?, ?, 'CAR', ?, 12.9752, 77.6064, 12.9784, 77.6408, 9900, 1, ?, ?, 'UNPAID',
            4862, 796, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        id,
        rider.id(),
        driver.id(),
        status.name(),
        CAR_RATE_CARD,
        method.name(),
        finalFarePaise,
        feePaise,
        utc(now.minus(Duration.ofMinutes(30))),
        utc(now.minus(Duration.ofMinutes(25))),
        completed ? utc(now.minus(Duration.ofMinutes(20))) : null,
        completed ? utc(now.minus(Duration.ofMinutes(18))) : null,
        completed ? utc(now) : null,
        completed ? null : utc(now),
        completed ? null : "RIDER");
    return id;
  }

  /** The trip module's event, through the real event bus: listeners run after the commit. */
  protected void publishCompleted(
      UUID tripId, User rider, User driver, long farePaise, PaymentMethod method) {
    publish(
        new TripCompleted(
            UUID.randomUUID(),
            CLOCK.instant(),
            tripId,
            rider.id(),
            driver.id(),
            farePaise,
            method));
  }

  protected void publishCancelled(
      UUID tripId, User rider, User driver, long feePaise, PaymentMethod method) {
    publish(
        new TripCancelled(
            UUID.randomUUID(),
            CLOCK.instant(),
            tripId,
            rider.id(),
            driver.id(),
            TripActor.RIDER,
            TripStatus.DRIVER_ASSIGNED,
            feePaise,
            method));
  }

  /** Every publication inside a transaction: module listeners only hear committed events. */
  protected void publish(Object event) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> events.publishEvent(event));
  }

  protected PaymentStatus paymentStatusOf(UUID tripId) {
    return PaymentStatus.valueOf(
        jdbc.queryForObject("SELECT payment_status FROM trips WHERE id = ?", String.class, tripId));
  }

  // --- Money -----------------------------------------------------------------------------------

  /** Starts a top-up over HTTP, as the rider's app does. */
  protected ResultActions startTopup(User rider, long amountPaise, String idempotencyKey)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/wallet/topups")
            .header("Authorization", "Bearer " + rider.token())
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amountPaise\":" + amountPaise + "}"));
  }

  /** The top-up's id, from a successful start. */
  protected UUID startedTopup(User rider, long amountPaise) throws Exception {
    String body =
        startTopup(rider, amountPaise, UUID.randomUUID().toString())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JsonPath.read(body, "$.topupId"));
  }

  protected String gatewayRefOf(UUID topupId) {
    return jdbc.queryForObject(
        "SELECT gateway_ref FROM topups WHERE id = ?", String.class, topupId);
  }

  /** The gateway's webhook, correctly signed. */
  protected ResultActions webhook(String gatewayRef, Outcome outcome, long amountPaise)
      throws Exception {
    String body =
        """
        {"eventId":"evt_%s","gatewayRef":"%s","status":"%s","amountPaise":%d}"""
            .formatted(UUID.randomUUID(), gatewayRef, outcome, amountPaise);
    return signedWebhook(body);
  }

  protected ResultActions signedWebhook(String body) throws Exception {
    String timestamp = Long.toString(CLOCK.instant().getEpochSecond());
    return mockMvc.perform(
        post(MockPaymentGateway.WEBHOOK_PATH)
            .header(WebhookSignatures.TIMESTAMP_HEADER, timestamp)
            .header(WebhookSignatures.SIGNATURE_HEADER, signatures.sign(timestamp, body))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  /** A completed top-up: started over HTTP, and confirmed by the gateway. */
  protected UUID topUp(User rider, long amountPaise) throws Exception {
    UUID topupId = startedTopup(rider, amountPaise);
    webhook(gatewayRefOf(topupId), Outcome.SUCCEEDED, amountPaise);
    return topupId;
  }

  /** An account's cached balance; zero for one never used. */
  protected long balance(UUID ownerId, String accountType) {
    return jdbc
        .queryForList(
            "SELECT balance_paise FROM ledger_accounts WHERE owner_id = ? AND account_type = ?",
            Long.class,
            ownerId,
            accountType)
        .stream()
        .findFirst()
        .orElse(0L);
  }

  protected long wallet(User rider) {
    return balance(rider.id(), "RIDER_WALLET");
  }

  protected long earnings(User driver) {
    return balance(driver.id(), "DRIVER_EARNINGS");
  }

  protected long revenue() {
    return balance(AccountKey.PLATFORM, "PLATFORM_REVENUE");
  }

  protected int transactionsOf(String type, UUID referenceId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ledger_transactions WHERE type = ? AND reference_id = ?",
        Integer.class,
        type,
        referenceId);
  }

  protected static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
