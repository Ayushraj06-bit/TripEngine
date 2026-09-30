package com.rideflow.location.internal;

import com.rideflow.location.DriverWentStale;
import com.rideflow.shared.RideFlowProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes drivers who stopped reporting.
 *
 * <p>PROJECT_SPEC section 7 gives the GEO sets and {@code drivers:lastseen} no TTL, because a TTL
 * cannot express "quiet for thirty seconds" - it would expire a driver who is simply stationary.
 * This sweep is what keeps the map honest, and without it matching spends its whole budget offering
 * trips to phones in tunnels.
 *
 * <p>{@link SchedulerLock} is not optional. Two instances sweeping independently would both publish
 * {@code DriverWentStale} for the same driver, so anything reacting to it - the trip module from
 * Phase 4 - would act twice on one event.
 *
 * <p>The sweep drains in batches within a single run. After an outage the backlog can be large, and
 * one unbounded query would block Redis for every caller while it ran.
 */
@Component
class StaleDriverSweeper {

  private static final Logger log = LoggerFactory.getLogger(StaleDriverSweeper.class);

  /**
   * How many batches one run will drain before leaving the rest for the next tick. A bound, so a
   * pathological backlog cannot hold the lock indefinitely.
   */
  private static final int MAX_BATCHES_PER_RUN = 20;

  private final DriverStateStore store;
  private final ApplicationEventPublisher events;
  private final RideFlowProperties properties;
  private final Clock clock;
  private final TransactionTemplate transactions;

  StaleDriverSweeper(
      DriverStateStore store,
      ApplicationEventPublisher events,
      RideFlowProperties properties,
      Clock clock,
      TransactionTemplate transactions) {
    this.store = store;
    this.events = events;
    this.properties = properties;
    this.clock = clock;
    this.transactions = transactions;
  }

  /**
   * Runs every {@code rideflow.location.stale-sweep-interval} - five seconds, well inside {@code
   * stale-after-seconds}, so a driver is noticed promptly rather than up to a whole window late.
   */
  @Scheduled(fixedDelayString = "${rideflow.location.stale-sweep-interval}")
  @SchedulerLock(name = "location-stale-driver-sweep", lockAtMostFor = "PT30S")
  void sweep() {
    int swept = 0;
    for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
      List<DriverStateStore.StaleDriver> stale = store.sweepStale();
      if (stale.isEmpty()) {
        break;
      }
      publishStale(stale, clock.instant());
      swept += stale.size();

      if (stale.size() < properties.location().staleSweepBatchSize()) {
        // A partial batch means the backlog is drained.
        break;
      }
    }
    if (swept > 0) {
      log.info("Removed {} stale driver(s) from the live map", swept);
    }
  }

  /**
   * Publishes inside a short transaction of its own.
   *
   * <p>Not optional. Every {@code @ApplicationModuleListener} is a transactional event listener,
   * and Spring skips those entirely when an event is published with no transaction active.
   * Published bare, {@code DriverWentStale} would reach nobody - the trip module would never learn
   * its driver vanished, and nothing would say so.
   *
   * <p>The Redis sweep stays outside the transaction, per PROJECT_SPEC section 11. The transaction
   * holds only the event publication rows: the outbox, which section 11 explicitly permits.
   */
  private void publishStale(List<DriverStateStore.StaleDriver> stale, Instant detectedAt) {
    transactions.executeWithoutResult(
        status -> {
          for (var driver : stale) {
            events.publishEvent(
                new DriverWentStale(
                    driver.driverId(), Instant.ofEpochMilli(driver.lastSeenMs()), detectedAt));
          }
        });
  }
}
