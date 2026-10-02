package com.rideflow.notification;

import java.util.UUID;

/**
 * Real-time push to one user, wherever they are connected (PROJECT_SPEC section 5).
 *
 * <p>Best effort, by design: a user who is not connected misses the message. WebSocket is never the
 * source of truth - every client resyncs over REST after connecting - so a lost push costs a
 * moment, not data.
 */
public interface Notifications {

  /**
   * Sends {@code payload}, as JSON, to {@code userId}'s {@code destination}: {@code
   * "/queue/offers"} reaches a client subscribed to {@code /user/queue/offers}.
   */
  void sendToUser(UUID userId, String destination, Object payload);
}
