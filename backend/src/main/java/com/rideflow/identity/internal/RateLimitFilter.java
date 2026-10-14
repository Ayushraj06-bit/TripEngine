package com.rideflow.identity.internal;

import com.rideflow.shared.KeyedRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Brute-force protection on the endpoints that hand out tokens, per client address.
 *
 * <p>Account lockout alone is not enough: it protects one account from many guesses, but does
 * nothing about one attacker spraying a password across thousands of phone numbers, and nothing
 * about a flood of registrations. The per-phone half of the login limit lives in {@code
 * PhoneRateLimiter}, because the phone is in the request body.
 */
@Component
class RateLimitFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

  private static final String LOGIN_PATH = "/api/v1/auth/login";
  private static final String REGISTER_PREFIX = "/api/v1/auth/register";

  /** Higher than per phone, because a household or office shares one address. */
  private final KeyedRateLimiter loginPerAddress;

  /** Registration is once per person, so anything more than a trickle is abuse. */
  private final KeyedRateLimiter registerPerAddress;

  RateLimitFilter(IdentityProperties properties, Clock clock) {
    this.loginPerAddress =
        new KeyedRateLimiter(
            properties.loginAttemptsPerAddressPerMinute(),
            Duration.ofMinutes(1),
            "login attempts",
            clock);
    this.registerPerAddress =
        new KeyedRateLimiter(
            properties.registrationsPerAddressPerHour(),
            Duration.ofHours(1),
            "registrations",
            clock);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !(path.equals(LOGIN_PATH) || path.startsWith(REGISTER_PREFIX));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String address = clientAddress(request);
    OptionalLong wait =
        request.getRequestURI().equals(LOGIN_PATH)
            ? loginPerAddress.tryConsume(address)
            : registerPerAddress.tryConsume(address);

    if (wait.isPresent()) {
      reject(response, wait.getAsLong());
      return;
    }
    chain.doFilter(request, response);
  }

  /** For tests; see {@link KeyedRateLimiter#reset()}. */
  void reset() {
    loginPerAddress.reset();
    registerPerAddress.reset();
  }

  /**
   * The caller's address, preferring the proxy header because every request reaches us through
   * nginx in the scale profile, where the socket address is the load balancer for everyone.
   */
  private static String clientAddress(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      // Left-most entry is the original client; the rest are proxies.
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  /** Written here rather than thrown: a filter runs outside the controller advice. */
  private void reject(HttpServletResponse response, long seconds) throws IOException {
    log.debug("Rate limited; retry after {}s", seconds);
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader("Retry-After", Long.toString(seconds));
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response
        .getWriter()
        .write(
            """
            {"type":"about:blank","title":"Too many requests","status":429,\
            "detail":"Too many attempts. Try again in %d seconds.","code":"RATE_LIMITED"}"""
                .formatted(seconds));
  }
}
