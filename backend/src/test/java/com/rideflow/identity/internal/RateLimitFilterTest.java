package com.rideflow.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The per-address limits come from configuration, and the address from the socket. */
class RateLimitFilterTest {

  private final RateLimitFilter filter =
      new RateLimitFilter(
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
              new IdentityProperties.BootstrapAdmin(null, null, null)),
          Clock.systemUTC());

  private int forged;

  private int status(String path, String address) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setRemoteAddr(address);
    // Ignored: anyone can write it.
    request.addHeader("X-Forwarded-For", "203.0.113." + forged++);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response.getStatus();
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
}
