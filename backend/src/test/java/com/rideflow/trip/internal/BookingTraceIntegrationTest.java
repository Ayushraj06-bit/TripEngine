package com.rideflow.trip.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rideflow.shared.VehicleType;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Phase 10: one booking is one trace, from the HTTP request into the module listeners that match it
 * and tell people about it. The listeners run asynchronously after the booking commits; they stay
 * in the trace only because Boot's task executor is forced and propagates context
 * (application.yml), which nothing else would notice going away.
 */
class BookingTraceIntegrationTest extends TripIntegrationTestBase {

  @Test
  void runsTheBookingsListenersInTheRequestsTrace() throws Exception {
    User rider = rider();
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

    String body =
        mockMvc
            .perform(
                post("/api/v1/trips")
                    .header("Authorization", "Bearer " + rider.token())
                    .header("Idempotency-Key", newKey())
                    // W3C trace context from the caller; not sampled, so nothing is exported.
                    .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-00")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"quoteId":"%s","vehicleType":"CAR","paymentMethod":"WALLET"}"""
                            .formatted(quote(rider, VehicleType.CAR).quoteId())))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID tripId = UUID.fromString(JsonPath.read(body, "$.id"));

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> TripTestConfiguration.LISTENER_TRACES.containsKey(tripId));
    assertThat(TripTestConfiguration.LISTENER_TRACES.get(tripId)).isEqualTo(traceId);
  }
}
