package com.rideflow.trip.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.rideflow.trip.DriverAssigned;
import com.rideflow.trip.TripEvent;
import com.rideflow.trip.TripRequested;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The outbox end to end: events land in the Modulith registry with the change, and reach {@value
 * TripEvent#TOPIC} on a real broker, keyed by trip.
 */
@Tag("integration")
class TripKafkaIntegrationTest extends TripIntegrationTestBase {

  @Autowired KafkaContainer kafka;

  @Test
  void storesEventsInTheRegistryAndDeliversThemToKafkaKeyedByTrip() throws Exception {
    User rider = rider();
    User driver = driver();

    UUID tripId = bookedTrip(rider);
    // Each event fully delivered before the next change, so this test does not depend on
    // the order in which two asynchronous sends happen to run.
    awaitDelivered(tripId, TripRequested.class);
    trips.assignDriver(tripId, driver.id());
    awaitDelivered(tripId, DriverAssigned.class);

    List<ConsumerRecord<String, String>> received = consume(tripId, 2);

    assertThat(received).extracting(ConsumerRecord::key).containsOnly(tripId.toString());
    assertThat(received)
        .extracting(ConsumerRecord::value)
        .allSatisfy(
            json -> {
              assertThat((String) JsonPath.read(json, "$.tripId")).isEqualTo(tripId.toString());
              assertThat(json).doesNotContainIgnoringCase("otp");
            });
    assertThat((Object) JsonPath.read(received.get(0).value(), "$.quotedFarePaise")).isNotNull();
    assertThat((String) JsonPath.read(received.get(1).value(), "$.driverId"))
        .isEqualTo(driver.id().toString());
    // The type travels beside the JSON: DriverArrived and TripStarted have the same fields.
    assertThat(received)
        .extracting(
            record ->
                new String(
                    record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
        .containsExactly("TripRequested", "DriverAssigned");
  }

  /** Stored in the registry, and every listener - Kafka included - has completed it. */
  private void awaitDelivered(UUID tripId, Class<? extends TripEvent> type) {
    String sql =
        "SELECT count(*) FILTER (WHERE completion_date IS NULL) AS pending, count(*) AS total"
            + " FROM event_publication WHERE event_type = ? AND serialized_event LIKE ?";
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                jdbc.queryForObject(
                    sql,
                    (row, index) -> row.getLong("total") > 0 && row.getLong("pending") == 0,
                    type.getName(),
                    "%" + tripId + "%"));
  }

  private List<ConsumerRecord<String, String>> consume(UUID tripId, int expected) {
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "trip-events-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest");
    List<ConsumerRecord<String, String>> mine = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
      consumer.subscribe(List.of(TripEvent.TOPIC));
      // A consumer must be used from one thread.
      await()
          .pollInSameThread()
          .atMost(Duration.ofSeconds(60))
          .until(
              () -> {
                consumer
                    .poll(Duration.ofMillis(500))
                    .forEach(
                        record -> {
                          if (tripId.toString().equals(record.key())) {
                            mine.add(record);
                          }
                        });
                return mine.size() >= expected;
              });
    }
    return mine;
  }
}
