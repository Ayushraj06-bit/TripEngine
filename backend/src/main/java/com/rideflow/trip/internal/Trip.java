package com.rideflow.trip.internal;

import com.rideflow.pricing.ConsumedQuote;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.Money;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.ActiveTrip;
import com.rideflow.trip.FlaggedTrips;
import com.rideflow.trip.PaymentMethod;
import com.rideflow.trip.TripActor;
import com.rideflow.trip.TripStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The trip aggregate.
 *
 * <p>Its methods make the change a transition implies; they do not decide whether it is allowed.
 * That is {@link TripStateMachine}'s job, and {@link TripService} consults it before calling any of
 * them.
 */
@Entity
@Table(name = "trips")
class Trip {

  @Id private UUID id;
  private UUID riderId;
  private UUID driverId;

  @Enumerated(EnumType.STRING)
  private VehicleType vehicleType;

  @Enumerated(EnumType.STRING)
  private TripStatus status;

  private double pickupLat;
  private double pickupLng;
  private double dropLat;
  private double dropLng;
  private long quotedFarePaise;
  private BigDecimal surgeMultiplier;
  private UUID rateCardId;

  @Enumerated(EnumType.STRING)
  private PaymentMethod paymentMethod;

  @Enumerated(EnumType.STRING)
  private PaymentStatus paymentStatus;

  private String otp;
  private int otpAttempts;
  private Instant otpLockedUntil;
  private Long finalFarePaise;

  @Column(name = "distance_m")
  private long distanceMeters;

  @Column(name = "duration_s")
  private long durationSeconds;

  @Enumerated(EnumType.STRING)
  private TripActor cancelledBy;

  private String cancelReason;
  private Long cancellationFeePaise;
  private Instant requestedAt;
  private Instant assignedAt;
  private Instant arrivedAt;
  private Instant startedAt;
  private Instant completedAt;
  private Instant cancelledAt;
  private Instant noDriverFoundAt;

  /** Why a human should look at this trip; null for almost every trip. The first reason stands. */
  private String flaggedReason;

  /**
   * Optimistic locking: two changes racing on one trip cannot both win. A wrapper type, so a new
   * trip is recognised as new and inserted without a pointless select first.
   */
  @Version private Long version;

  protected Trip() {} // JPA

  static Trip request(
      UUID id, UUID riderId, ConsumedQuote quote, PaymentMethod paymentMethod, Instant now) {
    Trip trip = new Trip();
    trip.id = id;
    trip.riderId = riderId;
    trip.vehicleType = quote.vehicleType();
    trip.status = TripStatus.REQUESTED;
    trip.pickupLat = quote.pickup().latitude();
    trip.pickupLng = quote.pickup().longitude();
    trip.dropLat = quote.drop().latitude();
    trip.dropLng = quote.drop().longitude();
    trip.quotedFarePaise = quote.fare().total().paise();
    trip.surgeMultiplier = quote.surgeMultiplier();
    trip.rateCardId = quote.rateCardId();
    trip.paymentMethod = paymentMethod;
    trip.paymentStatus = PaymentStatus.UNPAID;
    trip.distanceMeters = quote.distanceMeters();
    trip.durationSeconds = quote.durationSeconds();
    trip.requestedAt = now;
    return trip;
  }

  void assign(UUID driverId, String otp, Instant now) {
    this.driverId = driverId;
    this.otp = otp;
    this.otpAttempts = 0;
    this.otpLockedUntil = null;
    this.assignedAt = now;
    this.status = TripStatus.DRIVER_ASSIGNED;
  }

  void arrive(Instant now) {
    this.arrivedAt = now;
    this.status = TripStatus.DRIVER_ARRIVED;
  }

  void start(Instant now) {
    clearOtp();
    this.startedAt = now;
    this.status = TripStatus.IN_PROGRESS;
  }

  /**
   * Settles the trip. Distance and duration were the quote's estimate until now; from here on they
   * are what actually happened.
   */
  void complete(Instant now, FinalFares.FinalFare fare) {
    this.finalFarePaise = fare.farePaise();
    this.distanceMeters = fare.distanceMeters();
    this.durationSeconds = fare.durationSeconds();
    if (fare.flag() != null) {
      flag(fare.flag());
    }
    this.completedAt = now;
    this.status = TripStatus.COMPLETED;
  }

  /**
   * Raises the trip for review. The first reason stands: a later one must not overwrite the
   * evidence of what was noticed first.
   *
   * @return true if this call flagged it
   */
  boolean flag(String reason) {
    if (flaggedReason != null) {
      return false;
    }
    this.flaggedReason = reason;
    return true;
  }

