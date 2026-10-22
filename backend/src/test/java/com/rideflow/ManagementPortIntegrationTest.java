package com.rideflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Actuator on its own port, as deployed (Phase 10): Prometheus can scrape it there, and the public
 * port offers nothing but the two probes.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ManagementPortIntegrationTest {

  @LocalServerPort int publicPort;
  @LocalManagementPort int managementPort;

  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> get(int port, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void servesMetricsOnTheManagementPortWithoutCredentials() throws Exception {
    HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");

    assertThat(metrics.statusCode()).isEqualTo(200);
    // What the dashboards and alerts read.
    assertThat(metrics.body())
        .contains("events_publications_incomplete")
        .contains("trips_requested_total")
        .contains("matching_first_offer_seconds_bucket")
        .contains("hikaricp_connections_active")
        .contains("resilience4j_circuitbreaker_state");
  }

  @Test
  void keepsMetricsOffThePublicPort() throws Exception {
    assertThat(get(publicPort, "/actuator/prometheus").statusCode()).isIn(401, 404);
    assertThat(get(publicPort, "/actuator/health").statusCode()).isIn(401, 404);
  }

  @Test
  void answersTheProbesOnThePublicPort() throws Exception {
    assertThat(get(publicPort, "/livez").statusCode()).isEqualTo(200);
    assertThat(get(publicPort, "/readyz").statusCode()).isEqualTo(200);
    assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
  }
}
