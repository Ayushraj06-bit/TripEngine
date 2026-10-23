package com.rideflow.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.redis.testcontainers.RedisContainer;
import com.rideflow.shared.RateLimitBuckets;
import io.lettuce.core.RedisClient;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The per-address limits come from configuration, and the address from the socket. */
@Tag("integration")
class RateLimitFilterTest {

  private static final RedisContainer REDIS = new RedisContainer("redis:7.4.11-alpine");
  private static final IdentityProperties PROPERTIES =
      new IdentityProperties(
          "https://rideflow.test",
          Duration.ofMinutes(15),
          Duration.ofDays(7),
          10,
          Duration.ofMinutes(15),
          2,
          1,
          null,
          null,
          new IdentityProperties.BootstrapAdmin(null, null, null));

  private static RedisClient client;
  private static RateLimitBuckets buckets;

  @BeforeAll
  static void startRedis() {
    REDIS.start();
    client = RedisClient.create(REDIS.getRedisURI());
    buckets = new RateLimitBuckets(() -> client, Clock.systemUTC(), Duration.ofSeconds(1));
  }

  @AfterAll
  static void stopRedis() {
    buckets.close();
    client.shutdown();
    REDIS.stop();
  }

  private final RateLimitFilter filter = new RateLimitFilter(PROPERTIES, buckets);

  private int forged;

  @BeforeEach
  void startEmpty() {
    filter.reset();
  }

  private MockHttpServletResponse send(RateLimitFilter through, String path, String address)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setRemoteAddr(address);
    // Ignored: anyone can write it.
    request.addHeader("X-Forwarded-For", "203.0.113." + forged++);
    MockHttpServletResponse response = new MockHttpServletResponse();
    through.doFilter(request, response, new MockFilterChain());
    return response;
  }

  private int status(String path, String address) throws Exception {
    return send(filter, path, address).getStatus();
  }

  @Test
  void allowsAsManyLoginsAsConfigured() throws Exception {
    assertThat(status("/api/v1/auth/login", "198.51.100.1")).isEqualTo(200);
    assertThat(status("/api/v1/auth/login", "198.51.100.1")).isEqualTo(200);
    assertThat(status("/api/v1/auth/login", "198.51.100.1")).isEqualTo(429);
    assertThat(status("/api/v1/auth/login", "198.51.100.2")).isEqualTo(200);
  }

  @Test
  void allowsAsManyRegistrationsAsConfigured() throws Exception {
    assertThat(status("/api/v1/auth/register/rider", "198.51.100.3")).isEqualTo(200);
    assertThat(status("/api/v1/auth/register/driver", "198.51.100.3")).isEqualTo(429);
  }

  /** Two instances behind a load balancer: an address's allowance is spent across both. */
  @Test
  void sharesTheAllowanceWithTheOtherInstance() throws Exception {
    RateLimitFilter otherInstance = new RateLimitFilter(PROPERTIES, buckets);

    assertThat(status("/api/v1/auth/login", "198.51.100.4")).isEqualTo(200);
    assertThat(send(otherInstance, "/api/v1/auth/login", "198.51.100.4").getStatus())
        .isEqualTo(200);
    assertThat(status("/api/v1/auth/login", "198.51.100.4")).isEqualTo(429);
  }

  /** Refused, not let through: these limits are what stands between a guesser and the database. */
  @Test
  void answers503WhileRedisIsAway() throws Exception {
    RedisClient nowhere = RedisClient.create("redis://127.0.0.1:1");
    try (RateLimitBuckets unreachable =
        new RateLimitBuckets(() -> nowhere, Clock.systemUTC(), Duration.ofSeconds(1))) {
      MockHttpServletResponse response =
          send(new RateLimitFilter(PROPERTIES, unreachable), "/api/v1/auth/login", "198.51.100.5");

      assertThat(response.getStatus()).isEqualTo(503);
      assertThat(response.getHeader("Retry-After")).isEqualTo("5");
      assertThat(response.getContentAsString()).contains("\"code\":\"SERVICE_UNAVAILABLE\"");
    } finally {
      nowhere.shutdown();
    }
  }
}
