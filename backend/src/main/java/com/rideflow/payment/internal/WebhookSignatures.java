package com.rideflow.payment.internal;

import com.rideflow.shared.BusinessException;
import com.rideflow.shared.RideFlowProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Signs and checks the gateway's webhooks (ADR 0013): {@code v1=} and the hex HMAC-SHA256, under
 * the shared secret, of the timestamp, a dot, and the raw body.
 *
 * <p>Signing the timestamp with the body means an old delivery cannot be replayed with a fresh
 * timestamp, and one older than {@code webhook-tolerance-seconds} is refused outright. The
 * comparison takes the same time however much of the signature is right, so it leaks nothing.
 */
@Component
public class WebhookSignatures {

  public static final String TIMESTAMP_HEADER = "X-Gateway-Timestamp";

  public static final String SIGNATURE_HEADER = "X-Gateway-Signature";

  private static final String VERSION = "v1=";

  private static final Logger log = LoggerFactory.getLogger(WebhookSignatures.class);

  private final SecretKeySpec key;
  private final Clock clock;
  private final long toleranceSeconds;

  WebhookSignatures(RideFlowProperties properties, Clock clock) {
    var payment = properties.payment();
    this.key = new SecretKeySpec(secret(payment.webhookSecret()), "HmacSHA256");
    this.clock = clock;
    this.toleranceSeconds = payment.webhookToleranceSeconds();
  }

  /** The signature header value for a body sent at {@code timestamp} (epoch seconds). */
  public String sign(String timestamp, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return VERSION
          + HexFormat.of()
              .formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      // HmacSHA256 is required of every Java platform.
      throw new IllegalStateException(e);
    }
  }

  /**
   * @throws BusinessException 401 {@code INVALID_SIGNATURE} if a header is missing, the timestamp
   *     is too far from now, or the signature does not match
   */
  public void verify(String timestamp, String signature, String body) {
    if (timestamp == null || signature == null || body == null) {
      throw invalid();
    }
    long sentAt;
    try {
      sentAt = Long.parseLong(timestamp);
    } catch (NumberFormatException e) {
      throw invalid();
    }
    // Compared with now plus or minus the tolerance, never by subtracting: a crafted timestamp
    // could overflow the difference into one that passes.
    long now = clock.instant().getEpochSecond();
    if (sentAt < now - toleranceSeconds || sentAt > now + toleranceSeconds) {
      throw invalid();
    }
    byte[] expected = sign(timestamp, body).getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8))) {
      throw invalid();
    }
  }

  private static BusinessException invalid() {
    return new BusinessException(
        "INVALID_SIGNATURE",
        HttpStatus.UNAUTHORIZED,
        "The webhook signature is missing, wrong, or too old");
  }

  private static byte[] secret(String configured) {
    if (configured != null && !configured.isBlank()) {
      return configured.getBytes(StandardCharsets.UTF_8);
    }
    log.warn(
        "No rideflow.payment.webhook-secret configured; generated one for this process. Webhooks"
            + " signed for any other instance will be refused. Set it before running more than"
            + " one backend.");
    byte[] generated = new byte[32];
    new SecureRandom().nextBytes(generated);
    return generated;
  }
}
