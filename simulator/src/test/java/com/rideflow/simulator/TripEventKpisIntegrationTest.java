package com.rideflow.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaOperations;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The KPI consumer against a real broker: trip events counted once each, and anything else sent to
 * the dead-letter topic without holding up what comes after it. No users are simulated.
 */
@SpringBootTest(
    properties = {
      "simulator.drivers=0",
      "simulator.riders=0",
      "spring.kafka.consumer.auto-offset-reset=earliest"
    })
@Testcontainers
class TripEventKpisIntegrationTest {

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

  @Autowired KafkaOperations<Object, Object> kafka;
  @Autowired TripEventKpis kpis;

  private void send(String eventType, String value) {
    ProducerRecord<Object, Object> record =
        new ProducerRecord<>(TripEventKpis.TOPIC, UUID.randomUUID().toString(), value);
    if (eventType != null) {
      record
          .headers()
          .add(new RecordHeader("eventType", eventType.getBytes(StandardCharsets.UTF_8)));
    }
    kafka.send(record);
  }

  private static String event(UUID eventId) {
    return """
        {"eventId":"%s","occurredAt":"2026-10-13T10:00:00Z","tripId":"%s"}"""
        .formatted(eventId, UUID.randomUUID());
  }

  @Test
  void countsTripEventsOnceAndDeadLettersWhatIsNotOne() {
    UUID requested = UUID.randomUUID();
    send("TripRequested", event(requested));
    send("TripRequested", event(requested)); // Redelivered: at least once.
    String headerless = event(UUID.randomUUID());
    send(null, headerless); // From some other producer.
    send("TripCompleted", "not json");
    send("TripCompleted", event(UUID.randomUUID()));
    send("TripCancelled", event(UUID.randomUUID()));

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              TripEventKpis.Kpis now = kpis.kpis();
              assertThat(now.requested()).isOne();
              assertThat(now.completed()).isOne();
              // One completed of the two that ended.
              assertThat(now.completionRate()).isEqualTo(0.5);
            });
    assertThat(deadLetters(2)).containsExactlyInAnyOrder("not json", headerless);
  }

  /** The values on the dead-letter topic, once {@code expected} have arrived. */
  private List<String> deadLetters(int expected) {
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "dlt-check-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest");
    List<String> values = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
      consumer.subscribe(List.of(TripEventKpis.DEAD_LETTERS));
      await()
          .pollInSameThread()
          .atMost(Duration.ofSeconds(30))
          .until(
              () -> {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> values.add(record.value()));
                return values.size() >= expected;
              });
    }
    return values;
  }
}
