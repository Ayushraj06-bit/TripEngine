package com.rideflow.payment.internal.web;

import com.rideflow.payment.internal.MockPaymentGateway;
import com.rideflow.shared.RideFlowProperties.Payment.Outcome;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The mock gateway's "hosted payment page": paying here makes the gateway send its webhook.
 *
 * <p>It hands out money to whoever calls it, as a test gateway does, so it exists only while {@code
 * mock-gateway.enabled} is true. A real gateway has no such endpoint on our side at all.
 */
@RestController
@ConditionalOnProperty(
    prefix = "rideflow.payment.mock-gateway",
    name = "enabled",
    havingValue = "true")
class MockGatewayController {

  private final MockPaymentGateway gateway;

  MockGatewayController(MockPaymentGateway gateway) {
    this.gateway = gateway;
  }

  /**
   * @param outcome how the payment ends; the configured default if absent
   * @param delayMs how long before the webhook; the configured default if absent
   */
  @PostMapping(MockPaymentGateway.PAY_PATH)
  @ResponseStatus(HttpStatus.ACCEPTED)
  MockPaymentGateway.Payment pay(
      @PathVariable String gatewayRef,
      @RequestParam(required = false) Outcome outcome,
      @RequestParam(required = false) @Min(0) @Max(60_000) Long delayMs) {
    return gateway.pay(gatewayRef, outcome, delayMs);
  }
}