  ActiveTrip activeTrip() {
    return new ActiveTrip(id, riderId, status, pickup(), drop());
  }

  FlaggedTrips.FlaggedTrip flagged() {
    return new FlaggedTrips.FlaggedTrip(
        id,
        status,
        riderId,
        driverId,
        flaggedReason,
        quotedFarePaise,
        finalFarePaise,
        distanceMeters,
        durationSeconds,
        requestedAt,
        completedAt);
  }

  void cancel(TripActor by, String reason, Money fee, Instant now) {
    clearOtp();
    this.cancelledBy = by;
    this.cancelReason = reason;
    this.cancellationFeePaise = fee.paise();
    this.cancelledAt = now;
    this.status = TripStatus.CANCELLED;
  }

  /**
   * The driver backed out before arriving: the trip needs a driver again.
   *
   * @return the driver who backed out
   */
  UUID returnToMatching() {
    UUID previous = driverId;
    clearOtp();
    this.driverId = null;
    this.assignedAt = null;
    this.status = TripStatus.REQUESTED;
    return previous;
  }

  void noDriverFound(Instant now) {
    this.noDriverFoundAt = now;
    this.status = TripStatus.NO_DRIVER_FOUND;
  }

  boolean isOtpLockedAt(Instant now) {
    return otpLockedUntil != null && now.isBefore(otpLockedUntil);
  }

  /** Constant time, as any comparison of a secret should be. */
  boolean otpMatches(String candidate) {
    return otp != null
        && candidate != null
        && MessageDigest.isEqual(
            otp.getBytes(StandardCharsets.US_ASCII), candidate.getBytes(StandardCharsets.US_ASCII));
  }

  /**
   * Counts a wrong OTP. The last allowed attempt locks the OTP for {@code lock} and starts the
   * count again, so each lock is followed by a fresh set of attempts.
   *
   * @return true when this attempt caused the lock
   */
  boolean recordWrongOtp(Instant now, int maxAttempts, Duration lock) {
    otpAttempts++;
    if (otpAttempts < maxAttempts) {
      return false;
    }
    otpAttempts = 0;
    otpLockedUntil = now.plus(lock);
    return true;
  }

  private void clearOtp() {
    this.otp = null;
    this.otpAttempts = 0;
    this.otpLockedUntil = null;
  }

  TripView view(TripActor viewer) {
    return new TripView(
        id,
        status,
        vehicleType,
        new TripView.Point(pickupLat, pickupLng),
        new TripView.Point(dropLat, dropLng),
        quotedFarePaise,
        // numeric(8, 4) reads back as 1.0000; show the value the rider was quoted.
        surgeMultiplier.stripTrailingZeros(),
        paymentMethod,
        paymentStatus,
        riderId,
        driverId,
        viewer == TripActor.RIDER ? otp : null,
        finalFarePaise,
        cancellationFeePaise,
        cancelledBy,
        cancelReason,
        distanceMeters,
        durationSeconds,
        requestedAt,
        assignedAt,
        arrivedAt,
        startedAt,
        completedAt,
        cancelledAt,
        noDriverFoundAt);
  }

  UUID id() {
    return id;
  }

  UUID riderId() {
    return riderId;
  }

  UUID driverId() {
    return driverId;
  }

  TripStatus status() {
    return status;
  }

  VehicleType vehicleType() {
    return vehicleType;
  }

  GeoPoint pickup() {
    return new GeoPoint(pickupLat, pickupLng);
  }

  GeoPoint drop() {
    return new GeoPoint(dropLat, dropLng);
  }

  long quotedFarePaise() {
    return quotedFarePaise;
  }

  BigDecimal surgeMultiplier() {
    return surgeMultiplier;
  }

  UUID rateCardId() {
    return rateCardId;
  }

  PaymentMethod paymentMethod() {
    return paymentMethod;
  }

  Long finalFarePaise() {
    return finalFarePaise;
  }

  Instant requestedAt() {
    return requestedAt;
  }

  Instant assignedAt() {
    return assignedAt;
  }

  Instant arrivedAt() {
    return arrivedAt;
  }

  Instant startedAt() {
    return startedAt;
  }

  long distanceMeters() {
    return distanceMeters;
  }

  String flaggedReason() {
    return flaggedReason;
  }

  Instant otpLockedUntil() {
    return otpLockedUntil;
  }

  int otpAttempts() {
    return otpAttempts;
  }
}
