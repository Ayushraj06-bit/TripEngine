package com.rideflow.payment.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rideflow.shared.BusinessException;
import com.rideflow.shared.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class WebhookSignaturesTest {

  private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
  private static final String BODY = "{\"gatewayRef\":\"mock_1\",\"status\":\"SUCCEEDED\"}";

  private final MutableClock clock = new MutableClock(NOW);
  private final WebhookSignatures signatures = withSecret("shh");

  private WebhookSignatures withSecret(String secret) {
    return new WebhookSignatures(
        PaymentProperties.with(Map.of("rideflow.payment.webhook-secret", secret)), clock);
  }

  private static String at(Instant instant) {
    return Long.toString(instant.getEpochSecond());
  }

  private void assertRefused(String timestamp, String signature, String body) {
    assertThatThrownBy(() -> signatures.verify(timestamp, signature, body))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("INVALID_SIGNATURE");
              org.assertj.core.api.Assertions.assertThat(e.status())
                  .isEqualTo(HttpStatus.UNAUTHORIZED);
            });
  }

  @Test
  void acceptsWhatItSigned() {
    String timestamp = at(NOW);

    assertThatCode(() -> signatures.verify(timestamp, signatures.sign(timestamp, BODY), BODY))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesAChangedBodyOrTimestamp() {
    String timestamp = at(NOW);
    String signature = signatures.sign(timestamp, BODY);

    assertRefused(timestamp, signature, BODY.replace("SUCCEEDED", "FAILED"));
    assertRefused(at(NOW.plusSeconds(1)), signature, BODY);
  }

  @Test
  void refusesAnotherSecretsSignature() {
    String timestamp = at(NOW);

    assertRefused(timestamp, withSecret("guess").sign(timestamp, BODY), BODY);
  }

  @Test
  void refusesMissingOrMalformedHeaders() {
    String timestamp = at(NOW);

    assertRefused(null, signatures.sign(timestamp, BODY), BODY);
    assertRefused(timestamp, null, BODY);
    assertRefused(timestamp, signatures.sign(timestamp, BODY), null);
    assertRefused("yesterday", signatures.sign("yesterday", BODY), BODY);
  }

  /** Five minutes either way, and not a second more: a replay, or a clock gone wrong. */
  @Test
  void acceptsATimestampWithinTheToleranceAndNoFurther() {
    String edge = at(NOW.minus(Duration.ofSeconds(300)));
    String past = at(NOW.minus(Duration.ofSeconds(301)));
    String future = at(NOW.plus(Duration.ofSeconds(301)));

    assertThatCode(() -> signatures.verify(edge, signatures.sign(edge, BODY), BODY))
        .doesNotThrowAnyException();
    assertRefused(past, signatures.sign(past, BODY), BODY);
    assertRefused(future, signatures.sign(future, BODY), BODY);
  }

  /** A timestamp chosen so that "now minus it" overflows to a value that would look fresh. */
  @Test
  void refusesATimestampThatWouldOverflowTheAgeCheck() {
    String crafted = Long.toString(NOW.getEpochSecond() + Long.MIN_VALUE);

    assertRefused(crafted, signatures.sign(crafted, BODY), BODY);
  }

  /** Without a configured secret each process makes its own, so no other can sign for it. */
  @Test
  void generatesASecretOfItsOwnWhenNoneIsConfigured() {
    WebhookSignatures one = withSecret("");
    WebhookSignatures other = withSecret("");
    String timestamp = at(NOW);

    assertThatThrownBy(() -> one.verify(timestamp, other.sign(timestamp, BODY), BODY))
        .isInstanceOf(BusinessException.class);
  }
}
