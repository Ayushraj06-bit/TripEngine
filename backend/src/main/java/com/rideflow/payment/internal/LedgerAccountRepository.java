package com.rideflow.payment.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

  Optional<LedgerAccount> findByOwnerTypeAndOwnerIdAndAccountType(
      AccountType.OwnerType ownerType, UUID ownerId, AccountType accountType);
}
