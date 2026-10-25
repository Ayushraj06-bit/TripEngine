package com.rideflow.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;

import com.rideflow.simulator.BackendApi.ApiException;
import com.rideflow.simulator.Geo.Point;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The backend when what it depends on fails, through the full stack (Phase 10). Run with {@code
 * ./mvnw -Pchaos verify}: each test breaks something on purpose - pauses Redis, stops Kafka,
 * restarts the backend - and checks that it fails fast, loses nothing, and recovers on its own.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullStackChaos {

  private static final Logger log = LoggerFactory.getLogger(FullStackChaos.class);

  private static final Point MG_ROAD = new Point(12.9752, 77.6064);

  /** Fast, short trips in a small area, as in the E2E run, so trips complete within a test. */
  private static final String[] BUSY_CITY = {
    "simulator.tick=1500ms",
    "simulator.gps-noise-meters=2",
    "simulator.service-area.min-lat=12.965",
    "simulator.service-area.max-lat=12.985",
    "simulator.service-area.min-lng=77.595",
    "simulator.service-area.max-lng=77.615",
    "simulator.demand.min-trip-meters=400",
    "simulator.demand.max-trip-meters=800",
    "simulator.demand.hotspot-radius-meters=400",
    "simulator.driver.accept-probability=0.95",
    "simulator.driver.response-delay.min=300ms",
    "simulator.driver.response-delay.max=800ms",
    "simulator.driver.min-speed-kmph=70",
    "simulator.driver.max-speed-kmph=90",
    "simulator.driver.disconnect-probability=0",
    "simulator.driver.spoof-probability=0"
  };

  @BeforeAll
  static void startTheStack() {
    FullStack.start();
  }

  private static ConfigurableApplicationContext simulator(String... settings) {
    List<String> all = new java.util.ArrayList<>(List.of(BUSY_CITY));
    all.addAll(List.of(settings));
    return FullStack.simulator(all.toArray(String[]::new));
  }

  private static void awaitStarted(ConfigurableApplicationContext app) {
    Simulation simulation = app.getBean(Simulation.class);
    await().atMost(Duration.ofMinutes(3)).until(() -> simulation.startedAt().isPresent());
  }

  private static UserSession user(BackendApi api, Clock clock, String role, String phone) {
    String password = "chaos-user-password";
    return new UserSession(
        api, clock, phone, password, api.registerOrLogin(role, phone, "Chaos " + role, password));
  }

  /**
   * Redis paused while 20 drivers stream positions: requests that need it fail within seconds and
   * the thread count stays flat, readiness goes DOWN while liveness stays UP, and once Redis is
   * back everything works again with nobody touching it.
   */
  @Test
  @Order(1)
  void failsFastWhileRedisIsPausedAndRecoversOnItsOwn() throws Exception {
    try (ConfigurableApplicationContext app =
        simulator(
            "simulator.drivers=20",
            "simulator.riders=0",
            "simulator.demand.requests-per-minute=0")) {
      awaitStarted(app);
      BackendApi api = app.getBean(BackendApi.class);
      UserSession rider = user(api, app.getBean(Clock.class), "RIDER", "+917300000001");
      String accepted = "result=\"accepted\"";
      await()
          .atMost(Duration.ofSeconds(60))
          .until(() -> FullStack.metric("location_updates_total", accepted) > 40);
      assertThat(FullStack.get("/readyz").status()).isEqualTo(200);
      double threadsBefore = FullStack.metric("jvm_threads_live_threads", "");

      var docker = FullStack.REDIS.getDockerClient();
      docker.pauseContainerCmd(FullStack.REDIS.getContainerId()).exec();
      try {
        await()
            .atMost(Duration.ofSeconds(20))
            .until(() -> FullStack.get("/readyz").status() == 503);

        // A rider's request that needs Redis is refused in about a second: 503, not a hang.
        long start = System.nanoTime();
        ApiException refused =
            catchThrowableOfType(
                ApiException.class,
                () -> api.nearbyDrivers(rider, new Point(12.9701, 77.6001), "CAR"));
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        assertThat(refused.status()).isEqualTo(503);
        assertThat(took).isLessThan(Duration.ofSeconds(5));

        // Twenty seconds of positions arriving with Redis away: the process stays live and
        // answers at once, and drops the positions rather than queueing them.
        await()
            .during(Duration.ofSeconds(20))
            .atMost(Duration.ofSeconds(30))
            .until(
                () -> {
                  FullStack.Timed live = FullStack.get("/livez");
                  return live.status() == 200 && live.took().compareTo(Duration.ofSeconds(1)) < 0;
                });
        assertThat(FullStack.metric("location_updates_total", "result=\"unavailable\""))
            .isPositive();
        double threadsDuring = FullStack.metric("jvm_threads_live_threads", "");
        log.info(
            "Live threads before the pause {}, after 20 s of it {}", threadsBefore, threadsDuring);
        assertThat(threadsDuring).as("threads piling up").isLessThan(threadsBefore + 50);
      } finally {
        docker.unpauseContainerCmd(FullStack.REDIS.getContainerId()).exec();
      }

      // Back on its own: ready again, positions taken again, the rider's request answered.
      await().atMost(Duration.ofSeconds(60)).until(() -> FullStack.get("/readyz").status() == 200);
      double acceptedAfter = FullStack.metric("location_updates_total", accepted);
      await()
          .atMost(Duration.ofSeconds(60))
          .until(() -> FullStack.metric("location_updates_total", accepted) > acceptedAfter + 20);
      await()
          .atMost(Duration.ofSeconds(30))
          .ignoreExceptions()
          .until(() -> api.nearbyDrivers(rider, new Point(12.9702, 77.6002), "CAR") >= 0);
    }
  }

  /**
   * Kafka stopped for a minute while trips complete: every trip event waits in the publication
   * registry, and once Kafka is back each reaches the topic. Kafka delivers at least once, so a
   * consumer may see one twice; the backend's own consumers count each once.
   */
  @Test
  @Order(2)
  void losesNoEventWhileKafkaIsDown() throws Exception {
    long completedBefore = FullStack.count("SELECT count(*) FROM trips WHERE status = 'COMPLETED'");
    var docker = FullStack.KAFKA.getDockerClient();
    String kafka = FullStack.KAFKA.getContainerId();
    try (ConfigurableApplicationContext app =
        simulator(
            "simulator.drivers=15",
            "simulator.riders=8",
            "simulator.demand.requests-per-minute=30",
            "simulator.demand.payment-method=CASH")) {
      awaitStarted(app);
      await()
          .atMost(Duration.ofMinutes(3))
          .until(
              () ->
                  FullStack.count("SELECT count(*) FROM trips WHERE status = 'COMPLETED'")
                      > completedBefore);

      docker.stopContainerCmd(kafka).exec();
      long completedAtStop =
          FullStack.count("SELECT count(*) FROM trips WHERE status = 'COMPLETED'");
      try {
        // A minute without Kafka, trips carrying on: the backend stays live throughout.
        await()
            .during(Duration.ofSeconds(60))
            .atMost(Duration.ofSeconds(75))
            .until(() -> FullStack.get("/livez").status() == 200);
        assertThat(FullStack.metric("events_publications_incomplete", ""))
            .as("events waiting for Kafka")
            .isPositive();
        assertThat(FullStack.count("SELECT count(*) FROM trips WHERE status = 'COMPLETED'"))
            .as("trips completed while Kafka was down")
            .isGreaterThan(completedAtStop);
      } finally {
        docker.startContainerCmd(kafka).exec();
      }
    }

    // Every publication completes once Kafka is back: redelivery runs every few seconds here.
    await()
        .atMost(Duration.ofMinutes(4))
        .pollInterval(Duration.ofSeconds(5))
        .until(
            () ->
                FullStack.count(
                        "SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                    == 0);

    Set<String> published = new HashSet<>();
    for (Object id :
        FullStack.query(
            "SELECT DISTINCT serialized_event::jsonb ->> 'eventId' FROM event_publication"
                + " WHERE listener_id ILIKE '%externaliz%'")) {
      published.add((String) id);
    }
    List<String> onTopic = topicEventIds();
    Set<String> distinct = new HashSet<>(onTopic);
    log.info(
        "{} trip events published, {} records on the topic, {} distinct",
        published.size(),
        onTopic.size(),
        distinct.size());
    assertThat(published).as("events externalised").isNotEmpty();
    assertThat(distinct).as("events lost on the way to Kafka").containsAll(published);

    // Consumers count each event once: one charge per completed trip, however often its
    // TripCompleted arrived.
    assertThat(
            FullStack.count(
                "SELECT count(*) FROM trips t WHERE t.status = 'COMPLETED' AND (SELECT count(*)"
                    + " FROM ledger_transactions lt WHERE lt.reference_id = t.id"
                    + " AND lt.type IN ('TRIP_CHARGE', 'CASH_COMMISSION')) <> 1"))
        .as("completed trips charged other than once")
        .isZero();
  }

  /**
   * The backend killed while a trip waits for a driver, then started again: the trip is matched by
   * the new process - from the matching state in Redis, or the recovery sweep - and assigned.
   */
  @Test
  @Order(3)
  void assignsATripBookedBeforeTheBackendRestarted() throws Exception {
    UUID tripId;
    long farePaise;
    UserSession rider;
    try (ConfigurableApplicationContext app =
        FullStack.simulator("simulator.drivers=0", "simulator.riders=0")) {
      BackendApi api = app.getBean(BackendApi.class);
      rider = user(api, app.getBean(Clock.class), "RIDER", "+917300000002");
      Point drop = Geo.offset(MG_ROAD, 90, 800);
      var quote =
          java.util.stream.StreamSupport.stream(
                  api.quote(rider, MG_ROAD, drop).get("quotes").spliterator(), false)
              .filter(q -> "CAR".equals(q.get("vehicleType").asString()))
              .findFirst()
              .orElseThrow();
      farePaise = quote.get("totalPaise").asLong();
      // Nobody is online near the pickup: matching keeps searching.
      tripId =
          UUID.fromString(
              api.book(
                      rider,
                      UUID.randomUUID().toString(),
                      quote.get("quoteId").asString(),
                      "CAR",
                      "CASH")
                  .get("id")
                  .asString());
    }

    Instant killed = Instant.now();
    FullStack.restartBackend();
    log.info("Backend restarted in {}", Duration.between(killed, Instant.now()));

    // A new app context: the backend's port changed with its container.
    try (ConfigurableApplicationContext app =
        FullStack.simulator("simulator.drivers=0", "simulator.riders=0")) {
      BackendApi api = app.getBean(BackendApi.class);
      Clock clock = app.getBean(Clock.class);
      UserSession admin =
          new UserSession(
              api,
              clock,
              FullStack.ADMIN_PHONE,
              FullStack.ADMIN_PASSWORD,
              api.login(FullStack.ADMIN_PHONE, FullStack.ADMIN_PASSWORD));
      UserSession driver = user(api, clock, "DRIVER", "+917300000003");
      var profile = api.driverProfile(driver);
      api.registerVehicle(driver, "CAR", "KA01CH0003");
      api.approve(admin, UUID.fromString(profile.get("driverId").asString()));

      String stompUrl = FullStack.backendUrl().toString().replaceFirst("^http", "ws") + "/ws";
      Map<String, List<tools.jackson.databind.JsonNode>> inbox =
          new java.util.concurrent.ConcurrentHashMap<>();
      StompLink link =
          new StompLink(
              app.getBean(WebSocketStompClient.class), stompUrl, app.getBean(JsonMapper.class));
      link.connect(
          driver.accessToken(),
          List.of("/user/queue/offers"),
          new StompLink.Listener() {
            @Override
            public void onMessage(String destination, tools.jackson.databind.JsonNode envelope) {
              inbox
                  .computeIfAbsent(
                      destination, d -> new java.util.concurrent.CopyOnWriteArrayList<>())
                  .add(envelope);
            }

            @Override
            public void onLost() {}
          });
      api.goOnline(driver);

      // Online at the pickup, reporting every second, until the trip is offered. Trips the
      // earlier tests' riders left searching may be offered first: an offer names no trip, so
      // anything not at this trip's exact fare is refused and the wait goes on.
      long[] seq = {0};
      Set<String> refused = new HashSet<>();
      tools.jackson.databind.JsonNode[] ours = {null};
      await()
          .pollInterval(Duration.ofMillis(1_100))
          .atMost(Duration.ofSeconds(90))
          .until(
              () -> {
                Map<String, Object> update = new LinkedHashMap<>();
                update.put("lat", MG_ROAD.lat());
                update.put("lng", MG_ROAD.lng());
                update.put("accuracyMeters", 5);
                update.put("seq", ++seq[0]);
                update.put("clientTime", Instant.now().toString());
                link.send(SimulatedDriver.LOCATION, update);
                for (var envelope : inbox.getOrDefault("/user/queue/offers", List.of())) {
                  var payload = envelope.get("payload");
                  String offerId = payload.get("offerId").asString();
                  if (payload.get("estimatedFarePaise").asLong() == farePaise) {
                    ours[0] = payload;
                  } else if (refused.add(offerId)) {
                    api.rejectOffer(driver, UUID.fromString(offerId));
                  }
                }
                return ours[0] != null;
              });

      var offer = ours[0];
      var accepted = api.acceptOffer(driver, UUID.fromString(offer.get("offerId").asString()));
      assertThat(accepted.get("tripId").asString()).isEqualTo(tripId.toString());
      assertThat(api.trip(rider, tripId).get("status").asString()).isEqualTo("DRIVER_ASSIGNED");
      link.disconnect();
    }
  }

  /** Every eventId on rideflow.trip-events, duplicates included, read inside the network. */
  private static List<String> topicEventIds() throws Exception {
    var result =
        FullStack.KAFKA.execInContainer(
            "/opt/kafka/bin/kafka-console-consumer.sh",
            "--bootstrap-server",
            "kafka:19092",
            "--topic",
            "rideflow.trip-events",
            "--from-beginning",
            "--timeout-ms",
            "20000");
    List<String> ids = new java.util.ArrayList<>();
    Matcher matcher =
        Pattern.compile("\"eventId\":\"([0-9a-f-]{36})\"").matcher(result.getStdout());
    while (matcher.find()) {
      ids.add(matcher.group(1));
    }
    return ids;
  }
}
