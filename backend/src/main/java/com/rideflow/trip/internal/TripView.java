package com.rideflow.trip.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.PaymentMethod;
import com.rideflow.trip.TripActor;
import com.rideflow.trip.TripStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A trip as its rider or driver sees it. Amounts are in paise.
 *
 * @param otp only in the rider's own view, and only between assignment and start. The rider reads
 *     it out to the driver; a driver who could see it could start the trip without the rider.
 *     Omitted from the JSON entirely when absent.
 */
public record TripView(
    UUID id,
    TripStatus status,
    VehicleType vehicleType,
    Point pickup,
    Point drop,
    long quotedFarePaise,
    BigDecimal surgeMultiplier,
    PaymentMethod paymentMethod,
    PaymentStatus paymentStatus,
    UUID riderId,
    UUID driverId,
    @JsonInclude(JsonInclude.Include.NON_NULL) String otp,
    Long finalFarePaise,
    Long cancellationFeePaise,
    TripActor cancelledBy,
    String cancelReason,
    long distanceMeters,
    long durationSeconds,
    Instant requestedAt,
    Instant assignedAt,
    Instant arrivedAt,
    Instant startedAt,
    Instant completedAt,
    Instant cancelledAt,
    Instant noDriverFoundAt) {

  public record Point(double lat, double lng) {}

  /** Without the OTP, so logging a view can never leak one (PROJECT_SPEC section 11). */
  @Override
  public String toString() {
    return "TripView[id=%s, status=%s]".formatted(id, status);
  }
}
