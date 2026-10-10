package com.rideflow.payment.internal;

import com.rideflow.shared.BusinessException;
import com.rideflow.shared.IdGenerator;
import com.rideflow.shared.Money;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.shared.RideFlowProperties.Payment.Outcome;
import com.rideflow.trip.TripPayments;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wallet top-ups through the gateway (ADR 0013).
 *
 * <p>Starting one is idempotent by the rider's {@code Idempotency-Key}, kept on the top-up row: a
 * retried request finds the same top-up and the same payment. Confirming one is idempotent by the
 * top-up's status: of any number of deliveries of the gateway's webhook, one finds it PENDING and
 * credits the wallet; the rest find it settled and change nothing.
 */
@Service
public class TopupService {

  private static final Logger log = LoggerFactory.getLogger(TopupService.class);

  private final TopupRepository topups;
  private final PaymentGateway gateway;
  private final Ledger ledger;
  private final Postings postings;
  private final TripPayments tripPayments;
  private final JdbcTemplate jdbc;
  private final IdGenerator ids;
  private final Clock clock;
  private final RideFlowProperties.Payment rules;
  private final TransactionTemplate transactions;

  TopupService(
      TopupRepository topups,
      PaymentGateway gateway,
      Ledger ledger,
      Postings postings,
      TripPayments tripPayments,
      JdbcTemplate jdbc,
      IdGenerator ids,
      Clock clock,
      RideFlowProperties properties,
      PlatformTransactionManager transactionManager) {
    this.topups = topups;
    this.gateway = gateway;
    this.ledger = ledger;
    this.postings = postings;
    this.tripPayments = tripPayments;
    this.jdbc = jdbc;
    this.ids = ids;
    this.clock = clock;
    this.rules = properties.payment();
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * A top-up as the rider sees it.
   *
   * @param paymentUrl where to pay; null once the top-up is settled
   */
  public record TopupView(
      UUID topupId, TopupStatus status, long amountPaise, String paymentUrl, Instant createdAt) {}

  /**
   * Starts a top-up, or finds the one this key already started.
   *
   * @throws BusinessException 422 {@code TOPUP_AMOUNT_OUT_OF_RANGE}; 422 {@code
   *     IDEMPOTENCY_KEY_REUSED} for a key used with another amount; 503 {@code
   *     PAYMENT_GATEWAY_UNAVAILABLE}, after which the same key can be tried again
   */
  public TopupView start(UUID riderId, String idempotencyKey, long amountPaise) {
    if (amountPaise < rules.minTopupPaise() || amountPaise > rules.maxTopupPaise()) {
      throw new BusinessException(
          "TOPUP_AMOUNT_OUT_OF_RANGE",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "A top-up must be between ₹%s and ₹%s"
              .formatted(
                  Money.ofPaise(rules.minTopupPaise()).toRupeeString(),
                  Money.ofPaise(rules.maxTopupPaise()).toRupeeString()),
          Map.of("minPaise", rules.minTopupPaise(), "maxPaise", rules.maxTopupPaise()));
    }

    Topup topup =
        transactions.execute(
            status -> {
              jdbc.update(
                  """
                  INSERT INTO topups (id, user_id, amount_paise, status, idempotency_key, created_at)
                  VALUES (?, ?, ?, 'PENDING', ?, ?)
                  ON CONFLICT (user_id, idempotency_key) DO NOTHING""",
                  ids.newId(),
                  riderId,
                  amountPaise,
                  idempotencyKey,
                  OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
              return topups.findByUserIdAndIdempotencyKey(riderId, idempotencyKey).orElseThrow();
            });
    if (topup.amountPaise() != amountPaise) {
      throw BusinessException.businessRule(
          "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different top-up");
    }
    if (topup.status() != TopupStatus.PENDING) {
      return view(topup, null);
    }

    // Outside any transaction (PROJECT_SPEC section 11). The gateway answers the same payment for
    // the same top-up, so a retry - or two requests racing with one key - takes one payment.
    PaymentGateway.Session session =
        gateway.createPayment(topup.id(), Money.ofPaise(topup.amountPaise()));
    if (topup.gatewayRef() == null) {
      transactions.executeWithoutResult(
          status -> topups.recordGatewayRef(topup.id(), session.gatewayRef()));
    }
    return view(topup, session.paymentUrl());
  }

  /**
   * The gateway's answer about a payment, already authenticated by its signature.
   *
   * @throws BusinessException 404 for a payment we never started; 422 {@code AMOUNT_MISMATCH}
   */
  public void confirm(GatewayNotification notification) {
    postings.run(
        () -> {
          Topup topup =
              topups
                  .findByGatewayRef(notification.gatewayRef())
                  .orElseThrow(() -> BusinessException.notFound("Top-up not found"));
          if (topup.amountPaise() != notification.amountPaise()) {
            log.warn(
                "Gateway reported {} paise for top-up {} of {} paise; not applied",
                notification.amountPaise(),
                topup.id(),
                topup.amountPaise());
            throw BusinessException.businessRule(
                "AMOUNT_MISMATCH", "The amount paid does not match the top-up");
          }
          TopupStatus outcome =
              notification.status() == Outcome.SUCCEEDED
                  ? TopupStatus.SUCCEEDED
                  : TopupStatus.FAILED;
          if (topups.settle(topup.id(), outcome, clock.instant()) == 0) {
            // Settled by an earlier delivery: gateways retry until they hear a 2xx.
            return;
          }
          if (outcome == TopupStatus.SUCCEEDED) {
            credit(topup);
          }
        });
  }

  private void credit(Topup topup) {
    Money amount = Money.ofPaise(topup.amountPaise());
    AccountKey wallet = AccountKey.wallet(topup.userId());
    ledger
        .post(
            TransactionType.TOPUP,
            topup.id(),
            List.of(Leg.debit(AccountKey.CLEARING, amount), Leg.credit(wallet, amount)))
        .filter(posted -> posted.balanceOf(wallet) >= 0)
        // Back in credit: whatever waited on a top-up is paid.
        .ifPresent(posted -> tripPayments.settleOutstanding(topup.userId()));
  }

  private static TopupView view(Topup topup, String paymentUrl) {
    return new TopupView(
        topup.id(), topup.status(), topup.amountPaise(), paymentUrl, topup.createdAt());
  }
}
