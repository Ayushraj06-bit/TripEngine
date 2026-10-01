package com.rideflow.trip.internal;

import com.rideflow.trip.TripActor;
import com.rideflow.trip.TripStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;

/**
 * One row of the {@code trip_events} audit log. Written once, never changed: {@link Immutable}
 * keeps Hibernate from ever updating it, and a trigger rejects anything that tries.
 */
@Entity
@Table(name = "trip_events")
@Immutable
class TripEventRow {

  @Id private UUID id;
  private UUID tripId;

  @Enumerated(EnumType.STRING)
  private TripStatus fromStatus;

  @Enumerated(EnumType.STRING)
  private TripStatus toStatus;

  @Enumerated(EnumType.STRING)
  private TripActor actorType;

  private UUID actorId;
  private Instant occurredAt;

  /**
   * JSON. Written with a cast, so no JSON type mapping is needed for a column only ever written.
   */
  @ColumnTransformer(write = "?::jsonb")
  private String metadata;

  protected TripEventRow() {} // JPA

  TripEventRow(
      UUID id,
      UUID tripId,
      TripStatus fromStatus,
      TripStatus toStatus,
      TripActor actorType,
      UUID actorId,
      Instant occurredAt,
      String metadata) {
    this.id = id;
    this.tripId = tripId;
    this.fromStatus = fromStatus;
    this.toStatus = toStatus;
    this.actorType = actorType;
    this.actorId = actorId;
    this.occurredAt = occurredAt;
    this.metadata = metadata;
  }
}
