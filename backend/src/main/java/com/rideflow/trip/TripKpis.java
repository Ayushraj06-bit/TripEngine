package com.rideflow.trip;

import java.time.Duration;

/** Live trip numbers, for {@code admin}'s live map. */
public interface TripKpis {

  /** The trips active now, and how the trips requested in the last {@code window} have fared. */
  Kpis over(Duration window);

  /**
   * Rates are shares of the window's requests, so a trip still looking for a driver counts towards
   * neither: they settle as the window's trips end.
   *
   * @param activeTrips requested, assigned, arrived or under way now
   * @param requested trips requested in the window
   * @param tripsPerMinute requests per minute over the window
   * @param averageSecondsToAssign from request to driver, over the window's trips that got one;
   *     null if none did
   * @param noDriverRate share of the window's requests that found no driver, 0 to 1
   * @param cancelRate share of the window's requests that were cancelled, 0 to 1
   */
  record Kpis(
      long activeTrips,
      long requested,
      double tripsPerMinute,
      Double averageSecondsToAssign,
      double noDriverRate,
      double cancelRate) {}
}
