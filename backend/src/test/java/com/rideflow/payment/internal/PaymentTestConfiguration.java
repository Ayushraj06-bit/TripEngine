package com.rideflow.payment.internal;

import com.rideflow.shared.Money;
import com.rideflow.shared.MutableClock;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * What the payment and rating integration tests share, so they share one context: a clock they can
 * move, and a gateway they can take down.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PaymentTestConfiguration {

  public static final Instant START = Instant.parse("2026-10-01T10:00:00Z");
  public static final MutableClock CLOCK = new MutableClock(START);

  @Bean
  @Primary
  Clock testClock() {
    return CLOCK;
  }

  @Bean
  @Primary
  SwitchableGateway testGateway(@Qualifier("mockPaymentGateway") PaymentGateway gateway) {
    return new SwitchableGateway(gateway);
  }

  /** The real mock gateway, with a switch for an outage. */
  static final class SwitchableGateway implements PaymentGateway {

    private final PaymentGateway real;
    private volatile boolean down;

    SwitchableGateway(PaymentGateway real) {
      this.real = real;
    }

    void goDown() {
      down = true;
    }

    void reset() {
      down = false;
    }

    @Override
    public Session createPayment(UUID topupId, Money amount) {
      if (down) {
        throw new com.rideflow.shared.BusinessException(
            "PAYMENT_GATEWAY_UNAVAILABLE",
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "The payment gateway is not answering. Try again shortly.");
      }
      return real.createPayment(topupId, amount);
    }
  }
}
