package com.rideflow.pricing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rideflow.TestcontainersConfiguration;
import com.rideflow.identity.Role;
import com.rideflow.identity.internal.AuthService;
import com.rideflow.pricing.ConsumedQuote;
import com.rideflow.pricing.internal.QuoteService.Quote;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.MutableClock;
import com.rideflow.shared.VehicleType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Quoting a trip, and turning a quote into a booking exactly once. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, QuoteIntegrationTest.TestBeans.class})
@Tag("integration")
class QuoteIntegrationTest {

  private static final Instant START = Instant.parse("2026-10-01T10:00:00Z");
  private static final MutableClock CLOCK = new MutableClock(START);
  private static final ControllableSurge SURGE = new ControllableSurge();

  /** Roughly MG Road and Indiranagar, Bengaluru: about 3.7 km apart in a straight line. */
  private static final GeoPoint MG_ROAD = new GeoPoint(12.9752, 77.6064);

  private static final GeoPoint INDIRANAGAR = new GeoPoint(12.9784, 77.6408);

  /** Well outside the configured Bengaluru box. */
  private static final GeoPoint MYSURU = new GeoPoint(12.2958, 76.6394);

  /** The CAR card V6 seeds. */
  private static final UUID CAR_CARD_V1 = UUID.fromString("01940000-0000-7000-8000-000000000003");

  /** GeoPoint's earth radius, so fixtures are exactly as far apart as their names say. */
  private static final double EARTH_RADIUS_M = 6_371_008.8;

  @TestConfiguration
  static class TestBeans {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }

