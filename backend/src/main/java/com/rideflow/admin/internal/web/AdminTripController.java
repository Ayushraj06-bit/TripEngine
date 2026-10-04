package com.rideflow.admin.internal.web;

import com.rideflow.trip.FlaggedTrips;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trips flagged for review, for admins: implausibly short trips, big repricings, drivers who went
 * silent mid-ride, trips with no recorded route. {@code trip} decides what is flagged; this only
 * lists it.
 */
@RestController
@RequestMapping("/api/v1/admin/trips")
@PreAuthorize("hasRole('ADMIN')")
class AdminTripController {

  private final FlaggedTrips flaggedTrips;

  AdminTripController(FlaggedTrips flaggedTrips) {
    this.flaggedTrips = flaggedTrips;
  }

  /**
   * Newest first, cursor paginated.
   *
   * @param cursor the last trip id from the previous page, or absent for the first
   * @param limit capped at 50 by PROJECT_SPEC section 10
   */
  @GetMapping("/flagged")
  FlaggedTrips.Page flagged(
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
    return flaggedTrips.list(cursor, limit);
  }
}
