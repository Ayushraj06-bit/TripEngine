package com.rideflow.notification;

import java.util.UUID;

/**
 * Real-time push to one user, wherever they are connected (PROJECT_SPEC section 5).
 *
 * <p>Best effort, by design: a user who is not connected misses the message, and nothing fails.
 * WebSocket is never the source of truth - every client resyncs with {@code GET /trips/active}
 * after connecting - so a lost push costs a moment, not data.
 *
 * <p>The client receives an envelope: {@code {type, tripId, seq, sentAt, payload}}. {@code seq}
 * rises with every message of a trip, across every instance, so a client that keeps the highest it
 * has seen can drop duplicates and stragglers.
 */
public interface RealtimePublisher {

  /**
   * Sends {@code message} to {@code userId}'s {@code destination}: {@code "/queue/trip-updates"}
   * reaches a client subscribed to {@code /user/queue/trip-updates}.
   */
  void toUser(UUID userId, String destination, RealtimeMessage message);
}
