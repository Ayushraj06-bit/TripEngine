package com.rideflow.simulator;

import com.rideflow.simulator.Geo.Point;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * The backend's public REST API, as the apps call it. Nothing here knows the backend's classes:
 * requests are plain maps and answers plain JSON.
 *
 * <p>Every call is counted on {@code simulator.requests} by operation, and every failure on {@code
 * simulator.errors} by the problem {@code code} the backend answered with - {@code
 * OFFER_NOT_PENDING}, {@code TRIP_INVALID_TRANSITION} - or {@code IO} when there was no answer.
 */
@Component
public class BackendApi {

  private static final String PAY_PAGE = "/mock-gateway/payments/{ref}/pay";

  private final RestClient http;
  private final URI backendUrl;
  private final MeterRegistry meters;

  BackendApi(RestClient.Builder builder, SimulatorProperties properties, MeterRegistry meters) {
    this.backendUrl = properties.backendUrl();
    this.http = builder.baseUrl(backendUrl.toString()).build();
    this.meters = meters;
  }

  /** A refusal from the backend, or no answer at all ({@code status} 0, {@code code} IO). */
  public static class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    ApiException(String operation, int status, String code, Throwable cause) {
      super(operation + " failed: " + status + " " + code, cause);
      this.status = status;
      this.code = code;
    }

    public int status() {
      return status;
    }

