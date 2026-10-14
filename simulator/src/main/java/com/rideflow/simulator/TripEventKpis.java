package com.rideflow.simulator;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live KPIs from {@value #TOPIC}, read as any external consumer would: the backend's trip events,
 * not the simulator's own view of its riders.
 *
 * <p>Delivery is at least once, so events are de-duplicated on {@code eventId}. A message that is
 * not a trip event goes to {@value #DEAD_LETTERS} without retries: reading it again would not make
 * it one (see {@link SimulatorApplication#kafkaErrorHandler}).
 */
@Component
public class TripEventKpis {

  private static final Logger log = LoggerFactory.getLogger(TripEventKpis.class);

  static final String TOPIC = "rideflow.trip-events";
  static final String DEAD_LETTERS = TOPIC + ".DLT";

  /** The KPIs cover the events of the last five minutes, as the admin live map's do. */
  static final Duration WINDOW = Duration.ofMinutes(5);

  /** Event ids remembered for de-duplication: far more than one window's worth. */
  private static final int REMEMBERED_IDS = 100_000;

  /** A message on the topic that is not a trip event this consumer can read. */
  static class NotATripEvent extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NotATripEvent(String message) {
      super(message);
    }
  }

  private record Seen(Instant at, String type) {}

  /**
   * @param tripsPerMinute trips completed a minute over the window
   * @param completionRate of trips that ended in the window, the share that completed: 0 to 1
   */
  public record Kpis(
      long requested, long completed, double tripsPerMinute, double completionRate) {}

  private final JsonMapper json;
  private final Clock clock;
  private final Deque<Seen> window = new ConcurrentLinkedDeque<>();
  private final Set<String> seenIds =
      Collections.synchronizedSet(
          Collections.newSetFromMap(
              new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                  return size() > REMEMBERED_IDS;
                }
              }));

  TripEventKpis(JsonMapper json, Clock clock, MeterRegistry meters) {
    this.json = json;
    this.clock = clock;
    Gauge.builder("simulator.kafka.trips_per_minute", this, k -> k.kpis().tripsPerMinute())
        .description("Trips completed a minute, from rideflow.trip-events")
        .register(meters);
    Gauge.builder("simulator.kafka.completion_rate", this, k -> k.kpis().completionRate())
        .description("Of trips that ended, the share completed, from rideflow.trip-events")
        .register(meters);
  }

  @KafkaListener(id = "trip-event-kpis", topics = TOPIC)
  void on(ConsumerRecord<String, String> record) {
    Header type = record.headers().lastHeader("eventType");
    if (type == null) {
      throw new NotATripEvent("No eventType header");
    }
    JsonNode event = json.readTree(record.value());
    if (!event.hasNonNull("eventId") || !event.hasNonNull("tripId")) {
      throw new NotATripEvent("No eventId or tripId");
    }
    if (seenIds.add(event.get("eventId").asString())) {
      window.add(new Seen(clock.instant(), new String(type.value(), StandardCharsets.UTF_8)));
    }
  }

  public Kpis kpis() {
    Instant since = clock.instant().minus(WINDOW);
    while (!window.isEmpty() && window.peekFirst().at().isBefore(since)) {
      window.pollFirst();
    }
    long requested = 0;
    long completed = 0;
    long ended = 0;
    for (Seen seen : window) {
      switch (seen.type()) {
        case "TripRequested" -> requested++;
        case "TripCompleted" -> {
          completed++;
          ended++;
        }
        case "TripCancelled", "TripNoDriverFound" -> ended++;
        default -> {
          // Assignments, arrivals, payments: steps on the way, not outcomes.
        }
      }
    }
    return new Kpis(
        requested,
        completed,
        completed / (double) WINDOW.toMinutes(),
        ended == 0 ? 0 : completed / (double) ended);
  }

  @Scheduled(
      fixedDelayString = "${simulator.kpi-log-interval}",
      initialDelayString = "${simulator.kpi-log-interval}")
  void logKpis() {
    Kpis kpis = kpis();
    log.info(
        "Kafka KPIs, last {} min: {} trips requested, {} completed ({} a minute), completion rate"
            + " {}%",
        WINDOW.toMinutes(),
        kpis.requested(),
        kpis.completed(),
        "%.1f".formatted(kpis.tripsPerMinute()),
        "%.0f".formatted(100 * kpis.completionRate()));
  }
}
