package com.rideflow.shared;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
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
    @NotNull @Valid Payment payment,
    @NotNull @Valid Notification notification,
    @NotNull @Valid Events events,
    @NotNull @Valid Rating rating,
    @NotNull @Valid Admin admin,
    @DefaultValue @Valid Web web,
    @NotNull @Valid ServiceAreaProperties serviceArea) {

  /** What browsers may do (Phase 10). */
  public record Web(
      /**
       * Origins whose pages may open the WebSocket, as patterns, besides this server's own. Empty
       * by default: the live map is served from here. Apps and the simulator send no Origin header,
       * and are not affected.
       */
      @DefaultValue List<String> allowedOrigins) {}

  /** Location ingestion and throttling. */
  public record Location(
      @Positive long minIntervalMs,
      @Positive double minMoveMeters,
      @Positive int forceWriteSeconds,
      @Positive int staleAfterSeconds,
      @Positive double maxSpeedKmph,
      @Positive double maxAccuracyMeters,
      /* Not in section 9; added by Phase 2 rather than hard-coded, per section 11. */
      @Positive int spoofStrikesBeforeFlag,
      @Positive int staleSweepBatchSize,
      @Positive int nearbyResultLimit,
      @Positive int nearbyCacheSeconds,
      /** Decimal places to round a public position to. 3 is roughly 110 m. */
      @Min(1) @Max(6) int nearbyRoundingDecimals,
      /* Phase 6. */
      /** At most one live position a rider is sent, per this many milliseconds. */
      @Positive long driverLocationForwardMs,
      /** Route points kept per trip; older ones are trimmed. */
      @Positive int routeMaxPoints,
      @Positive int routeTtlHours) {}

  /**
   * The polygon the service operates inside.
   *
   * @param vertices at least three points, in order around the boundary
   */
  public record ServiceAreaProperties(@NotEmpty List<@Valid Vertex> vertices) {

    public record Vertex(double lat, double lng) {}

    /** The validated value object the rest of the application uses. */
    public com.rideflow.shared.ServiceArea toServiceArea() {
      return new com.rideflow.shared.ServiceArea(
          vertices.stream().map(v -> new GeoPoint(v.lat(), v.lng())).toList());
    }
  }

  /** Candidate search and offers. */
  public record Matching(
      @NotEmpty List<@Positive Double> radiusStepsKm,
      @Positive int candidatesPerSearch,
      @Positive int offerTimeoutSeconds,
      @Positive int deadlineSeconds,
      @Positive int researchIntervalSeconds,
      @Positive int fairnessBucketMeters,
      @DecimalMin("1.0") @DecimalMax("5.0") double minDriverRating,
      /* Not in section 9; added by Phase 5 rather than hard-coded, per section 11. */
      @Positive int lockGraceSeconds,
      @Positive int stateTtlMinutes,
      @Positive int offerPollBatchSize,
      @Positive int recoveryGraceSeconds,
      @Positive int recoveryBatchSize,
      /** Decimal places of the pickup shown in an offer. 3 is roughly 110 m. */
      @Min(1) @Max(6) int offerPickupRoundingDecimals) {}

  /** Route estimation and quotes. */
  public record Pricing(
      @DecimalMin("1.0") double roadFactor,
      @Positive int quoteTtlMinutes,
      @Positive int minTripMeters,
      @Positive int maxTripKm,
      @DecimalMin("0.0") @DecimalMax("1.0") double repriceDeviation,
      /* Not in section 9; added by Phase 3 rather than hard-coded, per section 11. */
      @NotNull @Valid AverageSpeedKmph averageSpeedKmph,
      @Positive int quoteRateLimitPerMinute,
      @Positive int rateCardRefreshSeconds) {

    /** City averages, used to turn an estimated distance into an estimated duration. */
    public record AverageSpeedKmph(
        @Positive double bike, @Positive double auto, @Positive double car) {

      public double of(VehicleType type) {
        return switch (type) {
          case BIKE -> bike;
          case AUTO -> auto;
          case CAR -> car;
        };
      }
    }
  }

  /** Trip lifecycle timers. */
  public record Trip(
      @Positive int arrivalGeofenceMeters,
      @Positive int freeWaitMinutes,
      @Positive int noShowAfterMinutes,
      @Positive int otpMaxAttempts,
      @Positive int riderCancelGraceSeconds,
      /* Not in section 9; added by Phase 4 rather than hard-coded, per section 11. */
      @Positive int otpLockMinutes,
      @Positive int idempotencyRetentionHours,
      /* Phase 6: when a finished or running trip is flagged for a human to look at. */
      @Positive int driverSilenceFlagMinutes,
      @Positive int shortTripFlagSeconds,
      @Positive int shortTripFlagMeters,
      /** Repricing that raises the fare by more than this fraction is flagged. 0.5 is +50%. */
      @DecimalMin("0.0") double repriceFlagIncrease,
      @Positive int silenceCheckBatchSize) {}

  /** The surge engine. */
  public record Surge(
      @Min(1) @Max(12) int geohashPrecision,
      @Positive int windowMinutes,
      @Positive int tickSeconds,
      @Positive double maxStepPerTick,
      @DecimalMin("1.0") double cap,
      @Positive int ttlSeconds,
      /* Phase 8. */
      /** Demand-to-supply ratio up to which each multiplier applies; above the last, the cap. */
      @NotEmpty List<@Valid Step> steps,
      /** How long a looked-up multiplier is kept locally before Redis is asked again. */
      @Min(0) int cacheSeconds,
      /** Section 7: how long a minute's demand counter lives. */
      @Positive int demandTtlMinutes,
      /** The longest an admin override may last. */
      @Positive int overrideMaxHours) {

    /**
     * @param maxRatio the highest demand-to-supply ratio this step covers, inclusive
     */
    public record Step(@DecimalMin("0.0") double maxRatio, @DecimalMin("1.0") double multiplier) {}

    @AssertTrue(message = "surge steps must rise in ratio and stay within the cap")
    public boolean isStepTableValid() {
      if (steps == null) {
        return true;
      }
      for (int i = 0; i < steps.size(); i++) {
        Step step = steps.get(i);
        if (step == null
            || step.multiplier() > cap
            || (i > 0 && step.maxRatio() <= steps.get(i - 1).maxRatio())) {
          return false;
        }
      }
      return true;
    }
  }

  /** Domain events. */
  public record Events(
      /** How old an event whose listener has not completed must be before it is redelivered. */
      @Positive int redeliverAfterSeconds) {}

  /** Real-time delivery. */
  public record Notification(
      /** Section 7: a trip's message counter lives 24 hours. */
      @Positive int sequenceTtlHours) {}

  /** Commission, wallet top-ups, the gateway, and the ledger. */
  public record Payment(
      @Min(0) @Max(100) int commissionPercent,
      /* Phase 7. */
      @Positive long minTopupPaise,
      @Positive long maxTopupPaise,
      /** The gateway's webhook signing secret. Blank generates one per process (ADR 0013). */
      String webhookSecret,
      /** How far a webhook's signed timestamp may be from now; further is a replay. */
      @Positive int webhookToleranceSeconds,
      /** Tries at a posting that keeps losing optimistic-lock races before it gives up. */
      @Positive int postingMaxAttempts,
      /** Most mismatches one reconciliation lists; its counts are always complete. */
      @Positive int reconcileReportLimit,
      @NotNull @Valid MockGateway mockGateway) {

    @AssertTrue(message = "min-topup-paise must not exceed max-topup-paise")
    public boolean isTopupRangeValid() {
      return minTopupPaise <= maxTopupPaise;
    }

    /**
     * The stand-in for a real gateway, which PROJECT_SPEC section 2 puts out of scope.
     *
     * @param baseUrl where its "hosted payment page" lives, for the URL a top-up returns
     * @param webhookUrl where it delivers webhooks; blank means this instance's own endpoint
     * @param outcome what a payment does unless the call to pay says otherwise
     * @param unavailable refuse to create payments, as a gateway outage would
     * @param timeout to connect to, and to wait on, the webhook endpoint
     */
    public record MockGateway(
        boolean enabled,
        @NotBlank String baseUrl,
        String webhookUrl,
        @NotNull Outcome outcome,
        @Min(0) long delayMs,
        boolean unavailable,
        @NotNull Duration timeout) {}

    /** How a payment at the gateway ends. */
    public enum Outcome {
      SUCCEEDED,
      FAILED
    }
  }

  /** The admin live map. */
  public record Admin(
      /** The KPIs on the live map cover the trips requested in this many minutes. */
      @Positive int liveMapKpiWindowMinutes) {}

  /** Ratings. */
  public record Rating(
      /** How long after a trip completes either side may rate it. */
      @Positive int windowDays) {}
}
