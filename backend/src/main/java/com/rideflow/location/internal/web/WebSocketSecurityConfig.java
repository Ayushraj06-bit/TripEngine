package com.rideflow.location.internal.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Lets the WebSocket handshake through the HTTP filter chain.
 *
 * <p>The token travels in the STOMP CONNECT frame, not on the HTTP upgrade request, so without this
 * the handshake falls to the deny-all chain and every client gets a 401 before it can send a frame.
 *
 * <p>Permitting the handshake does not permit anything else. {@link StompAuthInterceptor} gates
 * every frame: CONNECT needs a valid token, and SEND or SUBSCRIBE on a session that never
 * authenticated is refused. An anonymous handshake can do nothing but fail to connect.
 */
@Configuration
class WebSocketSecurityConfig {

  @Bean
  @Order(1)
  SecurityFilterChain webSocketHandshakeFilterChain(HttpSecurity http) throws Exception {
    return http.securityMatcher("/ws", "/ws/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        // No cookie-based session is involved, so there is no CSRF surface here.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .build();
  }
}
