package com.rideflow.payment.internal.web;

import com.rideflow.payment.internal.TopupService;
import com.rideflow.payment.internal.WalletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own money: a rider's wallet, or a driver's earnings, and topping the wallet up. */
@RestController
@RequestMapping("/api/v1/wallet")
class WalletController {

  private final WalletService wallets;
  private final TopupService topups;

  WalletController(WalletService wallets, TopupService topups) {
    this.wallets = wallets;
    this.topups = topups;
  }

  /** Balance and movements, newest first, cursor paginated. */
  @GetMapping
  @PreAuthorize("hasAnyRole('RIDER', 'DRIVER')")
  WalletService.Wallet wallet(
      @AuthenticationPrincipal Jwt principal,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
    return wallets.of(
        UUID.fromString(principal.getSubject()),
        "DRIVER".equals(principal.getClaimAsString("role")),
        cursor,
        limit);
  }

  /** Starts a top-up; the answer holds the URL where the rider pays. */
  @PostMapping("/topups")
  @PreAuthorize("hasRole('RIDER')")
  @ResponseStatus(HttpStatus.CREATED)
  TopupService.TopupView topUp(
      @AuthenticationPrincipal Jwt principal,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
      @Valid @RequestBody TopupRequest request) {
    return topups.start(
        UUID.fromString(principal.getSubject()), idempotencyKey, request.amountPaise());
  }

  record TopupRequest(@NotNull @Positive Long amountPaise) {}
}
