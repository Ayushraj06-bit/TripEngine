package com.rideflow.notification;

import java.util.Objects;
import java.util.UUID;

/**
 * What to push: a type the client switches on, the payload, and the trip it belongs to.
 *
 * @param tripId null for a message that belongs to no trip yet - an offer - which then carries no
 *     sequence number
 * @param seq a number reserved with {@link RealtimePublisher#nextSeq}, or null to take the next one
 *     when the message is sent
 */
public record RealtimeMessage(String type, UUID tripId, Long seq, Object payload) {

  public RealtimeMessage {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(payload, "payload");
    if (seq != null && tripId == null) {
      throw new IllegalArgumentException("Only a trip's messages carry a seq");
    }
  }

  public static RealtimeMessage of(String type, Object payload) {
    return new RealtimeMessage(type, null, null, payload);
  }

  public static RealtimeMessage forTrip(String type, UUID tripId, Object payload) {
    return new RealtimeMessage(type, Objects.requireNonNull(tripId, "tripId"), null, payload);
  }

  /** A trip's message under a seq reserved before its payload was read. */
  public static RealtimeMessage forTrip(String type, UUID tripId, long seq, Object payload) {
    return new RealtimeMessage(type, Objects.requireNonNull(tripId, "tripId"), seq, payload);
  }
}
