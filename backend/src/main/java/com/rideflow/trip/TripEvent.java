package com.rideflow.trip;

import java.time.Instant;
import java.util.UUID;

/**
 * Something that happened to a trip.
 *
 * <p>Each one is published in the transaction that made the change, next to its row in {@code
 * trip_events}, so the Modulith registry stores it in the same commit (the outbox, PROJECT_SPEC
 * section 5). Every event is externalised to {@value #TOPIC} keyed by trip id, so all the events of
 * one trip land on one partition, in order. The catalogue is {@code docs/events.md}.
 *
 * <p>No event carries the OTP: they are stored in the database and copied to Kafka.
 */
public sealed interface TripEvent
    permits TripRequested,
        TripReturnedToMatching,
        DriverAssigned,
        DriverArrived,
        TripStarted,
        TripCompleted,
        TripCancelled,
        TripNoDriverFound {

  String TOPIC = "rideflow.trip-events";

  /** For {@code @Externalized}: the topic, then the routing key. */
  String EXTERNALIZED = TOPIC + "::#{#this.tripId()}";

  /** Unique per event, so a consumer can discard a redelivery. */
  UUID eventId();

  Instant occurredAt();

  UUID tripId();
}
