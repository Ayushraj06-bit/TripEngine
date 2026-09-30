package com.rideflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.rideflow.identity.Role;
import com.rideflow.identity.internal.AuthService;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
class SecurityMatrixTest {

  private static final String PASSWORD = "correct-horse-battery";

  /** Inside the configured service area, so a permitted caller gets a real answer. */
  private static final String NEARBY = "/api/v1/drivers/nearby?lat=12.9752&lng=77.6064&type=CAR";

  private static final String QUOTES = "/api/v1/fares/quotes";

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
          default -> throw new IllegalArgumentException("unhandled method " + method);
        };

    // A body for the routes that need one, so a 400 never masquerades as a 403.
    if (method != HttpMethod.GET) {
      request.contentType(MediaType.APPLICATION_JSON).content(bodyFor(path));
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
    if (path.endsWith("/reject")) {
      return """
          {"reason":"Documents unreadable"}""";
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
