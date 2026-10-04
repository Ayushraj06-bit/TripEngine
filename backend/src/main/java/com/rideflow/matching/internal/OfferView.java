package com.rideflow.matching.internal;

import com.rideflow.shared.VehicleType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * An offer as the driver sees it: pushed on {@code /user/queue/offers}, and readable from {@code
 * GET /api/v1/offers/current}.
 *
 * <p>The pickup is rounded to about 110 m and nothing identifies the rider. The driver learns the
 * exact point by accepting; someone who only browses offers learns nothing precise.
 */
public record OfferView(
    UUID offerId,
    Point pickup,
    VehicleType vehicleType,
    long estimatedFarePaise,
    Instant expiresAt) {

  public record Point(double lat, double lng) {}

  static OfferView of(UUID offerId, OfferStore.Match match, Instant expiresAt, int decimals) {
    return new OfferView(
        offerId,
        new Point(
            round(match.pickup().latitude(), decimals),
            round(match.pickup().longitude(), decimals)),
        match.vehicleType(),
        match.farePaise(),
        expiresAt);
  }

  private static double round(double value, int decimals) {
    return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
  }
}
