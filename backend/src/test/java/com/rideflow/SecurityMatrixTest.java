package com.rideflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.rideflow.identity.Role;
import com.rideflow.identity.internal.AuthService;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Every endpoint against every caller.
 *
 * <p>A table rather than a test per case, because authorization regressions are errors of omission:
 * somebody adds a route and forgets the role check. A matrix makes the missing row visible, and
 * each later phase appends its endpoints here.
 *
 * <p>What matters is the distinction between the three failure modes. **401** means no usable
 * token. **403** means a valid token whose role is wrong for this route. **404** means the resource
 * does not exist for this caller - PROJECT_SPEC section 10 uses it so that probing cannot confirm
 * someone else's data exists. Collapsing any two of those hides a real bug.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class SecurityMatrixTest {

  private static final String PASSWORD = "correct-horse-battery";

  /** Inside the configured service area, so a permitted caller gets a real answer. */
  private static final String NEARBY = "/api/v1/drivers/nearby?lat=12.9752&lng=77.6064&type=CAR";

  private static final String QUOTES = "/api/v1/fares/quotes";

  private static final String TRIPS = "/api/v1/trips";

  /** A trip nobody has: a permitted caller reaching the route gets 404, not 403. */
  private static final String TRIP = TRIPS + "/" + UUID.randomUUID();

  private static final String TOPUPS = "/api/v1/wallet/topups";

  private static final String SURGE = "/api/v1/admin/surge";

  private static final String WEBHOOK = "/api/v1/webhooks/payment-gateway";

  /** A ledger transaction nobody has. */
  private static final String REFUND = "/api/v1/admin/payments/" + UUID.randomUUID() + "/refund";

  /** Likewise an offer nobody has. */
  private static final String OFFER = "/api/v1/offers/" + UUID.randomUUID();

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthService auth;
  @Autowired private JdbcTemplate jdbc;

  private String riderToken;
  private String driverToken;
  private String adminToken;

  @BeforeEach
  void registerOneOfEachRole() {
    jdbc.execute("TRUNCATE refresh_tokens, vehicles, drivers, users CASCADE");

    riderToken = auth.register("+919000000001", "Rider", PASSWORD, Role.RIDER).accessToken();
    driverToken = auth.register("+919000000002", "Driver", PASSWORD, Role.DRIVER).accessToken();
    // No public route creates an admin, by design: only AdminBootstrap does.
    adminToken = auth.register("+919000000003", "Admin", PASSWORD, Role.ADMIN).accessToken();
  }

  /** Caller identities, including the three ways a token can be unusable. */
  private enum Caller {
    ANONYMOUS,
    RIDER,
    DRIVER,
    ADMIN,
    GARBAGE_TOKEN
  }

  private static Stream<Arguments> matrix() {
    return Stream.of(
        // --- a rider's own account: any authenticated role, nobody else ---
        row(HttpMethod.GET, "/api/v1/users/me", Caller.ANONYMOUS, 401),
        row(HttpMethod.GET, "/api/v1/users/me", Caller.GARBAGE_TOKEN, 401),
        row(HttpMethod.GET, "/api/v1/users/me", Caller.RIDER, 200),
        row(HttpMethod.GET, "/api/v1/users/me", Caller.DRIVER, 200),
        row(HttpMethod.GET, "/api/v1/users/me", Caller.ADMIN, 200),

        // --- driver routes: DRIVER only ---
        row(HttpMethod.GET, "/api/v1/drivers/me", Caller.ANONYMOUS, 401),
        row(HttpMethod.GET, "/api/v1/drivers/me", Caller.RIDER, 403),
        row(HttpMethod.GET, "/api/v1/drivers/me", Caller.ADMIN, 403),
        row(HttpMethod.GET, "/api/v1/drivers/me", Caller.DRIVER, 200),
        row(HttpMethod.POST, "/api/v1/drivers/me/online", Caller.ANONYMOUS, 401),
        row(HttpMethod.POST, "/api/v1/drivers/me/online", Caller.RIDER, 403),
        // A driver with no vehicle and no approval: 422, which proves the route was
        // reached and the business rule ran, not the authorization check.
        row(HttpMethod.POST, "/api/v1/drivers/me/online", Caller.DRIVER, 422),
        row(HttpMethod.POST, "/api/v1/drivers/me/offline", Caller.RIDER, 403),
        row(HttpMethod.POST, "/api/v1/drivers/me/offline", Caller.DRIVER, 200),
        row(HttpMethod.PUT, "/api/v1/drivers/me/vehicle", Caller.RIDER, 403),
        row(HttpMethod.PUT, "/api/v1/drivers/me/vehicle", Caller.ANONYMOUS, 401),

        // --- admin routes: ADMIN only ---
        row(HttpMethod.GET, "/api/v1/admin/drivers", Caller.ANONYMOUS, 401),
        row(HttpMethod.GET, "/api/v1/admin/drivers", Caller.GARBAGE_TOKEN, 401),
        row(HttpMethod.GET, "/api/v1/admin/drivers", Caller.RIDER, 403),
        row(HttpMethod.GET, "/api/v1/admin/drivers", Caller.DRIVER, 403),
        row(HttpMethod.GET, "/api/v1/admin/drivers", Caller.ADMIN, 200),
        row(
            HttpMethod.POST,
            "/api/v1/admin/drivers/" + UUID.randomUUID() + "/approve",
            Caller.DRIVER,
            403),
        // Admin reaching a driver that does not exist: 404, so the role check passed.
        row(
            HttpMethod.POST,
            "/api/v1/admin/drivers/" + UUID.randomUUID() + "/approve",
            Caller.ADMIN,
            404),

        // --- nearby drivers (Phase 2): RIDER only, and anonymous callers never ---
        row(HttpMethod.GET, NEARBY, Caller.ANONYMOUS, 401),
        row(HttpMethod.GET, NEARBY, Caller.GARBAGE_TOKEN, 401),
        row(HttpMethod.GET, NEARBY, Caller.RIDER, 200),
        row(HttpMethod.GET, NEARBY, Caller.DRIVER, 403),
        row(HttpMethod.GET, NEARBY, Caller.ADMIN, 403),

        // --- fare quotes (Phase 3): RIDER only ---
        row(HttpMethod.POST, QUOTES, Caller.ANONYMOUS, 401),
        row(HttpMethod.POST, QUOTES, Caller.GARBAGE_TOKEN, 401),
        row(HttpMethod.POST, QUOTES, Caller.RIDER, 200),
        row(HttpMethod.POST, QUOTES, Caller.DRIVER, 403),
        row(HttpMethod.POST, QUOTES, Caller.ADMIN, 403),

        // --- trips (Phase 4) ---
        row(HttpMethod.POST, TRIPS, Caller.ANONYMOUS, 401),
        row(HttpMethod.POST, TRIPS, Caller.DRIVER, 403),
        row(HttpMethod.POST, TRIPS, Caller.ADMIN, 403),
        // A rider with a made-up quote: 410 shows the booking itself ran.
        row(HttpMethod.POST, TRIPS, Caller.RIDER, 410),
        row(HttpMethod.GET, TRIPS + "/active", Caller.ANONYMOUS, 401),
        row(HttpMethod.GET, TRIPS + "/active", Caller.RIDER, 204),
        row(HttpMethod.GET, TRIPS + "/active", Caller.DRIVER, 204),
        row(HttpMethod.GET, TRIPS + "/active", Caller.ADMIN, 403),
        row(HttpMethod.GET, TRIPS, Caller.RIDER, 200),
        row(HttpMethod.GET, TRIPS, Caller.DRIVER, 200),
        row(HttpMethod.GET, TRIPS, Caller.ADMIN, 403),
        row(HttpMethod.GET, TRIP, Caller.ANONYMOUS, 401),
        row(HttpMethod.GET, TRIP, Caller.RIDER, 404),
        row(HttpMethod.GET, TRIP, Caller.DRIVER, 404),
        row(HttpMethod.GET, TRIP, Caller.ADMIN, 403),
        row(HttpMethod.POST, TRIP + "/cancel", Caller.ANONYMOUS, 401),
        row(HttpMethod.POST, TRIP + "/cancel", Caller.RIDER, 404),
        row(HttpMethod.POST, TRIP + "/cancel", Caller.DRIVER, 404),
        row(HttpMethod.POST, TRIP + "/cancel", Caller.ADMIN, 403),
        row(HttpMethod.POST, TRIP + "/arrive", Caller.RIDER, 403),
        row(HttpMethod.POST, TRIP + "/arrive", Caller.DRIVER, 404),
        row(HttpMethod.POST, TRIP + "/start", Caller.RIDER, 403),
        row(HttpMethod.POST, TRIP + "/start", Caller.DRIVER, 404),
        row(HttpMethod.POST, TRIP + "/complete", Caller.ANONYMOUS, 401),
        row(HttpMethod.POST, TRIP + "/complete", Caller.RIDER, 403),
        row(HttpMethod.POST, TRIP + "/complete", Caller.DRIVER, 404),

        // --- offers (Phase 5): DRIVER only ---
        row(HttpMethod.POST, OFFER + "/accept", Caller.ANONYMOUS, 401),
        row(HttpMethod.POST, OFFER + "/accept", Caller.RIDER, 403),
        row(HttpMethod.POST, OFFER + "/accept", Caller.ADMIN, 403),
        row(HttpMethod.POST, OFFER + "/accept", Caller.DRIVER, 404),
        row(HttpMethod.POST, OFFER + "/reject", Caller.RIDER, 403),
        row(HttpMethod.POST, OFFER + "/reject", Caller.DRIVER, 404),
        row(HttpMethod.GET, "/api/v1/offers/current", Caller.ANONYMOUS, 401),
        row(HttpMethod.GET, "/api/v1/offers/current", Caller.RIDER, 403),
        row(HttpMethod.GET, "/api/v1/offers/current", Caller.DRIVER, 204),

        // --- wallet (Phase 7): riders and drivers, each their own account ---
        row(HttpMethod.GET, "/api/v1/wallet", Caller.ANONYMOUS, 401),
        row(HttpMethod.GET, "/api/v1/wallet", Caller.GARBAGE_TOKEN, 401),
        row(HttpMethod.GET, "/api/v1/wallet", Caller.RIDER, 200),
        row(HttpMethod.GET, "/api/v1/wallet", Caller.DRIVER, 200),
        row(HttpMethod.GET, "/api/v1/wallet", Caller.ADMIN, 403),
        row(HttpMethod.POST, TOPUPS, Caller.ANONYMOUS, 401),
        row(HttpMethod.POST, TOPUPS, Caller.RIDER, 201),
        row(HttpMethod.POST, TOPUPS, Caller.DRIVER, 403),
        row(HttpMethod.POST, TOPUPS, Caller.ADMIN, 403),
        // The gateway carries no token: its signature is its credential, whoever else calls.
        row(HttpMethod.POST, WEBHOOK, Caller.ANONYMOUS, 401),
        row(HttpMethod.POST, WEBHOOK, Caller.RIDER, 401),
        row(HttpMethod.POST, WEBHOOK, Caller.ADMIN, 401),

        // --- ratings (Phase 7): either side of the trip ---
        row(HttpMethod.POST, TRIP + "/rating", Caller.ANONYMOUS, 401),
        row(HttpMethod.POST, TRIP + "/rating", Caller.ADMIN, 403),
        row(HttpMethod.POST, TRIP + "/rating", Caller.RIDER, 404),
        row(HttpMethod.POST, TRIP + "/rating", Caller.DRIVER, 404),

        // --- refunds and reconciliation (Phase 7): ADMIN only ---
        row(HttpMethod.POST, REFUND, Caller.ANONYMOUS, 401),
        row(HttpMethod.POST, REFUND, Caller.RIDER, 403),
        row(HttpMethod.POST, REFUND, Caller.DRIVER, 403),
        row(HttpMethod.POST, REFUND, Caller.ADMIN, 404),
        row(HttpMethod.GET, "/api/v1/admin/ledger/reconcile", Caller.ANONYMOUS, 401),
        row(HttpMethod.GET, "/api/v1/admin/ledger/reconcile", Caller.RIDER, 403),
        row(HttpMethod.GET, "/api/v1/admin/ledger/reconcile", Caller.DRIVER, 403),
        row(HttpMethod.GET, "/api/v1/admin/ledger/reconcile", Caller.ADMIN, 200),

        // --- surge (Phase 8): ADMIN only ---
        row(HttpMethod.GET, SURGE, Caller.ANONYMOUS, 401),
        row(HttpMethod.GET, SURGE, Caller.RIDER, 403),
        row(HttpMethod.GET, SURGE, Caller.DRIVER, 403),
        row(HttpMethod.GET, SURGE, Caller.ADMIN, 200),
        row(HttpMethod.POST, SURGE + "/overrides", Caller.ANONYMOUS, 401),
        row(HttpMethod.POST, SURGE + "/overrides", Caller.RIDER, 403),
        row(HttpMethod.POST, SURGE + "/overrides", Caller.ADMIN, 201),
        row(HttpMethod.DELETE, SURGE + "/overrides/tdr1v9", Caller.ANONYMOUS, 401),
        row(HttpMethod.DELETE, SURGE + "/overrides/tdr1v9", Caller.DRIVER, 403),
        row(HttpMethod.DELETE, SURGE + "/overrides/tdr1v9", Caller.ADMIN, 204),
        row(HttpMethod.POST, SURGE + "/kill-switch", Caller.ANONYMOUS, 401),
        row(HttpMethod.POST, SURGE + "/kill-switch", Caller.RIDER, 403),
        row(HttpMethod.POST, SURGE + "/kill-switch", Caller.ADMIN, 200),

        // --- flagged trips (Phase 6): ADMIN only ---
        row(HttpMethod.GET, "/api/v1/admin/trips/flagged", Caller.ANONYMOUS, 401),
        row(HttpMethod.GET, "/api/v1/admin/trips/flagged", Caller.RIDER, 403),
        row(HttpMethod.GET, "/api/v1/admin/trips/flagged", Caller.DRIVER, 403),
        row(HttpMethod.GET, "/api/v1/admin/trips/flagged", Caller.ADMIN, 200),

        // --- WebSocket handshake (Phase 2): open over HTTP, because the token travels in
        // the STOMP CONNECT frame. A plain GET without an upgrade is not a WebSocket
        // request, so 400 here shows the path is reachable, not that anything is allowed:
        // StompSecurityIntegrationTest covers what happens after the upgrade.
        row(HttpMethod.GET, "/ws", Caller.ANONYMOUS, 400),

        // --- auth routes: open, because they are how a caller gets a token ---
        row(HttpMethod.POST, "/api/v1/auth/login", Caller.ANONYMOUS, 400),

        // --- actuator, from Phase 0's two chains ---
        row(HttpMethod.GET, "/actuator/health/liveness", Caller.ANONYMOUS, 200),
        row(HttpMethod.GET, "/actuator/prometheus", Caller.ANONYMOUS, 401),
        row(HttpMethod.GET, "/actuator/modulith", Caller.ANONYMOUS, 401));
  }

  private static Arguments row(HttpMethod method, String path, Caller caller, int expectedStatus) {
    return Arguments.of(method, path, caller, expectedStatus);
  }

  @ParameterizedTest(name = "{1} as {2} -> {3}")
  @MethodSource("matrix")
  @DisplayName("every endpoint answers each caller with the status it should")
  void enforcesAuthorization(HttpMethod method, String path, Caller caller, int expectedStatus)
      throws Exception {

    MockHttpServletRequestBuilder request =
        switch (method.name()) {
          case "GET" -> get(path);
          case "POST" -> post(path);
          case "PUT" -> put(path);
          case "DELETE" -> delete(path);
          default -> throw new IllegalArgumentException("unhandled method " + method);
        };

    // A body for the routes that need one, so a 400 never masquerades as a 403.
    if (method != HttpMethod.GET) {
      request.contentType(MediaType.APPLICATION_JSON).content(bodyFor(path));
    }
    if (method == HttpMethod.POST && (path.equals(TRIPS) || path.equals(TOPUPS))) {
      request.header("Idempotency-Key", UUID.randomUUID().toString());
    }
    tokenFor(caller).ifPresent(token -> request.header("Authorization", "Bearer " + token));

    int actual = mockMvc.perform(request).andReturn().getResponse().getStatus();
    assertThat(actual)
        .withFailMessage(
            "%s %s as %s: expected %d but got %d", method, path, caller, expectedStatus, actual)
        .isEqualTo(expectedStatus);
  }

  private java.util.Optional<String> tokenFor(Caller caller) {
    return switch (caller) {
      case ANONYMOUS -> java.util.Optional.empty();
      case RIDER -> java.util.Optional.of(riderToken);
      case DRIVER -> java.util.Optional.of(driverToken);
      case ADMIN -> java.util.Optional.of(adminToken);
      // Not a JWT at all: the resource server must reject it before any route runs.
      case GARBAGE_TOKEN -> java.util.Optional.of("not.a.jwt");
    };
  }

  private static String bodyFor(String path) {
    if (path.endsWith("/vehicle")) {
      return """
          {"type":"CAR","plateNumber":"KA01AB1234","model":"Swift","colour":"White"}""";
    }
    if (path.endsWith("/surge/overrides")) {
      return """
          {"cell":"tdr1v9","multiplier":1.5,"expiresAt":"%s"}"""
          .formatted(java.time.Instant.now().plus(java.time.Duration.ofHours(1)));
    }
    if (path.endsWith("/kill-switch")) {
      // Off: the matrix only checks who may call it, and must not leave surge switched off.
      return """
          {"on":false}""";
    }
    if (path.equals(TOPUPS)) {
      return """
          {"amountPaise":50000}""";
    }
    if (path.endsWith("/rating")) {
      return """
          {"stars":5}""";
    }
    if (path.endsWith("/reject")) {
      return """
          {"reason":"Documents unreadable"}""";
    }
    if (path.equals(TRIPS)) {
      return """
          {"quoteId":"%s","vehicleType":"CAR","paymentMethod":"WALLET"}"""
          .formatted(UUID.randomUUID());
    }
    if (path.endsWith("/cancel")) {
      return """
          {"reason":"Plans changed"}""";
    }
    if (path.endsWith("/start")) {
      return """
          {"otp":"1234"}""";
    }
    if (path.equals(QUOTES)) {
      return """
          {"pickup":{"lat":12.9752,"lng":77.6064},"drop":{"lat":12.9784,"lng":77.6408}}""";
    }
    if (path.endsWith("/login")) {
      // Deliberately empty, so this row asserts the route is open rather than that a
      // login succeeds.
      return "{}";
    }
    return "{}";
  }
}
