package com.rideflow.payment.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Money a rider is adding to their wallet through the gateway: PENDING until the gateway's webhook
 * says SUCCEEDED or FAILED.
 *
 * <p>Read through JPA; written by {@link TopupService} with statements that each make one change
 * only if the row is still as expected - so a race or a redelivered webhook changes nothing twice.
 */
@Entity
@Table(name = "topups")
class Topup {

  @Id private UUID id;
  private UUID userId;
  private long amountPaise;

  @Enumerated(EnumType.STRING)
  private TopupStatus status;

  private String gatewayRef;
  private String idempotencyKey;
  private Instant createdAt;
  private Instant completedAt;

  protected Topup() {} // JPA

  UUID id() {
    return id;
  }

  UUID userId() {
    return userId;
  }

  long amountPaise() {
    return amountPaise;
  }

  TopupStatus status() {
    return status;
  }

  String gatewayRef() {
    return gatewayRef;
  }

  Instant createdAt() {
    return createdAt;
  }
}
