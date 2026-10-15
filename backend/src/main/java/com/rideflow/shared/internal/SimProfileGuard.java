package com.rideflow.shared.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Refuses to start with both {@code prod} and {@code sim} active. The sim profile lifts the
 * per-address login and registration limits and turns on the mock gateway, whose page credits any
 * wallet that asks: in production, a typo away from brute-force protection off and free money.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod & sim")
class SimProfileGuard {

  SimProfileGuard() {
    throw new IllegalStateException(
        "The sim profile is for the traffic simulator and must never run with prod");
  }
}
