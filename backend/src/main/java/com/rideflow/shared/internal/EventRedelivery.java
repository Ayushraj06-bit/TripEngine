package com.rideflow.shared.internal;

import com.rideflow.shared.RideFlowProperties;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers again every event whose listener has not completed (PROJECT_SPEC section 5: the outbox
 * guarantees delivery, not just storage).
 *
 * <p>Without this, a listener that failed - a trip charge that lost every retry to a database blip
 * - would wait for the next restart, leaving the trip unpaid and its driver too. Every listener is
 * idempotent, so delivering an event a second time is safe, even to one still running.
 *
 * <p>Once per cluster: ShedLock.
 */
@Component
public class EventRedelivery {

  private static final Logger log = LoggerFactory.getLogger(EventRedelivery.class);

  private final IncompleteEventPublications publications;
  private final Duration after;

  EventRedelivery(IncompleteEventPublications publications, RideFlowProperties properties) {
    this.publications = publications;
    this.after = Duration.ofSeconds(properties.events().redeliverAfterSeconds());
  }

  @Scheduled(
      fixedDelayString = "${rideflow.events.redelivery-interval}",
      initialDelayString = "${rideflow.events.redelivery-interval}")
  @SchedulerLock(name = "event-redelivery", lockAtMostFor = "PT5M")
  void redeliver() {
    redeliverOlderThan(after);
  }

  /** Every incomplete publication older than {@code age}, delivered again. */
  public void redeliverOlderThan(Duration age) {
    log.debug("Redelivering events whose listeners have not completed in {}", age);
    publications.resubmitIncompletePublicationsOlderThan(age);
  }
}
