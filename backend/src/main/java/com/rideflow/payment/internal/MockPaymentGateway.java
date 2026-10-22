package com.rideflow.payment.internal;

import com.rideflow.shared.BusinessException;
import com.rideflow.shared.IdGenerator;
import com.rideflow.shared.KeyedRateLimiter;
import com.rideflow.shared.Money;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.shared.RideFlowProperties.Payment.Outcome;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stand-in payment gateway (PROJECT_SPEC section 2 puts the real one out of scope, ADR 0013).
 *
 * <p>It behaves as a hosted gateway does. A top-up gets a payment URL; paying there makes the
 * gateway call our webhook, signed, over HTTP - the same path a real gateway would take. Whoever
 * pays chooses how it ends and how long the gateway takes to say so, the way a real gateway's test
 * cards do; {@code mock-gateway.outcome} and {@code delay-ms} are the defaults. With {@code
 * unavailable}, it refuses to start payments at all.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

  public static final String PAY_PATH = "/mock-gateway/payments/{gatewayRef}/pay";

  public static final String WEBHOOK_PATH = "/api/v1/webhooks/payment-gateway";

  private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

  private final TopupRepository topups;
  private final WebhookSignatures signatures;
  private final JsonMapper json;
  private final IdGenerator ids;
  private final Clock clock;
  private final RideFlowProperties.Payment.MockGateway settings;
  private final Environment environment;
  private final RestClient http;
  private final SimpleAsyncTaskExecutor deliveries = new SimpleAsyncTaskExecutor("mock-gateway-");

  MockPaymentGateway(
      TopupRepository topups,
      WebhookSignatures signatures,
      JsonMapper json,
      IdGenerator ids,
      Clock clock,
      RideFlowProperties properties,
      Environment environment) {
    this.topups = topups;
    this.signatures = signatures;
    this.json = json;
    this.ids = ids;
    this.clock = clock;
    this.settings = properties.payment().mockGateway();
    this.environment = environment;
    // Connect and read timeouts: a webhook endpoint that hangs must not hold a thread forever.
    JdkClientHttpRequestFactory requests =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(settings.timeout()).build());
    requests.setReadTimeout(settings.timeout());
    this.http = RestClient.builder().requestFactory(requests).build();
    deliveries.setVirtualThreads(true);
  }

  @Override
  public Session createPayment(UUID topupId, Money amount) {
    if (settings.unavailable()) {
      throw new BusinessException(
          "PAYMENT_GATEWAY_UNAVAILABLE",
          HttpStatus.SERVICE_UNAVAILABLE,
          "The payment gateway is not answering. Try again shortly.",
          Map.of(KeyedRateLimiter.RETRY_AFTER_SECONDS, 5L));
    }
    // Derived from the top-up, so asking again answers the same session.
    String gatewayRef = "mock_" + topupId.toString().replace("-", "");
    return new Session(
        gatewayRef, settings.baseUrl() + PAY_PATH.replace("{gatewayRef}", gatewayRef));
  }

  /** What paying at the gateway's page set in motion. */
  public record Payment(String gatewayRef, Outcome outcome, long delayMs) {}

  /**
   * The rider pays: the webhook follows after the delay.
   *
   * @param outcome how it ends, or null for the configured default
   * @param delayMs how long until the webhook, or null for the configured default
   * @throws BusinessException 404 for a payment the gateway never started
   */
  public Payment pay(String gatewayRef, Outcome outcome, Long delayMs) {
    Topup topup =
        topups
            .findByGatewayRef(gatewayRef)
            .orElseThrow(() -> BusinessException.notFound("Payment not found"));
    Payment payment =
        new Payment(
            gatewayRef,
            outcome == null ? settings.outcome() : outcome,
            delayMs == null ? settings.delayMs() : delayMs);
    CompletableFuture.runAsync(
        () -> deliver(payment, topup.amountPaise()),
        CompletableFuture.delayedExecutor(payment.delayMs(), TimeUnit.MILLISECONDS, deliveries));
    return payment;
  }

  /** One delivery, as a gateway makes it. A failure is logged; this gateway does not retry. */
  private void deliver(Payment payment, long amountPaise) {
    String body =
        json.writeValueAsString(
            new GatewayNotification(
                ids.newId().toString(), payment.gatewayRef(), payment.outcome(), amountPaise));
    String timestamp = Long.toString(clock.instant().getEpochSecond());
    try {
      http.post()
          .uri(webhookUrl())
          .contentType(MediaType.APPLICATION_JSON)
          .header(WebhookSignatures.TIMESTAMP_HEADER, timestamp)
          .header(WebhookSignatures.SIGNATURE_HEADER, signatures.sign(timestamp, body))
          .body(body)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      log.warn("The mock gateway could not deliver its webhook for {}", payment.gatewayRef(), e);
    }
  }

  /** The configured URL, or this instance's own endpoint on the port it is listening on. */
  private String webhookUrl() {
    if (settings.webhookUrl() != null && !settings.webhookUrl().isBlank()) {
      return settings.webhookUrl();
    }
    return "http://localhost:"
        + environment.getProperty("local.server.port", "8080")
        + WEBHOOK_PATH;
  }
}
