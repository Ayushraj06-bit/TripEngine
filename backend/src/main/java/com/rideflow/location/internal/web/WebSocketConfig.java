package com.rideflow.location.internal.web;

import com.rideflow.shared.RideFlowProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket at {@code /ws}.
 *
 * <p>A simple in-memory broker, not a relay. PROJECT_SPEC section 5 fans messages out across
 * instances through Redis Pub/Sub, so each instance only ever delivers to the clients connected to
 * it, and an external broker would be infrastructure buying nothing.
 *
 * <p>Heartbeats matter more here than they look. A driver's phone losing signal in a tunnel does
 * not close the TCP connection, so without heartbeats the server keeps a dead session and its
 * metrics report a driver who is not there. Ten seconds each way is well inside the thirty-second
 * staleness window, so a lost session is noticed before the sweeper would have to guess.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private static final long HEARTBEAT_MS = 10_000;

  private final StompAuthInterceptor authInterceptor;
  private final ExpiringSessions expiringSessions;
  private final List<String> allowedOrigins;

  WebSocketConfig(
      StompAuthInterceptor authInterceptor,
      ExpiringSessions expiringSessions,
      RideFlowProperties properties) {
    this.authInterceptor = authInterceptor;
    this.expiringSessions = expiringSessions;
    this.allowedOrigins = properties.web().allowedOrigins();
  }

  /**
   * A scheduler of its own for broker heartbeats.
   *
   * <p>Injecting Spring's own {@code messageBrokerTaskScheduler} here is a circular reference: the
   * configuration that supplies it is also the one that collects configurers, so this bean would
   * need a bean whose creation needs this bean.
   *
   * <p>Separate from the {@code @Scheduled} pool on purpose as well. Heartbeats are the mechanism
   * that notices a dead connection, and a long-running job holding the only thread would stall them
   * and start dropping healthy sessions.
   */
  @Bean
  TaskScheduler webSocketHeartbeatScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("ws-heartbeat-");
    scheduler.initialize();
    return scheduler;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    // No SockJS fallback: the clients are a mobile app and the simulator, both of
    // which speak WebSocket natively.
    // Same origin only, unless configured (Phase 10): any page on the internet could otherwise
    // open a socket here from a visitor's browser. The token is in the CONNECT frame rather than
    // a cookie, so it could not act as them - but it need not be allowed to try.
    registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins.toArray(String[]::new));
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry
        .enableSimpleBroker("/topic", "/queue")
        .setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
        // The simple broker needs its own scheduler to send heartbeats at all;
        // without one it silently does not.
        .setTaskScheduler(webSocketHeartbeatScheduler());
    registry.setApplicationDestinationPrefixes("/app");
    // Spring rewrites /user/** to the session's own queue, which is what makes
    // "subscribe only to your own destinations" enforceable.
    registry.setUserDestinationPrefix("/user");
  }

  /** Every session passes through ExpiringSessions, which closes it when its token expires. */
  @Override
  public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
    registration.addDecoratorFactory(expiringSessions);
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    // First in the chain: nothing else should see a frame from an unauthenticated
    // session, or from one reaching for a destination it does not own.
    registration.interceptors(authInterceptor);
  }
}
