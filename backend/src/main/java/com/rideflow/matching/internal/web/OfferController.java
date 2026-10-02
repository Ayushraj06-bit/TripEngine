package com.rideflow.matching.internal.web;

import com.rideflow.matching.internal.Matcher;
import com.rideflow.matching.internal.OfferView;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A driver's answer to an offer. The driver is always the caller, never a request field. */
@RestController
@RequestMapping("/api/v1/offers")
class OfferController {

  private final Matcher matcher;

  OfferController(Matcher matcher) {
    this.matcher = matcher;
  }

  @PostMapping("/{offerId}/accept")
  @PreAuthorize("hasRole('DRIVER')")
  Matcher.AcceptedOffer accept(@AuthenticationPrincipal Jwt principal, @PathVariable UUID offerId) {
    return matcher.accept(offerId, UUID.fromString(principal.getSubject()));
  }

  @PostMapping("/{offerId}/reject")
  @PreAuthorize("hasRole('DRIVER')")
  Matcher.RejectedOffer reject(@AuthenticationPrincipal Jwt principal, @PathVariable UUID offerId) {
    return matcher.reject(offerId, UUID.fromString(principal.getSubject()));
  }

  /** The offer the driver holds, or 204: how a reconnecting app catches up on a missed push. */
  @GetMapping("/current")
  @PreAuthorize("hasRole('DRIVER')")
  ResponseEntity<OfferView> current(@AuthenticationPrincipal Jwt principal) {
    return matcher
        .current(UUID.fromString(principal.getSubject()))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }
}
