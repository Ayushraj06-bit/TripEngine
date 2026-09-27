package com.rideflow.admin.internal.web;

import com.rideflow.driver.ApprovalStatus;
import com.rideflow.driver.DriverAdministration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Driver approval, for admins.
 *
 * <p>The endpoint lives here but the rule lives in {@code driver}, which owns {@code
 * approval_status}. This controller only calls {@code driver}'s public API, so PROJECT_SPEC section
 * 5's boundaries hold and {@code admin} never touches a driver table.
 */
@RestController
@RequestMapping("/api/v1/admin/drivers")
@PreAuthorize("hasRole('ADMIN')")
class AdminDriverController {

  private final DriverAdministration drivers;

  AdminDriverController(DriverAdministration drivers) {
    this.drivers = drivers;
  }

  /**
   * The review queue, cursor paginated.
   *
   * @param cursor the last driver id from the previous page, or absent for the first
   * @param limit capped at 50 by PROJECT_SPEC section 10
   */
  @GetMapping
  DriverAdministration.Page list(
      @RequestParam(defaultValue = "PENDING") ApprovalStatus approvalStatus,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
    return drivers.listByApprovalStatus(approvalStatus, cursor, limit);
  }

  /** Idempotent: approving an already-approved driver is a no-op, not an error. */
  @PostMapping("/{driverId}/approve")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void approve(@PathVariable UUID driverId) {
    drivers.approve(driverId);
  }

  @PostMapping("/{driverId}/reject")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void reject(@PathVariable UUID driverId, @Valid @RequestBody RejectRequest request) {
    drivers.reject(driverId, request.reason());
  }

  record RejectRequest(@NotBlank @Size(max = 500) String reason) {}
}
