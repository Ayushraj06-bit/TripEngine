package com.rideflow.pricing.internal;

import com.rideflow.shared.Money;
import com.rideflow.shared.VehicleType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** The {@code rate_cards} row. Read-only: a price change is a new version, never an update. */
@Entity
@Table(name = "rate_cards")
class RateCardRow {

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  private VehicleType vehicleType;

  private int version;
  private long basePaise;
  private long perKmPaise;
  private long perMinPaise;
  private long minFarePaise;
  private long waitingPerMinPaise;
  private long cancellationFeePaise;
  private Instant effectiveFrom;

  protected RateCardRow() {} // JPA

  RateCard toRateCard() {
    return new RateCard(
        id,
        vehicleType,
        version,
        Money.ofPaise(basePaise),
        Money.ofPaise(perKmPaise),
        Money.ofPaise(perMinPaise),
        Money.ofPaise(minFarePaise),
        Money.ofPaise(waitingPerMinPaise),
        Money.ofPaise(cancellationFeePaise),
        effectiveFrom);
  }
}