    @Bean
    @Primary
    SurgeProvider testSurge() {
      return SURGE;
    }
  }

  /** A surge engine the test controls: a value, or a failure. */
  static final class ControllableSurge implements SurgeProvider {

    private volatile Supplier<BigDecimal> answer = () -> BigDecimal.ONE;

    void answerWith(Supplier<BigDecimal> answer) {
      this.answer = answer;
    }

    @Override
    public BigDecimal multiplierAt(GeoPoint pickup, VehicleType type) {
      return answer.get();
    }
  }

  @Autowired private QuoteService quotes;
  @Autowired private CircuitBreakerRegistry breakers;
  @Autowired private RateCardService rateCards;
  @Autowired private StringRedisTemplate redis;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private AuthService auth;
  @Autowired private MockMvc mockMvc;

  @BeforeEach
  void reset() {
    CLOCK.set(START);
    SURGE.answerWith(() -> BigDecimal.ONE);
    rateCards.invalidate();
  }

  @AfterEach
  void restoreSeededRateCards() {
    jdbc.update("DELETE FROM rate_cards WHERE version > 1");
    jdbc.update(
        "UPDATE rate_cards SET effective_from = ? WHERE version = 1",
        OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    rateCards.invalidate();
  }

  /**
   * A rider nobody has used yet. The quote limiter is a singleton keyed by rider, so a fresh id is
   * a fresh bucket, and no test can drain one that another test needs.
   */
  private static UUID newRider() {
    return UUID.randomUUID();
  }

  /** The route estimator's breaker open: quotes are still priced, on the straight line. */
  @Test
  void quotesOnTheStraightLineWhileTheRouteBreakerIsOpen() {
    List<Quote> normal = quotes.quote(newRider(), MG_ROAD, INDIRANAGAR);
    CircuitBreaker breaker = breakers.circuitBreaker("route-estimator");
    breaker.transitionToForcedOpenState();
    try {
      List<Quote> open = quotes.quote(newRider(), MG_ROAD, INDIRANAGAR);

      assertThat(open)
          .extracting(Quote::distanceMeters)
          .containsExactlyElementsOf(normal.stream().map(Quote::distanceMeters).toList());
    } finally {
      breaker.transitionToClosedState();
    }
  }

  private Quote quoteFor(UUID rider, VehicleType type) {
    return quotes.quote(rider, MG_ROAD, INDIRANAGAR).stream()
        .filter(quote -> quote.vehicleType() == type)
        .findFirst()
        .orElseThrow();
  }

  private static GeoPoint northOf(GeoPoint from, double meters) {
    return new GeoPoint(
        from.latitude() + Math.toDegrees(meters / EARTH_RADIUS_M), from.longitude());
  }

  private static void assertRefused(ThrowingCallable call, String code, HttpStatus status) {
    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(call)
        .satisfies(
            e -> {
              assertThat(e.code()).isEqualTo(code);
              assertThat(e.status()).isEqualTo(status);
            });
  }

  private static void assertGone(ThrowingCallable call) {
    assertRefused(call, "QUOTE_EXPIRED", HttpStatus.GONE);
  }

  private static String key(UUID quoteId) {
    return "quote:" + quoteId;
  }

  @Nested
  class Quoting {

    @Test
    void pricesEveryVehicleTypeFromTheCardInForce() {
      List<Quote> result = quotes.quote(newRider(), MG_ROAD, INDIRANAGAR);

      assertThat(result)
          .extracting(Quote::vehicleType)
          .containsExactlyInAnyOrder(VehicleType.values());
      for (Quote quote : result) {
        RateCard card = rateCards.activeFor(quote.vehicleType()).orElseThrow();
        assertThat(quote.rateCardId()).isEqualTo(card.id());
        assertThat(quote.fare())
            .isEqualTo(
                FareCalculator.calculate(
                    card, quote.distanceMeters(), quote.durationSeconds(), BigDecimal.ONE));
        assertThat(quote.expiresAt()).isEqualTo(START.plus(Duration.ofMinutes(5)));
      }
      assertThat(quoteFor(newRider(), VehicleType.CAR).rateCardId()).isEqualTo(CAR_CARD_V1);
    }

    @Test
    void keepsEachQuoteInRedisOnlyALittleLongerThanItLasts() {
      Quote quote = quoteFor(newRider(), VehicleType.CAR);

      // Five minutes plus thirty seconds of grace: the key never outlives the quote by
      // more, and never dies before it.
      assertThat(redis.getExpire(key(quote.quoteId()), TimeUnit.SECONDS)).isBetween(300L, 330L);
    }

    @Test
    void refusesAPickupOutsideTheServiceArea() {
      assertRefused(
          () -> quotes.quote(newRider(), MYSURU, MG_ROAD),
          "OUTSIDE_SERVICE_AREA",
          HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void refusesADropOutsideTheServiceArea() {
      assertRefused(
          () -> quotes.quote(newRider(), MG_ROAD, MYSURU),
          "OUTSIDE_SERVICE_AREA",
          HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void acceptsAPickupExactlyOnTheServiceAreaEdge() {
      // The northern edge of the configured box. On the line counts as inside.
      GeoPoint onTheEdge = new GeoPoint(13.1400, 77.6000);

      assertThat(quotes.quote(newRider(), onTheEdge, new GeoPoint(13.1000, 77.6000))).hasSize(3);
    }

    @Test
    void refusesATripShorterThanTheMinimum() {
      // 225 m in a straight line is about 293 m by road: under the 300 m minimum.
      assertRefused(
          () -> quotes.quote(newRider(), MG_ROAD, northOf(MG_ROAD, 225)),
          "TRIP_TOO_SHORT",
          HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void acceptsATripJustOverTheMinimum() {
      // 235 m in a straight line is about 306 m by road.
      assertThat(quotes.quote(newRider(), MG_ROAD, northOf(MG_ROAD, 235)))
          .allSatisfy(quote -> assertThat(quote.distanceMeters()).isBetween(300L, 310L));
    }

    @Test
    void refusesATripLongerThanTheMaximum() {
      // Corner to corner of the service area: about 49 km straight, 64 km by road.
      assertRefused(
          () ->
              quotes.quote(newRider(), new GeoPoint(13.139, 77.461), new GeoPoint(12.821, 77.779)),
          "TRIP_TOO_LONG",
          HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void acceptsATripJustUnderTheMaximum() {
      GeoPoint from = new GeoPoint(12.821, 77.461);
      GeoPoint to = new GeoPoint(13.1173, 77.7573);

      List<Quote> result = quotes.quote(newRider(), from, to);

      // About 46 km straight, just under 60 km by road.
      assertThat(result)
          .hasSize(3)
          .allSatisfy(quote -> assertThat(quote.distanceMeters()).isBetween(59_000L, 60_000L));
    }
  }

  @Nested
  class RateCards {

    @Test
    void switchesToAFutureCardAtTheExactMomentTheClockReachesIt() {
      Instant activation = START.plus(Duration.ofHours(1));
      UUID carCardV2 = UUID.fromString("01940000-0000-7000-8000-0000000000c2");
      jdbc.update(
          """
          INSERT INTO rate_cards (id, vehicle_type, version, base_paise, per_km_paise,
              per_min_paise, min_fare_paise, waiting_per_min_paise, cancellation_fee_paise,
              effective_from)
          VALUES (?, 'CAR', 2, 6000, 1700, 250, 9000, 300, 5000, ?)""",
          carCardV2,
          OffsetDateTime.ofInstant(activation, ZoneOffset.UTC));
      rateCards.invalidate();

      assertThat(quoteFor(newRider(), VehicleType.CAR).rateCardId()).isEqualTo(CAR_CARD_V1);

      CLOCK.set(activation.minusMillis(1));
      assertThat(quoteFor(newRider(), VehicleType.CAR).rateCardId()).isEqualTo(CAR_CARD_V1);

      CLOCK.set(activation);
      Quote quote = quoteFor(newRider(), VehicleType.CAR);
      assertThat(quote.rateCardId()).isEqualTo(carCardV2);
      assertThat(quote.fare().baseFare().paise()).isEqualTo(6000);
    }

    @Test
    void leavesOutAVehicleTypeWithNoCardInForce() {
      jdbc.update(
          "UPDATE rate_cards SET effective_from = ? WHERE vehicle_type = 'BIKE'",
          OffsetDateTime.ofInstant(START.plus(Duration.ofDays(1)), ZoneOffset.UTC));
      rateCards.invalidate();

      assertThat(quotes.quote(newRider(), MG_ROAD, INDIRANAGAR))
          .extracting(Quote::vehicleType)
          .containsExactlyInAnyOrder(VehicleType.AUTO, VehicleType.CAR);
    }
  }

  @Nested
  class Surge {

    @Test
    void appliesTheMultiplierAndReportsIt() {
      SURGE.answerWith(() -> new BigDecimal("1.3"));

      Quote quote = quoteFor(newRider(), VehicleType.CAR);

      assertThat(quote.surgeMultiplier()).isEqualByComparingTo("1.3");
      assertThat(quote.fare().surgeCharge().isPositive()).isTrue();
    }

    @Test
    void pricesAtOneWhenTheSurgeLookupFails() {
      SURGE.answerWith(
          () -> {
            throw new IllegalStateException("surge store unreachable");
          });

      assertThat(quotes.quote(newRider(), MG_ROAD, INDIRANAGAR))
          .allSatisfy(
              quote -> {
                assertThat(quote.surgeMultiplier()).isEqualByComparingTo("1");
                assertThat(quote.fare().surgeCharge().isZero()).isTrue();
              });
    }

    @Test
    void neverPricesBelowOne() {
      SURGE.answerWith(() -> new BigDecimal("0.8"));

      assertThat(quoteFor(newRider(), VehicleType.CAR).surgeMultiplier()).isEqualByComparingTo("1");
    }
  }

  @Nested
  class Consuming {

    @Test
    void handsBackTheQuoteAsPricedAndOnlyOnce() {
      UUID rider = newRider();
      Quote quote = quoteFor(rider, VehicleType.CAR);

      ConsumedQuote consumed = quotes.consume(quote.quoteId(), rider, VehicleType.CAR);

      assertThat(consumed).isEqualTo(quote.toConsumed());
      assertThat(redis.hasKey(key(quote.quoteId()))).isFalse();
      assertGone(() -> quotes.consume(quote.quoteId(), rider, VehicleType.CAR));
    }

    @Test
    void hidesAnotherRidersQuoteAndLeavesItForTheOwner() {
      UUID owner = newRider();
      Quote quote = quoteFor(owner, VehicleType.CAR);

      assertRefused(
          () -> quotes.consume(quote.quoteId(), newRider(), VehicleType.CAR),
          "QUOTE_NOT_FOUND",
          HttpStatus.NOT_FOUND);

      // The failed attempt destroyed nothing.
      assertThat(quotes.consume(quote.quoteId(), owner, VehicleType.CAR).quoteId())
          .isEqualTo(quote.quoteId());
    }

    @Test
    void refusesADifferentVehicleTypeAndLeavesTheQuote() {
      UUID rider = newRider();
      Quote quote = quoteFor(rider, VehicleType.CAR);

      assertRefused(
          () -> quotes.consume(quote.quoteId(), rider, VehicleType.BIKE),
          "QUOTE_VEHICLE_TYPE_MISMATCH",
          HttpStatus.UNPROCESSABLE_CONTENT);

      assertThat(quotes.consume(quote.quoteId(), rider, VehicleType.CAR).quoteId())
          .isEqualTo(quote.quoteId());
    }

    @Test
    void honoursAQuoteUntilItsLastMillisecond() {
      UUID rider = newRider();
      Quote quote = quoteFor(rider, VehicleType.CAR);

      CLOCK.set(quote.expiresAt().minusMillis(1));

      assertThat(quotes.consume(quote.quoteId(), rider, VehicleType.CAR).quoteId())
          .isEqualTo(quote.quoteId());
    }

    @Test
    void refusesAQuoteFromTheMomentItExpiresAndClearsItAway() {
      UUID rider = newRider();
      Quote quote = quoteFor(rider, VehicleType.CAR);

      CLOCK.set(quote.expiresAt());

      assertGone(() -> quotes.consume(quote.quoteId(), rider, VehicleType.CAR));
      assertThat(redis.hasKey(key(quote.quoteId()))).isFalse();
    }

    @Test
    void refusesAQuoteThatNeverExisted() {
      assertGone(() -> quotes.consume(UUID.randomUUID(), newRider(), VehicleType.CAR));
    }

    @Test
    void letsExactlyOneOfTenConcurrentBookingsHaveTheQuote() throws Exception {
      UUID rider = newRider();
      Quote quote = quoteFor(rider, VehicleType.CAR);
      int threads = 10;
      CountDownLatch ready = new CountDownLatch(threads);
      CountDownLatch go = new CountDownLatch(1);

      List<Future<Boolean>> outcomes = new ArrayList<>();
      try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
        for (int i = 0; i < threads; i++) {
          outcomes.add(
              pool.submit(
                  () -> {
                    ready.countDown();
                    go.await();
                    try {
                      quotes.consume(quote.quoteId(), rider, VehicleType.CAR);
                      return true;
                    } catch (BusinessException e) {
                      assertThat(e.code()).isEqualTo("QUOTE_EXPIRED");
                      return false;
                    }
                  }));
        }
        ready.await();
        go.countDown();

        int winners = 0;
        for (Future<Boolean> outcome : outcomes) {
          if (outcome.get(30, TimeUnit.SECONDS)) {
            winners++;
          }
        }
        assertThat(winners).isOne();
      }
    }

    @Test
    void restoresAConsumedQuoteWithoutExtendingItsLife() {
      UUID rider = newRider();
      Quote quote = quoteFor(rider, VehicleType.CAR);
      ConsumedQuote consumed = quotes.consume(quote.quoteId(), rider, VehicleType.CAR);

      CLOCK.advance(Duration.ofMinutes(2));
      quotes.restore(consumed);
      assertThat(quotes.consume(quote.quoteId(), rider, VehicleType.CAR)).isEqualTo(consumed);

      quotes.restore(consumed);
      CLOCK.set(quote.expiresAt());
      assertGone(() -> quotes.consume(quote.quoteId(), rider, VehicleType.CAR));
    }

    @Test
    void doesNotRestoreAQuotePastItsExpiry() {
      UUID rider = newRider();
      Quote quote = quoteFor(rider, VehicleType.CAR);
      ConsumedQuote consumed = quotes.consume(quote.quoteId(), rider, VehicleType.CAR);

      CLOCK.set(quote.expiresAt());
      quotes.restore(consumed);

      assertThat(redis.hasKey(key(quote.quoteId()))).isFalse();
    }
  }

  @Nested
  class Http {

    private static final String MG_ROAD_TO_INDIRANAGAR =
        """
        {"pickup":{"lat":12.9752,"lng":77.6064},"drop":{"lat":12.9784,"lng":77.6408}}""";

    private String riderToken;

    @BeforeEach
    void registerRider() {
      jdbc.execute("TRUNCATE refresh_tokens, vehicles, drivers, users CASCADE");
      riderToken =
          auth.register("+919000008888", "Asha", "correct-horse-battery", Role.RIDER).accessToken();
    }

    private ResultActions postQuote(String body) throws Exception {
      return mockMvc.perform(
          post("/api/v1/fares/quotes")
              .header("Authorization", "Bearer " + riderToken)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body));
    }

    @Test
    void returnsAQuotePerVehicleTypeWithItsBreakdown() throws Exception {
      postQuote(MG_ROAD_TO_INDIRANAGAR)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.quotes.length()").value(3))
          .andExpect(jsonPath("$.quotes[0].quoteId").isNotEmpty())
          .andExpect(jsonPath("$.quotes[0].vehicleType").isNotEmpty())
          .andExpect(jsonPath("$.quotes[0].totalPaise").isNumber())
          .andExpect(jsonPath("$.quotes[0].breakdown.basePaise").isNumber())
          .andExpect(jsonPath("$.quotes[0].breakdown.distancePaise").isNumber())
          .andExpect(jsonPath("$.quotes[0].breakdown.timePaise").isNumber())
          .andExpect(jsonPath("$.quotes[0].breakdown.minimumFareTopUpPaise").isNumber())
          .andExpect(jsonPath("$.quotes[0].breakdown.surgePaise").isNumber())
          .andExpect(jsonPath("$.quotes[0].breakdown.roundingPaise").isNumber())
          .andExpect(jsonPath("$.quotes[0].surgeMultiplier").value(1))
          .andExpect(jsonPath("$.quotes[0].rateCardId").isNotEmpty())
          .andExpect(jsonPath("$.quotes[0].distanceMeters").isNumber())
          .andExpect(jsonPath("$.quotes[0].durationSeconds").isNumber())
          .andExpect(jsonPath("$.quotes[0].expiresAt").value("2026-10-01T10:05:00Z"));
    }

    @Test
    void rejectsAMissingDrop() throws Exception {
      postQuote(
              """
          {"pickup":{"lat":12.9752,"lng":77.6064}}""")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsALatitudeOffThePlanet() throws Exception {
      postQuote(
              """
          {"pickup":{"lat":91,"lng":77.6064},"drop":{"lat":12.9784,"lng":77.6408}}""")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void reportsAPickupOutsideTheServiceArea() throws Exception {
      postQuote(
              """
          {"pickup":{"lat":12.2958,"lng":76.6394},"drop":{"lat":12.9784,"lng":77.6408}}""")
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.code").value("OUTSIDE_SERVICE_AREA"));
    }

    @Test
    void refusesTheThirtyFirstQuoteInAMinuteAndSaysWhenToRetry() throws Exception {
      for (int i = 0; i < 30; i++) {
        postQuote(MG_ROAD_TO_INDIRANAGAR).andExpect(status().isOk());
      }

      // Thirty a minute refills one every two seconds.
      postQuote(MG_ROAD_TO_INDIRANAGAR)
          .andExpect(status().isTooManyRequests())
          .andExpect(header().string("Retry-After", "2"))
          .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

      // Per rider: someone else is unaffected.
      assertThat(quotes.quote(newRider(), MG_ROAD, INDIRANAGAR)).hasSize(3);

      CLOCK.advance(Duration.ofSeconds(2));
      postQuote(MG_ROAD_TO_INDIRANAGAR).andExpect(status().isOk());
    }
  }
}
