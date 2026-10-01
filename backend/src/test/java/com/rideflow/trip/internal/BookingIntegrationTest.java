package com.rideflow.trip.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rideflow.pricing.internal.QuoteService;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.PaymentMethod;
import com.rideflow.trip.TripEvent;
import com.rideflow.trip.TripRequested;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

/** {@code POST /api/v1/trips}: quotes in, trips out, exactly once per Idempotency-Key. */
@RecordApplicationEvents
class BookingIntegrationTest extends TripIntegrationTestBase {

  @Autowired ApplicationEvents events;
  @Autowired IdempotencyStore idempotencyKeys;

  private long tripsOf(User rider) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM trips WHERE rider_id = ?", Long.class, rider.id());
  }

  @Nested
  class Booking {

    @Test
    void turnsAQuoteIntoARequestedTrip() throws Exception {
      User rider = rider();
      QuoteService.Quote quote = quote(rider, VehicleType.CAR);

      String body =
          book(rider, quote.quoteId(), VehicleType.CAR, newKey())
              .andExpect(status().isAccepted())
              .andExpect(jsonPath("$.status").value("REQUESTED"))
              .andExpect(jsonPath("$.vehicleType").value("CAR"))
              .andExpect(jsonPath("$.riderId").value(rider.id().toString()))
              .andExpect(jsonPath("$.quotedFarePaise").value(quote.fare().total().paise()))
              .andExpect(jsonPath("$.paymentMethod").value("WALLET"))
              .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
              .andExpect(jsonPath("$.otp").doesNotExist())
              .andReturn()
              .getResponse()
              .getContentAsString();

      UUID tripId = UUID.fromString(JsonPath.read(body, "$.id"));
      assertThat(auditRows(tripId)).isOne();
      assertThat(events.stream(TripRequested.class))
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.tripId()).isEqualTo(tripId);
                assertThat(event.quotedFarePaise()).isEqualTo(quote.fare().total().paise());
              });
    }

    @Test
    void refusesASecondActiveTrip() throws Exception {
      User rider = rider();
      bookedTrip(rider);

      book(rider, quote(rider, VehicleType.BIKE).quoteId(), VehicleType.BIKE, newKey())
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("ACTIVE_TRIP_EXISTS"));
    }

    @Test
    void requiresAnIdempotencyKey() throws Exception {
      User rider = rider();

      mockMvc
          .perform(
              post("/api/v1/trips")
                  .header("Authorization", "Bearer " + rider.token())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"quoteId":"%s","vehicleType":"CAR","paymentMethod":"WALLET"}"""
                          .formatted(quote(rider, VehicleType.CAR).quoteId())))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
      assertThat(tripsOf(rider)).isZero();
    }
  }

  @Nested
  class IdempotencyKeys {

    @Test
    void answersARepeatWithTheStoredResponseAndBooksOnce() throws Exception {
      User rider = rider();
      UUID quoteId = quote(rider, VehicleType.CAR).quoteId();
      String key = newKey();

      String first =
          book(rider, quoteId, VehicleType.CAR, key)
              .andExpect(status().isAccepted())
              .andReturn()
              .getResponse()
              .getContentAsString();
      String second =
          book(rider, quoteId, VehicleType.CAR, key)
              .andExpect(status().isAccepted())
              .andReturn()
              .getResponse()
              .getContentAsString();

      // The same response. Not the same bytes: jsonb normalises key order and spacing.
      JSONAssert.assertEquals(first, second, true);
      assertThat(tripsOf(rider)).isOne();
      assertThat(events.stream(TripEvent.class)).hasSize(1);
    }

    @Test
    void refusesAKeyReusedForADifferentRequest() throws Exception {
      User rider = rider();
      String key = newKey();
      book(rider, quote(rider, VehicleType.CAR).quoteId(), VehicleType.CAR, key)
          .andExpect(status().isAccepted());

      book(rider, quote(rider, VehicleType.AUTO).quoteId(), VehicleType.AUTO, key)
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
      assertThat(tripsOf(rider)).isOne();
    }

    @Test
    void asksARepeatToWaitWhileTheFirstIsStillRunning() throws Exception {
      User rider = rider();
      UUID quoteId = quote(rider, VehicleType.CAR).quoteId();
      String key = newKey();
      // What a request midway through looks like: claimed, no response yet.
      idempotencyKeys.claim(
          rider.id(),
          key,
          BookingService.requestHash(quoteId, VehicleType.CAR, PaymentMethod.WALLET));

      book(rider, quoteId, VehicleType.CAR, key)
          .andExpect(status().isConflict())
          .andExpect(header().string("Retry-After", "1"))
          .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_IN_FLIGHT"));
      assertThat(tripsOf(rider)).isZero();
    }

    @Test
    void replaysARejectionToo() throws Exception {
      User rider = rider();
      UUID quoteId = quote(rider, VehicleType.CAR).quoteId();
      String key = newKey();
      CLOCK.advance(Duration.ofMinutes(5));

      book(rider, quoteId, VehicleType.CAR, key)
          .andExpect(status().isGone())
          .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
      book(rider, quoteId, VehicleType.CAR, key)
          .andExpect(status().isGone())
          .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
    }

    @Test
    void purgesKeysOlderThanTheRetentionPeriod() {
      User rider = rider();
      jdbc.update(
          "INSERT INTO idempotency_keys (user_id, key, request_hash, created_at) VALUES (?, ?, 'x', ?)",
          rider.id(),
          "old",
          utc(START.minus(Duration.ofHours(24)).minusSeconds(1)));
      jdbc.update(
          "INSERT INTO idempotency_keys (user_id, key, request_hash, created_at) VALUES (?, ?, 'x', ?)",
          rider.id(),
          "recent",
          utc(START.minus(Duration.ofHours(23))));

      idempotencyKeys.purgeExpired();

      assertThat(jdbc.queryForList("SELECT key FROM idempotency_keys", String.class))
          .containsExactly("recent");
    }
  }

  @Nested
  class Quotes {

    @Test
    void refusesAnExpiredQuote() throws Exception {
      User rider = rider();
      UUID quoteId = quote(rider, VehicleType.CAR).quoteId();
      CLOCK.advance(Duration.ofMinutes(5));

      book(rider, quoteId, VehicleType.CAR, newKey())
          .andExpect(status().isGone())
          .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
      assertThat(tripsOf(rider)).isZero();
    }

    @Test
    void refusesAQuoteThatWasAlreadyUsed() throws Exception {
      User rider = rider();
      UUID quoteId = quote(rider, VehicleType.CAR).quoteId();
      String body =
          book(rider, quoteId, VehicleType.CAR, newKey())
              .andExpect(status().isAccepted())
              .andReturn()
              .getResponse()
              .getContentAsString();
      trips.cancel(UUID.fromString(JsonPath.read(body, "$.id")), rider.asRider(), "Plans changed");

      book(rider, quoteId, VehicleType.CAR, newKey())
          .andExpect(status().isGone())
          .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
    }

    @Test
    void hidesAnotherRidersQuoteAndLeavesItForThem() throws Exception {
      User owner = rider();
      User thief = rider();
      UUID quoteId = quote(owner, VehicleType.CAR).quoteId();

      book(thief, quoteId, VehicleType.CAR, newKey())
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("QUOTE_NOT_FOUND"));

      book(owner, quoteId, VehicleType.CAR, newKey()).andExpect(status().isAccepted());
    }

    @Test
    void refusesAQuoteForADifferentVehicleType() throws Exception {
      User rider = rider();

      book(rider, quote(rider, VehicleType.CAR).quoteId(), VehicleType.BIKE, newKey())
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.code").value("QUOTE_VEHICLE_TYPE_MISMATCH"));
    }
  }

  @Nested
  class Concurrency {

    /** The partial unique index at work: the check in code could race, the index cannot. */
    @Test
    void letsExactlyOneOfTwentyConcurrentBookingsThrough() throws Exception {
      User rider = rider();
      List<QuoteService.Quote> offered = new ArrayList<>();
      while (offered.size() < 20) {
        offered.addAll(quotes.quote(rider.id(), MG_ROAD, INDIRANAGAR));
      }
      List<QuoteService.Quote> attempts = offered.subList(0, 20);

      CountDownLatch ready = new CountDownLatch(attempts.size());
      CountDownLatch go = new CountDownLatch(1);
      List<Future<MvcResult>> results = new ArrayList<>();
      try (ExecutorService pool = Executors.newFixedThreadPool(attempts.size())) {
        for (QuoteService.Quote quote : attempts) {
          results.add(
              pool.submit(
                  () -> {
                    ready.countDown();
                    go.await();
                    return book(rider, quote.quoteId(), quote.vehicleType(), newKey()).andReturn();
                  }));
        }
        ready.await();
        go.countDown();

        List<String> outcomes = new ArrayList<>();
        for (Future<MvcResult> result : results) {
          var response = result.get(60, TimeUnit.SECONDS).getResponse();
          outcomes.add(
              response.getStatus() == 202
                  ? "202"
                  : response.getStatus()
                      + " "
                      + JsonPath.read(response.getContentAsString(), "$.code"));
        }
        assertThat(outcomes)
            .containsOnly("202", "409 ACTIVE_TRIP_EXISTS")
            .filteredOn("202"::equals)
            .hasSize(1);
      }
      assertThat(tripsOf(rider)).isOne();

      // The nineteen refused quotes went back to Redis, so the rider can still use them.
      List<QuoteService.Quote> restored =
          attempts.stream().filter(quote -> quoteStillExists(quote.quoteId())).toList();
      assertThat(restored).hasSize(19);

      trips.cancel(
          trips.active(rider.asRider()).orElseThrow().id(), rider.asRider(), "Plans changed");
      QuoteService.Quote retry = restored.getFirst();
      book(rider, retry.quoteId(), retry.vehicleType(), newKey()).andExpect(status().isAccepted());
    }
  }

  @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

  private boolean quoteStillExists(UUID quoteId) {
    return Boolean.TRUE.equals(redis.hasKey("quote:" + quoteId));
  }
}
