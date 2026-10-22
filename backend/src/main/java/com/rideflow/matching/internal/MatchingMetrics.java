package com.rideflow.matching.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * What the matching engine is doing, for the dashboard. Prometheus renders these as {@code
 * time_to_assign_seconds}, {@code offers_sent_total}, {@code offers_result_total{result}}, {@code
 * no_driver_found_total} and {@code match_restarts_total}.
 */
@Component
class MatchingMetrics {

  private final MeterRegistry meters;
  private final Timer timeToAssign;
  private final Timer firstOffer;
  private final Counter offersSent;
  private final Counter noDriverFound;
  private final Counter restarts;

  MatchingMetrics(MeterRegistry meters) {
    this.meters = meters;
    this.timeToAssign =
        Timer.builder("time.to.assign")
            .description("From a trip needing a driver to a driver accepting it")
            .publishPercentileHistogram()
            .register(meters);
    this.firstOffer =
        Timer.builder("matching.first.offer")
            .description("From a trip being booked to its first offer going out (PROJECT_SPEC 2)")
            .publishPercentileHistogram()
            .register(meters);
    this.offersSent = Counter.builder("offers.sent").register(meters);
    this.noDriverFound = Counter.builder("no.driver.found").register(meters);
    this.restarts =
        Counter.builder("match.restarts")
            .description("Matching restarted by the recovery sweep after a crash or lost state")
            .register(meters);
  }

  void timeToAssign(Duration duration) {
    timeToAssign.record(duration);
  }

  void firstOffer(Duration sinceBooking) {
    firstOffer.record(sinceBooking);
  }

  void offerSent() {
    offersSent.increment();
  }

  /**
   * @param result accepted, rejected, expired or withdrawn
   */
  void offerResult(String result) {
    Counter.builder("offers.result").tag("result", result).register(meters).increment();
  }

  void noDriverFound() {
    noDriverFound.increment();
  }

  void restart() {
    restarts.increment();
  }
}
