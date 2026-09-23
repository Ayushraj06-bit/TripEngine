package com.rideflow.shared.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the application's {@link Clock}.
 *
 * <p>PROJECT_SPEC section 11 forbids {@code Instant.now()} and friends in business code. Everything
 * that needs the time injects this bean instead, which is what makes it possible to test offer
 * timeouts, the cancellation grace period and the no-show timer without waiting for real seconds to
 * pass, and what lets PROJECT_SPEC section 12 rule out {@code Thread.sleep} in tests.
 *
 * <p>The clock is UTC. PROJECT_SPEC section 7 stores every timestamp as {@code timestamptz} in UTC,
 * so a clock in the machine's local zone would quietly reintroduce a zone at the edges.
 *
 * <p>A test replaces this by defining its own {@code Clock} bean, usually a {@link
 * Clock#fixed(java.time.Instant, java.time.ZoneId)} or a mutable test double.
 */
@Configuration
public class ClockConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
