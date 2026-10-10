package com.rideflow.pricing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rideflow.payment.internal.PaymentIntegrationTestBase;
import com.rideflow.pricing.SurgeDemand;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.PaymentMethod;
import com.rideflow.trip.TripRequested;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The surge engine against real Redis: demand in, multipliers out, and the levers admins hold.
 * Shares the payment tests' context - the same controllable clock - rather than starting another.
 */
class SurgeIntegrationTest extends PaymentIntegrationTestBase {

  private static final Logger log = LoggerFactory.getLogger(SurgeIntegrationTest.class);

  private static final GeoPoint MG_ROAD = new GeoPoint(12.9752, 77.6064);
  private static final GeoPoint INDIRANAGAR = new GeoPoint(12.9784, 77.6408);
  private static final int PRECISION = 6;
  private static final String MG_ROAD_CELL = Geohash.encode(MG_ROAD, PRECISION);
  private static final List<String> AVAILABLE =
      List.of("drivers:available:BIKE", "drivers:available:AUTO", "drivers:available:CAR");

  @Autowired SurgeJob job;
  @Autowired RedisSurgeProvider provider;
  @Autowired SurgeDemand demand;
  @Autowired StringRedisTemplate redis;
  @Autowired LockProvider lockProvider;
  @Autowired MeterRegistry meters;

  @BeforeEach
  void clearSurge() {
    Set<String> keys = new HashSet<>(redis.keys("surge:*"));
    keys.addAll(AVAILABLE);
    redis.delete(keys);
    provider.forgetAll();
  }

  private void requests(GeoPoint at, int count) {
    for (int i = 0; i < count; i++) {
      demand.recordRequest(UUID.randomUUID(), at, CLOCK.instant());
    }
  }

  private void driversAt(GeoPoint at, int count) {
    for (int i = 0; i < count; i++) {
      redis
          .opsForGeo()
          .add(
              "drivers:available:CAR",
              new Point(at.longitude(), at.latitude()),
              UUID.randomUUID().toString());
    }
  }

  private String stored(String cell) {
    return redis.opsForValue().get(SurgeKeys.multiplier(cell));
  }

  /** What a quote there is priced at now, past any cached value. */
  private BigDecimal priceAt(GeoPoint point) {
    provider.forgetAll();
    return provider.multiplierAt(point, VehicleType.CAR);
  }

