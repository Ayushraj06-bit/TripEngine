package com.rideflow.payment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Whether the ledger still adds up, for {@code admin} and the nightly check. */
public interface LedgerReconciliation {

  /**
   * Checks, in one consistent snapshot, that every transaction balances, that every account's
   * cached balance equals the sum of its entries, and that all balances together come to zero.
   */
  Report reconcile();

  /**
   * @param unbalancedTransactions the first {@code reconcile-report-limit} of them; the count is
   *     all
   * @param mismatchedAccounts likewise
   */
  record Report(
      Instant checkedAt,
      boolean consistent,
      long unbalancedTransactionCount,
      List<UUID> unbalancedTransactions,
      long mismatchedAccountCount,
      List<AccountMismatch> mismatchedAccounts,
      long totalBalancePaise) {}

  record AccountMismatch(UUID accountId, long cachedBalancePaise, long entriesBalancePaise) {}
}
