package com.rideflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rideflow.payment.internal.PaymentIntegrationTestBase;
import com.rideflow.trip.PaymentMethod;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import net.ttddyy.dsproxy.QueryCountHolder;
import org.junit.jupiter.api.Test;

/**
 * N+1 guards: the reads behind the apps' busiest screens run a fixed number of statements, however
 * much there is to show. A lazy association or a query in a loop shows up here as a count that
 * grows with the data.
 */
class QueryBudgetIntegrationTest extends PaymentIntegrationTestBase {

  /** Statements the request ran, on the request's thread. */
  private long statementsFor(String path, User user) throws Exception {
    QueryCountHolder.clear();
    mockMvc
        .perform(get(path).header("Authorization", "Bearer " + user.token()))
        .andExpect(status().is2xxSuccessful());
    return QueryCountHolder.getGrandTotal().getTotal();
  }

  @Test
  void readsTripHistoryInTheSameStatementsForThreeTripsAsForTwenty() throws Exception {
    User rider = rider();
    User driver = driver();
    for (int i = 0; i < 3; i++) {
      completedTrip(rider, driver, 9_900, PaymentMethod.CASH);
    }
    long few = statementsFor("/api/v1/trips?limit=50", rider);
    for (int i = 0; i < 17; i++) {
      completedTrip(rider, driver, 9_900, PaymentMethod.CASH);
    }

    assertThat(statementsFor("/api/v1/trips?limit=50", rider)).isEqualTo(few);
    assertThat(few).isLessThanOrEqualTo(HISTORY_BUDGET);
  }

  @Test
  void readsTheActiveTripInAFixedBudget() throws Exception {
    User rider = rider();
    jdbc.update(
        """
        INSERT INTO trips (id, rider_id, vehicle_type, status, pickup_lat, pickup_lng, drop_lat,
            drop_lng, quoted_fare_paise, surge_multiplier, rate_card_id, payment_method,
            payment_status, distance_m, duration_s, requested_at)
        VALUES (?, ?, 'CAR', 'REQUESTED', 12.9752, 77.6064, 12.9784, 77.6408, 9900, 1,
            (SELECT id FROM rate_cards WHERE vehicle_type = 'CAR' LIMIT 1), 'CASH', 'UNPAID',
            4862, 796, ?)""",
        UUID.randomUUID(),
        rider.id(),
        OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC));

    assertThat(statementsFor("/api/v1/trips/active", rider)).isLessThanOrEqualTo(ACTIVE_BUDGET);
  }

  @Test
  void readsTheWalletInTheSameStatementsForThreeMovementsAsForTwenty() throws Exception {
    User rider = rider();
    for (int i = 0; i < 3; i++) {
      topUp(rider, 10_000);
    }
    long few = statementsFor("/api/v1/wallet?limit=50", rider);
    for (int i = 0; i < 17; i++) {
      topUp(rider, 10_000);
    }

    assertThat(statementsFor("/api/v1/wallet?limit=50", rider)).isEqualTo(few);
    assertThat(few).isLessThanOrEqualTo(WALLET_BUDGET);
  }

  // Measured when these tests were written: one query for a page of trips or the active trip, two
  // for the wallet (its balance, then a page of movements). A change that needs more says why.
  private static final long HISTORY_BUDGET = 1;
  private static final long ACTIVE_BUDGET = 1;
  private static final long WALLET_BUDGET = 2;
}
