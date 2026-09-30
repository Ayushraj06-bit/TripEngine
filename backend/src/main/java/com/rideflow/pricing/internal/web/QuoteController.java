package com.rideflow.pricing.internal.web;

import com.rideflow.pricing.FareBreakdown;
import com.rideflow.pricing.internal.QuoteService;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.VehicleType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fare quotes, one per vehicle type, in a single response.
 *
 * <p>No {@code Idempotency-Key}, a deliberate exception to PROJECT_SPEC section 10: repeating the
 * call only produces fresh quotes, which is harmless, short-lived and rate limited. Nothing is
 * booked or charged until a quote is consumed.
 */
@RestController
@RequestMapping("/api/v1/fares")
class QuoteController {

  private final QuoteService quotes;

  QuoteController(QuoteService quotes) {
    this.quotes = quotes;
  }

  @PostMapping("/quotes")
  @PreAuthorize("hasRole('RIDER')")
  QuotesResponse quote(
      @AuthenticationPrincipal Jwt principal, @Valid @RequestBody QuoteRequest request) {
    // The rider is the caller, never an id from the body (PROJECT_SPEC section 10).
    UUID riderId = UUID.fromString(principal.getSubject());
    return new QuotesResponse(
        quotes.quote(riderId, request.pickup().toGeoPoint(), request.drop().toGeoPoint()).stream()
            .map(QuoteView::of)
            .toList());
  }

  record QuoteRequest(@NotNull @Valid Point pickup, @NotNull @Valid Point drop) {}

  record Point(
      @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
      @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {

    GeoPoint toGeoPoint() {
      try {
        return new GeoPoint(lat, lng);
      } catch (IllegalArgumentException e) {
        // Bean Validation cannot see NaN or infinity; GeoPoint can. Either way it is
        // the caller's input, so a 400, not a 500.
        throw new BusinessException("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, e.getMessage());
      }
    }
  }

  record QuotesResponse(List<QuoteView> quotes) {}

  /** Amounts in paise, as everywhere else (PROJECT_SPEC section 7). */
  record QuoteView(
      UUID quoteId,
      VehicleType vehicleType,
      long totalPaise,
      BreakdownView breakdown,
      BigDecimal surgeMultiplier,
      UUID rateCardId,
      long distanceMeters,
      long durationSeconds,
      Instant expiresAt) {

    static QuoteView of(QuoteService.Quote quote) {
      return new QuoteView(
          quote.quoteId(),
          quote.vehicleType(),
          quote.fare().total().paise(),
          BreakdownView.of(quote.fare()),
          quote.surgeMultiplier(),
          quote.rateCardId(),
          quote.distanceMeters(),
          quote.durationSeconds(),
          quote.expiresAt());
    }
  }

  record BreakdownView(
      long basePaise,
      long distancePaise,
      long timePaise,
      long minimumFareTopUpPaise,
      long surgePaise,
      long roundingPaise) {

    static BreakdownView of(FareBreakdown fare) {
      return new BreakdownView(
          fare.baseFare().paise(),
          fare.distanceFare().paise(),
          fare.timeFare().paise(),
          fare.minimumFareTopUp().paise(),
          fare.surgeCharge().paise(),
          fare.roundingPaise());
    }
  }
}
