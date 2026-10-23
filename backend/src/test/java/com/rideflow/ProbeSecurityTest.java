package com.rideflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The health probes with the real filter chain in place.
 *
 * <p>Deliberately separate from {@link ApplicationIntegrationTest}, which runs with {@code
 * addFilters = false} to exercise the error model anonymously. That switch also hides
 * authorization, so a test that used it could not tell a reachable probe from an unreachable one -
 * which is exactly the bug this file exists to catch.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class ProbeSecurityTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void answersLivenessWithoutCredentials() throws Exception {
    // An orchestrator sends no credentials. If this needs auth, a healthy instance
    // looks dead and gets restarted.
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void answersReadinessWithoutCredentials() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void answersOverallHealthWithoutCredentials() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void keepsMetricsBehindAuthentication() throws Exception {
    // Metrics describe request rates, latencies and JVM internals. Not public.
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  }

  @Test
  void keepsTheModuleStructureBehindAuthentication() throws Exception {
    // This endpoint publishes the internal architecture.
    mockMvc.perform(get("/actuator/modulith")).andExpect(status().isUnauthorized());
  }

  @Test
  void keepsEverythingElseBehindAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/anything")).andExpect(status().isUnauthorized());
  }
}
