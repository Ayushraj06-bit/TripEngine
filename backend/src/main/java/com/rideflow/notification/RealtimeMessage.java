package com.rideflow.notification;

import java.util.Objects;
import java.util.UUID;

/**
 * What to push: a type the client switches on, the payload, and the trip it belongs to.
 *
 * @param tripId null for a message that belongs to no trip yet - an offer - which then carries no
 *     sequence number
 */
public record RealtimeMessage(String type, UUID tripId, Object payload) {

  public RealtimeMessage {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(payload, "payload");
  }

  public static RealtimeMessage of(String type, Object payload) {
    return new RealtimeMessage(type, null, payload);
  }

  public static RealtimeMessage forTrip(String type, UUID tripId, Object payload) {
    return new RealtimeMessage(type, Objects.requireNonNull(tripId, "tripId"), payload);
  }
}
