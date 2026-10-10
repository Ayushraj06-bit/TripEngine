package com.rideflow.payment.internal.web;

import com.rideflow.payment.internal.GatewayNotification;
import com.rideflow.payment.internal.MockPaymentGateway;
import com.rideflow.payment.internal.TopupService;
import com.rideflow.payment.internal.WebhookSignatures;
import com.rideflow.shared.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway tells us how a payment ended (ADR 0013).
 *
 * <p>No user token: the gateway authenticates with its signature, checked against the raw body
 * before anything is parsed. A 2xx tells the gateway to stop retrying, so a repeat delivery gets
 * one too, having changed nothing.
 */
@RestController
class PaymentWebhookController {

  private final WebhookSignatures signatures;
  private final TopupService topups;
  private final JsonMapper json;

  PaymentWebhookController(WebhookSignatures signatures, TopupService topups, JsonMapper json) {
    this.signatures = signatures;
    this.topups = topups;
    this.json = json;
  }

  /**
   * @throws BusinessException 401 {@code INVALID_SIGNATURE}; 400 {@code MALFORMED_WEBHOOK}
   */
  @PostMapping(MockPaymentGateway.WEBHOOK_PATH)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void receive(
      @RequestHeader(value = WebhookSignatures.TIMESTAMP_HEADER, required = false) String timestamp,
      @RequestHeader(value = WebhookSignatures.SIGNATURE_HEADER, required = false) String signature,
      @RequestBody(required = false) String body) {
    signatures.verify(timestamp, signature, body);
    topups.confirm(parse(body));
  }

  private GatewayNotification parse(String body) {
    GatewayNotification notification;
    try {
      notification = json.readValue(body, GatewayNotification.class);
    } catch (JacksonException e) {
      throw malformed();
    }
    if (notification.gatewayRef() == null
        || notification.status() == null
        || notification.amountPaise() <= 0) {
      throw malformed();
    }
    return notification;
  }

  private static BusinessException malformed() {
    return new BusinessException(
        "MALFORMED_WEBHOOK", HttpStatus.BAD_REQUEST, "The webhook body is not a payment update");
  }
}
