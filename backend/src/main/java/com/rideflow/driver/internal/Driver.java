package com.rideflow.driver.internal;

import com.rideflow.driver.ApprovalStatus;
import com.rideflow.driver.CandidateFacts;
import com.rideflow.driver.DriverStatus;
import com.rideflow.shared.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.http.HttpStatus;

@Entity
@Table(name = "drivers")
// Updates write only the columns that changed. Offer counts are incremented by separate
// UPDATE statements; a status change writing every column back from its stale copy would
// silently undo them.
@DynamicUpdate
class Driver {

  /** The driver is the user; there is no separate driver id. */
  @Id
  @Column(name = "user_id")
  private UUID userId;

  @Enumerated(EnumType.STRING)
  private ApprovalStatus approvalStatus;

  @Enumerated(EnumType.STRING)
  private DriverStatus status;

  private int ratingSum;
  private int ratingCount;
  private int offersReceived;
  private int offersAccepted;
  private String rejectionReason;
  private String flaggedReason;
  private Instant flaggedAt;
  private Instant approvalChangedAt;
  private Instant lastStatusChangeAt;

  /**
   * Optimistic locking. PROJECT_SPEC section 8 makes the database the final guard on assignment,
   * and two concurrent requests to go online or take a trip must not both win.
   */
  @Version private long version;

  protected Driver() {} // JPA

  Driver(UUID userId, Instant now) {
    this.userId = userId;
    this.approvalStatus = ApprovalStatus.PENDING;
    this.status = DriverStatus.OFFLINE;
    this.lastStatusChangeAt = now;
  }

  UUID userId() {
    return userId;
  }

  ApprovalStatus approvalStatus() {
    return approvalStatus;
  }

  DriverStatus status() {
    return status;
  }

  /** Moves forward with every change to the row, once it is flushed. */
  long version() {
    return version;
  }

  String rejectionReason() {
    return rejectionReason;
  }

  int ratingCount() {
    return ratingCount;
  }

  /** Average rating, or empty until the driver has been rated at all. */
  java.util.OptionalDouble averageRating() {
    return ratingCount == 0
        ? java.util.OptionalDouble.empty()
        : java.util.OptionalDouble.of((double) ratingSum / ratingCount);
  }

  /**
   * Raises this driver for admin review.
   *
   * <p>Keeps the first reason and timestamp: a repeat offender must not overwrite the evidence of
   * the original offence, and an admin wants to know when it started.
   *
   * @return true when this call is what flagged them
   */
  boolean flagForReview(String reason, Instant now) {
    if (flaggedReason != null) {
      return false;
    }
    this.flaggedReason = reason;
    this.flaggedAt = now;
    return true;
  }

  String flaggedReason() {
    return flaggedReason;
  }

  CandidateFacts candidateFacts() {
    return new CandidateFacts(
        userId,
        ratingCount == 0 ? null : (double) ratingSum / ratingCount,
        offersReceived,
        offersAccepted,
        lastStatusChangeAt);
  }

  void approve(Instant now) {
    this.approvalStatus = ApprovalStatus.APPROVED;
    // Cleared, because the CHECK constraint forbids a reason on anything but REJECTED.
    this.rejectionReason = null;
    this.approvalChangedAt = now;
  }

  /**
   * @return true when rejection forced the driver offline, so the map can be updated
   */
  boolean reject(String reason, Instant now) {
    this.approvalStatus = ApprovalStatus.REJECTED;
    this.rejectionReason = reason;
    this.approvalChangedAt = now;
    // A rejected driver must not stay on the map.
    return changeStatus(DriverStatus.OFFLINE, now);
  }

  /**
   * Moves the driver to AVAILABLE.
   *
   * @return true when the status actually moved, so the caller knows whether to publish an event
   * @throws BusinessException 422 unless the driver is approved and has a vehicle
   */
  boolean goOnline(boolean hasVehicle, Instant now) {
    if (approvalStatus != ApprovalStatus.APPROVED) {
      throw new BusinessException(
          "DRIVER_NOT_APPROVED",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "Your account is " + approvalStatus + ". An admin must approve it before you go online.");
    }
    if (!hasVehicle) {
      throw new BusinessException(
          "DRIVER_HAS_NO_VEHICLE",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "Add a vehicle before going online");
    }
    if (status == DriverStatus.ON_TRIP) {
      throw new BusinessException(
          "DRIVER_ON_TRIP", HttpStatus.CONFLICT, "You are already on a trip");
    }
    return changeStatus(DriverStatus.AVAILABLE, now);
  }

  /**
   * Moves the driver to OFFLINE.
   *
   * @return true when the status actually moved
   * @throws BusinessException 409 while on a trip, per PROJECT_SPEC section 6
   */
  boolean goOffline(Instant now) {
    if (status == DriverStatus.ON_TRIP) {
      throw new BusinessException(
          "DRIVER_ON_TRIP",
          HttpStatus.CONFLICT,
          "You cannot go offline during a trip. Complete it first.");
    }
    return changeStatus(DriverStatus.OFFLINE, now);
  }

  /**
   * The driver has a trip. From any status: a driver with a trip is on it, even if a request to go
   * offline slipped in just before the assignment landed.
   *
   * @return true when the status actually moved
   */
  boolean startTrip(Instant now) {
    return changeStatus(DriverStatus.ON_TRIP, now);
  }

  /**
   * The driver has no trip any more: back to taking work. Only from ON_TRIP, so it can never put an
   * offline driver back on the map.
   *
   * @return true when the status actually moved
   */
  boolean endTrip(Instant now) {
    return status == DriverStatus.ON_TRIP && changeStatus(DriverStatus.AVAILABLE, now);
  }

  /** True when the status actually moved, so the caller knows whether to publish an event. */
  private boolean changeStatus(DriverStatus next, Instant now) {
    if (status == next) {
      return false;
    }
    this.status = next;
    this.lastStatusChangeAt = now;
    return true;
  }
}
