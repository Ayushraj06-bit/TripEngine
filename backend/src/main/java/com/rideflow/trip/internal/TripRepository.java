package com.rideflow.trip.internal;

import com.rideflow.trip.PaymentStatus;
import com.rideflow.trip.TripStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface TripRepository extends JpaRepository<Trip, UUID> {

  /** At most one, guaranteed by the partial unique index. */
  Optional<Trip> findFirstByRiderIdAndStatusIn(UUID riderId, Collection<TripStatus> statuses);

  Optional<Trip> findFirstByDriverIdAndStatusIn(UUID driverId, Collection<TripStatus> statuses);

  /**
   * History pages. Keyset on {@code (requested_at, id)}, newest first, so each page is an index
   * range scan on {@code trips_*_history_idx} however deep the rider has scrolled; an offset would
   * re-read every earlier page.
   */
  List<Trip> findByRiderIdOrderByRequestedAtDescIdDesc(UUID riderId, Limit limit);

  List<Trip> findByDriverIdOrderByRequestedAtDescIdDesc(UUID driverId, Limit limit);

  /**
   * Trips under way since before {@code startedBefore} and not yet flagged, longest-running first,
   * a window at a time, for the silence check: a keyset scan of {@code trips_in_progress_idx}.
   */
  Window<Trip> findByStatusAndFlaggedReasonIsNullAndStartedAtBeforeOrderByStartedAtAscIdAsc(
      TripStatus status, Instant startedBefore, ScrollPosition position, Limit limit);

  /**
   * Adds a reason to the trip's flag, unless it is already there. The only way the column is
   * written.
   *
   * <p>One conditional UPDATE. Two flaggers at once both keep their reason, a repeat changes
   * nothing, and the version is left alone - so the silence check flagging a trip never turns the
   * driver's own completion into a 409.
   *
   * @return 1 if the reason was added, 0 if it was already there or there is no such trip
   */
  @Modifying
  @Query(
      """
      update Trip t
      set t.flaggedReason =
          case when t.flaggedReason is null then :reason
               else concat(t.flaggedReason, ' | ', :reason) end
      where t.id = :id
        and (t.flaggedReason is null or locate(:reason, t.flaggedReason) = 0)
      """)
  int flag(UUID id, String reason);

  /**
   * Sets the trip's payment status, if it is not that already. The only way the column changes; the
   * version is left alone (see {@link TripPaymentRecorder}).
   *
   * @return 1 if it changed
   */
  @Modifying
  @Query(
      "update Trip t set t.paymentStatus = :status where t.id = :id and t.paymentStatus <> :status")
  int setPaymentStatus(UUID id, PaymentStatus status);

  /** A partial-index lookup on {@code trips_outstanding_idx}. */
  List<Trip> findByRiderIdAndPaymentStatus(UUID riderId, PaymentStatus paymentStatus);

  boolean existsByRiderIdAndPaymentStatus(UUID riderId, PaymentStatus paymentStatus);

  /** Flagged trips, newest first: an index scan on {@code trips_flagged_idx}. */
  List<Trip> findByFlaggedReasonIsNotNullOrderByIdDesc(Limit limit);

  List<Trip> findByFlaggedReasonIsNotNullAndIdLessThanOrderByIdDesc(UUID cursor, Limit limit);

  @Query(
      """
      select t from Trip t
      where t.riderId = :riderId
        and (t.requestedAt < :requestedAt or (t.requestedAt = :requestedAt and t.id < :id))
      order by t.requestedAt desc, t.id desc""")
  List<Trip> findRiderHistoryAfter(UUID riderId, Instant requestedAt, UUID id, Limit limit);

  @Query(
      """
      select t from Trip t
      where t.driverId = :driverId
        and (t.requestedAt < :requestedAt or (t.requestedAt = :requestedAt and t.id < :id))
      order by t.requestedAt desc, t.id desc""")
  List<Trip> findDriverHistoryAfter(UUID driverId, Instant requestedAt, UUID id, Limit limit);
}
