package com.rideflow.trip.internal;

import com.rideflow.pricing.TripFees;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.trip.RoutePoint;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * What a finished trip costs (PROJECT_SPEC section 8).
 *
 * <ul>
 *   <li><strong>The quoted fare</strong>, unless the route actually driven differs from the
 *       estimate by more than {@code reprice-deviation}, in either direction. Then the fare is
 *       worked out again for the real distance and duration, with the rate card stored on the trip
 *       and the surge it was quoted at - never today's rates.
 *   <li><strong>Plus waiting</strong>: whole minutes between arrival and start beyond {@code
 *       free-wait-minutes}, at the card's waiting rate. Whole minutes, rounded down, so exactly the
 *       free time costs nothing and the fare stays in whole rupees.
 * </ul>
 *
 * <p>Repricing works both ways on purpose. A trip that ended early, or never moved, pays for what
 * was driven rather than the full quote - which is also what makes a zero-distance trip cost the
 * minimum fare.
 *
 * <p>But no route at all is not a route of zero metres. A phone that died, or Redis losing the
 * list, would otherwise cut the fare to the minimum and underpay the driver; with nothing to
 * measure, the quote stands and the trip is flagged for a human.
 */
@Component
class FinalFares {

  /**
   * @param flag why a human should look at this trip, or null
   */
  record FinalFare(
      long farePaise,
      long distanceMeters,
      long durationSeconds,
      boolean repriced,
      long waitingChargePaise,
      String flag) {}

  private final TripFees fees;
  private final RideFlowProperties properties;

  FinalFares(TripFees fees, RideFlowProperties properties) {
    this.fees = fees;
    this.properties = properties;
  }

  FinalFare settle(Trip trip, List<RoutePoint> route, Instant completedAt) {
    var rules = properties.trip();
    long seconds = Duration.between(trip.startedAt(), completedAt).toSeconds();
    // Two points at least: one alone has no length, and is no more evidence than none.
    boolean recorded = route.size() >= 2;
    long meters =
        recorded
            ? RouteDistance.meters(route, properties.location().maxSpeedKmph())
            : trip.distanceMeters();

    boolean repriced = recorded && deviates(trip.distanceMeters(), meters);
    long fare =
        repriced
            ? fees.fareFor(trip.rateCardId(), meters, seconds, trip.surgeMultiplier())
                .total()
                .paise()
            : trip.quotedFarePaise();

    long waited = Duration.between(trip.arrivedAt(), trip.startedAt()).toSeconds();
    long chargeableMinutes = Math.max(0, (waited - rules.freeWaitMinutes() * 60L) / 60);
    long waiting = chargeableMinutes * fees.waitingPerMinute(trip.rateCardId()).paise();

    String flag = null;
    if (!recorded) {
      flag = "No route was recorded; charged the quoted fare";
    } else if (seconds < rules.shortTripFlagSeconds() && meters < rules.shortTripFlagMeters()) {
      flag = "Completed in %d s over %d m".formatted(seconds, meters);
    } else if (repriced && fare > trip.quotedFarePaise() * (1 + rules.repriceFlagIncrease())) {
      flag =
          "Repriced from %d to %d paise for %d m driven"
              .formatted(trip.quotedFarePaise(), fare, meters);
    }
    return new FinalFare(fare + waiting, meters, seconds, repriced, waiting, flag);
  }

  /** Strictly more than the allowed deviation: exactly at it, the quote stands. */
  private boolean deviates(long estimatedMeters, long actualMeters) {
    double allowed = estimatedMeters * properties.pricing().repriceDeviation();
    return Math.abs(actualMeters - estimatedMeters) > allowed;
  }
}
