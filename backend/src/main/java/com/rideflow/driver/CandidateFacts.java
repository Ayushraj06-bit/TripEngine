package com.rideflow.driver;

import java.time.Instant;
import java.util.UUID;

/**
 * A driver, as matching ranks them.
 *
 * @param averageRating null until the driver has been rated: a new driver is not a bad one
 * @param availableSince when the driver last became available, so how long they have been idle
 */
public record CandidateFacts(
    UUID driverId,
    Double averageRating,
    int offersReceived,
    int offersAccepted,
    Instant availableSince) {

  /**
   * Accepted over received. 1.0 before any offer, so a new driver is not ranked as though they had
   * turned work down.
   */
  public double acceptanceRate() {
    return offersReceived == 0 ? 1.0 : (double) offersAccepted / offersReceived;
  }
}
