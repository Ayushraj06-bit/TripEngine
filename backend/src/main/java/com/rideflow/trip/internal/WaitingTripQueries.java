package com.rideflow.trip.internal;

import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.WaitingTrip;
import com.rideflow.trip.WaitingTrips;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads for {@code matching}. Plain SQL, because "waiting since" comes from the audit log: the
 * latest time the trip moved into REQUESTED, which is the booking or a driver backing out. That
 * needs no extra column, and it cannot drift from what actually happened.
 */
@Component
class WaitingTripQueries implements WaitingTrips {

  private static final String SELECT =
      """
      SELECT t.id, t.rider_id, t.vehicle_type, t.pickup_lat, t.pickup_lng, t.quoted_fare_paise,
             COALESCE((SELECT max(e.occurred_at) FROM trip_events e
                       WHERE e.trip_id = t.id AND e.to_status = 'REQUESTED'),
                      t.requested_at) AS waiting_since
      FROM trips t
      WHERE t.status = 'REQUESTED'
      """;

  private final JdbcTemplate jdbc;

  WaitingTripQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<WaitingTrip> waiting(UUID tripId) {
    return jdbc.query(SELECT + " AND t.id = ?", WaitingTripQueries::row, tripId).stream()
        .findFirst();
  }

  @Override
  public List<WaitingTrip> waitingSince(Instant cutoff, int limit) {
    // requested_at <= waiting_since, so filtering on it first lets trips_waiting_idx
    // narrow the scan before the audit log is consulted.
    return jdbc.query(
        "SELECT * FROM ("
            + SELECT
            + " AND t.requested_at < ?) w WHERE w.waiting_since < ? ORDER BY w.waiting_since LIMIT ?",
        WaitingTripQueries::row,
        utc(cutoff),
        utc(cutoff),
        limit);
  }

  private static WaitingTrip row(ResultSet row, int index) throws SQLException {
    return new WaitingTrip(
        row.getObject("id", UUID.class),
        row.getObject("rider_id", UUID.class),
        VehicleType.valueOf(row.getString("vehicle_type")),
        new GeoPoint(row.getDouble("pickup_lat"), row.getDouble("pickup_lng")),
        row.getLong("quoted_fare_paise"),
        row.getObject("waiting_since", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
