package com.rideflow.pricing.internal;

import java.time.Instant;
import java.util.UUID;

/** The surge engine's Redis keys (PROJECT_SPEC section 7, ADR 0014), in one place. */
final class SurgeKeys {

  /**
   * Cells with a request in the demand window, scored by the minute of their latest. Trimmed by
   * every tick, so it holds only what the next tick needs.
   */
  static final String ACTIVE_CELLS = "surge:demand:cells";

  /** What the last tick priced, for the admin heatmap and the next tick. TTL as {@code surge:*}. */
  static final String SNAPSHOT = "surge:cells";

  private SurgeKeys() {}

  static String multiplier(String cell) {
    return "surge:" + cell;
  }

  static String demand(String cell, long epochMinute) {
    return "surge:demand:" + cell + ":" + epochMinute;
  }

  /** Marks a request already counted, so a redelivered event counts once. */
  static String counted(UUID requestId) {
    return "surge:demand:seen:" + requestId;
  }

  static long epochMinute(Instant instant) {
    return Math.floorDiv(instant.getEpochSecond(), 60);
  }
}
