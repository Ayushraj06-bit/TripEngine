package com.rideflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The headers every API answer carries (docs/security.md, A05), and no cross-origin access. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SecurityHeadersTest {

  @Autowired MockMvc mockMvc;

  private static void hardened(ResultActions response) throws Exception {
    response
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(
            header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"));
  }

  @Test
  void hardensAnAnonymousRefusal() throws Exception {
    hardened(mockMvc.perform(get("/api/v1/trips/active")));
  }

  @Test
  void hardensAPublicEndpointsAnswer() throws Exception {
    hardened(mockMvc.perform(get("/actuator/health/liveness")));
  }

  /** No CORS configuration: a browser page from another origin is not let in. */
  @Test
  void grantsNoCrossOriginAccess() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/auth/login")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }
}
