package com.rideflow.driver.internal;

import com.rideflow.driver.DriverRatings;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One UPDATE that adds to the totals where they are, like the offer counts: no read, so two ratings
 * at once cannot lose one, and no version, so a rating never collides with the driver going online.
 */
@Component
class DriverRatingTotals implements DriverRatings {

  private final DriverRepository drivers;

  DriverRatingTotals(DriverRepository drivers) {
    this.drivers = drivers;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void addRating(UUID driverId, int stars) {
    if (drivers.addRating(driverId, stars) != 1) {
      throw new IllegalStateException("No driver " + driverId + " to rate");
    }
  }
}
