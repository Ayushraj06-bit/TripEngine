package com.rideflow.trip;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * Where the money for a trip stands changed: charged, waiting for the rider to top up, settled, or
 * refunded. Published by {@link TripPayments} in the transaction that moved the money.
 */
@Externalized(TripEvent.EXTERNALIZED)
public record TripPaymentStatusChanged(
    UUID eventId,
    Instant occurredAt,
    UUID tripId,
    UUID riderId,
    UUID driverId,
    PaymentStatus paymentStatus)
    implements TripEvent {}
