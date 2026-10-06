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
 * rises with every message of a trip, across every instance and every destination. Delivery is not
 * ordered, so a client keeps the highest seq it has seen per trip <em>and destination</em>, and
 * drops anything at or below it there: a duplicate, or a state already overtaken. Comparing across
 * destinations would drop a trip update overtaken by a mere position.
 */
public interface RealtimePublisher {

  /**
   * Sends {@code message} to {@code userId}'s {@code destination}: {@code "/queue/trip-updates"}
   * reaches a client subscribed to {@code /user/queue/trip-updates}.
   */
  void toUser(UUID userId, String destination, RealtimeMessage message);

  /**
   * Reserves the next {@code seq} of a trip's messages, for a message whose payload is a state read
   * from the database. Reserve first and read second: a read made later is never older, so a higher
   * number can never carry an older state.
   */
  long nextSeq(UUID tripId);
}
