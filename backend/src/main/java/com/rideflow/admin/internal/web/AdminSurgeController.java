package com.rideflow.admin.internal.web;

import com.rideflow.pricing.SurgeAdministration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The surge heatmap and its levers, for admins. {@code pricing} runs surge; this exposes it. */
@RestController
@RequestMapping("/api/v1/admin/surge")
@PreAuthorize("hasRole('ADMIN')")
class AdminSurgeController {

  private final SurgeAdministration surge;

  AdminSurgeController(SurgeAdministration surge) {
    this.surge = surge;
  }

  /** Every cell the last tick priced, with its demand and supply, and the overrides in force. */
  @GetMapping
  SurgeAdministration.Heatmap heatmap() {
    return surge.heatmap();
  }

  @PostMapping("/overrides")
  @ResponseStatus(HttpStatus.CREATED)
  SurgeAdministration.CellOverride setOverride(
      @AuthenticationPrincipal Jwt admin, @Valid @RequestBody OverrideRequest request) {
    return surge.setOverride(
        request.cell(), request.multiplier(), request.expiresAt(), adminId(admin));
  }

  @DeleteMapping("/overrides/{cell}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void removeOverride(@AuthenticationPrincipal Jwt admin, @PathVariable String cell) {
    surge.removeOverride(cell, adminId(admin));
  }

  /** {@code {"on": true}} prices every fare at 1.0 until it is turned off again. */
  @PostMapping("/kill-switch")
  SurgeAdministration.KillSwitch killSwitch(
      @AuthenticationPrincipal Jwt admin, @Valid @RequestBody KillSwitchRequest request) {
    return surge.setKillSwitch(request.on(), adminId(admin));
  }

  private static UUID adminId(Jwt admin) {
    return UUID.fromString(admin.getSubject());
  }

  record OverrideRequest(
      @NotBlank String cell, @NotNull BigDecimal multiplier, @NotNull Instant expiresAt) {}

  record KillSwitchRequest(@NotNull Boolean on) {}
}
