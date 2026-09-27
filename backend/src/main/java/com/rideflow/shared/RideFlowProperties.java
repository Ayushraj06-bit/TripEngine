package com.rideflow.shared;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every business constant from PROJECT_SPEC section 9.
 *
 * <p>PROJECT_SPEC section 11 forbids hard-coded magic numbers, so these are the only source of
 * these values. They are {@link Validated}, which means a nonsensical value fails the application
 * at startup rather than producing a wrong fare or an offer that never times out.
 *
 * <p>Units are in the property names, exactly as section 9 writes them, rather than converted to
 * {@code Duration}. That keeps the configuration file and this class readable side by side.
 */
@ConfigurationProperties("rideflow")
@Validated
public record RideFlowProperties(
    @NotNull @Valid Location location,
    @NotNull @Valid Matching matching,
    @NotNull @Valid Pricing pricing,
    @NotNull @Valid Trip trip,
    @NotNull @Valid Surge surge,
    @NotNull @Valid Payment payment) {

  /** Location ingestion and throttling. */
  public record Location(
      @Positive long minIntervalMs,
      @Positive double minMoveMeters,
      @Positive int forceWriteSeconds,
      @Positive int staleAfterSeconds,
      @Positive double maxSpeedKmph,
      @Positive double maxAccuracyMeters) {}

  /** Candidate search and offers. */
  public record Matching(
      @NotEmpty List<@Positive Double> radiusStepsKm,
      @Positive int candidatesPerSearch,
      @Positive int offerTimeoutSeconds,
      @Positive int deadlineSeconds,
      @Positive int researchIntervalSeconds,
      @Positive int fairnessBucketMeters,
      @DecimalMin("1.0") @DecimalMax("5.0") double minDriverRating) {}

  /** Route estimation and quotes. */
  public record Pricing(
      @DecimalMin("1.0") double roadFactor,
      @Positive int quoteTtlMinutes,
      @Positive int minTripMeters,
      @Positive int maxTripKm,
      @DecimalMin("0.0") @DecimalMax("1.0") double repriceDeviation) {}

  /** Trip lifecycle timers. */
  public record Trip(
      @Positive int arrivalGeofenceMeters,
      @Positive int freeWaitMinutes,
      @Positive int noShowAfterMinutes,
      @Positive int otpMaxAttempts,
      @Positive int riderCancelGraceSeconds) {}

  /** The surge engine. */
  public record Surge(
      @Min(1) @Max(12) int geohashPrecision,
      @Positive int windowMinutes,
      @Positive int tickSeconds,
      @Positive double maxStepPerTick,
      @DecimalMin("1.0") double cap,
      @Positive int ttlSeconds) {}

  /** Commission. */
  public record Payment(@Min(0) @Max(100) int commissionPercent) {}
}
