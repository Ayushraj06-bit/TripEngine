package com.rideflow.location.internal;

import com.rideflow.shared.VehicleType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Gauges for the live map.
 *
 * <p>The counters in {@link LocationIngestService} say what is happening to individual updates.
 * These say what the map currently looks like, which is the number an operator actually watches:
 * available drivers falling to zero while ingest stays healthy means the GEO sets are being emptied
 * by something - a bad status change, an over-eager sweep - rather than drivers going home.
 */
@Component
class LocationMetrics {

  /**
   * The ids, not a count: Spring may raise {@link SessionDisconnectEvent} more than once for a
   * session, and raises it too for a socket whose STOMP CONNECT was refused. A counter decremented
   * on each read -89,567 after an hour of the simulator reconnecting (Phase 10).
   */
  private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();

  LocationMetrics(MeterRegistry meters, DriverStateStore store) {
    Gauge.builder("location.websocket.sessions", activeSessions, Set::size)
        .description("WebSocket sessions connected to this instance")
        .register(meters);

    for (VehicleType type : VehicleType.values()) {
      // Read straight from Redis on scrape. The alternative, a counter maintained in
      // Java, would drift from the set it claims to describe the moment the sweeper
      // or another instance removed somebody.
      Gauge.builder("location.drivers.available", store, s -> s.availableCount(type))
          .tag("vehicleType", type.name())
          .description("Drivers currently listed as available for this vehicle type")
          .register(meters);
    }
  }

  /**
   * Counted per instance, deliberately.
   *
   * <p>PROJECT_SPEC section 5 has each instance deliver only to its own connections, so "sessions
   * here" is the number that explains this instance's load. The cluster total is the sum across
   * instances, which Prometheus does.
   */
  @EventListener
  void onConnected(SessionConnectedEvent event) {
    String sessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
    if (sessionId != null) {
      activeSessions.add(sessionId);
    }
  }

  @EventListener
  void onDisconnected(SessionDisconnectEvent event) {
    activeSessions.remove(event.getSessionId());
  }
}
