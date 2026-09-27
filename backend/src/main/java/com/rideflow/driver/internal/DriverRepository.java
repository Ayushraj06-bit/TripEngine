package com.rideflow.driver.internal;

import com.rideflow.driver.ApprovalStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
