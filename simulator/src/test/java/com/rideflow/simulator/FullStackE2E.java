package com.rideflow.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rideflow.simulator.Geo.Point;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The backend end to end, through its public REST and STOMP APIs only: one ride by hand, then the
 * simulator's traffic for a minute. Run with {@code ./mvnw -Pe2e verify}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullStackE2E {

  private static final Point MG_ROAD = new Point(12.9752, 77.6064);

  /** Fast enough that a trip fits in a test, slow enough for the backend's speed check. */
  private static final double TEST_SPEED_KMPH = 100;

  private static final Duration STEP = Duration.ofMillis(1_100);

  @BeforeAll
  static void startTheStack() {
    FullStack.start();
  }

  /** One app's messages, by destination. */
  private record Inbox(Map<String, List<JsonNode>> byDestination) implements StompLink.Listener {

    Inbox() {
      this(new java.util.concurrent.ConcurrentHashMap<>());
    }

    @Override
    public void onMessage(String destination, JsonNode envelope) {
      byDestination.computeIfAbsent(destination, d -> new CopyOnWriteArrayList<>()).add(envelope);
    }

    @Override
    public void onLost() {}

    List<JsonNode> on(String destination) {
      return byDestination.getOrDefault(destination, List.of());
    }

    Optional<JsonNode> find(String destination, String type) {
      return on(destination).stream()
          .filter(m -> type.equals(m.get("type").asString()))
          .reduce((first, second) -> second);
    }
  }

  /**
   * Register, approve, go online, stream location, quote, book, receive the offer, accept, arrive,
   * start with the OTP, the rider watching the car come, complete, pay from the wallet, and rate
   * both ways.
   */
  @Test
  @Order(1)
  void carriesOneRideFromSignUpToRatings() throws Exception {
    try (ConfigurableApplicationContext app =
        FullStack.simulator("simulator.drivers=0", "simulator.riders=0")) {
      BackendApi api = app.getBean(BackendApi.class);
      Clock clock = app.getBean(Clock.class);
      JsonMapper json = app.getBean(JsonMapper.class);
      WebSocketStompClient stomp = app.getBean(WebSocketStompClient.class);
      String stompUrl = FullStack.backendUrl().toString().replaceFirst("^http", "ws") + "/ws";

      // Sign-up, and the admin's approval.
      UserSession admin =
          new UserSession(
              api,
              clock,
              FullStack.ADMIN_PHONE,
              FullStack.ADMIN_PASSWORD,
              api.login(FullStack.ADMIN_PHONE, FullStack.ADMIN_PASSWORD));
      UserSession driver = user(api, clock, "DRIVER", "+917200000001");
      UserSession rider = user(api, clock, "RIDER", "+917200000002");
      JsonNode profile = api.driverProfile(driver);
      assertThat(profile.get("approvalStatus").asString()).isEqualTo("PENDING");
      api.registerVehicle(driver, "CAR", "KA01E2E0001");
      api.approve(admin, UUID.fromString(profile.get("driverId").asString()));

      // A wallet with money in it, through the mock gateway's payment page.
      api.topUp(rider, 200_000);
      await().atMost(Duration.ofSeconds(30)).until(() -> api.walletBalance(rider) == 200_000);

      // Both apps connected.
      Inbox driverApp = new Inbox();
      Inbox riderApp = new Inbox();
      StompLink driverLink = new StompLink(stomp, stompUrl, json);
      StompLink riderLink = new StompLink(stomp, stompUrl, json);
      driverLink.connect(
          driver.accessToken(),
          List.of("/user/queue/offers", "/user/queue/trip-updates"),
          driverApp);
      riderLink.connect(
          rider.accessToken(),
          List.of("/user/queue/trip-updates", "/user/queue/driver-location"),
          riderApp);

      // Online, and streaming until the rider's map shows the car.
      Movement car = new Movement(Geo.offset(MG_ROAD, 0, 300));
      AtomicLong seq = new AtomicLong();
      api.goOnline(driver);
      await()
          .pollInterval(STEP)
          .atMost(Duration.ofSeconds(30))
          .until(
              () -> {
                report(driverLink, car, seq);
                return api.nearbyDrivers(rider, MG_ROAD, "CAR") > 0;
              });

      // Quote and book - twice with one key, which books once.
      Point drop = Geo.offset(MG_ROAD, 90, 600);
      JsonNode quote = carQuote(api.quote(rider, MG_ROAD, drop));
      String key = UUID.randomUUID().toString();
      JsonNode booked = api.book(rider, key, quote.get("quoteId").asString(), "CAR", "WALLET");
      assertThat(booked.get("status").asString()).isEqualTo("REQUESTED");
      assertThat(
              api.book(rider, key, quote.get("quoteId").asString(), "CAR", "WALLET")
                  .get("id")
                  .asString())
          .isEqualTo(booked.get("id").asString());
      UUID tripId = UUID.fromString(booked.get("id").asString());

      // The offer reaches the driver's app, who accepts.
      await()
          .atMost(Duration.ofSeconds(15))
          .until(() -> driverApp.find("/user/queue/offers", "OFFER").isPresent());
      JsonNode offer = driverApp.find("/user/queue/offers", "OFFER").orElseThrow().get("payload");
      JsonNode accepted = api.acceptOffer(driver, UUID.fromString(offer.get("offerId").asString()));
      assertThat(accepted.get("tripId").asString()).isEqualTo(tripId.toString());

      // The rider's app shows the code; the driver's never does.
      await()
          .atMost(Duration.ofSeconds(15))
          .until(() -> riderApp.find("/user/queue/trip-updates", "DriverAssigned").isPresent());
      String otp =
          riderApp
              .find("/user/queue/trip-updates", "DriverAssigned")
              .orElseThrow()
              .get("payload")
              .get("otp")
              .asString();
      assertThat(otp).matches("\\d{4}");
      assertThat(api.trip(driver, tripId).has("otp")).isFalse();

      // To the pickup, and arrived.
      drive(driverLink, car, MG_ROAD, seq);
      assertThat(api.arrive(driver, tripId).get("status").asString()).isEqualTo("DRIVER_ARRIVED");

      // Started with the rider's code, then to the drop with the rider watching.
      assertThat(api.start(driver, tripId, otp).get("status").asString()).isEqualTo("IN_PROGRESS");
      drive(driverLink, car, drop, seq);
      await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () ->
                  riderApp.on("/user/queue/driver-location").stream()
                      .anyMatch(m -> tripId.toString().equals(m.get("tripId").asString())));

      // Completed, and paid from the wallet.
      JsonNode completed = api.complete(driver, tripId);
      assertThat(completed.get("status").asString()).isEqualTo("COMPLETED");
      long fare = completed.get("finalFarePaise").asLong();
      assertThat(fare).isPositive();
      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> api.walletBalance(rider) == 200_000 - fare);

      // Rated both ways.
      api.rate(rider, tripId, 5);
      api.rate(driver, tripId, 4);
      assertThat(FullStack.count("SELECT count(*) FROM ratings WHERE trip_id = '" + tripId + "'"))
          .isEqualTo(2);
      assertThat(api.driverProfile(driver).get("ratingCount").asInt()).isOne();

      driverLink.disconnect();
      riderLink.disconnect();
    }
  }

  /**
   * Twenty drivers and ten riders for a minute, in a small area at test speeds so trips finish
   * inside it. Then the invariants that must hold whatever the traffic did.
   */
  @Test
  @Order(2)
  void keepsItsInvariantsUnderAMinuteOfSimulatedTraffic() throws Exception {
    try (ConfigurableApplicationContext app =
        FullStack.simulator(
            "simulator.drivers=20",
            "simulator.riders=10",
            "simulator.tick=1500ms",
            "simulator.gps-noise-meters=2",
            "simulator.service-area.min-lat=12.965",
            "simulator.service-area.max-lat=12.985",
            "simulator.service-area.min-lng=77.595",
            "simulator.service-area.max-lng=77.615",
            "simulator.demand.requests-per-minute=30",
            "simulator.demand.min-trip-meters=400",
            "simulator.demand.max-trip-meters=800",
            "simulator.demand.hotspot-radius-meters=400",
            "simulator.driver.accept-probability=0.9",
            "simulator.driver.response-delay.min=500ms",
            "simulator.driver.response-delay.max=1500ms",
            "simulator.driver.min-speed-kmph=70",
            "simulator.driver.max-speed-kmph=90")) {
      Simulation simulation = app.getBean(Simulation.class);
      await().atMost(Duration.ofMinutes(3)).until(() -> simulation.startedAt().isPresent());
      Instant started = simulation.startedAt().orElseThrow();
      await()
          .atMost(Duration.ofSeconds(90))
          .pollInterval(Duration.ofSeconds(1))
          .until(() -> !Instant.now().isBefore(started.plus(Duration.ofSeconds(60))));
    }

    assertThat(FullStack.count("SELECT count(*) FROM trips WHERE status = 'COMPLETED'"))
        .as("trips completed")
        .isPositive();

    // The unique index forbids two active trips at one instant; this checks every instant.
    assertThat(
            FullStack.count(
                """
                SELECT count(*) FROM trips a JOIN trips b
                  ON a.driver_id = b.driver_id AND a.id < b.id
                WHERE a.assigned_at IS NOT NULL AND b.assigned_at IS NOT NULL
                  AND tstzrange(a.assigned_at, coalesce(a.completed_at, a.cancelled_at, 'infinity'))
                   && tstzrange(b.assigned_at, coalesce(b.completed_at, b.cancelled_at, 'infinity'))
                """))
        .as("drivers with two trips at once")
        .isZero();

    // Charged after the trip, asynchronously: give the last few a moment.
    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () ->
                assertThat(
                        FullStack.query(
                            """
                            SELECT t.id FROM trips t WHERE t.status = 'COMPLETED' AND NOT EXISTS (
                              SELECT 1 FROM ledger_transactions lt
                              WHERE lt.reference_id = t.id
                                AND lt.type IN ('TRIP_CHARGE', 'CASH_COMMISSION')
                                AND (SELECT sum(CASE e.direction WHEN 'CREDIT' THEN e.amount_paise
                                                                 ELSE -e.amount_paise END)
                                     FROM ledger_entries e WHERE e.transaction_id = lt.id) = 0)
                            """))
                    .as("completed trips without a balanced ledger transaction")
                    .isEmpty());

    // ECS JSON: {"log":{"level":"ERROR",...}}. The INFO check proves the match can see levels at
    // all, so a change of log format fails here instead of passing silently.
    List<String> log = FullStack.BACKEND.getLogs().lines().toList();
    assertThat(log).anyMatch(line -> line.contains("\"level\":\"INFO\""));
    assertThat(log)
        .filteredOn(line -> line.contains("\"level\":\"ERROR\""))
        .as("error logs from the backend")
        .isEmpty();
  }

  private static UserSession user(BackendApi api, Clock clock, String role, String phone) {
    String password = "e2e-user-password";
    return new UserSession(
        api, clock, phone, password, api.registerOrLogin(role, phone, "E2E " + role, password));
  }

  private static JsonNode carQuote(JsonNode quotes) {
    for (JsonNode quote : quotes.get("quotes")) {
      if ("CAR".equals(quote.get("vehicleType").asString())) {
        return quote;
      }
    }
    throw new AssertionError("No CAR quote in " + quotes);
  }

  /** Drives to {@code target}, one position a step, as the app would. */
  private static void drive(StompLink link, Movement car, Point target, AtomicLong seq) {
    car.headFor(target, TEST_SPEED_KMPH);
    await()
        .pollInterval(STEP)
        .atMost(Duration.ofSeconds(60))
        .until(
            () -> {
              boolean there = car.advance(STEP);
              report(link, car, seq);
              return there;
            });
  }

  private static void report(StompLink link, Movement car, AtomicLong seq) {
    Map<String, Object> update = new LinkedHashMap<>();
    update.put("lat", car.position().lat());
    update.put("lng", car.position().lng());
    update.put("accuracyMeters", 5);
    update.put("seq", seq.incrementAndGet());
    update.put("clientTime", Instant.now().toString());
    assertThat(link.send(SimulatedDriver.LOCATION, update)).isTrue();
  }
}
