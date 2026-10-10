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

  /** Override cells, scored by expiry in epoch ms, so admins can list them without a scan. */
  static final String OVERRIDES = "surge:overrides";

  /**
   * Present while surge is switched off. The one surge key with no TTL, on purpose: a kill switch
   * that expired would turn surge back on by itself.
   */
  static final String KILL_SWITCH = "surge:disabled";

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

  static String override(String cell) {
    return "surge:override:" + cell;
  }

  static long epochMinute(Instant instant) {
    return Math.floorDiv(instant.getEpochSecond(), 60);
  }
}
