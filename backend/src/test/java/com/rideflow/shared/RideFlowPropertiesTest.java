package com.rideflow.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * PROJECT_SPEC section 11 forbids hard-coded magic numbers, and section 9 lists the values. These
 * tests check the other half of that: that a nonsensical value stops the application at startup
 * rather than silently producing a wrong fare or an offer that never times out.
 *
 * <p>No containers here - binding and validation need nothing but a context.
 */
class RideFlowPropertiesTest {

  /** The section 9 defaults, as application.yml sets them. */
  private static final String[] VALID =
      new String[] {
        "rideflow.location.min-interval-ms=1000",
        "rideflow.location.min-move-meters=10",
        "rideflow.location.force-write-seconds=15",
        "rideflow.location.stale-after-seconds=30",
        "rideflow.location.max-speed-kmph=150",
        "rideflow.location.max-accuracy-meters=100",
        "rideflow.location.spoof-strikes-before-flag=5",
        "rideflow.location.stale-sweep-batch-size=500",
        "rideflow.location.nearby-result-limit=15",
        "rideflow.location.nearby-cache-seconds=2",
        "rideflow.location.nearby-rounding-decimals=3",
        "rideflow.location.driver-location-forward-ms=2000",
        "rideflow.location.route-max-points=5000",
        "rideflow.location.route-ttl-hours=6",
        "rideflow.matching.radius-steps-km=2,4,7",
        "rideflow.matching.candidates-per-search=20",
        "rideflow.matching.offer-timeout-seconds=12",
        "rideflow.matching.deadline-seconds=60",
        "rideflow.matching.research-interval-seconds=5",
        "rideflow.matching.fairness-bucket-meters=300",
        "rideflow.matching.min-driver-rating=3.5",
        "rideflow.matching.lock-grace-seconds=3",
        "rideflow.matching.state-ttl-minutes=10",
        "rideflow.matching.offer-poll-batch-size=100",
        "rideflow.matching.recovery-grace-seconds=10",
        "rideflow.matching.recovery-batch-size=200",
        "rideflow.matching.offer-pickup-rounding-decimals=3",
        "rideflow.pricing.road-factor=1.3",
        "rideflow.pricing.quote-ttl-minutes=5",
        "rideflow.pricing.min-trip-meters=300",
        "rideflow.pricing.max-trip-km=60",
        "rideflow.pricing.reprice-deviation=0.20",
        "rideflow.pricing.average-speed-kmph.bike=25",
        "rideflow.pricing.average-speed-kmph.auto=20",
        "rideflow.pricing.average-speed-kmph.car=22",
        "rideflow.pricing.quote-rate-limit-per-minute=30",
        "rideflow.pricing.rate-card-refresh-seconds=60",
        "rideflow.trip.arrival-geofence-meters=150",
        "rideflow.trip.free-wait-minutes=3",
        "rideflow.trip.no-show-after-minutes=5",
        "rideflow.trip.otp-max-attempts=3",
        "rideflow.trip.rider-cancel-grace-seconds=120",
        "rideflow.trip.otp-lock-minutes=2",
        "rideflow.trip.idempotency-retention-hours=24",
        "rideflow.trip.driver-silence-flag-minutes=10",
        "rideflow.trip.short-trip-flag-seconds=60",
        "rideflow.trip.short-trip-flag-meters=100",
        "rideflow.trip.reprice-flag-increase=0.5",
        "rideflow.trip.silence-check-batch-size=500",
        "rideflow.surge.geohash-precision=6",
        "rideflow.surge.window-minutes=5",
        "rideflow.surge.tick-seconds=30",
        "rideflow.surge.max-step-per-tick=0.25",
        "rideflow.surge.cap=2.5",
        "rideflow.surge.ttl-seconds=90",
        "rideflow.surge.steps[0].max-ratio=1.0",
        "rideflow.surge.steps[0].multiplier=1.0",
        "rideflow.surge.steps[1].max-ratio=2.0",
        "rideflow.surge.steps[1].multiplier=1.5",
        "rideflow.surge.cache-seconds=5",
        "rideflow.surge.demand-ttl-minutes=10",
        "rideflow.surge.override-max-hours=24",
        "rideflow.payment.commission-percent=20",
        "rideflow.payment.min-topup-paise=1000",
        "rideflow.payment.max-topup-paise=1000000",
        "rideflow.payment.webhook-tolerance-seconds=300",
        "rideflow.payment.posting-max-attempts=10",
        "rideflow.payment.reconcile-report-limit=100",
        "rideflow.payment.mock-gateway.enabled=true",
        "rideflow.payment.mock-gateway.base-url=http://localhost:8080",
        "rideflow.payment.mock-gateway.outcome=SUCCEEDED",
        "rideflow.payment.mock-gateway.delay-ms=0",
        "rideflow.payment.mock-gateway.unavailable=false",
        "rideflow.notification.sequence-ttl-hours=24",
        "rideflow.rating.window-days=7",
        "rideflow.service-area.vertices[0].lat=13.14",
        "rideflow.service-area.vertices[0].lng=77.46",
        "rideflow.service-area.vertices[1].lat=13.14",
        "rideflow.service-area.vertices[1].lng=77.78",
        "rideflow.service-area.vertices[2].lat=12.82",
        "rideflow.service-area.vertices[2].lng=77.78",
        "rideflow.service-area.vertices[3].lat=12.82",
        "rideflow.service-area.vertices[3].lng=77.46",
      };

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(EnablesProperties.class);

