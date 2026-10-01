package com.rideflow.trip.internal;

import com.rideflow.trip.TripStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
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
