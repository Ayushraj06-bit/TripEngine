package com.rideflow.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rideflow.payment.LedgerReconciliation;
import com.rideflow.shared.Money;
import com.rideflow.shared.RideFlowProperties.Payment.Outcome;
import com.rideflow.trip.PaymentMethod;
import com.rideflow.trip.PaymentStatus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Money moving: trips charged, wallets topped up, refunds given, all through the ledger. */
class PaymentIntegrationTest extends PaymentIntegrationTestBase {

  private static final Duration EVENTUALLY = Duration.ofSeconds(10);

  @Autowired TripCharges charges;
  @Autowired LedgerReconciliation reconciliation;

  /**
   * Charges a trip now. The listener itself runs asynchronously, a call to it returning at once, so
   * tests that need the charge done call what it calls; the listener's own wiring is tested through
   * the event bus.
   */
  private void charge(UUID tripId, User rider, User driver, long fare, PaymentMethod method) {
    charges.chargeFare(tripId, rider.id(), driver.id(), Money.ofPaise(fare), method);
  }

  /** Until every event published to the payment listener has been handled. */
  private void awaitPaymentListener() {
    await()
        .atMost(EVENTUALLY)
        .until(
            () ->
                jdbc.queryForObject(
                        "SELECT count(*) FROM event_publication WHERE completion_date IS NULL"
                            + " AND listener_id LIKE '%TripPaymentListener%'",
                        Integer.class)
                    == 0);
  }

  private void assertConsistent() {
    LedgerReconciliation.Report report = reconciliation.reconcile();
    assertThat(report.consistent()).as("%s", report).isTrue();
  }

  private UUID transactionId(String type, UUID referenceId) {
    return jdbc.queryForObject(
        "SELECT id FROM ledger_transactions WHERE type = ? AND reference_id = ?",
        UUID.class,
        type,
        referenceId);
  }