  @EnableConfigurationProperties(RideFlowProperties.class)
  static class EnablesProperties {}

  @Test
  void bindsEveryValueFromSectionNine() {
    runner
        .withPropertyValues(VALID)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              RideFlowProperties properties = context.getBean(RideFlowProperties.class);

              assertThat(properties.location().minIntervalMs()).isEqualTo(1000);
              assertThat(properties.location().staleAfterSeconds()).isEqualTo(30);
              assertThat(properties.matching().radiusStepsKm()).containsExactly(2.0, 4.0, 7.0);
              assertThat(properties.matching().offerTimeoutSeconds()).isEqualTo(12);
              assertThat(properties.matching().minDriverRating()).isEqualTo(3.5);
              assertThat(properties.pricing().roadFactor()).isEqualTo(1.3);
              assertThat(properties.pricing().repriceDeviation()).isEqualTo(0.20);
              assertThat(properties.trip().arrivalGeofenceMeters()).isEqualTo(150);
              assertThat(properties.trip().otpMaxAttempts()).isEqualTo(3);
              assertThat(properties.surge().cap()).isEqualTo(2.5);
              assertThat(properties.surge().geohashPrecision()).isEqualTo(6);
              assertThat(properties.payment().commissionPercent()).isEqualTo(20);
              assertThat(properties.location().spoofStrikesBeforeFlag()).isEqualTo(5);
              assertThat(properties.location().nearbyRoundingDecimals()).isEqualTo(3);
              assertThat(properties.serviceArea().vertices()).hasSize(4);
            });
  }

  @Test
  void failsToStartWhenTheOfferTimeoutIsNegative() {
    runner
        .withPropertyValues(VALID)
        .withPropertyValues("rideflow.matching.offer-timeout-seconds=-1")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("offerTimeoutSeconds"));
  }

  @Test
  void failsToStartWhenTheOfferTimeoutIsZero() {
    // Zero is as broken as negative: an offer that expires immediately can never be accepted.
    runner
        .withPropertyValues(VALID)
        .withPropertyValues("rideflow.matching.offer-timeout-seconds=0")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsToStartWhenTheSurgeCapWouldDiscountTheFare() {
    // A cap below 1.0 would mean surge makes rides cheaper.
    runner
        .withPropertyValues(VALID)
        .withPropertyValues("rideflow.surge.cap=0.5")
        .run(
            context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("cap"));
  }

  @Test
  void failsToStartWhenCommissionExceedsTheWholeFare() {
    runner
        .withPropertyValues(VALID)
        .withPropertyValues("rideflow.payment.commission-percent=101")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("commissionPercent"));
  }

  @Test
  void failsToStartWhenSurgeStepsDoNotRiseOrExceedTheCap() {
    runner
        .withPropertyValues(VALID)
        .withPropertyValues("rideflow.surge.steps[1].max-ratio=0.5")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("surge steps must rise in ratio"));
    runner
        .withPropertyValues(VALID)
        .withPropertyValues("rideflow.surge.steps[1].multiplier=3.0")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsToStartWhenTheSmallestTopUpExceedsTheLargest() {
    runner
        .withPropertyValues(VALID)
        .withPropertyValues("rideflow.payment.min-topup-paise=2000000")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("min-topup-paise must not exceed max-topup-paise"));
  }

  @Test
  void failsToStartWhenThereAreNoSearchRadiusSteps() {
    // An empty list would make matching search nowhere and silently find no driver.
    runner
        .withPropertyValues(VALID)
        .withPropertyValues("rideflow.matching.radius-steps-km=")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsToStartWhenTheRoadFactorIsBelowStraightLineDistance() {
    // Below 1.0 the estimate would be shorter than the great-circle distance.
    runner
        .withPropertyValues(VALID)
        .withPropertyValues("rideflow.pricing.road-factor=0.9")
        .run(
            context ->
                assertThat(context).hasFailed().getFailure().hasStackTraceContaining("roadFactor"));
  }

  @Test
  void failsToStartWithoutAServiceArea() {
    // The polygon has no sensible default: a service with no boundary would quote
    // and dispatch anywhere on earth.
    runner
        .withPropertyValues(VALID)
        .withPropertyValues("rideflow.service-area.vertices[0].lat=")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void failsToStartWhenAWholeSectionIsMissing() {
    // Dropping a block must fail loudly, not bind nulls that blow up later at runtime.
    runner.run(context -> assertThat(context).hasFailed());
  }
}
