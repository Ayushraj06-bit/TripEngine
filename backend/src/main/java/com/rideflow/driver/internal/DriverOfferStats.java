package com.rideflow.driver.internal;

import com.rideflow.driver.CandidateFacts;
import com.rideflow.driver.DriverCandidates;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Offer counts and ranking facts. Counts change with single {@code UPDATE ... SET x = x + 1}
 * statements rather than read-modify-write, so concurrent offers never lose an increment.
 */
@Service
class DriverOfferStats implements DriverCandidates {

  private final DriverRepository drivers;

  DriverOfferStats(DriverRepository drivers) {
    this.drivers = drivers;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, CandidateFacts> factsFor(Collection<UUID> driverIds) {
    return drivers.findAllById(driverIds).stream()
        .map(Driver::candidateFacts)
        .collect(Collectors.toMap(CandidateFacts::driverId, Function.identity()));
  }

  @Override
  @Transactional
  public void recordOfferSent(UUID driverId) {
    drivers.incrementOffersReceived(driverId);
  }

  @Override
  @Transactional
  public void recordOfferAccepted(UUID driverId) {
    drivers.incrementOffersAccepted(driverId);
  }

  /**
   * The driver backed out of a trip they had accepted: PROJECT_SPEC section 6's "acceptance stats
   * drop". Counted as an offer they did not accept.
   */
  @Transactional
  void recordBackOut(UUID driverId) {
    drivers.decrementOffersAccepted(driverId);
  }
}