  private ResultActions adminPost(User admin, String path, String body) throws Exception {
    return mockMvc.perform(
        post("/api/v1/admin/surge" + path)
            .header("Authorization", "Bearer " + admin.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions override(User admin, String cell, String multiplier, Instant expiresAt)
      throws Exception {
    return adminPost(
        admin,
        "/overrides",
        """
        {"cell":"%s","multiplier":%s,"expiresAt":"%s"}"""
            .formatted(cell, multiplier, expiresAt));
  }

  @Nested
  class Ticking {

    /** No drivers and plenty of requests: the cap is the target, reached one step a tick. */
    @Test
    void climbsToTheCapAStepATickAndComesBackDownTheSameWay() {
      requests(MG_ROAD, 10);

      List<String> up = new java.util.ArrayList<>();
      for (int tick = 0; tick < 7; tick++) {
        job.tick();
        up.add(stored(MG_ROAD_CELL));
      }
      assertThat(up).containsExactly("1.25", "1.5", "1.75", "2", "2.25", "2.5", "2.5");

      // The requests leave the window: nothing pushes the price up, so it steps back down.
      CLOCK.advance(Duration.ofMinutes(6));
      List<String> down = new java.util.ArrayList<>();
      for (int tick = 0; tick < 6; tick++) {
        job.tick();
        down.add(stored(MG_ROAD_CELL));
      }
      // Back at 1.0 the key goes: 1.0 is the default.
      assertThat(down).containsExactly("2.25", "2", "1.75", "1.5", "1.25", null);
      assertThat(job.snapshot()).doesNotContainKey(MG_ROAD_CELL);
    }

    @Test
    void staysAtOneWhereSupplyMeetsDemand() {
      requests(MG_ROAD, 6);
      driversAt(MG_ROAD, 6);

      job.tick();

      assertThat(stored(MG_ROAD_CELL)).isNull();
      assertThat(job.snapshot().get(MG_ROAD_CELL).supply()).isEqualTo(6);
      assertThat(priceAt(MG_ROAD)).isEqualByComparingTo("1");
    }

    /** A rider on one side of a cell border is priced like a rider on the other. */
    @Test
    void pricesBothSidesOfACellBorderAlike() {
      Geohash.Bounds cell = Geohash.bounds(MG_ROAD_CELL);
      GeoPoint inside = new GeoPoint(cell.center().latitude(), cell.maxLng() - 0.00001);
      GeoPoint across = new GeoPoint(cell.center().latitude(), cell.maxLng() + 0.00001);
      assertThat(Geohash.encode(across, PRECISION)).isNotEqualTo(MG_ROAD_CELL);
      requests(inside, 8);
      driversAt(across, 2);

      for (int tick = 0; tick < 6; tick++) {
        job.tick();
      }

      assertThat(priceAt(inside)).isGreaterThan(BigDecimal.ONE);
      assertThat(priceAt(across)).isEqualByComparingTo(priceAt(inside));
      // Two cells away, no block that reaches it holds a request.
      GeoPoint twoAway =
          new GeoPoint(cell.center().latitude(), cell.maxLng() + 1.5 * cell.widthDegrees());
      assertThat(priceAt(twoAway)).isEqualByComparingTo("1");
    }

    @Test
    void neverComputesACellWithNoDemandNearby() {
      requests(MG_ROAD, 10);

      job.tick();

      // The cell and its eight neighbours; nothing else in the city.
      assertThat(job.snapshot().keySet())
          .containsExactlyInAnyOrderElementsOf(Geohash.block(MG_ROAD_CELL));
    }

    /** If the job stops, the keys it wrote lapse and fares fall back to 1.0 on their own. */
    @Test
    void letsAStoppedJobsMultipliersLapseToOne() {
      requests(MG_ROAD, 10);
      job.tick();
      String key = SurgeKeys.multiplier(MG_ROAD_CELL);
      assertThat(redis.getExpire(key)).isBetween(1L, 90L);
      assertThat(priceAt(MG_ROAD)).isEqualByComparingTo("1.3");

      // The TTL running out, without the 90-second wait.
      redis.expire(key, Duration.ofMillis(1));
      await().atMost(Duration.ofSeconds(5)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));

      assertThat(priceAt(MG_ROAD)).isEqualByComparingTo("1");
    }

    /** Another instance holding the tick's lock: this one skips its turn. */
    @Test
    void runsOnlyWhereItHoldsTheClusterLock() {
      requests(MG_ROAD, 10);
      SimpleLock held =
          lockProvider
              .lock(
                  new LockConfiguration(
                      Instant.now(), "surge-tick", Duration.ofSeconds(30), Duration.ZERO))
              .orElseThrow();
      try {
        job.tick();
        assertThat(stored(MG_ROAD_CELL)).isNull();
      } finally {
        held.unlock();
      }

      job.tick();
      assertThat(stored(MG_ROAD_CELL)).isEqualTo("1.25");
    }

    @Test
    void countsSurgingCellsAndRecordsTheirMultipliers() {
      long recordedBefore = meters.get("surge.multiplier").summary().count();
      requests(MG_ROAD, 10);

      job.tick();

      assertThat(meters.get("surge.active.cells").gauge().value()).isEqualTo(9);
      assertThat(meters.get("surge.multiplier").summary().count()).isEqualTo(recordedBefore + 9);
    }

    /**
     * Load: 2,000 available drivers and 300 cells with demand - 2,000-odd cells priced with their
     * neighbours. One tick must finish well inside the 30 seconds before the next.
     */
    @Test
    void pricesThreeHundredBusyCellsWellInsideATick() {
      Random random = new Random(8);
      for (int i = 0; i < 2_000; i++) {
        redis
            .opsForGeo()
            .add(
                AVAILABLE.get(i % 3),
                new Point(77.48 + random.nextDouble() * 0.27, 12.85 + random.nextDouble() * 0.25),
                "driver-" + i);
      }
      Set<String> busy = new HashSet<>();
      while (busy.size() < 300) {
        GeoPoint at =
            new GeoPoint(12.85 + random.nextDouble() * 0.25, 77.48 + random.nextDouble() * 0.27);
        if (busy.add(Geohash.encode(at, PRECISION))) {
          requests(at, 2);
        }
      }

      long first = timed(job::tick);
      long second = timed(job::tick);

      int priced = job.snapshot().size();
      log.info(
          "Surge tick over {} busy cells, {} priced, 2,000 drivers: first {} ms, then {} ms",
          busy.size(),
          priced,
          first,
          second);
      assertThat(priced).isGreaterThan(1_000);
      assertThat(first).isLessThan(3_000);
      assertThat(second).isLessThan(3_000);
    }

    private static long timed(Runnable work) {
      long start = System.nanoTime();
      work.run();
      return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }
  }

  @Nested
  class CountingDemand {

    @Test
    void countsARequestOnceHoweverOftenItIsDelivered() {
      UUID request = UUID.randomUUID();

      demand.recordRequest(request, MG_ROAD, CLOCK.instant());
      demand.recordRequest(request, MG_ROAD, CLOCK.instant());

      String bucket = SurgeKeys.demand(MG_ROAD_CELL, SurgeKeys.epochMinute(CLOCK.instant()));
      assertThat(redis.opsForValue().get(bucket)).isEqualTo("1");
      assertThat(redis.getExpire(bucket)).isBetween(1L, 600L);
    }

    @Test
    void countsEveryTripRequestedThroughTheEventBus() {
      publish(
          new TripRequested(
              UUID.randomUUID(),
              CLOCK.instant(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              VehicleType.CAR,
              MG_ROAD.latitude(),
              MG_ROAD.longitude(),
              INDIRANAGAR.latitude(),
              INDIRANAGAR.longitude(),
              9_900,
              BigDecimal.ONE,
              PaymentMethod.WALLET));

      String bucket = SurgeKeys.demand(MG_ROAD_CELL, SurgeKeys.epochMinute(CLOCK.instant()));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> "1".equals(redis.opsForValue().get(bucket)));
    }
  }

  /** Precedence: the kill switch, then an override, then the computed value, then 1.0. */
  @Nested
  class WhatAQuoteIsPricedAt {

    @BeforeEach
    void computedAtTwo() {
      redis.opsForValue().set(SurgeKeys.multiplier(MG_ROAD_CELL), "2", Duration.ofSeconds(90));
    }

    @Test
    void usesTheComputedValueRoundedToATenth() {
      redis.opsForValue().set(SurgeKeys.multiplier(MG_ROAD_CELL), "1.75", Duration.ofSeconds(90));

      assertThat(priceAt(MG_ROAD)).isEqualByComparingTo("1.8");
      assertThat(priceAt(INDIRANAGAR)).isEqualByComparingTo("1");
    }

    @Test
    void prefersAnOverrideEvenBelowTheComputedValueUntilItExpires() throws Exception {
      User admin = admin();

      override(admin, MG_ROAD_CELL, "1.5", CLOCK.instant().plus(Duration.ofMinutes(30)))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.multiplier").value(1.5));
      assertThat(priceAt(MG_ROAD)).isEqualByComparingTo("1.5");

      // Past its expiry by our clock, whatever Redis still holds.
      CLOCK.advance(Duration.ofMinutes(31));
      assertThat(priceAt(MG_ROAD)).isEqualByComparingTo("2");
    }

    @Test
    void stopsSurgeEverywhereWhileTheKillSwitchIsOn() throws Exception {
      User admin = admin();
      override(admin, MG_ROAD_CELL, "2.5", CLOCK.instant().plus(Duration.ofHours(1)));

      adminPost(admin, "/kill-switch", "{\"on\":true}")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.on").value(true));
      assertThat(priceAt(MG_ROAD)).isEqualByComparingTo("1");
      assertThat(redis.getExpire(SurgeKeys.KILL_SWITCH))
          .as("never expires on its own")
          .isEqualTo(-1);

      adminPost(admin, "/kill-switch", "{\"on\":false}").andExpect(jsonPath("$.on").value(false));
      assertThat(priceAt(MG_ROAD)).isEqualByComparingTo("2.5");
    }

    @Test
    void goesBackToTheComputedValueOnceAnOverrideIsRemoved() throws Exception {
      User admin = admin();
      override(admin, MG_ROAD_CELL, "1.2", CLOCK.instant().plus(Duration.ofHours(1)));

      mockMvc
          .perform(
              delete("/api/v1/admin/surge/overrides/" + MG_ROAD_CELL)
                  .header("Authorization", "Bearer " + admin.token()))
          .andExpect(status().isNoContent());

      assertThat(priceAt(MG_ROAD)).isEqualByComparingTo("2");
    }

    /** A cell's value is kept five seconds: a quote storm costs Redis one read per cell. */
    @Test
    void keepsALookedUpValueForTheCacheTime() {
      provider.forgetAll();
      assertThat(provider.multiplierAt(MG_ROAD, VehicleType.CAR)).isEqualByComparingTo("2");
      redis.opsForValue().set(SurgeKeys.multiplier(MG_ROAD_CELL), "1.5", Duration.ofSeconds(90));

      assertThat(provider.multiplierAt(MG_ROAD, VehicleType.BIKE)).isEqualByComparingTo("2");
      CLOCK.advance(Duration.ofSeconds(6));
      assertThat(provider.multiplierAt(MG_ROAD, VehicleType.CAR)).isEqualByComparingTo("1.5");
    }
  }

  @Nested
  class AdminsAndTheHeatmap {

    @Test
    void showsEachPricedCellWithItsDemandSupplyAndBounds() throws Exception {
      requests(MG_ROAD, 10);
      driversAt(MG_ROAD, 2);
      job.tick();
      User admin = admin();
      override(admin, MG_ROAD_CELL, "1.5", CLOCK.instant().plus(Duration.ofHours(1)));

      mockMvc
          .perform(get("/api/v1/admin/surge").header("Authorization", "Bearer " + admin.token()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.killSwitchOn").value(false))
          .andExpect(jsonPath("$.cells.length()").value(9))
          .andExpect(
              jsonPath("$.cells[?(@.cell == '%s')].demand".formatted(MG_ROAD_CELL)).value(10))
          .andExpect(jsonPath("$.cells[?(@.cell == '%s')].supply".formatted(MG_ROAD_CELL)).value(2))
          .andExpect(
              jsonPath("$.cells[?(@.cell == '%s')].multiplier".formatted(MG_ROAD_CELL)).value(1.3))
          .andExpect(jsonPath("$.cells[0].minLat").isNumber())
          .andExpect(jsonPath("$.overrides[0].cell").value(MG_ROAD_CELL));
    }

    @Test
    void refusesABadOverride() throws Exception {
      User admin = admin();
      Instant later = CLOCK.instant().plus(Duration.ofHours(1));

      override(admin, "nota", "1.5", later)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_CELL"));
      override(admin, MG_ROAD_CELL, "0.9", later)
          .andExpect(jsonPath("$.code").value("OVERRIDE_OUT_OF_RANGE"));
      override(admin, MG_ROAD_CELL, "3.0", later)
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.code").value("OVERRIDE_OUT_OF_RANGE"));
      override(admin, MG_ROAD_CELL, "1.5", CLOCK.instant().minusSeconds(1))
          .andExpect(jsonPath("$.code").value("INVALID_OVERRIDE_EXPIRY"));
      override(admin, MG_ROAD_CELL, "1.5", CLOCK.instant().plus(Duration.ofHours(25)))
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.code").value("INVALID_OVERRIDE_EXPIRY"));
      adminPost(admin, "/overrides", "{}").andExpect(status().isBadRequest());
    }
  }

  /** The quote is the price: surge moving afterwards does not touch a booking made with it. */
  @Test
  void chargesTheQuotedFareWhenSurgeRisesBeforeBooking() throws Exception {
    User rider = rider();
    User admin = admin();
    String body = quote(rider);
    String quoteId = carQuote(body, "quoteId");
    long quoted = ((Number) carQuote(body, "totalPaise")).longValue();
    assertThat(((Number) carQuote(body, "surgeMultiplier")).doubleValue()).isEqualTo(1.0);

    override(admin, MG_ROAD_CELL, "2.0", CLOCK.instant().plus(Duration.ofHours(1)))
        .andExpect(status().isCreated());

    String trip =
        mockMvc
            .perform(
                post("/api/v1/trips")
                    .header("Authorization", "Bearer " + rider.token())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"quoteId":"%s","vehicleType":"CAR","paymentMethod":"WALLET"}"""
                            .formatted(quoteId)))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(((Number) JsonPath.read(trip, "$.quotedFarePaise")).longValue()).isEqualTo(quoted);
    assertThat(((Number) JsonPath.read(trip, "$.surgeMultiplier")).doubleValue()).isEqualTo(1.0);

    // A new quote, though, sees the surge.
    String later = quote(rider());
    assertThat(((Number) carQuote(later, "surgeMultiplier")).doubleValue()).isEqualTo(2.0);
    assertThat(((Number) carQuote(later, "totalPaise")).longValue()).isGreaterThan(quoted);
  }

  private String quote(User rider) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/fares/quotes")
                .header("Authorization", "Bearer " + rider.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"pickup":{"lat":%s,"lng":%s},"drop":{"lat":%s,"lng":%s}}"""
                        .formatted(
                            MG_ROAD.latitude(),
                            MG_ROAD.longitude(),
                            INDIRANAGAR.latitude(),
                            INDIRANAGAR.longitude())))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static <T> T carQuote(String body, String field) {
    List<T> values = JsonPath.read(body, "$.quotes[?(@.vehicleType == 'CAR')]." + field);
    return values.getFirst();
  }
}
