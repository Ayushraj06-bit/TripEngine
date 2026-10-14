package com.rideflow.payment.internal;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

  Optional<LedgerAccount> findByOwnerTypeAndOwnerIdAndAccountType(
      AccountType.OwnerType ownerType, UUID ownerId, AccountType accountType);

  /** The same, row-locked until the transaction ends: for a posting about to change it. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<LedgerAccount> findForUpdateByOwnerTypeAndOwnerIdAndAccountType(
      AccountType.OwnerType ownerType, UUID ownerId, AccountType accountType);
}
