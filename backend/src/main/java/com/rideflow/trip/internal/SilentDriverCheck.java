package com.rideflow.trip.internal;

import com.rideflow.shared.RideFlowProperties;
import com.rideflow.trip.DriverPositions;
import com.rideflow.trip.TripStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.support.WindowIterator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Flags a trip in progress whose driver has been silent for {@code driver-silence-flag-minutes}.
 *
 * <p>Silence is not cancellation: a phone can die mid-ride and the ride still happens, so the trip
 * runs on and a human looks at it later. Checked from here, against the database, rather than in
 * response to {@code DriverWentStale}: {@code trip} cannot listen to {@code location} without a
 * module cycle, and a scan also catches a driver who went silent while this instance was down.
 */
@Component
class SilentDriverCheck {

  private static final Logger log = LoggerFactory.getLogger(SilentDriverCheck.class);

  private final TripRepository trips;
  private final TripService tripService;
  private final DriverPositions positions;
  private final Clock clock;
  private final RideFlowProperties properties;

  SilentDriverCheck(
      TripRepository trips,
      TripService tripService,
      DriverPositions positions,
      Clock clock,
      RideFlowProperties properties) {
    this.trips = trips;
    this.tripService = tripService;
    this.positions = positions;
    this.clock = clock;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${rideflow.trip.driver-silence-check-interval}")
  @SchedulerLock(name = "trip-driver-silence-check", lockAtMostFor = "PT5M")
  void check() {
    var rules = properties.trip();
    Instant cutoff = clock.instant().minus(Duration.ofMinutes(rules.driverSilenceFlagMinutes()));
    // Every trip, a window at a time: a fixed first batch would be filled for good by long
    // rides that are fine, and the rest never checked. A trip younger than the limit cannot
    // have been silent that long during it, so it is not read at all.
    WindowIterator<Trip> inProgress =
        WindowIterator.of(
                position ->
                    trips
                        .findByStatusAndFlaggedReasonIsNullAndStartedAtBeforeOrderByStartedAtAscIdAsc(
                            TripStatus.IN_PROGRESS,
                            cutoff,
                            position,
                            Limit.of(rules.silenceCheckBatchSize())))
            .startingAt(ScrollPosition.keyset());
    while (inProgress.hasNext()) {
      Trip trip = inProgress.next();
      // Silence during the trip: a driver quiet since before it started has been quiet
      // during it only since the start.
      Instant heardFrom =
          positions
              .lastReportedAt(trip.driverId())
              .filter(at -> at.isAfter(trip.startedAt()))
              .orElse(trip.startedAt());
      if (heardFrom.isBefore(cutoff)) {
        try {
          tripService.flag(
              trip.id(),
              "Driver silent for over %d minutes during the trip"
                  .formatted(rules.driverSilenceFlagMinutes()));
        } catch (RuntimeException e) {
          // Most likely the trip changed under us; the next run looks again.
          log.warn("Could not flag trip {} for a silent driver", trip.id(), e);
        }
      }
    }
  }
}
