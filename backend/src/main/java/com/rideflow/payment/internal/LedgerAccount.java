package com.rideflow.payment.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/**
 * A ledger account and its balance.
 *
 * <p>The balance is a cache of the account's entries, for fast reads, changed in the same
 * transaction as the entries are written. Under the version: two postings that read the same
 * balance cannot both write theirs, so a top-up and a charge landing at once both count.
 *
 * <p>Created by {@link Ledger} with an insert that tolerates a race; JPA only reads and updates it.
 */
@Entity
@Table(name = "ledger_accounts")
class LedgerAccount {

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  private AccountType.OwnerType ownerType;

  private UUID ownerId;

  @Enumerated(EnumType.STRING)
  private AccountType accountType;

  private long balancePaise;

  @Version private long version;

  protected LedgerAccount() {} // JPA

  UUID id() {
    return id;
  }

  long balancePaise() {
    return balancePaise;
  }

  /** Credits add, debits subtract. Overflow throws rather than wrapping. */
  void apply(Direction direction, long amountPaise) {
    balancePaise =
        direction == Direction.CREDIT
            ? Math.addExact(balancePaise, amountPaise)
            : Math.subtractExact(balancePaise, amountPaise);
  }
}
