package com.rideflow.shared.internal.web;

import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * The baseline security posture: health probes open, everything else closed.
 *
 * <p>Two chains, and both are necessary.
 *
 * <p>The <strong>probe chain</strong> exists because Spring Security is on the classpath, so with
 * no {@code SecurityFilterChain} at all Boot applies HTTP Basic over every path with a password
 * generated at startup. That would cover {@code /actuator/health/liveness}, and an orchestrator
 * sends no credentials, so a perfectly healthy instance would look dead and be restarted.
 *
 * <p>The <strong>deny-all chain</strong> exists because of what fixing that breaks. Boot's security
 * auto-configuration backs off completely as soon as any {@code SecurityFilterChain} bean is
 * defined - it does not merge with it. Adding the probe chain alone therefore did not just open the
 * probes, it removed the default protection from every path the probe chain does not match, and
 * {@code /actuator/prometheus} started answering anonymously with a 200. This chain restores the
 * default: anything not explicitly permitted requires authentication.
 *
 * <p>There is deliberately no authentication mechanism yet, so the deny-all chain can only ever
 * answer 401. Phase 1's identity module adds the JWT resource server and its own chain for {@code
 * /api/**}, ordered between these two.
 */
@Configuration
class BaseSecurityConfig {

  /** Only the liveness and readiness probes, and info. Never metrics or module structure. */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  SecurityFilterChain probeSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.securityMatcher(
            "/actuator/health", "/actuator/health/**", "/actuator/info", "/livez", "/readyz")
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        // Probes carry no credentials and no state.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .build();
  }

  /**
   * Actuator on its own port (Phase 10): open, because only the internal network can reach it -
   * Prometheus scrapes it, and no load balancer publishes it. The matcher only matches requests
   * served by the management server, so the same paths on the public port stay closed.
   *
   * <p>Only when the port really is separate: on one shared port, metrics stay closed.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 1)
  @ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
  SecurityFilterChain managementPortSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.securityMatcher(EndpointRequest.toAnyEndpoint())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .build();
  }

  /**
   * Swagger UI and the API description it reads, wherever springdoc is on. The prod profile turns
   * it off (application-prod.yml), and this chain with it.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 2)
  @ConditionalOnProperty(name = "springdoc.api-docs.enabled", matchIfMissing = true)
  SecurityFilterChain apiDocsSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.securityMatcher("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .build();
  }

  /**
   * Everything else. Closed by default, so a new endpoint is protected until someone decides
   * otherwise rather than the other way round.
   */
  @Bean
  @Order(Ordered.LOWEST_PRECEDENCE)
  SecurityFilterChain defaultDenySecurityFilterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // A plain 401 rather than a browser Basic prompt: this is a JSON API.
        .exceptionHandling(
            handling ->
                handling.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .build();
  }
}
