package com.rideflow.rating.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rideflow.payment.internal.PaymentIntegrationTestBase;
import com.rideflow.trip.PaymentMethod;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Ratings, over HTTP. Shares the payment tests' context - same setup, one start-up - for a
 * completed trip is all either needs.
 */
class RatingIntegrationTest extends PaymentIntegrationTestBase {

  private ResultActions rate(User user, UUID tripId, String body) throws Exception {
    return mockMvc.perform(
        post("/api/v1/trips/" + tripId + "/rating")
            .header("Authorization", "Bearer " + user.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions stars(User user, UUID tripId, int stars) throws Exception {
    return rate(user, tripId, "{\"stars\":" + stars + "}");
  }

  private UUID completed(User rider, User driver) {
    return completedTrip(rider, driver, 9_900, PaymentMethod.WALLET);
  }

  @Test
  void averagesADriversRatingsExactly() throws Exception {
    User driver = driver();
    for (int given : new int[] {5, 4, 3}) {
      User rider = rider();
      stars(rider, completed(rider, driver), given).andExpect(status().isCreated());
    }

    mockMvc
        .perform(get("/api/v1/drivers/me").header("Authorization", "Bearer " + driver.token()))
        .andExpect(jsonPath("$.averageRating").value(4.0))
        .andExpect(jsonPath("$.ratingCount").value(3));
    assertThat(
            jdbc.queryForObject(
                "SELECT rating_sum FROM drivers WHERE user_id = ?", Integer.class, driver.id()))
        .isEqualTo(12);
  }

  @Test
  void letsEachSideRateTheOtherOnceAndLeavesTheDriversTotalsToTheRider() throws Exception {
    User rider = rider();
    User driver = driver();
    UUID tripId = completed(rider, driver);

    stars(driver, tripId, 2)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.raterRole").value("DRIVER"));
    stars(rider, tripId, 5)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.raterRole").value("RIDER"));

    stars(rider, tripId, 4)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ALREADY_RATED"));
    stars(driver, tripId, 4).andExpect(status().isConflict());
    assertThat(
            jdbc.queryForObject(
                "SELECT rating_count FROM drivers WHERE user_id = ?", Integer.class, driver.id()))
        .isOne();
  }

  /** The same rating sent twice at once: one is kept, and the driver's totals count it once. */
  @Test
  void keepsOneOfTwoRatingsSentAtTheSameMoment() throws Exception {
    User rider = rider();
    User driver = driver();
    UUID tripId = completed(rider, driver);
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);

    java.util.List<Integer> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var sent = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 2; i++) {
        sent.add(
            pool.submit(
                () -> {
                  go.await();
                  statuses.add(stars(rider, tripId, 4).andReturn().getResponse().getStatus());
                  return null;
                }));
      }
      go.countDown();
      for (var each : sent) {
        each.get(30, java.util.concurrent.TimeUnit.SECONDS);
      }
    }

    assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT rating_count FROM drivers WHERE user_id = ?", Integer.class, driver.id()))
        .isOne();
  }

  @Test
  void refusesATripThatHasNotCompleted() throws Exception {
    User rider = rider();
    UUID tripId = cancelledTrip(rider, driver(), 0, PaymentMethod.WALLET);

    stars(rider, tripId, 5)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRIP_NOT_COMPLETED"));
  }

  /** Someone else's trip answers exactly like a trip that does not exist. */
  @Test
  void answers404ToAnyoneWhoWasNotOnTheTrip() throws Exception {
    UUID tripId = completed(rider(), driver());

    stars(rider(), tripId, 5).andExpect(status().isNotFound());
    stars(driver(), tripId, 5).andExpect(status().isNotFound());
    stars(rider(), UUID.randomUUID(), 5).andExpect(status().isNotFound());
  }

  @Test
  void refusesStarsOutsideOneToFiveAndALongComment() throws Exception {
    User rider = rider();
    UUID tripId = completed(rider, driver());

    stars(rider, tripId, 0).andExpect(status().isBadRequest());
    stars(rider, tripId, 6).andExpect(status().isBadRequest());
    rate(rider, tripId, "{}").andExpect(status().isBadRequest());
    rate(rider, tripId, "{\"stars\":5,\"comment\":\"%s\"}".formatted("a".repeat(501)))
        .andExpect(status().isBadRequest());

    stars(rider, tripId, 5).andExpect(status().isCreated());
  }

  /**
   * The trip is the one backdated, not the clock moved on: a week later the users' 15-minute tokens
   * would have run out long before the window.
   */
  @Test
  void closesSevenDaysAfterTheTripEnds() throws Exception {
    User rider = rider();
    User driver = driver();
    CLOCK.advance(Duration.ofDays(-7));
    UUID tripId = completed(rider, driver);
    CLOCK.advance(Duration.ofDays(7));

    stars(driver, tripId, 4).andExpect(status().isCreated());

    CLOCK.advance(Duration.ofSeconds(1));
    stars(rider, tripId, 4)
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("RATING_WINDOW_CLOSED"));
  }

  /** Stored as written, returned as text: a dashboard that renders it shows it, never runs it. */
  @Test
  void escapesAScriptInACommentOnTheWayOut() throws Exception {
    User rider = rider();
    UUID tripId = completed(rider, driver());
    String comment = "<script>alert('hi')</script> great driver";

    rate(rider, tripId, "{\"stars\":5,\"comment\":\"%s\"}".formatted(comment))
        .andExpect(status().isCreated())
        .andExpect(
            jsonPath("$.comment")
                .value("&lt;script&gt;alert(&#39;hi&#39;)&lt;/script&gt; great driver"));

    assertThat(
            jdbc.queryForObject(
                "SELECT comment FROM ratings WHERE trip_id = ?", String.class, tripId))
        .isEqualTo(comment);
  }
}
