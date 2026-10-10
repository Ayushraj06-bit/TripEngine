package com.rideflow.payment.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface TopupRepository extends JpaRepository<Topup, UUID> {

  Optional<Topup> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

  Optional<Topup> findByGatewayRef(String gatewayRef);

  /** The gateway's reference, recorded once. */
  @Modifying
  @Query("update Topup t set t.gatewayRef = :gatewayRef where t.id = :id and t.gatewayRef is null")
  int recordGatewayRef(UUID id, String gatewayRef);

  /**
   * Settles a pending top-up. Of any number of deliveries of the gateway's answer, only one finds
   * it pending.
   *
   * @return 1 for the delivery that settled it, 0 for the rest
   */
  @Modifying
  @Query(
      "update Topup t set t.status = :status, t.completedAt = :at"
          + " where t.id = :id and t.status = com.rideflow.payment.internal.TopupStatus.PENDING")
  int settle(UUID id, TopupStatus status, Instant at);
}
