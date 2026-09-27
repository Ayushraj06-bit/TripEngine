package com.rideflow.identity.internal;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * JWT security for the API.
 *
 * <p>PROJECT_SPEC section 3 rules out hand-written JWT filters, so validation is entirely Spring's
 * OAuth2 resource server: signature, expiry and issuer are checked by {@link JwtDecoder} before any
 * of our code runs. A tampered payload, an expired token or a token signed with another key
 * therefore fails at the filter with a 401 and never reaches a controller.
 *
 * <p>This chain sits between the two from Phase 0: the probe chain (highest precedence) keeps
 * health endpoints open, and the deny-all chain (lowest) closes everything neither of them claims.
 */
@Configuration
@EnableConfigurationProperties(IdentityProperties.class)
@EnableMethodSecurity
class SecurityConfig {

  private final RsaKeys keys;
  private final IdentityProperties properties;
  private final Clock clock;

  SecurityConfig(IdentityProperties properties, Clock clock) {
    this.keys = RsaKeys.from(properties);
    this.properties = properties;
    this.clock = clock;
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  JwtEncoder jwtEncoder() {
    RSAKey key = new RSAKey.Builder(keys.publicKey()).privateKey(keys.privateKey()).build();
    return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
  }

  @Bean
  JwtDecoder jwtDecoder() {
    // The public key alone verifies. A module extracted later needs only this.
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();

    // The decoder validates expiry against its own system clock by default, which
    // means a token issued from the injected Clock is judged against wall time. In
    // production the two agree; in a test using a controllable clock they do not, and
    // the result is a suite that passes in the morning and fails in the afternoon.
    // Handing it our Clock makes expiry mean the same thing everywhere.
    JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
    timestamps.setClock(clock);

    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            timestamps, new JwtIssuerValidator(properties.issuer())));
    return decoder;
  }

  /**
   * Maps the {@code role} claim onto a Spring authority.
   *
   * <p>Without this the token carries a role that method security cannot see, and every
   * {@code @PreAuthorize("hasRole(...)")} would deny.
   */
  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthorityPrefix("ROLE_");
    authorities.setAuthoritiesClaimName(TokenService.ROLE_CLAIM);

    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }

  @Bean
  @Order(0)
  SecurityFilterChain apiSecurityFilterChain(
      HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
    return http.securityMatcher("/api/**")
        .authorizeHttpRequests(
            auth ->
                auth
                    // Registration, login and refresh are how a caller gets a token in
                    // the first place, so they cannot require one.
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
        // Stateless with the token in a header: no cookie, so no CSRF surface.
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
