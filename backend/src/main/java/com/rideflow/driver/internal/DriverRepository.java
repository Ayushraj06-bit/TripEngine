package com.rideflow.driver.internal;

import com.rideflow.driver.ApprovalStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface DriverRepository extends JpaRepository<Driver, UUID> {

  /**
   * First page of a cursor-paginated listing.
   *
   * <p>Ordered by the primary key, which is a UUIDv7 and therefore already chronological, so the
   * cursor is simply the last id seen and no composite key or opaque encoding is needed.
   */
  List<Driver> findByApprovalStatusOrderByUserIdAsc(ApprovalStatus approvalStatus, Limit limit);

  /** Subsequent pages: everything after the cursor. */
  List<Driver> findByApprovalStatusAndUserIdGreaterThanOrderByUserIdAsc(
      ApprovalStatus approvalStatus, UUID cursor, Limit limit);

  Optional<Driver> findByUserId(UUID userId);

  /** Locked until the transaction ends: two changes to one driver's status take turns. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from Driver d where d.userId = :driverId")
  Optional<Driver> findForUpdate(UUID driverId);

  @Modifying
  @Query("update Driver d set d.offersReceived = d.offersReceived + 1 where d.userId = :driverId")
  int incrementOffersReceived(UUID driverId);

  /**
   * Guarded so accepted never exceeds received, which the table's CHECK enforces: an offer whose
   * "sent" count was lost to a failure goes uncounted rather than failing the acceptance.
   */
  @Modifying
  @Query(
      "update Driver d set d.offersAccepted = d.offersAccepted + 1"
          + " where d.userId = :driverId and d.offersAccepted < d.offersReceived")
  int incrementOffersAccepted(UUID driverId);

  @Modifying
  @Query(
      "update Driver d set d.ratingSum = d.ratingSum + :stars, d.ratingCount = d.ratingCount + 1"
          + " where d.userId = :driverId")
  int addRating(UUID driverId, int stars);

  @Modifying
  @Query(
      "update Driver d set d.offersAccepted = d.offersAccepted - 1"
          + " where d.userId = :driverId and d.offersAccepted > 0")
  int decrementOffersAccepted(UUID driverId);
}
