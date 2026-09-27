package com.rideflow.identity.internal;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Brute-force protection on the endpoints that hand out tokens.
 *
 * <p>Account lockout alone is not enough: it protects one account from many guesses, but does
 * nothing about one attacker spraying one password across thousands of phone numbers, and nothing
 * about a flood of registrations. So there are two dimensions - per phone and per client address.
 *
 * <p>Buckets are per instance and in memory. PROJECT_SPEC section 3 specifies Bucket4j with a Redis
 * backend, and that is the right answer for a cluster, because a two-instance deployment doubles
 * every limit here. Redis-backed buckets are a small change to this class and no change anywhere
 * else; local buckets are a deliberate Phase 1 simplification, not the final state.
 */
@Component
class RateLimitFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

  private static final String LOGIN_PATH = "/api/v1/auth/login";
  private static final String REGISTER_PREFIX = "/api/v1/auth/register";

  /** Higher, because a household or office shares one address. */
  private static final Bandwidth LOGIN_PER_ADDRESS =
      Bandwidth.builder().capacity(20).refillGreedy(20, Duration.ofMinutes(1)).build();

  /** Registration is once per person, so anything more than a trickle is abuse. */
  private static final Bandwidth REGISTER_PER_ADDRESS =
      Bandwidth.builder().capacity(3).refillGreedy(3, Duration.ofHours(1)).build();

  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !(path.equals(LOGIN_PATH) || path.startsWith(REGISTER_PREFIX));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String path = request.getRequestURI();
    String address = clientAddress(request);

    Bucket bucket =
        path.equals(LOGIN_PATH)
            ? bucketFor("login:ip:" + address, LOGIN_PER_ADDRESS)
            : bucketFor("register:ip:" + address, REGISTER_PER_ADDRESS);

    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (!probe.isConsumed()) {
      reject(response, probe.getNanosToWaitForRefill());
      return;
    }
    chain.doFilter(request, response);
  }

  private Bucket bucketFor(String key, Bandwidth bandwidth) {
    return buckets.computeIfAbsent(key, ignored -> Bucket.builder().addLimit(bandwidth).build());
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

  private void reject(HttpServletResponse response, long nanosToWait) throws IOException {
    long seconds = secondsToWait(nanosToWait);
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

  private static long secondsToWait(long nanos) {
    // Round up: telling a client to retry in 0 seconds invites an immediate retry.
    return Math.max(1, Duration.ofNanos(nanos).toSeconds() + 1);
  }
}
