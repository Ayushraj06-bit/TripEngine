package com.rideflow.payment.internal;

import com.rideflow.shared.Money;
import java.util.UUID;

/**
 * The payment gateway, from our side: where a top-up's payment starts. Its answer arrives later, as
 * a webhook. {@link MockPaymentGateway} is the only implementation; a real gateway replaces it here
 * (ADR 0013).
 */
interface PaymentGateway {

  /**
   * Starts the payment for a top-up, or returns the one already started: the top-up id is the
   * gateway's idempotency key, so asking twice never takes two payments.
   *
   * @throws com.rideflow.shared.BusinessException 503 {@code PAYMENT_GATEWAY_UNAVAILABLE}
   */
  Session createPayment(UUID topupId, Money amount);

  /**
   * @param paymentUrl where the rider goes to pay
   */
  record Session(String gatewayRef, String paymentUrl) {}
}
