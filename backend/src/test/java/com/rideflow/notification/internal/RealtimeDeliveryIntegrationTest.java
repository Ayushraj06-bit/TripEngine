package com.rideflow.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rideflow.TestcontainersConfiguration;
import com.rideflow.notification.RealtimeMessage;
import com.rideflow.notification.RealtimePublisher;
import com.rideflow.shared.RideFlowProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fan-out across instances, without needing two JVMs: each "instance" is its own Redis listener
 * with its own idea of who is connected to it, all on one real Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RealtimeDeliveryIntegrationTest {

  private static final Duration EVENTUALLY = Duration.ofSeconds(10);

  @Autowired RealtimePublisher publisher;
  @Autowired StringRedisTemplate redis;
  @Autowired RedisConnectionFactory connections;
  @Autowired JsonMapper json;
  @Autowired RideFlowProperties properties;
  @Autowired RedisScript<Long> tripSequenceScript;

  @Nested
  class Sequence {

    /** Two instances numbering one trip's messages at once: 1 to 1000, each once, in order. */
    @Test
    void risesStrictlyForATripAcrossInstances() throws Exception {
      UUID tripId = UUID.randomUUID();
      List<TripSequence> instances =
          List.of(
              new TripSequence(redis, tripSequenceScript, properties),
              new TripSequence(redis, tripSequenceScript, properties));
      CountDownLatch go = new CountDownLatch(1);

      List<Future<List<Long>>> results = new ArrayList<>();
      try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        for (TripSequence instance : instances) {
          results.add(
              pool.submit(
                  () -> {
                    go.await();
                    List<Long> seen = new ArrayList<>();
                    for (int i = 0; i < 500; i++) {
                      seen.add(instance.next(tripId));
                    }
                    return seen;
                  }));
        }
        go.countDown();

        List<Long> all = new ArrayList<>();
        for (Future<List<Long>> result : results) {
          List<Long> seen = result.get(30, TimeUnit.SECONDS);
          // Each sender sees its own numbers rise.
          assertThat(seen).isSorted().doesNotHaveDuplicates();
          all.addAll(seen);
        }
        assertThat(all)
            .containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 1_000).boxed().toList());
      }
      assertThat(redis.getExpire(TripSequence.key(tripId))).isPositive();
    }

    @Test
    void startsAgainAtOneForEachTrip() {
      TripSequence sequence = new TripSequence(redis, tripSequenceScript, properties);
      UUID first = UUID.randomUUID();
      sequence.next(first);
      sequence.next(first);

      assertThat(sequence.next(UUID.randomUUID())).isOne();
    }
  }

  @Nested
  class FanOut {

    record Delivered(String destination, String envelope) {}

    private final UUID rider = UUID.randomUUID();
    private final UUID driver = UUID.randomUUID();
    private final List<Delivered> onInstanceA = new CopyOnWriteArrayList<>();
    private final List<Delivered> onInstanceB = new CopyOnWriteArrayList<>();
    private final List<RedisMessageListenerContainer> instances = new ArrayList<>();

    /** One pretend instance, where only {@code connectedUser} has a WebSocket. */
    private void instanceWith(UUID connectedUser, List<Delivered> delivered) {
      SimpUserRegistry users = mock(SimpUserRegistry.class);
      when(users.getUser(connectedUser.toString())).thenReturn(mock(SimpUser.class));
      SimpMessagingTemplate broker =
          new SimpMessagingTemplate(
              (message, timeout) -> {
                delivered.add(
                    new Delivered(
                        SimpMessageHeaderAccessor.getDestination(message.getHeaders()),
                        new String((byte[]) message.getPayload(), StandardCharsets.UTF_8)));
                return true;
              });
      RedisMessageListenerContainer container = new RedisMessageListenerContainer();
      container.setConnectionFactory(connections);
      container.addMessageListener(
          new LocalDelivery(users, broker, json), new ChannelTopic(RedisRealtimePublisher.CHANNEL));
      container.afterPropertiesSet();
      container.start();
      instances.add(container);
    }

    @BeforeEach
    void startTwoInstances() {
      instanceWith(rider, onInstanceA);
      instanceWith(driver, onInstanceB);
      // Pub/Sub keeps nothing for a late subscriber: wait until both are listening.
      await()
          .atMost(EVENTUALLY)
          .until(
              () -> {
                publisher.toUser(rider, "/queue/probe", RealtimeMessage.of("PROBE", Map.of()));
                publisher.toUser(driver, "/queue/probe", RealtimeMessage.of("PROBE", Map.of()));
                return !onInstanceA.isEmpty() && !onInstanceB.isEmpty();
              });
    }

    /** What arrived apart from the probes, some of which may still be in flight. */
    private List<Delivered> real(List<Delivered> delivered) {
      return delivered.stream().filter(d -> !d.destination().endsWith("/queue/probe")).toList();
    }

    @AfterEach
    void stopInstances() throws Exception {
      for (RedisMessageListenerContainer container : instances) {
        container.destroy();
      }
    }

    @Test
    void deliversEachMessageOnlyWhereItsUserIsConnected() {
      UUID tripId = UUID.randomUUID();

      // The driver's position, produced wherever the driver's socket is, for the rider on A.
      publisher.toUser(
          rider,
          "/queue/driver-location",
          RealtimeMessage.forTrip("DRIVER_LOCATION", tripId, Map.of("lat", 12.97)));
      publisher.toUser(
          driver, "/queue/trip-updates", RealtimeMessage.forTrip("TripStarted", tripId, Map.of()));

      await()
          .atMost(EVENTUALLY)
          .until(() -> real(onInstanceA).size() == 1 && real(onInstanceB).size() == 1);
      Delivered toRider = real(onInstanceA).getFirst();
      assertThat(toRider.destination()).isEqualTo("/user/" + rider + "/queue/driver-location");
      assertThat(toRider.envelope())
          .contains("\"type\":\"DRIVER_LOCATION\"", "\"tripId\":\"" + tripId + "\"", "\"seq\":1");
      Delivered toDriver = real(onInstanceB).getFirst();
      assertThat(toDriver.destination()).isEqualTo("/user/" + driver + "/queue/trip-updates");
      assertThat(toDriver.envelope()).contains("\"seq\":2");
    }

    @Test
    void dropsAMessageForSomeoneConnectedNowhereWithoutAnError() {
      publisher.toUser(
          UUID.randomUUID(), "/queue/trip-updates", RealtimeMessage.of("TripStarted", Map.of()));
      publisher.toUser(rider, "/queue/trip-updates", RealtimeMessage.of("Marker", Map.of()));

      await().atMost(EVENTUALLY).until(() -> !real(onInstanceA).isEmpty());
      // Messages are handed on concurrently, so the stranger's may land after the marker:
      // watch a while for it to turn up anywhere.
      await()
          .during(Duration.ofMillis(500))
          .atMost(EVENTUALLY)
          .until(() -> real(onInstanceA).size() == 1 && real(onInstanceB).isEmpty());
      assertThat(real(onInstanceA).getFirst().envelope()).contains("Marker");
    }

    @Test
    void sendsAReservedSeqAsItIs() {
      UUID tripId = UUID.randomUUID();
      long reserved = publisher.nextSeq(tripId);

      publisher.toUser(
          rider, "/queue/trip-updates", RealtimeMessage.forTrip("Later", tripId, Map.of()));
      publisher.toUser(
          rider,
          "/queue/trip-updates",
          RealtimeMessage.forTrip("Reserved", tripId, reserved, Map.of()));

      await().atMost(EVENTUALLY).until(() -> real(onInstanceA).size() == 2);
      assertThat(real(onInstanceA))
          .extracting(Delivered::envelope)
          .anySatisfy(e -> assertThat(e).contains("\"type\":\"Reserved\"", "\"seq\":1"))
          .anySatisfy(e -> assertThat(e).contains("\"type\":\"Later\"", "\"seq\":2"));
    }
  }
}
