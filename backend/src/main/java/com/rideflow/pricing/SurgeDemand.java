package com.rideflow.pricing;

import com.rideflow.shared.GeoPoint;
import java.time.Instant;
import java.util.UUID;

/**
 * Ride requests, counted per cell and minute, for the surge engine.
 *
 * <p>{@code trip} reports each request: it already depends on {@code pricing}, so {@code pricing}
 * listening to trip events itself would close a module cycle.
 */
public interface SurgeDemand {

  /**
   * Counts one request at {@code pickup}, in the minute it was made. Once per {@code requestId}: a
   * redelivered event counts nothing.
   */
  void recordRequest(UUID requestId, GeoPoint pickup, Instant requestedAt);
}
