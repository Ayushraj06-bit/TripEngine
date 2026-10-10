package com.rideflow.payment.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A user's own account and its history: a rider's wallet, or a driver's earnings.
 *
 * <p>The balance is the account's cached balance, one row; the history is its entries, newest
 * first, a page at a time on {@code ledger_entries_account_idx}.
 */
@Service
public class WalletService {

  private final Ledger ledger;
  private final JdbcTemplate jdbc;

  WalletService(Ledger ledger, JdbcTemplate jdbc) {
    this.ledger = ledger;
    this.jdbc = jdbc;
  }

  /**
   * @param balancePaise below zero while a trip waits on a top-up
   * @param nextCursor pass back as {@code cursor} for the next page; null on the last
   */
  public record Wallet(
      AccountType account, long balancePaise, List<Movement> movements, UUID nextCursor) {}

  /** One entry on the account, with what it was for. */
  public record Movement(
      UUID entryId,
      UUID transactionId,
      TransactionType type,
      UUID referenceId,
      Direction direction,
      long amountPaise,
      Instant createdAt) {}

  /**
   * @param driver true for a driver's earnings, false for a rider's wallet
   * @param cursor the last entry id of the previous page, or null for the first page
   */
  @Transactional(readOnly = true)
  public Wallet of(UUID userId, boolean driver, UUID cursor, int limit) {
    AccountKey key = driver ? AccountKey.earnings(userId) : AccountKey.wallet(userId);
    var account = ledger.find(key);
    if (account.isEmpty()) {
      return new Wallet(key.type(), 0, List.of(), null);
    }

    UUID accountId = account.get().id();
    String page =
        """
        SELECT e.id, e.transaction_id, t.type, t.reference_id, e.direction, e.amount_paise,
               t.created_at
          FROM ledger_entries e
          JOIN ledger_transactions t ON t.id = e.transaction_id
         WHERE e.account_id = ? %s
         ORDER BY e.id DESC
         LIMIT ?""";
    // Ids are UUIDv7: "older than the cursor" is "smaller than it". A cursor from someone else's
    // account only says where to start in this one.
    List<Movement> movements =
        cursor == null
            ? jdbc.query(page.formatted(""), this::movement, accountId, limit + 1)
            : jdbc.query(
                page.formatted("AND e.id < ?"), this::movement, accountId, cursor, limit + 1);

    boolean more = movements.size() > limit;
    List<Movement> shown = more ? movements.subList(0, limit) : movements;
    return new Wallet(
        key.type(),
        account.get().balancePaise(),
        List.copyOf(shown),
        more ? shown.getLast().entryId() : null);
  }

  private Movement movement(java.sql.ResultSet row, int index) throws java.sql.SQLException {
    return new Movement(
        row.getObject("id", UUID.class),
        row.getObject("transaction_id", UUID.class),
        TransactionType.valueOf(row.getString("type")),
        row.getObject("reference_id", UUID.class),
        Direction.valueOf(row.getString("direction")),
        row.getLong("amount_paise"),
        row.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
