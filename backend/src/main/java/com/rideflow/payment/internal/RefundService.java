package com.rideflow.payment.internal;

import com.rideflow.payment.Refunds;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.Money;
import com.rideflow.trip.PaymentStatus;
import com.rideflow.trip.TripPayments;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Refunds as reversing transactions: every entry of the original, mirrored. The original stays as
 * it was - the ledger is append-only - and the pair of them nets to nothing.
 *
 * <p>Once only: the refund's reference is the transaction it reverses, unique with its type.
 */
@Service
class RefundService implements Refunds {

  private static final Logger log = LoggerFactory.getLogger(RefundService.class);

  private final Ledger ledger;
  private final Postings postings;
  private final TripPayments tripPayments;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  RefundService(
      Ledger ledger, Postings postings, TripPayments tripPayments, JdbcTemplate jdbc, Clock clock) {
    this.ledger = ledger;
    this.postings = postings;
    this.tripPayments = tripPayments;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  private record Original(TransactionType type, UUID tripId) {}

  @Override
  public Refund refund(UUID transactionId, UUID refundedBy) {
    Refund refund =
        postings.run(
            () -> {
              Original original =
                  jdbc
                      .query(
                          "SELECT type, reference_id FROM ledger_transactions WHERE id = ?",
                          (row, index) ->
                              new Original(
                                  TransactionType.valueOf(row.getString("type")),
                                  row.getObject("reference_id", UUID.class)),
                          transactionId)
                      .stream()
                      .findFirst()
                      .orElseThrow(() -> BusinessException.notFound("Transaction not found"));
              if (!TransactionType.REFUNDABLE.contains(original.type())) {
                throw BusinessException.businessRule(
                    "NOT_REFUNDABLE", "Only trip charges and cancellation fees can be refunded");
              }

              List<Leg> mirrored =
                  jdbc.query(
                      """
                      SELECT a.owner_id, a.account_type, e.direction, e.amount_paise
                        FROM ledger_entries e
                        JOIN ledger_accounts a ON a.id = e.account_id
                       WHERE e.transaction_id = ?""",
                      (row, index) ->
                          new Leg(
                              new AccountKey(
                                  row.getObject("owner_id", UUID.class),
                                  AccountType.valueOf(row.getString("account_type"))),
                              Direction.valueOf(row.getString("direction")).opposite(),
                              Money.ofPaise(row.getLong("amount_paise"))),
                      transactionId);
              Ledger.Posted posted =
                  ledger
                      .post(TransactionType.REFUND, transactionId, mirrored)
                      .orElseThrow(
                          () ->
                              BusinessException.conflict(
                                  "ALREADY_REFUNDED",
                                  "This transaction has already been refunded"));

              tripPayments.recordPaymentStatus(original.tripId(), PaymentStatus.REFUNDED);
              // The refund may bring the wallet back into credit, which settles anything else
              // that was waiting on it.
              mirrored.stream()
                  .map(Leg::account)
                  .filter(account -> account.type() == AccountType.RIDER_WALLET)
                  .filter(wallet -> posted.balanceOf(wallet) >= 0)
                  .findFirst()
                  .ifPresent(wallet -> tripPayments.settleOutstanding(wallet.ownerId()));

              long amount =
                  mirrored.stream()
                      .filter(leg -> leg.direction() == Direction.CREDIT)
                      .mapToLong(leg -> leg.amount().paise())
                      .sum();
              return new Refund(posted.transactionId(), transactionId, amount, clock.instant());
            });
    log.info(
        "Admin {} refunded transaction {} as {}",
        refundedBy,
        transactionId,
        refund.refundTransactionId());
    return refund;
  }
}
