package com.rideflow.driver.internal.web;

import com.rideflow.driver.internal.DriverService;
import com.rideflow.shared.VehicleType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A driver's own profile, vehicle and availability.
 *
 * <p>Every route is DRIVER-only and acts on the caller's own id, taken from the principal. There is
 * no driver id in any path, so a driver cannot reach another driver's profile - the parameter that
 * would allow it does not exist.
 */
@RestController
@RequestMapping("/api/v1/drivers")
@PreAuthorize("hasRole('DRIVER')")
class DriverController {

  private final DriverService drivers;

  DriverController(DriverService drivers) {
    this.drivers = drivers;
  }

  @GetMapping("/me")
  DriverService.DriverProfile me(@AuthenticationPrincipal Jwt principal) {
    return drivers.profile(callerId(principal));
  }

  /** Registers or replaces the caller's vehicle. PUT, because a driver has at most one. */
  @PutMapping("/me/vehicle")
  DriverService.VehicleView registerVehicle(
      @AuthenticationPrincipal Jwt principal, @Valid @RequestBody VehicleRequest request) {
    return drivers.registerVehicle(
        callerId(principal),
        request.type(),
        request.plateNumber(),
        request.model(),
        request.colour());
  }

  @PostMapping("/me/online")
  DriverService.StatusView goOnline(@AuthenticationPrincipal Jwt principal) {
    return drivers.goOnline(callerId(principal));
  }

  @PostMapping("/me/offline")
  DriverService.StatusView goOffline(@AuthenticationPrincipal Jwt principal) {
    return drivers.goOffline(callerId(principal));
  }

  /** PROJECT_SPEC section 10: never trust an id from the request for "who am I". */
  private static UUID callerId(Jwt principal) {
    return UUID.fromString(principal.getSubject());
  }

  record VehicleRequest(
      @NotNull VehicleType type,
      @NotBlank @Size(max = 20) String plateNumber,
      @NotBlank @Size(max = 60) String model,
      @NotBlank @Size(max = 30) String colour) {}
}
