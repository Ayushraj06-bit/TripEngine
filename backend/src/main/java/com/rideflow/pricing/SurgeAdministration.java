package com.rideflow.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Surge as admins see and steer it, for {@code admin}. */
public interface SurgeAdministration {

  /** The cells the last tick priced, the overrides in force, and whether surge is switched off. */
  Heatmap heatmap();

  /**
   * Fixes a cell's multiplier until {@code expiresAt}, whatever its demand.
   *
   * @throws com.rideflow.shared.BusinessException 400 {@code INVALID_CELL}; 422 {@code
   *     OVERRIDE_OUT_OF_RANGE} below 1.0 or above the cap; 422 {@code INVALID_OVERRIDE_EXPIRY} in
   *     the past or beyond {@code override-max-hours}
   */
  CellOverride setOverride(String cell, BigDecimal multiplier, Instant expiresAt, UUID adminId);

  /** Removes a cell's override, if it has one. */
  void removeOverride(String cell, UUID adminId);

  /**
   * @param on true switches surge off everywhere: every fare is quoted at 1.0
   */
  KillSwitch setKillSwitch(boolean on, UUID adminId);

  /**
   * @param cells every cell the last tick priced, surging or settling back to 1.0
   */
  record Heatmap(boolean killSwitchOn, List<Cell> cells, List<CellOverride> overrides) {}

  /**
   * One cell as the last tick left it.
   *
   * @param multiplier what a fare there is priced at now, overrides aside
   * @param demand requests over the cell and its neighbours in the window
   * @param supply available drivers over the same block
   */
  record Cell(
      String cell,
      double centerLat,
      double centerLng,
      double minLat,
      double maxLat,
      double minLng,
      double maxLng,
      BigDecimal multiplier,
      long demand,
      long supply) {}

  record CellOverride(String cell, BigDecimal multiplier, Instant expiresAt) {}

  record KillSwitch(boolean on) {}
}
