package com.rideflow.location.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rideflow.TestcontainersConfiguration;
import com.rideflow.identity.Role;
import com.rideflow.identity.internal.AuthService;
import com.rideflow.notification.RealtimeMessage;
import com.rideflow.notification.RealtimePublisher;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * STOMP authentication and destination rules, over a real socket.
 *
 * <p>PROJECT_SPEC section 10: a client may SEND only to the {@code /app} destinations its role owns
 * and SUBSCRIBE only to its own {@code /user/queue} destinations. The interceptor enforcing that
 * only means something end to end - the handshake has to get through HTTP security, the CONNECT
 * frame has to bind a principal to the session, and later frames have to be judged against it - so
 * this runs a real server and a real STOMP client rather than calling the interceptor directly.
 *
 * <p>Writing it found that the HTTP handshake itself was being refused: the token travels in the
 * CONNECT frame, and the upgrade request fell to the deny-all chain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StompSecurityIntegrationTest {

  private static final Duration EVENTUALLY = Duration.ofSeconds(10);
  private static final String PASSWORD = "correct-horse-battery";

  @LocalServerPort private int port;

  @Autowired private AuthService auth;
  @Autowired private JwtEncoder jwtEncoder;
  @Autowired private Clock clock;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private SimpMessagingTemplate messaging;
  @Autowired private SimpUserRegistry userRegistry;
  @Autowired private RealtimePublisher realtime;
  @Autowired private ExpiringSessions expiringSessions;

  @Value("${rideflow.security.issuer}")
  private String issuer;

  private WebSocketStompClient client;

  @BeforeEach
  void setUp() {
    jdbc.execute("TRUNCATE refresh_tokens, vehicles, drivers, users CASCADE");
    redis.getConnectionFactory().getConnection().serverCommands().flushDb();

    client = new WebSocketStompClient(new StandardWebSocketClient());
    // Raw bytes both ways: the test controls the JSON exactly, and no client-side
    // Jackson converter has to agree with the server's about content types.
    client.setMessageConverter(new SimpleMessageConverter());
    client.setDefaultHeartbeat(new long[] {0, 0});
  }

  @AfterEach
  void tearDown() {
    client.stop();
  }

  /** A connection attempt, and every ERROR frame or transport failure it produced. */
  private record Connection(
      CompletableFuture<StompSession> session, CompletableFuture<String> error) {

    StompSession established() throws Exception {
      return session.get(EVENTUALLY.toSeconds(), TimeUnit.SECONDS);
    }

    /** True when the server refused the CONNECT, one way or the other. */
    boolean refused() throws InterruptedException {
      try {
        CompletableFuture.anyOf(session, error).get(EVENTUALLY.toSeconds(), TimeUnit.SECONDS);
      } catch (ExecutionException e) {
        return true;
      } catch (TimeoutException e) {
        return false;
      }
      boolean connected = session.isDone() && !session.isCompletedExceptionally();
      return !connected;
    }
  }

  private Connection connect(String accessToken) {
    return connect(accessToken, new WebSocketHttpHeaders());
  }

  private Connection connect(String accessToken, WebSocketHttpHeaders handshake) {
    CompletableFuture<String> error = new CompletableFuture<>();
    var handler =
        new StompSessionHandlerAdapter() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            // Frames reaching the session handler rather than a subscription are ERRORs.
            error.complete(String.valueOf(headers.getFirst("message")));
          }

          @Override
          public void handleTransportError(StompSession session, Throwable exception) {
            error.complete("transport: " + exception.getMessage());
          }

          @Override
          public void handleException(
              StompSession session,
              StompCommand command,
              StompHeaders headers,
              byte[] payload,
              Throwable exception) {
            error.complete("exception: " + exception.getMessage());
          }
        };

    StompHeaders connectHeaders = new StompHeaders();
    if (accessToken != null) {
      connectHeaders.add("Authorization", "Bearer " + accessToken);
    }
    return new Connection(
        client.connectAsync("ws://localhost:" + port + "/ws", handshake, connectHeaders, handler),
        error);
  }

  private String register(Role role, String phone) {
    return auth.register(phone, role.name(), PASSWORD, role).accessToken();
  }

  private UUID idOf(String phone) {
    return jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", UUID.class, phone);
  }

  private static void sendPosition(StompSession session, String destination) {
    StompHeaders headers = new StompHeaders();
    headers.setDestination(destination);
    headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
    String json =
        """
        {"lat":12.9752,"lng":77.6064,"accuracyMeters":10,"seq":1,\
        "clientTime":"2026-10-01T10:00:00Z"}""";
    session.send(headers, json.getBytes(StandardCharsets.UTF_8));
  }

  private boolean hasPosition(UUID userId) {
    return Boolean.TRUE.equals(redis.opsForHash().hasKey("driver:state:" + userId, "lat"));
  }

  @Nested
  class Connecting {

    @Test
    void acceptsAValidToken() throws Exception {
      Connection connection = connect(register(Role.RIDER, "+919000010001"));

      assertThat(connection.established().isConnected()).isTrue();
    }

    @Test
    void refusesAConnectWithNoToken() throws Exception {
      assertThat(connect(null).refused()).isTrue();
    }

    @Test
    void refusesATokenThatIsNotAJwt() throws Exception {
      assertThat(connect("not.a.jwt").refused()).isTrue();
    }

    /** A page on another site cannot open a socket from a visitor's browser (Phase 10). */
    @Test
    void refusesAHandshakeFromAnotherSitesPage() throws Exception {
      String token = register(Role.RIDER, "+919000010003");
      WebSocketHttpHeaders foreign = new WebSocketHttpHeaders();
      foreign.setOrigin("https://evil.example");
      WebSocketHttpHeaders own = new WebSocketHttpHeaders();
      own.setOrigin("http://localhost:" + port);

      assertThat(connect(token, foreign).refused()).isTrue();
      // The live map's own page, and apps that send no Origin at all, are let in.
      assertThat(connect(token, own).established().isConnected()).isTrue();
      assertThat(connect(token).established().isConnected()).isTrue();
    }

    /**
     * Behind nginx the page's origin carries the published port, not this server's. The origin
     * check sees it only when the proxy forwards it: without X-Forwarded-Port, Tomcat takes an http
     * request to be on port 80, and the live map's own socket is refused as foreign.
     */
    @Test
    void acceptsTheLiveMapsHandshakeThroughTheProxy() throws Exception {
      // The origin check comes before the token is read: any account will do.
      String token = register(Role.RIDER, "+919000010004");
      WebSocketHttpHeaders proxied = new WebSocketHttpHeaders();
      proxied.setOrigin("http://localhost:8080");
      proxied.add("X-Forwarded-Proto", "http");
      WebSocketHttpHeaders withPort = new WebSocketHttpHeaders();
      withPort.addAll(proxied);
      withPort.add("X-Forwarded-Port", "8080");

      assertThat(connect(token, withPort).established().isConnected()).isTrue();
      assertThat(connect(token, proxied).refused()).isTrue();
    }

    @Test
    void refusesAnExpiredToken() throws Exception {
      Instant now = clock.instant();
      String expired =
          jwtEncoder
              .encode(
                  JwtEncoderParameters.from(
                      JwsHeader.with(SignatureAlgorithm.RS256).build(),
                      JwtClaimsSet.builder()
                          .issuer(issuer)
                          .subject(UUID.randomUUID().toString())
                          .issuedAt(now.minus(Duration.ofHours(2)))
                          .expiresAt(now.minus(Duration.ofHours(1)))
                          .claim("role", "DRIVER")
                          .build()))
              .getTokenValue();

      // Correctly signed by our own key, so only the expiry check can refuse it.
      assertThat(connect(expired).refused()).isTrue();
    }
  }

  @Nested
  class Sending {

    @Test
    void acceptsADriversPosition() throws Exception {
      String token = register(Role.DRIVER, "+919000020001");
      StompSession session = connect(token).established();

      sendPosition(session, "/app/driver/location");

      // The id comes from the session's principal, so the position lands under the
      // authenticated driver and nobody else.
      UUID driverId = idOf("+919000020001");
      await().atMost(EVENTUALLY).until(() -> hasPosition(driverId));
    }

    /**
     * A reconnected app numbers its positions from zero again. Without the reset on connect, every
     * one would be ignored as older than the last session's.
     */
    @Test
    void acceptsPositionsNumberedFromZeroAgainAfterReconnecting() throws Exception {
      String token = register(Role.DRIVER, "+919000020004");
      UUID driverId = idOf("+919000020004");
      // Where an earlier session's numbering had got to.
      redis.opsForHash().put("driver:state:" + driverId, "seq", "500");

      StompSession session = connect(token).established();
      sendPosition(session, "/app/driver/location");

      await().atMost(EVENTUALLY).until(() -> hasPosition(driverId));
      assertThat(redis.opsForHash().get("driver:state:" + driverId, "seq")).isEqualTo("1");
    }

    @Test
    void refusesARiderSendingDriverPositions() throws Exception {
      String token = register(Role.RIDER, "+919000020002");
      Connection connection = connect(token);
      StompSession session = connection.established();

      sendPosition(session, "/app/driver/location");

      assertThat(connection.error().get(EVENTUALLY.toSeconds(), TimeUnit.SECONDS))
          .contains("Not permitted");
      // The interceptor rejected the frame before it reached the controller, so the
      // ERROR arriving proves nothing was written - no waiting needed to show absence.
      assertThat(hasPosition(idOf("+919000020002"))).isFalse();
    }

    @Test
    void refusesADriverSendingOutsideTheirDestinations() throws Exception {
      Connection connection = connect(register(Role.DRIVER, "+919000020003"));
      StompSession session = connection.established();

      sendPosition(session, "/app/rider/anything");

      assertThat(connection.error().get(EVENTUALLY.toSeconds(), TimeUnit.SECONDS))
          .contains("Not permitted");
    }
  }

  @Nested
  class SessionLifetime {

    @Test
    void closesASessionOnceTheTokenItConnectedWithHasExpired() throws Exception {
      StompSession session = connect(register(Role.RIDER, "+919000040001")).established();

      // Long after the 15-minute access token: as the periodic check would find it.
      expiringSessions.closeExpired(Instant.now().plus(Duration.ofDays(1)));

      await().atMost(EVENTUALLY).until(() -> !session.isConnected());
    }

    @Test
    void leavesASessionWithAValidTokenOpen() throws Exception {
      StompSession session = connect(register(Role.RIDER, "+919000040002")).established();

      expiringSessions.closeExpired(Instant.now());

      assertThat(session.isConnected()).isTrue();
    }
  }

  @Nested
  class Subscribing {

    @Test
    void deliversMessagesOnARidersOwnQueue() throws Exception {
      String token = register(Role.RIDER, "+919000030001");
      UUID riderId = idOf("+919000030001");
      StompSession session = connect(token).established();

      CompletableFuture<String> received = new CompletableFuture<>();
      session.subscribe(
          "/user/queue/trip",
          new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
              return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
              received.complete(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
          });
      await().atMost(EVENTUALLY).until(() -> isSubscribed(riderId.toString(), "/user/queue/trip"));

      messaging.convertAndSendToUser(riderId.toString(), "/queue/trip", "driver-assigned");

      assertThat(received.get(EVENTUALLY.toSeconds(), TimeUnit.SECONDS))
          .isEqualTo("driver-assigned");
    }

    /**
     * The notification module end to end: published to Redis, received by every instance - here,
     * this one - and delivered to the user's own queue as JSON.
     */
    @Test
    void deliversANotificationPublishedThroughRedis() throws Exception {
      String token = register(Role.DRIVER, "+919000030004");
      UUID driverId = idOf("+919000030004");
      StompSession session = connect(token).established();

      CompletableFuture<String> received = new CompletableFuture<>();
      session.subscribe(
          "/user/queue/offers",
          new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
              return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
              received.complete(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
          });
      await()
          .atMost(EVENTUALLY)
          .until(() -> isSubscribed(driverId.toString(), "/user/queue/offers"));

      realtime.toUser(
          driverId,
          "/queue/offers",
          RealtimeMessage.of("OFFER", java.util.Map.of("offerId", "offer-1")));

      // The envelope: type, no trip and so no seq, and the payload inside.
      String json = received.get(EVENTUALLY.toSeconds(), TimeUnit.SECONDS);
      assertThat(json)
          .contains("\"type\":\"OFFER\"")
          .contains("\"seq\":null")
          .contains("\"payload\":{\"offerId\":\"offer-1\"}");
    }

    @Test
    void deliversToEverySessionOfOneUser() throws Exception {
      String token = register(Role.RIDER, "+919000030005");
      UUID riderId = idOf("+919000030005");
      List<CompletableFuture<String>> received =
          List.of(new CompletableFuture<>(), new CompletableFuture<>());
      // A phone and a laptop.
      for (CompletableFuture<String> sink : received) {
        connect(token)
            .established()
            .subscribe(
                "/user/queue/trip-updates",
                new StompFrameHandler() {
                  @Override
                  public Type getPayloadType(StompHeaders headers) {
                    return byte[].class;
                  }

                  @Override
                  public void handleFrame(StompHeaders headers, Object payload) {
                    sink.complete(new String((byte[]) payload, StandardCharsets.UTF_8));
                  }
                });
      }
      await()
          .atMost(EVENTUALLY)
          .until(
              () -> {
                var user = userRegistry.getUser(riderId.toString());
                return user != null
                    && user.getSessions().stream()
                            .filter(s -> !s.getSubscriptions().isEmpty())
                            .count()
                        == 2;
              });

      realtime.toUser(
          riderId,
          "/queue/trip-updates",
          RealtimeMessage.forTrip("TripStarted", UUID.randomUUID(), java.util.Map.of()));

      for (CompletableFuture<String> sink : received) {
        assertThat(sink.get(EVENTUALLY.toSeconds(), TimeUnit.SECONDS)).contains("\"seq\":1");
      }
    }

    @Test
    void refusesSubscribingToAnotherRidersTripUpdates() throws Exception {
      Connection connection = connect(register(Role.RIDER, "+919000030006"));
      StompSession session = connection.established();
      String destination = "/user/" + UUID.randomUUID() + "/queue/trip-updates";

      session.subscribe(destination, new NoOpFrameHandler());

      assertRefused(connection, session, "+919000030006", destination);
    }

    @Test
    void refusesSubscribingToAnotherUsersQueueByName() throws Exception {
      Connection connection = connect(register(Role.RIDER, "+919000030002"));
      StompSession session = connection.established();
      String destination = "/user/" + UUID.randomUUID() + "/queue/trip";

      session.subscribe(destination, new NoOpFrameHandler());

      // The case section 10 exists for: reading another rider's trip updates.
      assertRefused(connection, session, "+919000030002", destination);
    }

    /** The live map is broadcast: through Redis, to every admin subscribed on any instance. */
    @Test
    void deliversTheLiveMapToAnAdmin() throws Exception {
      String token = register(Role.ADMIN, "+919000030007");
      UUID adminId = idOf("+919000030007");
      StompSession session = connect(token).established();

      CompletableFuture<String> received = new CompletableFuture<>();
      CompletableFuture<String> contentType = new CompletableFuture<>();
      session.subscribe(
          "/topic/admin/live-map",
          new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
              return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
              contentType.complete(String.valueOf(headers.getContentType()));
              received.complete(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
          });
      await()
          .atMost(EVENTUALLY)
          .until(() -> isSubscribed(adminId.toString(), "/topic/admin/live-map"));

      realtime.toTopic(
          "/topic/admin/live-map", RealtimeMessage.of("LIVE_MAP", java.util.Map.of("drivers", 0)));

      assertThat(received.get(EVENTUALLY.toSeconds(), TimeUnit.SECONDS))
          .contains("\"type\":\"LIVE_MAP\"")
          .contains("\"payload\":{\"drivers\":0}");
      // Typed as JSON, so it travels as a text frame a browser can read, not a binary one.
      assertThat(contentType.get()).isEqualTo("application/json");
    }

    /** Every driver's position, every trip line: for admins only. */
    @Test
    void refusesTheLiveMapToRidersAndDrivers() throws Exception {
      for (Role role : List.of(Role.RIDER, Role.DRIVER)) {
        String phone = "+91900003001" + role.ordinal();
        Connection connection = connect(register(role, phone));
        StompSession session = connection.established();

        session.subscribe("/topic/admin/live-map", new NoOpFrameHandler());

        // Refused: the server sends an ERROR frame and closes the connection. Which of the two
        // the client hears first is a race, so this waits for either, then checks the outcome.
        connection.error().get(EVENTUALLY.toSeconds(), TimeUnit.SECONDS);
        await().atMost(EVENTUALLY).until(() -> !session.isConnected());
        assertThat(isSubscribed(idOf(phone).toString(), "/topic/admin/live-map"))
            .as(role.name())
            .isFalse();
      }
    }

    @Test
    void refusesSubscribingToASharedTopic() throws Exception {
      Connection connection = connect(register(Role.RIDER, "+919000030003"));
      StompSession session = connection.established();

      // Section 10: no shared per-trip topics, because anyone could subscribe to them.
      session.subscribe("/topic/trips", new NoOpFrameHandler());

      assertRefused(connection, session, "+919000030003", "/topic/trips");
    }

    /**
     * Refused: the server sends an ERROR frame and closes the connection, and which of the two the
     * client hears first is a race - the frame can be lost with the socket. So the reason is
     * checked when the frame wins, and the outcome always: no session, and no subscription.
     */
    private void assertRefused(
        Connection connection, StompSession session, String phone, String destination)
        throws Exception {
      assertThat(connection.error().get(EVENTUALLY.toSeconds(), TimeUnit.SECONDS))
          .satisfiesAnyOf(
              reason -> assertThat(reason).contains("own /user/queue"),
              reason -> assertThat(reason).startsWith("transport:"));
      await().atMost(EVENTUALLY).until(() -> !session.isConnected());
      assertThat(isSubscribed(idOf(phone).toString(), destination)).isFalse();
    }

    private boolean isSubscribed(String user, String destination) {
      var simpUser = userRegistry.getUser(user);
      return simpUser != null
          && simpUser.getSessions().stream()
              .flatMap(s -> s.getSubscriptions().stream())
              .anyMatch(s -> destination.equals(s.getDestination()));
    }
  }

  private static final class NoOpFrameHandler implements StompFrameHandler {
    @Override
    public Type getPayloadType(StompHeaders headers) {
      return byte[].class;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {}
  }
}
