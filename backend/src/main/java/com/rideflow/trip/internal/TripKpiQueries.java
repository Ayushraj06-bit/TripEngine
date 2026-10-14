package com.rideflow.trip.internal;

import com.rideflow.trip.TripKpis;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Two aggregate queries, every two seconds. The active count reads the one-active-trip-per-rider
 * index, whose condition is exactly "active"; the window reads {@code trips_requested_at_idx}.
 */
@Component
class TripKpiQueries implements TripKpis {

  private static final String ACTIVE =
      "SELECT count(*) FROM trips"
          + " WHERE status IN ('REQUESTED', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS')";

  private static final String WINDOW =
      """
      SELECT count(*) AS requested,
             count(*) FILTER (WHERE status = 'NO_DRIVER_FOUND') AS no_driver,
             count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
             avg(EXTRACT(EPOCH FROM assigned_at - requested_at)) AS seconds_to_assign
      FROM trips WHERE requested_at >= ?""";

  private final JdbcTemplate jdbc;
  private final Clock clock;

  TripKpiQueries(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public Kpis over(Duration window) {
    long active = jdbc.queryForObject(ACTIVE, Long.class);
    OffsetDateTime since = OffsetDateTime.ofInstant(clock.instant().minus(window), ZoneOffset.UTC);
    return jdbc.queryForObject(
        WINDOW,
        (row, index) -> {
          long requested = row.getLong("requested");
          double toAssign = row.getDouble("seconds_to_assign");
          return new Kpis(
              active,
              requested,
              requested / (window.toSeconds() / 60.0),
              row.wasNull() ? null : toAssign,
              share(row.getLong("no_driver"), requested),
              share(row.getLong("cancelled"), requested));
        },
        since);
  }

  private static double share(long part, long whole) {
    return whole == 0 ? 0 : (double) part / whole;
  }
}
