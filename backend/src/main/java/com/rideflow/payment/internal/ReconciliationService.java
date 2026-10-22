package com.rideflow.payment.internal;

import com.rideflow.payment.LedgerReconciliation;
import com.rideflow.shared.RideFlowProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks the ledger against itself (ADR 0012), on demand and nightly.
 *
 * <p>Three aggregate queries in one REPEATABLE READ snapshot, so a posting committing halfway
 * through cannot look like a mismatch. Each reads the whole ledger: fine nightly and on an admin's
 * request, and the reason this is not on any hot path.
 *
 * <p>Mismatches go to the log and to {@code ledger_reconciliation_mismatches}, for an alert. The
 * database already refuses unbalanced transactions and edited entries (V11), so a mismatch means
 * something got around that - a manual fix, a restored backup - and a human must look.
 */
@Service
class ReconciliationService implements LedgerReconciliation {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

  /** Credits count up, debits down: a balance is credits minus debits. */
  private static final String SIGNED =
      "CASE e.direction WHEN 'CREDIT' THEN e.amount_paise WHEN 'DEBIT' THEN -e.amount_paise END";

  private static final String UNBALANCED =
      """
      SELECT t.id, count(*) OVER () AS total
        FROM ledger_transactions t
        LEFT JOIN ledger_entries e ON e.transaction_id = t.id
       GROUP BY t.id
      HAVING coalesce(sum(%s), 0) <> 0 OR count(e.id) < 2
       ORDER BY t.id
       LIMIT ?"""
          .formatted(SIGNED);

  private static final String MISMATCHED =
      """
      SELECT a.id, a.balance_paise, coalesce(sum(%1$s), 0) AS from_entries,
             count(*) OVER () AS total
        FROM ledger_accounts a
        LEFT JOIN ledger_entries e ON e.account_id = a.id
       GROUP BY a.id, a.balance_paise
      HAVING a.balance_paise <> coalesce(sum(%1$s), 0)
       ORDER BY a.id
       LIMIT ?"""
          .formatted(SIGNED);

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final int reportLimit;
  private final TransactionTemplate snapshot;
  private final AtomicLong mismatches = new AtomicLong();

  ReconciliationService(
      JdbcTemplate jdbc,
      Clock clock,
      RideFlowProperties properties,
      PlatformTransactionManager transactionManager,
      MeterRegistry meters) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.reportLimit = properties.payment().reconcileReportLimit();
    this.snapshot = new TransactionTemplate(transactionManager);
    snapshot.setReadOnly(true);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    Gauge.builder("ledger.reconciliation.mismatches", mismatches, AtomicLong::get)
        .description("Problems the last ledger reconciliation found; 0 when it adds up")
        .register(meters);
  }

  private record Counted<T>(List<T> shown, long count) {}

  @Override
  public Report reconcile() {
    Report report =
        snapshot.execute(
            status -> {
              // The whole ledger, nightly: more than the 10 s every other statement gets.
              jdbc.execute("SET LOCAL statement_timeout = '10min'");
              Counted<UUID> unbalanced = unbalancedTransactions();
              Counted<AccountMismatch> mismatched = mismatchedAccounts();
              long total =
                  jdbc.queryForObject(
                      "SELECT coalesce(sum(balance_paise), 0) FROM ledger_accounts", Long.class);
              return new Report(
                  clock.instant(),
                  unbalanced.count() == 0 && mismatched.count() == 0 && total == 0,
                  unbalanced.count(),
                  unbalanced.shown(),
                  mismatched.count(),
                  mismatched.shown(),
                  total);
            });
    mismatches.set(
        report.unbalancedTransactionCount()
            + report.mismatchedAccountCount()
            + (report.totalBalancePaise() == 0 ? 0 : 1));
    return report;
  }

  @Scheduled(cron = "${rideflow.payment.reconcile-cron}")
  @SchedulerLock(name = "ledger-reconciliation", lockAtMostFor = "PT30M")
  void nightly() {
    Report report = reconcile();
    if (report.consistent()) {
      log.info("Ledger reconciliation: consistent");
      return;
    }
    log.error(
        "Ledger reconciliation found problems: {} unbalanced transactions {}, {} accounts whose"
            + " balance disagrees with their entries {}, all balances sum to {} paise",
        report.unbalancedTransactionCount(),
        report.unbalancedTransactions(),
        report.mismatchedAccountCount(),
        report.mismatchedAccounts(),
        report.totalBalancePaise());
  }

  private Counted<UUID> unbalancedTransactions() {
    long[] total = {0};
    List<UUID> ids =
        jdbc.query(
            UNBALANCED,
            (row, index) -> {
              total[0] = row.getLong("total");
              return row.getObject("id", UUID.class);
            },
            reportLimit);
    return new Counted<>(ids, total[0]);
  }

  private Counted<AccountMismatch> mismatchedAccounts() {
    long[] total = {0};
    List<AccountMismatch> accounts =
        jdbc.query(
            MISMATCHED,
            (row, index) -> {
              total[0] = row.getLong("total");
              return new AccountMismatch(
                  row.getObject("id", UUID.class),
                  row.getLong("balance_paise"),
                  row.getLong("from_entries"));
            },
            reportLimit);
    return new Counted<>(accounts, total[0]);
  }
}
