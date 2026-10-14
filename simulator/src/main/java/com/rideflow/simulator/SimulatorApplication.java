package com.rideflow.simulator;

import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.core.JacksonException;

/** The traffic simulator: fake drivers and riders against the backend's public API. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SimulatorApplication {

  /** Retries for a trip event the KPIs failed on for a reason that might pass. */
  private static final long RETRIES = 2;

  private static final long RETRY_INTERVAL_MS = 500;

  public static void main(String[] args) {
    SpringApplication.run(SimulatorApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * One client for every simulated app. No heartbeats: each app's tick is traffic enough, and a
   * thousand heartbeat timers would cost more than they tell.
   */
  @Bean
  WebSocketStompClient stompClient() {
    WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setDefaultHeartbeat(new long[] {0, 0});
    return client;
  }

  /**
   * Retries, then the dead-letter topic. A message that cannot be read - not JSON, not a trip event
   * - goes there at once: reading it again would not change it.
   *
   * <p>Partition -1 lets Kafka choose, so the dead-letter topic need not have as many partitions as
   * the topic it mirrors.
   */
  @Bean
  DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafka) {
    DefaultErrorHandler handler =
        new DefaultErrorHandler(
            new DeadLetterPublishingRecoverer(
                kafka, (record, e) -> new TopicPartition(TripEventKpis.DEAD_LETTERS, -1)),
            new FixedBackOff(RETRY_INTERVAL_MS, RETRIES));
    handler.addNotRetryableExceptions(JacksonException.class, TripEventKpis.NotATripEvent.class);
    return handler;
  }

  @Bean
  NewTopic deadLetterTopic() {
    return TopicBuilder.name(TripEventKpis.DEAD_LETTERS).partitions(1).build();
  }
}