    public String code() {
      return code;
    }
  }

  /** What the auth endpoints hand out. */
  public record Tokens(String accessToken, String refreshToken, long expiresInSeconds) {

    static Tokens of(JsonNode json) {
      return new Tokens(
          json.get("accessToken").asString(),
          json.get("refreshToken").asString(),
          json.get("expiresInSeconds").asLong());
    }
  }

  // --- Accounts ----------------------------------------------------------------------------------

  /** Registers, or logs in if the phone is already registered: what makes setup idempotent. */
  public Tokens registerOrLogin(String role, String phone, String name, String password) {
    try {
      return Tokens.of(
          call(
                  "auth.register",
                  HttpMethod.POST,
                  "/api/v1/auth/register/" + role.toLowerCase(java.util.Locale.ROOT),
                  null,
                  Map.of("phone", phone, "name", name, "password", password),
                  null)
              .orElseThrow());
    } catch (ApiException e) {
      if (e.status() != 409) {
        throw e;
      }
      return login(phone, password);
    }
  }

  public Tokens login(String phone, String password) {
    return Tokens.of(
        call(
                "auth.login",
                HttpMethod.POST,
                "/api/v1/auth/login",
                null,
                Map.of("phone", phone, "password", password),
                null)
            .orElseThrow());
  }

  public Tokens refresh(String refreshToken) {
    return Tokens.of(
        call(
                "auth.refresh",
                HttpMethod.POST,
                "/api/v1/auth/refresh",
                null,
                Map.of("refreshToken", refreshToken),
                null)
            .orElseThrow());
  }

  // --- Drivers -----------------------------------------------------------------------------------

  public JsonNode driverProfile(UserSession driver) {
    return get("drivers.me", "/api/v1/drivers/me", driver).orElseThrow();
  }

  public void registerVehicle(UserSession driver, String type, String plate) {
    call(
        "drivers.vehicle",
        HttpMethod.PUT,
        "/api/v1/drivers/me/vehicle",
        driver,
        Map.of("type", type, "plateNumber", plate, "model", "Simulated", "colour", "White"),
        null);
  }

  public void goOnline(UserSession driver) {
    post("drivers.online", "/api/v1/drivers/me/online", driver, null, null);
  }

  public void approve(UserSession admin, UUID driverId) {
    post("admin.approve", "/api/v1/admin/drivers/{id}/approve", admin, null, null, driverId);
  }

  public JsonNode acceptOffer(UserSession driver, UUID offerId) {
    return post("offers.accept", "/api/v1/offers/{id}/accept", driver, null, null, offerId)
        .orElseThrow();
  }

  public void rejectOffer(UserSession driver, UUID offerId) {
    post("offers.reject", "/api/v1/offers/{id}/reject", driver, null, null, offerId);
  }

  /** The offer the driver holds, if any: how a reconnected app catches up. */
  public Optional<JsonNode> currentOffer(UserSession driver) {
    return get("offers.current", "/api/v1/offers/current", driver);
  }

  // --- Trips -------------------------------------------------------------------------------------

  /** How many cars of {@code type} the rider's map shows near {@code at}. */
  public int nearbyDrivers(UserSession rider, Point at, String type) {
    return get(
            "drivers.nearby",
            "/api/v1/drivers/nearby?lat={lat}&lng={lng}&type={type}",
            rider,
            at.lat(),
            at.lng(),
            type)
        .orElseThrow()
        .size();
  }

  public JsonNode quote(UserSession rider, Point pickup, Point drop) {
    return post(
            "fares.quote",
            "/api/v1/fares/quotes",
            rider,
            Map.of(
                "pickup", Map.of("lat", pickup.lat(), "lng", pickup.lng()),
                "drop", Map.of("lat", drop.lat(), "lng", drop.lng())),
            null)
        .orElseThrow();
  }

  public JsonNode book(
      UserSession rider,
      String idempotencyKey,
      String quoteId,
      String vehicleType,
      String paymentMethod) {
    return post(
            "trips.book",
            "/api/v1/trips",
            rider,
            Map.of("quoteId", quoteId, "vehicleType", vehicleType, "paymentMethod", paymentMethod),
            idempotencyKey)
        .orElseThrow();
  }

  /** The caller's trip in progress, or empty once there is none: the REST source of truth. */
  public Optional<JsonNode> activeTrip(UserSession user) {
    return get("trips.active", "/api/v1/trips/active", user);
  }

  public JsonNode trip(UserSession user, UUID tripId) {
    return get("trips.get", "/api/v1/trips/{id}", user, tripId).orElseThrow();
  }

  public JsonNode cancel(UserSession user, UUID tripId, String reason) {
    return post(
            "trips.cancel",
            "/api/v1/trips/{id}/cancel",
            user,
            Map.of("reason", reason),
            null,
            tripId)
        .orElseThrow();
  }

  public JsonNode arrive(UserSession driver, UUID tripId) {
    return post("trips.arrive", "/api/v1/trips/{id}/arrive", driver, null, null, tripId)
        .orElseThrow();
  }

  public JsonNode start(UserSession driver, UUID tripId, String otp) {
    return post("trips.start", "/api/v1/trips/{id}/start", driver, Map.of("otp", otp), null, tripId)
        .orElseThrow();
  }

  public JsonNode complete(UserSession driver, UUID tripId) {
    return post("trips.complete", "/api/v1/trips/{id}/complete", driver, null, null, tripId)
        .orElseThrow();
  }

  public void rate(UserSession user, UUID tripId, int stars) {
    post(
        "trips.rate",
        "/api/v1/trips/{id}/rating",
        user,
        Map.of("stars", stars, "comment", "Simulated"),
        null,
        tripId);
  }

  // --- Wallet ------------------------------------------------------------------------------------

  public long walletBalance(UserSession user) {
    return get("wallet.get", "/api/v1/wallet?limit={limit}", user, 1)
        .orElseThrow()
        .get("balancePaise")
        .asLong();
  }

  /**
   * Starts a top-up and pays it on the mock gateway's page, as a rider tapping "pay" would. The
   * gateway's webhook credits the wallet shortly after; this does not wait for it.
   */
  public void topUp(UserSession rider, long amountPaise) {
    JsonNode topup =
        post(
                "wallet.topup",
                "/api/v1/wallet/topups",
                rider,
                Map.of("amountPaise", amountPaise),
                UUID.randomUUID().toString())
            .orElseThrow();
    // The page's address is the gateway's to choose; only its path is reached through ours. As a
    // template, so every payment counts under one URI in the HTTP client's metrics.
    String path = URI.create(topup.get("paymentUrl").asString()).getPath();
    String[] segments = path.split("/");
    if (path.matches("/mock-gateway/payments/[^/]+/pay")) {
      call("gateway.pay", HttpMethod.POST, PAY_PAGE, null, null, null, segments[3]);
    } else {
      call("gateway.pay", HttpMethod.POST, path, null, null, null);
    }
  }

  // --- Plumbing ----------------------------------------------------------------------------------

  private Optional<JsonNode> get(
      String operation, String template, UserSession user, Object... variables) {
    return call(operation, HttpMethod.GET, template, user, null, null, variables);
  }

  private Optional<JsonNode> post(
      String operation,
      String template,
      UserSession user,
      Object body,
      String idempotencyKey,
      Object... variables) {
    return call(operation, HttpMethod.POST, template, user, body, idempotencyKey, variables);
  }

  /**
   * One request: empty for a 204, the JSON otherwise, an {@link ApiException} for a failure.
   *
   * @param template with {@code {variables}} rather than ids written in: the HTTP client's metrics
   *     are tagged by template, and one per trip would be thousands of series
   */
  private Optional<JsonNode> call(
      String operation,
      HttpMethod method,
      String template,
      UserSession user,
      Object body,
      String idempotencyKey,
      Object... variables) {
    meters.counter("simulator.requests", "operation", operation).increment();
    try {
      RestClient.RequestBodySpec request = http.method(method).uri(template, variables);
      if (user != null) {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken());
      }
      if (idempotencyKey != null) {
        request.header("Idempotency-Key", idempotencyKey);
      }
      if (body != null) {
        request.contentType(MediaType.APPLICATION_JSON).body(body);
      }
      return Optional.ofNullable(request.retrieve().toEntity(JsonNode.class).getBody());
    } catch (RestClientResponseException e) {
      String code = problemCode(e);
      meters.counter("simulator.errors", "type", code).increment();
      throw new ApiException(operation, e.getStatusCode().value(), code, e);
    } catch (ResourceAccessException e) {
      meters.counter("simulator.errors", "type", "IO").increment();
      throw new ApiException(operation, 0, "IO", e);
    }
  }

  /** The RFC 9457 problem's {@code code}, or the bare status when the body has none. */
  private static String problemCode(RestClientResponseException e) {
    try {
      JsonNode problem = e.getResponseBodyAs(JsonNode.class);
      if (problem != null && problem.hasNonNull("code")) {
        return problem.get("code").asString();
      }
    } catch (RuntimeException unreadable) {
      // Not JSON: fall through to the status.
    }
    return "HTTP_" + e.getStatusCode().value();
  }
}
