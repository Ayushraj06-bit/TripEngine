package com.rideflow.payment.internal.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The gateway's way in: its webhook, and the mock's payment page. Neither carries a user's token -
 * the webhook is authenticated by its HMAC signature, in {@link PaymentWebhookController} - so they
 * get a chain of their own, ahead of the {@code /api/**} chain that would demand one.
 */
@Configuration
class PaymentSecurityConfig {

  @Bean
  @Order(-1)
  SecurityFilterChain paymentGatewayFilterChain(HttpSecurity http) throws Exception {
    return http.securityMatcher("/api/v1/webhooks/**", "/mock-gateway/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        // Server to server, no cookies: nothing for CSRF to protect.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .build();
  }
}
