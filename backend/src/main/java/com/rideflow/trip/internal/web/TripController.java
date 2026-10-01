package com.rideflow.trip.internal.web;

import com.rideflow.shared.VehicleType;
import com.rideflow.trip.PaymentMethod;
import com.rideflow.trip.TripActor;
import com.rideflow.trip.internal.BookingService;
import com.rideflow.trip.internal.Caller;
import com.rideflow.trip.internal.TripPage;
import com.rideflow.trip.internal.TripService;
import com.rideflow.trip.internal.TripView;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Booking and the trip lifecycle.
 *
 * <p>Who is asking always comes from the token: the subject is the user, the {@code role} claim
 * decides whether they act as the rider or the driver (PROJECT_SPEC section 10).
 */
@RestController
@RequestMapping("/api/v1/trips")
class TripController {

  private final BookingService bookings;
  private final TripService trips;

  TripController(BookingService bookings, TripService trips) {
    this.bookings = bookings;
    this.trips = trips;
  }

  /**
   * 202: the trip exists, but finding a driver has only started. The body is sent as the stored
   * JSON, so the schema is declared for the API docs.
   */
  @PostMapping
  @PreAuthorize("hasRole('RIDER')")
  @ApiResponse(
      responseCode = "202",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = TripView.class)))
  ResponseEntity<String> book(
      @AuthenticationPrincipal Jwt principal,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
      @Valid @RequestBody BookingRequest request) {
    String trip =
        bookings.book(
            UUID.fromString(principal.getSubject()),
            idempotencyKey,
            request.quoteId(),
            request.vehicleType(),
            request.paymentMethod());
    return ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON).body(trip);
  }

  /** 204 when there is none. */
  @GetMapping("/active")
  @PreAuthorize("hasAnyRole('RIDER', 'DRIVER')")
  ResponseEntity<TripView> active(@AuthenticationPrincipal Jwt principal) {
    return trips
        .active(caller(principal))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @GetMapping("/{tripId}")
  @PreAuthorize("hasAnyRole('RIDER', 'DRIVER')")
  TripView get(@AuthenticationPrincipal Jwt principal, @PathVariable UUID tripId) {
    return trips.get(tripId, caller(principal));
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('RIDER', 'DRIVER')")
  TripPage history(
      @AuthenticationPrincipal Jwt principal,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
    return trips.history(caller(principal), cursor, limit);
  }

  @PostMapping("/{tripId}/cancel")
  @PreAuthorize("hasAnyRole('RIDER', 'DRIVER')")
  TripView cancel(
      @AuthenticationPrincipal Jwt principal,
      @PathVariable UUID tripId,
      @Valid @RequestBody CancelRequest request) {
    return trips.cancel(tripId, caller(principal), request.reason());
  }

  @PostMapping("/{tripId}/arrive")
  @PreAuthorize("hasRole('DRIVER')")
  TripView arrive(@AuthenticationPrincipal Jwt principal, @PathVariable UUID tripId) {
    return trips.arrive(tripId, caller(principal));
  }

  @PostMapping("/{tripId}/start")
  @PreAuthorize("hasRole('DRIVER')")
  TripView start(
      @AuthenticationPrincipal Jwt principal,
      @PathVariable UUID tripId,
      @Valid @RequestBody StartRequest request) {
    return trips.start(tripId, caller(principal), request.otp());
  }

  @PostMapping("/{tripId}/complete")
  @PreAuthorize("hasRole('DRIVER')")
  TripView complete(@AuthenticationPrincipal Jwt principal, @PathVariable UUID tripId) {
    return trips.complete(tripId, caller(principal));
  }

  private static Caller caller(Jwt principal) {
    return new Caller(
        UUID.fromString(principal.getSubject()),
        TripActor.valueOf(principal.getClaimAsString("role")));
  }

  /**
   * @param vehicleType the type the rider chose. Redundant with the quote, deliberately: a client
   *     that sends the wrong quote id for the type on screen gets a 422 instead of the wrong car.
   */
  record BookingRequest(
      @NotNull UUID quoteId,
      @NotNull VehicleType vehicleType,
      @NotNull PaymentMethod paymentMethod) {}

  record CancelRequest(@NotBlank @Size(max = 500) String reason) {}

  record StartRequest(@NotNull @Pattern(regexp = "\\d{4}") String otp) {}
}
