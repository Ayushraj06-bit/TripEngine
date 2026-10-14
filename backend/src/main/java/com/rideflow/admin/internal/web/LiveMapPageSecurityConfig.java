package com.rideflow.admin.internal.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Serves the live map page ({@code static/admin/live-map.html}) to anyone.
 *
 * <p>A browser opening a URL cannot send a bearer token, so the page cannot be the thing that is
 * protected. It holds no data: an admin signs in on it, and the snapshot arrives on {@code
 * /topic/admin/live-map}, which only an ADMIN token may subscribe to (ADR 0015).
 */
@Configuration
class LiveMapPageSecurityConfig {

  static final String PAGE = "/admin/live-map.html";

  @Bean
  @Order(2)
  SecurityFilterChain liveMapPageFilterChain(HttpSecurity http) throws Exception {
    return http.securityMatcher(PAGE)
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        // A static page and no cookies: nothing for CSRF to protect.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .build();
  }
}