  /** Books a trip over HTTP: a quote first, then the booking. */
  private ResultActions book(User rider) throws Exception {
    String quotes =
        mockMvc
            .perform(
                post("/api/v1/fares/quotes")
                    .header("Authorization", "Bearer " + rider.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"pickup":{"lat":12.9752,"lng":77.6064},
                         "drop":{"lat":12.9784,"lng":77.6408}}"""))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> carQuotes = JsonPath.read(quotes, "$.quotes[?(@.vehicleType == 'CAR')].quoteId");
    String quoteId = carQuotes.getFirst();
    return mockMvc.perform(
        post("/api/v1/trips")
            .header("Authorization", "Bearer " + rider.token())
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"quoteId":"%s","vehicleType":"CAR","paymentMethod":"WALLET"}"""
                    .formatted(quoteId)));
  }

  @Nested
  class ChargingTrips {

    @Test
    void splitsAWalletFareBetweenTheDriverAndThePlatform() throws Exception {
      User rider = rider();
      User driver = driver();
      topUp(rider, 50_000);
      UUID tripId = completedTrip(rider, driver, 12_345, PaymentMethod.WALLET);

      publishCompleted(tripId, rider, driver, 12_345, PaymentMethod.WALLET);

      await().atMost(EVENTUALLY).until(() -> paymentStatusOf(tripId) == PaymentStatus.PAID);
      assertThat(wallet(rider)).isEqualTo(50_000 - 12_345);
      // 20% of 12,345 is 2,469 exactly; the driver gets the rest.
      assertThat(earnings(driver)).isEqualTo(9_876);
      assertThat(revenue()).isEqualTo(2_469);
      assertConsistent();
    }

    /** At-least-once delivery: the same event twice, one after the other and both at once. */
    @Test
    void chargesATripOnceHoweverOftenItsCompletionArrives() throws Exception {
      User rider = rider();
      User driver = driver();
      topUp(rider, 50_000);
      UUID tripId = completedTrip(rider, driver, 9_900, PaymentMethod.WALLET);
      CountDownLatch go = new CountDownLatch(1);
      try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        List<Future<?>> deliveries = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
          deliveries.add(
              pool.submit(
                  () -> {
                    go.await();
                    charge(tripId, rider, driver, 9_900, PaymentMethod.WALLET);
                    return null;
                  }));
        }
        go.countDown();
        for (Future<?> delivery : deliveries) {
          delivery.get(30, TimeUnit.SECONDS);
        }
      }
      charge(tripId, rider, driver, 9_900, PaymentMethod.WALLET);
      // And once more as the trip module sends it: through the event bus, to the listener.
      publishCompleted(tripId, rider, driver, 9_900, PaymentMethod.WALLET);
      awaitPaymentListener();
      assertThat(transactionsOf("TRIP_CHARGE", tripId)).isOne();
      assertThat(wallet(rider)).isEqualTo(50_000 - 9_900);
      assertThat(revenue()).isEqualTo(1_980);
      assertConsistent();
    }

    @Test
    void takesTheCommissionOnACashTripFromTheDriver() {
      User rider = rider();
      User driver = driver();
      UUID tripId = completedTrip(rider, driver, 10_000, PaymentMethod.CASH);

      charge(tripId, rider, driver, 10_000, PaymentMethod.CASH);
      charge(tripId, rider, driver, 10_000, PaymentMethod.CASH);

      assertThat(paymentStatusOf(tripId)).isEqualTo(PaymentStatus.PAID);
      assertThat(earnings(driver)).isEqualTo(-2_000);
      assertThat(revenue()).isEqualTo(2_000);
      assertThat(wallet(rider)).isZero();
      assertThat(transactionsOf("CASH_COMMISSION", tripId)).isOne();
      assertConsistent();
    }

    @Test
    void leavesAFareTheWalletCannotCoverPendingAndBlocksBookingUntilATopUp() throws Exception {
      User rider = rider();
      User driver = driver();
      topUp(rider, 3_000);
      UUID tripId = completedTrip(rider, driver, 9_900, PaymentMethod.WALLET);

      charge(tripId, rider, driver, 9_900, PaymentMethod.WALLET);

      assertThat(paymentStatusOf(tripId)).isEqualTo(PaymentStatus.PENDING_SETTLEMENT);
      assertThat(wallet(rider)).isEqualTo(-6_900);
      // The driver is paid whatever the rider's wallet says.
      assertThat(earnings(driver)).isEqualTo(7_920);
      book(rider)
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.code").value("OUTSTANDING_BALANCE"));

      topUp(rider, 10_000);

      assertThat(paymentStatusOf(tripId)).isEqualTo(PaymentStatus.PAID);
      assertThat(wallet(rider)).isEqualTo(3_100);
      book(rider).andExpect(status().isAccepted());
      assertConsistent();
    }

    @Test
    void keepsAFarePendingUntilTheWholeDebtIsCovered() throws Exception {
      User rider = rider();
      User driver = driver();
      UUID tripId = completedTrip(rider, driver, 9_900, PaymentMethod.WALLET);
      charge(tripId, rider, driver, 9_900, PaymentMethod.WALLET);

      topUp(rider, 5_000);
      assertThat(paymentStatusOf(tripId)).isEqualTo(PaymentStatus.PENDING_SETTLEMENT);

      topUp(rider, 5_000);
      assertThat(paymentStatusOf(tripId)).isEqualTo(PaymentStatus.PAID);
      assertThat(wallet(rider)).isEqualTo(100);
    }

    /** No cash changes hands for a ride that never happened: the fee comes from the wallet. */
    @Test
    void chargesACancellationFeeToTheWalletEvenForACashTrip() {
      User rider = rider();
      User driver = driver();
      UUID tripId = cancelledTrip(rider, driver, 5_000, PaymentMethod.CASH);

      publishCancelled(tripId, rider, driver, 5_000, PaymentMethod.CASH);

      await()
          .atMost(EVENTUALLY)
          .until(() -> paymentStatusOf(tripId) == PaymentStatus.PENDING_SETTLEMENT);
      assertThat(wallet(rider)).isEqualTo(-5_000);
      assertThat(earnings(driver)).isEqualTo(4_000);
      assertThat(revenue()).isEqualTo(1_000);
      assertThat(transactionsOf("CANCELLATION_FEE", tripId)).isOne();
    }

    @Test
    void movesNoMoneyForAFreeCancellation() {
      User rider = rider();
      User driver = driver();
      UUID tripId = cancelledTrip(rider, driver, 0, PaymentMethod.WALLET);

      publishCancelled(tripId, rider, driver, 0, PaymentMethod.WALLET);
      awaitPaymentListener();

      assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Integer.class))
          .isZero();
      assertThat(paymentStatusOf(tripId)).isEqualTo(PaymentStatus.UNPAID);
    }
  }

  /** A charge that failed is not left for the next restart: the redelivery job retries it. */
  @Nested
  class Redelivery {

    @Autowired com.rideflow.shared.internal.EventRedelivery redelivery;

    private boolean chargeCompleted() {
      return Boolean.TRUE.equals(
          jdbc.queryForObject(
              "SELECT completion_date IS NOT NULL FROM event_publication"
                  + " WHERE listener_id LIKE '%TripPaymentListener%'",
              Boolean.class));
    }

    @Test
    void chargesATripWhoseFirstChargeFailedOnceItIsDeliveredAgain() throws Exception {
      jdbc.update("DELETE FROM event_publication");
      User rider = rider();
      User driver = driver();
      topUp(rider, 50_000);
      UUID tripId = completedTrip(rider, driver, 9_900, PaymentMethod.WALLET);
      tripPayments.breakRecording();

      publishCompleted(tripId, rider, driver, 9_900, PaymentMethod.WALLET);
      await().atMost(EVENTUALLY).until(() -> tripPayments.refused() > 0);
      assertThat(chargeCompleted()).as("left for redelivery").isFalse();
      // Rolled back whole: the money did not move without the trip knowing.
      assertThat(transactionsOf("TRIP_CHARGE", tripId)).isZero();
      assertThat(paymentStatusOf(tripId)).isEqualTo(PaymentStatus.UNPAID);

      tripPayments.reset();
      // Publications are stamped by the application clock, which stands still in tests.
      CLOCK.advance(Duration.ofSeconds(1));
      redelivery.redeliverOlderThan(Duration.ZERO);

      await().atMost(EVENTUALLY).until(() -> paymentStatusOf(tripId) == PaymentStatus.PAID);
      assertThat(transactionsOf("TRIP_CHARGE", tripId)).isOne();
      assertThat(wallet(rider)).isEqualTo(50_000 - 9_900);
      await().atMost(EVENTUALLY).until(this::chargeCompleted);
    }
  }

  @Nested
  class StartingTopUps {

    @Test
    void startsPendingWithAPaymentUrlAndMovesNoMoneyYet() throws Exception {
      User rider = rider();

      startTopup(rider, 50_000, "key-1")
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.status").value("PENDING"))
          .andExpect(jsonPath("$.amountPaise").value(50_000))
          .andExpect(
              jsonPath("$.paymentUrl")
                  .value(
                      org.hamcrest.Matchers.matchesPattern(
                          "http://localhost:8080/mock-gateway/payments/mock_[0-9a-f]{32}/pay")));

      assertThat(wallet(rider)).isZero();
    }

    @Test
    void answersTheSameTopUpForTheSameKey() throws Exception {
      User rider = rider();

      String first =
          startTopup(rider, 50_000, "key-1").andReturn().getResponse().getContentAsString();
      String second =
          startTopup(rider, 50_000, "key-1").andReturn().getResponse().getContentAsString();

      assertThat((String) JsonPath.read(second, "$.topupId"))
          .isEqualTo(JsonPath.read(first, "$.topupId"));
      assertThat(jdbc.queryForObject("SELECT count(*) FROM topups", Integer.class)).isOne();
    }

    @Test
    void refusesAKeyReusedForAnotherAmount() throws Exception {
      User rider = rider();
      startTopup(rider, 50_000, "key-1").andExpect(status().isCreated());

      startTopup(rider, 60_000, "key-1")
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void refusesAnAmountOutsideTheLimits() throws Exception {
      User rider = rider();

      for (long amount : new long[] {999, 1_000_001}) {
        startTopup(rider, amount, UUID.randomUUID().toString())
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.code").value("TOPUP_AMOUNT_OUT_OF_RANGE"));
      }
      startTopup(rider, 0, UUID.randomUUID().toString()).andExpect(status().isBadRequest());
    }

    @Test
    void answers503WhileTheGatewayIsDownAndTheSameKeyWorksOnceItIsBack() throws Exception {
      User rider = rider();
      gateway.goDown();

      startTopup(rider, 50_000, "key-1")
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("PAYMENT_GATEWAY_UNAVAILABLE"));

      gateway.reset();
      startTopup(rider, 50_000, "key-1")
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.paymentUrl").isNotEmpty());
      assertThat(jdbc.queryForObject("SELECT count(*) FROM topups", Integer.class)).isOne();
    }
  }

  @Nested
  class GatewayWebhooks {

    @Test
    void creditsTheWalletWhenThePaymentSucceeds() throws Exception {
      User rider = rider();
      UUID topupId = startedTopup(rider, 50_000);

      webhook(gatewayRefOf(topupId), Outcome.SUCCEEDED, 50_000).andExpect(status().isNoContent());

      assertThat(wallet(rider)).isEqualTo(50_000);
      assertThat(balance(AccountKey.PLATFORM, "PLATFORM_CLEARING")).isEqualTo(-50_000);
      assertThat(
              jdbc.queryForObject("SELECT status FROM topups WHERE id = ?", String.class, topupId))
          .isEqualTo("SUCCEEDED");
    }

    @Test
    void refusesABadSignatureWith401() throws Exception {
      User rider = rider();
      String ref = gatewayRefOf(startedTopup(rider, 50_000));
      String body =
          """
          {"eventId":"evt_1","gatewayRef":"%s","status":"SUCCEEDED","amountPaise":50000}"""
              .formatted(ref);
      String now = Long.toString(CLOCK.instant().getEpochSecond());

      mockMvc
          .perform(
              post(MockPaymentGateway.WEBHOOK_PATH)
                  .header(WebhookSignatures.TIMESTAMP_HEADER, now)
                  .header(WebhookSignatures.SIGNATURE_HEADER, "v1=" + "0".repeat(64))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
      mockMvc
          .perform(
              post(MockPaymentGateway.WEBHOOK_PATH)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isUnauthorized());

      assertThat(wallet(rider)).isZero();
    }

    /** Gateways retry until they hear a 2xx, so each delivery gets one and only one credits. */
    @Test
    void creditsOnceWhenTheSameWebhookArrivesThreeTimes() throws Exception {
      User rider = rider();
      UUID topupId = startedTopup(rider, 50_000);
      String ref = gatewayRefOf(topupId);

      for (int delivery = 0; delivery < 3; delivery++) {
        webhook(ref, Outcome.SUCCEEDED, 50_000).andExpect(status().isNoContent());
      }

      assertThat(wallet(rider)).isEqualTo(50_000);
      assertThat(transactionsOf("TOPUP", topupId)).isOne();
    }

    @Test
    void creditsOnceWhenThreeDeliveriesArriveAtOnce() throws Exception {
      User rider = rider();
      UUID topupId = startedTopup(rider, 50_000);
      String ref = gatewayRefOf(topupId);
      CountDownLatch go = new CountDownLatch(1);

      try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
        List<Future<Integer>> deliveries =
            java.util.stream.IntStream.range(0, 3)
                .mapToObj(
                    i ->
                        pool.submit(
                            () -> {
                              go.await();
                              return webhook(ref, Outcome.SUCCEEDED, 50_000)
                                  .andReturn()
                                  .getResponse()
                                  .getStatus();
                            }))
                .toList();
        go.countDown();
        for (Future<Integer> delivery : deliveries) {
          assertThat(delivery.get(30, TimeUnit.SECONDS)).isEqualTo(204);
        }
      }

      assertThat(wallet(rider)).isEqualTo(50_000);
      assertThat(transactionsOf("TOPUP", topupId)).isOne();
      assertConsistent();
    }

    @Test
    void creditsNothingWhenThePaymentFailedEvenIfASuccessFollows() throws Exception {
      User rider = rider();
      UUID topupId = startedTopup(rider, 50_000);
      String ref = gatewayRefOf(topupId);

      webhook(ref, Outcome.FAILED, 50_000).andExpect(status().isNoContent());
      webhook(ref, Outcome.SUCCEEDED, 50_000).andExpect(status().isNoContent());

      assertThat(
              jdbc.queryForObject("SELECT status FROM topups WHERE id = ?", String.class, topupId))
          .isEqualTo("FAILED");
      assertThat(wallet(rider)).isZero();
    }

    @Test
    void refusesAnUnknownPaymentAWrongAmountAndAMalformedBody() throws Exception {
      User rider = rider();
      String ref = gatewayRefOf(startedTopup(rider, 50_000));

      webhook("mock_nobody", Outcome.SUCCEEDED, 50_000).andExpect(status().isNotFound());
      webhook(ref, Outcome.SUCCEEDED, 49_999)
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.code").value("AMOUNT_MISMATCH"));
      signedWebhook("not json")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("MALFORMED_WEBHOOK"));
      signedWebhook("{\"gatewayRef\":\"%s\"}".formatted(ref)).andExpect(status().isBadRequest());

      assertThat(wallet(rider)).isZero();
    }
  }

  /** The mock gateway end to end: its webhook comes back over HTTP, through the real server. */
  @Nested
  class TheMockGateway {

    private ResultActions pay(UUID topupId, String query) throws Exception {
      return mockMvc.perform(
          post("/mock-gateway/payments/" + gatewayRefOf(topupId) + "/pay" + query));
    }

    private String statusOf(UUID topupId) {
      return jdbc.queryForObject("SELECT status FROM topups WHERE id = ?", String.class, topupId);
    }

    @Test
    void deliversASignedWebhookWhenTheRiderPays() throws Exception {
      User rider = rider();
      UUID topupId = startedTopup(rider, 50_000);

      pay(topupId, "").andExpect(status().isAccepted());

      await().atMost(EVENTUALLY).until(() -> wallet(rider) == 50_000);
      assertThat(statusOf(topupId)).isEqualTo("SUCCEEDED");
    }

    @Test
    void canBeToldThePaymentFails() throws Exception {
      User rider = rider();
      UUID topupId = startedTopup(rider, 50_000);

      pay(topupId, "?outcome=FAILED").andExpect(jsonPath("$.outcome").value("FAILED"));

      await().atMost(EVENTUALLY).until(() -> "FAILED".equals(statusOf(topupId)));
      assertThat(wallet(rider)).isZero();
    }

    @Test
    void canBeToldToTakeItsTime() throws Exception {
      User rider = rider();
      UUID topupId = startedTopup(rider, 50_000);

      pay(topupId, "?delayMs=1500").andExpect(jsonPath("$.delayMs").value(1500));

      assertThat(statusOf(topupId)).isEqualTo("PENDING");
      await().atMost(EVENTUALLY).until(() -> "SUCCEEDED".equals(statusOf(topupId)));
    }

    @Test
    void knowsNothingOfAPaymentItNeverStarted() throws Exception {
      mockMvc
          .perform(post("/mock-gateway/payments/mock_nobody/pay"))
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  class Refunding {

    private ResultActions refund(User admin, UUID transactionId) throws Exception {
      return mockMvc.perform(
          post("/api/v1/admin/payments/" + transactionId + "/refund")
              .header("Authorization", "Bearer " + admin.token()));
    }

    private UUID chargedTrip(User rider, User driver, long fare) {
      UUID tripId = completedTrip(rider, driver, fare, PaymentMethod.WALLET);
      charge(tripId, rider, driver, fare, PaymentMethod.WALLET);
      return tripId;
    }

    @Test
    void reversesATripChargeWithAMirrorImageTransaction() throws Exception {
      User rider = rider();
      User driver = driver();
      User admin = admin();
      topUp(rider, 50_000);
      UUID tripId = chargedTrip(rider, driver, 12_345);
      UUID charge = transactionId("TRIP_CHARGE", tripId);

      refund(admin, charge)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.refundedTransactionId").value(charge.toString()))
          .andExpect(jsonPath("$.amountPaise").value(12_345));

      assertThat(wallet(rider)).isEqualTo(50_000);
      assertThat(earnings(driver)).isZero();
      assertThat(revenue()).isZero();
      assertThat(paymentStatusOf(tripId)).isEqualTo(PaymentStatus.REFUNDED);
      // The original is untouched: the refund is three more entries, not three changed ones.
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM ledger_entries WHERE transaction_id = ?",
                  Integer.class,
                  charge))
          .isEqualTo(3);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM ledger_entries WHERE transaction_id = ?",
                  Integer.class,
                  transactionId("REFUND", charge)))
          .isEqualTo(3);
      assertConsistent();
    }

    @Test
    void refusesToRefundTheSameTransactionTwice() throws Exception {
      User rider = rider();
      User admin = admin();
      topUp(rider, 50_000);
      UUID charge = transactionId("TRIP_CHARGE", chargedTrip(rider, driver(), 9_900));

      refund(admin, charge).andExpect(status().isOk());
      refund(admin, charge)
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("ALREADY_REFUNDED"));

      assertThat(wallet(rider)).isEqualTo(50_000);
    }

    @Test
    void answers404ForAnUnknownTransaction() throws Exception {
      refund(admin(), UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void refusesToRefundATopUp() throws Exception {
      User rider = rider();
      UUID topupId = topUp(rider, 50_000);

      refund(admin(), transactionId("TOPUP", topupId))
          .andExpect(status().isUnprocessableContent())
          .andExpect(jsonPath("$.code").value("NOT_REFUNDABLE"));
    }

    /** A refund that brings the wallet back into credit settles what was waiting on it. */
    @Test
    void settlesAnotherTripWhenARefundCoversTheDebt() throws Exception {
      User rider = rider();
      User driver = driver();
      topUp(rider, 5_000);
      UUID paid = chargedTrip(rider, driver, 5_000);
      assertThat(paymentStatusOf(paid))
          .as("a wallet left at exactly zero has covered its fare")
          .isEqualTo(PaymentStatus.PAID);
      UUID pending = chargedTrip(rider, driver, 3_000);
      assertThat(paymentStatusOf(pending)).isEqualTo(PaymentStatus.PENDING_SETTLEMENT);

      refund(admin(), transactionId("TRIP_CHARGE", paid)).andExpect(status().isOk());

      assertThat(paymentStatusOf(paid)).isEqualTo(PaymentStatus.REFUNDED);
      assertThat(paymentStatusOf(pending)).isEqualTo(PaymentStatus.PAID);
      assertThat(wallet(rider)).isEqualTo(2_000);
    }
  }

  @Nested
  class TheWallet {

    private ResultActions walletOf(User user, String query) throws Exception {
      return mockMvc.perform(
          get("/api/v1/wallet" + query).header("Authorization", "Bearer " + user.token()));
    }

    @Test
    void showsARiderTheirBalanceAndMovementsNewestFirstAPageAtATime() throws Exception {
      User rider = rider();
      User driver = driver();
      topUp(rider, 10_000);
      CLOCK.advance(Duration.ofMinutes(1));
      topUp(rider, 20_000);
      CLOCK.advance(Duration.ofMinutes(1));
      UUID tripId = completedTrip(rider, driver, 9_900, PaymentMethod.WALLET);
      charge(tripId, rider, driver, 9_900, PaymentMethod.WALLET);

      String first =
          walletOf(rider, "?limit=2")
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.account").value("RIDER_WALLET"))
              .andExpect(jsonPath("$.balancePaise").value(20_100))
              .andExpect(jsonPath("$.movements.length()").value(2))
              .andExpect(jsonPath("$.movements[0].type").value("TRIP_CHARGE"))
              .andExpect(jsonPath("$.movements[0].direction").value("DEBIT"))
              .andExpect(jsonPath("$.movements[0].referenceId").value(tripId.toString()))
              .andExpect(jsonPath("$.movements[1].amountPaise").value(20_000))
              .andReturn()
              .getResponse()
              .getContentAsString();

      walletOf(rider, "?limit=2&cursor=" + JsonPath.read(first, "$.nextCursor"))
          .andExpect(jsonPath("$.movements.length()").value(1))
          .andExpect(jsonPath("$.movements[0].amountPaise").value(10_000))
          .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void showsADriverTheirEarnings() throws Exception {
      User rider = rider();
      User driver = driver();
      topUp(rider, 50_000);
      UUID tripId = completedTrip(rider, driver, 12_345, PaymentMethod.WALLET);
      charge(tripId, rider, driver, 12_345, PaymentMethod.WALLET);

      walletOf(driver, "")
          .andExpect(jsonPath("$.account").value("DRIVER_EARNINGS"))
          .andExpect(jsonPath("$.balancePaise").value(9_876))
          .andExpect(jsonPath("$.movements[0].direction").value("CREDIT"));
    }

    @Test
    void showsANewRiderAnEmptyWallet() throws Exception {
      walletOf(rider(), "")
          .andExpect(jsonPath("$.balancePaise").value(0))
          .andExpect(jsonPath("$.movements.length()").value(0));
    }

    @Test
    void refusesALimitOutsideOneToFifty() throws Exception {
      walletOf(rider(), "?limit=51").andExpect(status().isBadRequest());
    }
  }
}
