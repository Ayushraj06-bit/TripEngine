package com.rideflow.rating.internal;

import com.rideflow.driver.DriverRatings;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.IdGenerator;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.trip.RatableTrips;
import com.rideflow.trip.TripActor;
import com.rideflow.trip.TripStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

/**
 * Each side of a completed trip rates the other, once, within {@code rating.window-days}.
 *
 * <p>A rider's rating of the driver goes into the driver's totals in the same transaction, so the
 * average - sum over count - never needs an AVG over every rating. A driver's rating of the rider
 * is kept, with no totals yet: nothing reads a rider's average.
 *
 * <p>The comment is stored as written and escaped on the way out: HTML that a dashboard might
 * render reaches it as text. Escaping on the way in would mangle what was said, and depend on every
 * later reader remembering it had been.
 */
@Service
public class RatingService {

  private final RatableTrips trips;
  private final DriverRatings driverRatings;
  private final JdbcTemplate jdbc;
  private final IdGenerator ids;
  private final Clock clock;
  private final Duration window;

  RatingService(
      RatableTrips trips,
      DriverRatings driverRatings,
      JdbcTemplate jdbc,
      IdGenerator ids,
      Clock clock,
      RideFlowProperties properties) {
    this.trips = trips;
    this.driverRatings = driverRatings;
    this.jdbc = jdbc;
    this.ids = ids;
    this.clock = clock;
    this.window = Duration.ofDays(properties.rating().windowDays());
  }

  /**
   * @param comment escaped for HTML
   */
  public record RatingView(
      UUID ratingId,
      UUID tripId,
      TripActor raterRole,
      int stars,
      String comment,
      Instant createdAt) {}

  /**
   * @param raterRole the caller's role: which side of the trip they rate from
   * @throws BusinessException 404 unless the caller was on the trip; 409 {@code
   *     TRIP_NOT_COMPLETED}; 422 {@code RATING_WINDOW_CLOSED}; 409 {@code ALREADY_RATED}
   */
  @Transactional
  public RatingView rate(
      UUID tripId, UUID raterId, TripActor raterRole, int stars, String comment) {
    RatableTrips.RatableTrip trip =
        trips
            .find(tripId)
            .filter(
                found ->
                    raterId.equals(
                        raterRole == TripActor.RIDER ? found.riderId() : found.driverId()))
            // Someone else's trip reads exactly like a missing one (PROJECT_SPEC section 10).
            .orElseThrow(() -> BusinessException.notFound("Trip not found"));
    if (trip.status() != TripStatus.COMPLETED) {
      throw BusinessException.conflict(
          "TRIP_NOT_COMPLETED", "A trip can be rated once it is completed");
    }
    Instant now = clock.instant();
    if (now.isAfter(trip.completedAt().plus(window))) {
      throw new BusinessException(
          "RATING_WINDOW_CLOSED",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "A trip can be rated for %d days after it ends".formatted(window.toDays()));
    }

    String kept = comment == null || comment.isBlank() ? null : comment.strip();
    UUID ratee = raterRole == TripActor.RIDER ? trip.driverId() : trip.riderId();
    UUID ratingId = ids.newId();
    int inserted =
        jdbc.update(
            """
            INSERT INTO ratings (id, trip_id, rater_id, rater_role, ratee_id, stars, comment,
                created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (trip_id, rater_role) DO NOTHING""",
            ratingId,
            tripId,
            raterId,
            raterRole.name(),
            ratee,
            stars,
            kept,
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    if (inserted == 0) {
      throw BusinessException.conflict("ALREADY_RATED", "You have already rated this trip");
    }
    if (raterRole == TripActor.RIDER) {
      driverRatings.addRating(ratee, stars);
    }
    return new RatingView(
        ratingId, tripId, raterRole, stars, kept == null ? null : HtmlUtils.htmlEscape(kept), now);
  }
}
