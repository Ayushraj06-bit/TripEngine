package com.rideflow.admin.internal.web;

import com.rideflow.payment.LedgerReconciliation;
import com.rideflow.payment.Refunds;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Refunds and the ledger check, for admins. {@code payment} does both; this only exposes them. */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminPaymentController {

  private final Refunds refunds;
  private final LedgerReconciliation reconciliation;

  AdminPaymentController(Refunds refunds, LedgerReconciliation reconciliation) {
    this.refunds = refunds;
    this.reconciliation = reconciliation;
  }

  @PostMapping("/payments/{transactionId}/refund")
  Refunds.Refund refund(@PathVariable UUID transactionId, @AuthenticationPrincipal Jwt admin) {
    return refunds.refund(transactionId, UUID.fromString(admin.getSubject()));
  }

  @GetMapping("/ledger/reconcile")
  LedgerReconciliation.Report reconcile() {
    return reconciliation.reconcile();
  }
}
