package com.rideflow.rating.internal.web;

import com.rideflow.rating.internal.RatingService;
import com.rideflow.trip.TripActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The rider or the driver of a completed trip rates the other side. */
@RestController
class RatingController {

  private final RatingService ratings;

  RatingController(RatingService ratings) {
    this.ratings = ratings;
  }

  @PostMapping("/api/v1/trips/{tripId}/rating")
  @PreAuthorize("hasAnyRole('RIDER', 'DRIVER')")
  @ResponseStatus(HttpStatus.CREATED)
  RatingService.RatingView rate(
      @PathVariable UUID tripId,
      @AuthenticationPrincipal Jwt principal,
      @Valid @RequestBody RatingRequest request) {
    return ratings.rate(
        tripId,
        UUID.fromString(principal.getSubject()),
        TripActor.valueOf(principal.getClaimAsString("role")),
        request.stars(),
        request.comment());
  }

  record RatingRequest(@NotNull @Min(1) @Max(5) Integer stars, @Size(max = 500) String comment) {}
}
