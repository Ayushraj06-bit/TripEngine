package com.rideflow.payment.internal;

import com.rideflow.shared.IdGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts double-entry transactions (ADR 0012): one {@code ledger_transactions} row, its entries, and
 * the balance of every account it touches, in the caller's transaction.
 *
 * <p>Idempotent by type and reference. The transaction row is inserted first, with {@code ON
 * CONFLICT DO NOTHING}: a second posting for the same trip, top-up or refund inserts nothing and
 * returns empty, and the caller knows it is a repeat. A unique-violation exception would abort the
 * whole database transaction instead; this way the caller carries on.
 *
 * <p>The database checks what this checks, at commit (V11): entries are never changed, and every
 * transaction balances.
 *
 * <p>Every account a posting touches is row-locked before its balance is read, always in {@link
 * #LOCK_ORDER}. Two postings sharing an account - every top-up shares the platform's clearing
 * account - queue on its lock instead of both reading one balance and one failing at commit, and
 * the one order means two postings can never each hold what the other waits for.
 */
@Component
class Ledger {

  /** By account type, then by owner: the same for every posting. */
  static final Comparator<AccountKey> LOCK_ORDER =
      Comparator.comparing(AccountKey::type).thenComparing(AccountKey::ownerId);

  private final LedgerAccountRepository accounts;
  private final JdbcTemplate jdbc;
  private final IdGenerator ids;
  private final Clock clock;

  Ledger(LedgerAccountRepository accounts, JdbcTemplate jdbc, IdGenerator ids, Clock clock) {
    this.accounts = accounts;
    this.jdbc = jdbc;
    this.ids = ids;
    this.clock = clock;
  }

  /**
   * What a posting did.
   *
   * @param balances every touched account's balance afterwards
   */
  record Posted(UUID transactionId, Map<AccountKey, Long> balances) {

    long balanceOf(AccountKey account) {
      Long balance = balances.get(account);
      if (balance == null) {
        throw new IllegalArgumentException(account + " was not part of this transaction");
      }
      return balance;
    }
  }

  /**
   * @return the posting, or empty if a transaction of this type for this reference already exists
   * @throws IllegalArgumentException for fewer than two legs, or legs that do not balance
   */
  @Transactional(propagation = Propagation.MANDATORY)
  Optional<Posted> post(TransactionType type, UUID referenceId, List<Leg> legs) {
    requireBalanced(legs);
    UUID transactionId = ids.newId();
    int inserted =
        jdbc.update(
            """
            INSERT INTO ledger_transactions (id, type, reference_id, created_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (type, reference_id) DO NOTHING""",
            transactionId,
            type.name(),
            referenceId,
            OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    if (inserted == 0) {
      return Optional.empty();
    }

    // Every account locked before any balance changes, in the one order: a lookup is a query, and
    // a query would flush a changed balance early, out of the primary-key order kept at commit.
    Map<AccountKey, LedgerAccount> touched = new LinkedHashMap<>();
    legs.stream()
        .map(Leg::account)
        .distinct()
        .sorted(LOCK_ORDER)
        .forEach(key -> touched.put(key, lockedAccount(key)));
    for (Leg leg : legs) {
      LedgerAccount account = touched.get(leg.account());
      jdbc.update(
          """
          INSERT INTO ledger_entries (id, transaction_id, account_id, direction, amount_paise)
          VALUES (?, ?, ?, ?, ?)""",
          ids.newId(),
          transactionId,
          account.id(),
          leg.direction().name(),
          leg.amount().paise());
      account.apply(leg.direction(), leg.amount().paise());
    }

    Map<AccountKey, Long> balances = new LinkedHashMap<>();
    touched.forEach((key, account) -> balances.put(key, account.balancePaise()));
    return Optional.of(new Posted(transactionId, Map.copyOf(balances)));
  }

  /** The account, if it has ever been used. */
  Optional<LedgerAccount> find(AccountKey key) {
    return accounts.findByOwnerTypeAndOwnerIdAndAccountType(
        key.type().ownerType(), key.ownerId(), key.type());
  }

  /** The account, opened on first use and locked. Two first uses at once open it once. */
  private LedgerAccount lockedAccount(AccountKey key) {
    return lock(key)
        .orElseGet(
            () -> {
              jdbc.update(
                  """
                  INSERT INTO ledger_accounts (id, owner_type, owner_id, account_type)
                  VALUES (?, ?, ?, ?)
                  ON CONFLICT (owner_type, owner_id, account_type) DO NOTHING""",
                  ids.newId(),
                  key.type().ownerType().name(),
                  key.ownerId(),
                  key.type().name());
              return lock(key).orElseThrow();
            });
  }

  private Optional<LedgerAccount> lock(AccountKey key) {
    return accounts.findForUpdateByOwnerTypeAndOwnerIdAndAccountType(
        key.type().ownerType(), key.ownerId(), key.type());
  }

  private static void requireBalanced(List<Leg> legs) {
    if (legs.size() < 2) {
      throw new IllegalArgumentException("A transaction needs two entries at least: " + legs);
    }
    long net = 0;
    for (Leg leg : legs) {
      if (!leg.amount().isPositive()) {
        throw new IllegalArgumentException("Every entry moves some money: " + legs);
      }
      long paise = leg.amount().paise();
      net =
          leg.direction() == Direction.CREDIT
              ? Math.addExact(net, paise)
              : Math.subtractExact(net, paise);
    }
    if (net != 0) {
      throw new IllegalArgumentException("Debits and credits differ by " + net + ": " + legs);
    }
  }
}
