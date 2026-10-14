package com.rideflow.simulator;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Everything the simulator does, from {@code simulator.*} in {@code application.yml}; any value can
 * be overridden on the command line ({@code --simulator.drivers=500}). The {@link Scenario} is
 * applied on top by {@link #withScenario()}.
 *
 * @param backendUrl the backend's base URL; STOMP is reached at {@code /ws} on the same host
 * @param requestsPerMinute ride requests across all riders, arriving as a Poisson process
 * @param tick how often each driver moves and reports a position, and each rider checks in
 */
@ConfigurationProperties("simulator")
@Validated
public record SimulatorProperties(
    @NotNull URI backendUrl,
    @NotNull Scenario scenario,
    @PositiveOrZero int drivers,
    @PositiveOrZero int riders,
    @NotNull Duration tick,
    @NotNull @Valid Demand demand,
    @NotNull @Valid DriverBehaviour driver,
    @NotNull @Valid Bounds serviceArea,
    @PositiveOrZero double gpsNoiseMeters,
    @NotNull @Valid Accounts accounts,
    @NotNull Duration kpiLogInterval) {

  /**
   * Riders.
   *
   * @param hotspotShare of pickups, the share near a hotspot; the rest are anywhere in the area
   * @param hotspotRadiusMeters how far from its hotspot a pickup may be
   * @param minTripMeters how far a drop is from its pickup, at least
   * @param vehicleMix relative weights of BIKE, AUTO and CAR, for bookings and for drivers'
   *     vehicles
   * @param paymentMethod WALLET (topped up through the mock gateway) or CASH
   * @param cancelAfter how long after booking a rider who cancels does so
   */
  public record Demand(
      @PositiveOrZero double requestsPerMinute,
      @NotEmpty List<@Valid Hotspot> hotspots,
      @DecimalMin("0") @DecimalMax("1") double hotspotShare,
      @Positive double hotspotRadiusMeters,
      @Positive double minTripMeters,
      @Positive double maxTripMeters,
      @NotEmpty Map<String, @Positive Integer> vehicleMix,
      @NotNull @Pattern(regexp = "WALLET|CASH") String paymentMethod,
      @DecimalMin("0") @DecimalMax("1") double cancelProbability,
      @NotNull @Valid Range cancelAfter) {

    @AssertTrue(message = "min-trip-meters must not exceed max-trip-meters")
    public boolean isTripLengthValid() {
      return minTripMeters <= maxTripMeters;
    }
  }

  /** A place riders gather, weighted against the other hotspots. */
  public record Hotspot(@NotBlank String name, double lat, double lng, @Positive int weight) {}

  /**
   * Drivers.
   *
   * @param acceptProbability of an offer; of the rest, half are rejected and half ignored, to let
   *     them time out
   * @param responseDelay how long a driver looks at an offer before answering
   * @param cancelProbability that a driver cancels a trip they accepted, before reaching the pickup
   * @param disconnectProbability per tick, for drivers and riders: the app loses its connection
   * @param offlineFor how long a lost connection stays lost
   * @param spoofProbability per tick: a position far from where the driver is, to trip the
   *     backend's spoof check
   */
  public record DriverBehaviour(
      @DecimalMin("0") @DecimalMax("1") double acceptProbability,
      @NotNull @Valid Range responseDelay,
      @DecimalMin("0") @DecimalMax("1") double cancelProbability,
      @DecimalMin("0") @DecimalMax("1") double disconnectProbability,
      @NotNull @Valid Range offlineFor,
      @DecimalMin("0") @DecimalMax("1") double spoofProbability,
      @Positive double minSpeedKmph,
      @Positive double maxSpeedKmph) {

    @AssertTrue(message = "min-speed-kmph must not exceed max-speed-kmph")
    public boolean isSpeedRangeValid() {
      return minSpeedKmph <= maxSpeedKmph;
    }
  }

  /** A duration chosen uniformly between {@code min} and {@code max}. */
  public record Range(@NotNull Duration min, @NotNull Duration max) {

    @AssertTrue(message = "min must not exceed max")
    public boolean isOrdered() {
      return min.compareTo(max) <= 0;
    }
  }

  /** The box drivers roam and riders are picked up and dropped in. */
  public record Bounds(double minLat, double maxLat, double minLng, double maxLng) {

    @AssertTrue(message = "min-lat must be below max-lat and min-lng below max-lng")
    public boolean isOrdered() {
      return minLat < maxLat && minLng < maxLng;
    }
  }

  /**
   * The simulated users, and the admin who approves the drivers.
   *
   * @param password every simulated user's: they are throwaway accounts on a test backend
   * @param adminPhone from {@code RIDEFLOW_ADMIN_PHONE}, as the backend's bootstrap admin
   * @param minBalancePaise a WALLET rider below this is topped up before booking
   */
  public record Accounts(
      @NotBlank String password,
      String adminPhone,
      String adminPassword,
      @Positive long minBalancePaise,
      @Positive long topUpPaise) {}

  /** These properties with {@link #scenario()}'s preset applied. */
  public SimulatorProperties withScenario() {
    return scenario.apply(this);
  }
}
