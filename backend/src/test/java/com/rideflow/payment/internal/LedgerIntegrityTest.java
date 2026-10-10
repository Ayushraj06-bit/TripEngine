package com.rideflow.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rideflow.identity.Role;
import com.rideflow.payment.LedgerReconciliation;
import com.rideflow.shared.Money;
import com.rideflow.shared.RideFlowProperties.Payment.Outcome;
import com.rideflow.trip.PaymentMethod;
import com.rideflow.trip.PaymentStatus;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/** The ledger under pressure: racing postings, attempts to rewrite history, and the audit. */
class LedgerIntegrityTest extends PaymentIntegrationTestBase {

  @Autowired TripCharges charges;
  @Autowired TopupService topups;
  @Autowired LedgerReconciliation reconciliation;
  @Autowired ReconciliationService nightlyCheck;
  @Autowired MeterRegistry meters;

  private void runTogether(List<Runnable> work) throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(work.size())) {
      List<Future<?>> running = new ArrayList<>();
      for (Runnable each : work) {
        running.add(
            pool.submit(
                () -> {
                  go.await();
                  each.run();
                  return null;
                }));
      }
      go.countDown();
      for (Future<?> each : running) {
        each.get(60, TimeUnit.SECONDS);
      }
    }
  }

  private String startTopupFor(User rider, long amount) {
    return gatewayRefOf(topups.start(rider.id(), UUID.randomUUID().toString(), amount).topupId());
  }

  @Nested
  class Races {

    /**
     * The confirmation credits the wallet while the charge debits it, both from the same balance.
     * Whichever commits second fails its version check, runs again on the new balance, and both
     * count. Whichever came first, the trip ends up paid.
     */
    @Test
    void countsATopUpAndATripChargeLandingOnOneWalletAtOnce() throws Exception {
      User driver = userRow(Role.DRIVER);
      for (int round = 0; round < 15; round++) {
        User rider = userRow(Role.RIDER);
        String ref = startTopupFor(rider, 20_000);
        UUID tripId = completedTrip(rider, driver, 7_000, PaymentMethod.WALLET);

        runTogether(
            List.of(
                () ->
                    topups.confirm(new GatewayNotification("evt", ref, Outcome.SUCCEEDED, 20_000)),
                () ->
                    charges.chargeFare(
                        tripId,
                        rider.id(),
                        driver.id(),
                        Money.ofPaise(7_000),
                        PaymentMethod.WALLET)));

        assertThat(wallet(rider)).as("round %d", round).isEqualTo(13_000);
        assertThat(paymentStatusOf(tripId)).as("round %d", round).isEqualTo(PaymentStatus.PAID);
      }
      assertThat(earnings(driver)).isEqualTo(15 * 5_600);
      assertThat(reconciliation.reconcile().consistent()).isTrue();
    }

    /** Every charge credits the one platform-revenue account; none of them may be lost. */
    @Test
    void chargesTenTripsAtOnceIntoOnePlatformAccount() throws Exception {
      User driver = userRow(Role.DRIVER);
      List<Runnable> work = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        User rider = userRow(Role.RIDER);
        UUID tripId = completedTrip(rider, driver, 10_000, PaymentMethod.WALLET);
        work.add(
            () ->
                charges.chargeFare(
                    tripId, rider.id(), driver.id(), Money.ofPaise(10_000), PaymentMethod.WALLET));
      }

      runTogether(work);

      assertThat(revenue()).isEqualTo(10 * 2_000);
      assertThat(earnings(driver)).isEqualTo(10 * 8_000);
      assertThat(reconciliation.reconcile().consistent()).isTrue();
    }
  }

  /** The database itself refuses to let history be rewritten or a posting be half written. */
  @Nested
  class TheDatabaseGuards {

    private UUID chargedEntry() {
      User rider = userRow(Role.RIDER);
      User driver = userRow(Role.DRIVER);
      charges.chargeFare(
          UUID.randomUUID(), rider.id(), driver.id(), Money.ofPaise(9_900), PaymentMethod.WALLET);
      return jdbc.queryForObject("SELECT id FROM ledger_entries LIMIT 1", UUID.class);
    }

    @Test
    void refusesToChangeOrDeleteAnEntry() {
      UUID entry = chargedEntry();

      assertThatThrownBy(
              () -> jdbc.update("UPDATE ledger_entries SET amount_paise = 1 WHERE id = ?", entry))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("append-only");
      assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE id = ?", entry))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("append-only");
      assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_transactions"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("append-only");
    }

    @Test
    void refusesToCommitATransactionThatDoesNotBalance() {
      UUID entry = chargedEntry();
      UUID account =
          jdbc.queryForObject(
              "SELECT account_id FROM ledger_entries WHERE id = ?", UUID.class, entry);

      assertThatThrownBy(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          status -> {
                            UUID transaction = UUID.randomUUID();
                            jdbc.update(
                                "INSERT INTO ledger_transactions (id, type, reference_id,"
                                    + " created_at) VALUES (?, 'TOPUP', ?, now())",
                                transaction,
                                UUID.randomUUID());
                            jdbc.update(
                                "INSERT INTO ledger_entries (id, transaction_id, account_id,"
                                    + " direction, amount_paise) VALUES (?, ?, ?, 'CREDIT', 500)",
                                UUID.randomUUID(),
                                transaction,
                                account);
                          }))
          .hasStackTraceContaining("does not balance");
      assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Integer.class))
          .isOne();
    }
  }

  @Nested
  class Reconciling {

    private double mismatchGauge() {
      return meters.get("ledger.reconciliation.mismatches").gauge().value();
    }

    @Test
    void findsAHealthyLedgerConsistentForAnAdmin() throws Exception {
      User rider = rider();
      topUp(rider, 50_000);
      charges.chargeFare(
          UUID.randomUUID(),
          rider.id(),
          userRow(Role.DRIVER).id(),
          Money.ofPaise(12_345),
          PaymentMethod.WALLET);

      mockMvc
          .perform(
              get("/api/v1/admin/ledger/reconcile")
                  .header("Authorization", "Bearer " + admin().token()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.consistent").value(true))
          .andExpect(jsonPath("$.totalBalancePaise").value(0))
          .andExpect(jsonPath("$.mismatchedAccounts.length()").value(0));
      assertThat(mismatchGauge()).isZero();
    }

    /** A balance edited by hand - accounts are not append-only, entries are - is caught. */
    @Test
    void findsAnAccountWhoseBalanceDisagreesWithItsEntries() throws Exception {
      User rider = rider();
      topUp(rider, 50_000);
      jdbc.update(
          "UPDATE ledger_accounts SET balance_paise = balance_paise + 1 WHERE owner_id = ?",
          rider.id());

      nightlyCheck.nightly();
      LedgerReconciliation.Report report = reconciliation.reconcile();

      assertThat(report.consistent()).isFalse();
      assertThat(report.mismatchedAccountCount()).isOne();
      assertThat(report.mismatchedAccounts().getFirst().cachedBalancePaise()).isEqualTo(50_001);
      assertThat(report.mismatchedAccounts().getFirst().entriesBalancePaise()).isEqualTo(50_000);
      assertThat(report.totalBalancePaise()).isOne();
      assertThat(report.unbalancedTransactionCount()).isZero();
      // The account, and the total that no longer comes to zero.
      assertThat(mismatchGauge()).isEqualTo(2);
    }
  }
}
