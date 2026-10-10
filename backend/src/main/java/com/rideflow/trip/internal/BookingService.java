package com.rideflow.trip.internal;

import com.rideflow.pricing.ConsumedQuote;
import com.rideflow.pricing.QuoteConsumer;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.KeyedRateLimiter;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.PaymentMethod;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/v1/trips}: turns a quote into a REQUESTED trip, exactly once per {@code
 * Idempotency-Key}.
 *
 * <p>The steps, and why they are in this order:
 *
 * <ol>
 *   <li><strong>Claim the key.</strong> First, in its own short transaction, so that of two
 *       concurrent requests with one key only one ever gets further (see {@link IdempotencyStore}).
 *   <li><strong>Consume the quote.</strong> A Redis call, so before the database transaction rather
 *       than inside it (PROJECT_SPEC section 11).
 *   <li><strong>One transaction:</strong> the trip, its first audit row, {@code TripRequested}, and
 *       the stored response. They commit together or not at all.
 *   <li><strong>On failure,</strong> the quote goes back to Redis so the rider can retry.
 * </ol>
 *
 * <p>The partial unique index {@code trips_one_active_per_rider} is the real guard against a second
 * active trip. Checking first in code would race; the index cannot.
 */
@Service
public class BookingService {

  static final String ONE_ACTIVE_TRIP_PER_RIDER = "trips_one_active_per_rider";

  private static final Logger log = LoggerFactory.getLogger(BookingService.class);

  private final IdempotencyStore keys;
  private final QuoteConsumer quotes;
  private final TripService trips;
  private final JsonMapper json;
  private final TransactionTemplate transactions;

  BookingService(
      IdempotencyStore keys,
      QuoteConsumer quotes,
      TripService trips,
      JsonMapper json,
      PlatformTransactionManager transactionManager) {
    this.keys = keys;
    this.quotes = quotes;
    this.trips = trips;
    this.json = json;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * @return the response body, JSON. A replay returns the same response, though not the same bytes:
   *     the stored copy comes back from a {@code jsonb} column, which normalises key order and
   *     spacing
   * @throws BusinessException 409 {@code ACTIVE_TRIP_EXISTS}; 422 {@code IDEMPOTENCY_KEY_REUSED}
   *     for a key used with a different request; 409 {@code IDEMPOTENCY_KEY_IN_FLIGHT} while a
   *     request with the key is still running; and whatever consuming the quote throws (410, 404,
   *     422)
   */
  public String book(
      UUID riderId,
      String idempotencyKey,
      UUID quoteId,
      VehicleType vehicleType,
      PaymentMethod paymentMethod) {

    String requestHash = requestHash(quoteId, vehicleType, paymentMethod);
    switch (keys.claim(riderId, idempotencyKey, requestHash)) {
      case IdempotencyStore.Claim.Claimed claimed -> {
        // This request does the work.
      }
      case IdempotencyStore.Claim.Replay replay -> {
        if (HttpStatus.valueOf(replay.status()).is2xxSuccessful()) {
          return replay.body();
        }
        throw keys.rejectionFrom(replay);
      }
      case IdempotencyStore.Claim.InFlight inFlight ->
          throw new BusinessException(
              "IDEMPOTENCY_KEY_IN_FLIGHT",
              HttpStatus.CONFLICT,
              "A request with this Idempotency-Key is still being processed. Retry shortly.",
              Map.of(KeyedRateLimiter.RETRY_AFTER_SECONDS, 1L));
      case IdempotencyStore.Claim.Mismatch mismatch ->
          throw BusinessException.businessRule(
              "IDEMPOTENCY_KEY_REUSED",
              "This Idempotency-Key was already used for a different request");
    }

    if (trips.hasOutstandingPayment(riderId)) {
      BusinessException outstanding =
          BusinessException.businessRule(
              "OUTSTANDING_BALANCE",
              "A previous trip is still unpaid. Top up your wallet to book again.");
      keys.storeRejection(riderId, idempotencyKey, outstanding);
      throw outstanding;
    }

    ConsumedQuote quote;
    try {
      quote = quotes.consume(quoteId, riderId, vehicleType);
    } catch (BusinessException rejected) {
      keys.storeRejection(riderId, idempotencyKey, rejected);
      throw rejected;
    } catch (RuntimeException failure) {
      keys.release(riderId, idempotencyKey);
      throw failure;
    }

    try {
      return transactions.execute(
          status -> {
            TripView trip = trips.request(riderId, quote, paymentMethod);
            String body = json.writeValueAsString(trip);
            keys.storeResponse(riderId, idempotencyKey, HttpStatus.ACCEPTED.value(), body);
            return body;
          });
    } catch (DataIntegrityViolationException failure) {
      restore(quote, failure);
      if (!TripService.violated(failure, ONE_ACTIVE_TRIP_PER_RIDER)) {
        keys.release(riderId, idempotencyKey);
        throw failure;
      }
      BusinessException conflict =
          BusinessException.conflict(
              "ACTIVE_TRIP_EXISTS",
              "You already have a trip in progress. Finish or cancel it first.");
      keys.storeRejection(riderId, idempotencyKey, conflict);
      throw conflict;
    } catch (RuntimeException failure) {
      restore(quote, failure);
      keys.release(riderId, idempotencyKey);
      throw failure;
    }
  }

  /** Puts the quote back for a retry. A failure to do so must not hide the original error. */
  private void restore(ConsumedQuote quote, RuntimeException cause) {
    try {
      quotes.restore(quote);
    } catch (RuntimeException restoreFailure) {
      log.warn(
          "Could not restore quote {} after a failed booking", quote.quoteId(), restoreFailure);
      cause.addSuppressed(restoreFailure);
    }
  }

  /**
   * Identifies "the same request" for idempotency: the endpoint and the parsed body, so field order
   * or whitespace in the JSON cannot make one request look like two.
   */
  static String requestHash(UUID quoteId, VehicleType vehicleType, PaymentMethod paymentMethod) {
    String canonical =
        String.join(
            "\n",
            "POST /api/v1/trips",
            String.valueOf(quoteId),
            String.valueOf(vehicleType),
            String.valueOf(paymentMethod));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // Every Java platform is required to provide SHA-256.
      throw new IllegalStateException(e);
    }
  }
}
