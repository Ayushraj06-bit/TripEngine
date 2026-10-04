package com.rideflow.trip.internal;

import com.rideflow.trip.FlaggedTrips;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flagged trips for admin review, newest first. Ordered by id, which is a UUIDv7 and so already
 * chronological: the cursor is just the last id seen, as for the admin driver list.
 */
@Component
class FlaggedTripQueries implements FlaggedTrips {

  private final TripRepository trips;

  FlaggedTripQueries(TripRepository trips) {
    this.trips = trips;
  }

  @Override
  @Transactional(readOnly = true)
  public Page list(UUID cursor, int limit) {
    Limit fetch = Limit.of(limit + 1);
    List<Trip> page =
        cursor == null
            ? trips.findByFlaggedReasonIsNotNullOrderByIdDesc(fetch)
            : trips.findByFlaggedReasonIsNotNullAndIdLessThanOrderByIdDesc(cursor, fetch);
    boolean more = page.size() > limit;
    List<Trip> shown = more ? page.subList(0, limit) : page;
    return new Page(shown.stream().map(Trip::flagged).toList(), more ? shown.getLast().id() : null);
  }
}
