package com.rideflow.driver;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** What {@code matching} needs from the driver module to rank candidates and keep offer counts. */
public interface DriverCandidates {

  /** Ranking facts for these drivers, in one query. Drivers without a profile are left out. */
  Map<UUID, CandidateFacts> factsFor(Collection<UUID> driverIds);

  /** An offer was sent to the driver. */
  void recordOfferSent(UUID driverId);

  /** The driver accepted an offer. */
  void recordOfferAccepted(UUID driverId);
}
