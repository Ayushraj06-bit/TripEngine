package com.rideflow.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rideflow.shared.BusinessException;
import com.rideflow.shared.KeyedRateLimiter;
import com.rideflow.shared.Money;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MockPaymentGatewayTest {

  private static MockPaymentGateway gateway(Map<String, String> settings) {
    return new MockPaymentGateway(
        null, null, null, null, null, PaymentProperties.with(settings), null);
  }

  @Test
  void answersTheSamePaymentForTheSameTopup() {
    MockPaymentGateway gateway = gateway(Map.of());
    UUID topup = UUID.fromString("01940000-0000-7000-8000-000000000042");

    PaymentGateway.Session first = gateway.createPayment(topup, Money.ofPaise(10_000));

    assertThat(gateway.createPayment(topup, Money.ofPaise(10_000))).isEqualTo(first);
    assertThat(first.gatewayRef()).isEqualTo("mock_01940000000070008000000000000042");
    assertThat(first.paymentUrl())
        .isEqualTo(
            "http://localhost:8080/mock-gateway/payments/mock_01940000000070008000000000000042/pay");
  }

  @Test
  void refusesToStartPaymentsWhenConfiguredUnavailable() {
    MockPaymentGateway gateway =
        gateway(Map.of("rideflow.payment.mock-gateway.unavailable", "true"));

    assertThatThrownBy(() -> gateway.createPayment(UUID.randomUUID(), Money.ofPaise(10_000)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.code()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
              assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(e.properties()).containsKey(KeyedRateLimiter.RETRY_AFTER_SECONDS);
            });
  }
}
