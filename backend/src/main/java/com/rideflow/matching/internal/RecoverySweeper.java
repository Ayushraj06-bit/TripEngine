package com.rideflow.matching.internal;

import com.rideflow.shared.RideFlowProperties;
import com.rideflow.trip.WaitingTrip;
import com.rideflow.trip.WaitingTrips;
import java.time.Clock;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Restarts matching for any trip the database says is waiting but Redis has lost track of.
 *
 * <p>This is what lets matching survive a crash at any moment. An instance dying between two steps,
 * or Redis losing its data, leaves trips REQUESTED in PostgreSQL with nobody working on them. The
 * database is the source of truth; this sweep brings Redis back in line with it.
 */
@Component
class RecoverySweeper {

  private static final Logger log = LoggerFactory.getLogger(RecoverySweeper.class);

  private final WaitingTrips waitingTrips;
  private final Matcher matcher;
  private final Clock clock;
  private final RideFlowProperties properties;

  RecoverySweeper(
      WaitingTrips waitingTrips, Matcher matcher, Clock clock, RideFlowProperties properties) {
    this.waitingTrips = waitingTrips;
    this.matcher = matcher;
    this.clock = clock;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${rideflow.matching.recovery-interval}",
      initialDelayString = "${rideflow.matching.recovery-interval}")
  @SchedulerLock(name = "matching-recovery", lockAtMostFor = "PT1M")
  void sweep() {
    Instant now = clock.instant();
    var rules = properties.matching();
    // Younger trips are left alone: their first steps may simply still be running.
    for (WaitingTrip trip :
        waitingTrips.waitingSince(
            now.minusSeconds(rules.recoveryGraceSeconds()), rules.recoveryBatchSize())) {
      try {
        if (!matcher.isHealthy(trip.tripId(), now)) {
          log.info("Restarting matching for trip {}", trip.tripId());
          matcher.restart(trip);
        }
      } catch (RuntimeException e) {
        log.warn("Could not recover matching for trip {}", trip.tripId(), e);
      }
    }
  }
}
