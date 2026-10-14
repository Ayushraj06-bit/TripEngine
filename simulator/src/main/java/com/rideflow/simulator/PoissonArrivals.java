package com.rideflow.simulator;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Ride requests as a Poisson process: independent arrivals at a mean rate, so the gap to the next
 * one is exponentially distributed. Bursts and lulls come out of it naturally, as with real riders.
 */
final class PoissonArrivals {

  private PoissonArrivals() {}

  /** The wait until the next arrival, at {@code perMinute} arrivals a minute on average. */
  static Duration nextGap(double perMinute, RandomGenerator random) {
    if (perMinute <= 0) {
      throw new IllegalArgumentException("rate must be positive: " + perMinute);
    }
    // Inverse transform: -ln(U) is exponential with mean 1. 1 - U keeps U = 0 out of the log.
    double minutes = -Math.log(1 - random.nextDouble()) / perMinute;
    return Duration.ofNanos(Math.round(minutes * 60e9));
  }
}
