package com.rideflow.pricing.internal;

import com.rideflow.pricing.ConsumedQuote;
import com.rideflow.pricing.FareBreakdown;
import com.rideflow.pricing.QuoteConsumer;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.IdGenerator;
import com.rideflow.shared.KeyedRateLimiter;
import com.rideflow.shared.Money;
import com.rideflow.shared.RateLimitBuckets;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.shared.ServiceArea;
import com.rideflow.shared.VehicleType;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prices a trip for every vehicle type at once, and hands the chosen quote to a booking exactly
 * once.
 *
 * <p>A quote is a promise of a price for {@code quote-ttl-minutes}, bound to one rider and one
 * vehicle type, stored in Redis as {@code quote:{id}} (PROJECT_SPEC section 7).
 */
@Service
public class QuoteService implements QuoteConsumer {

  private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

  /**
   * The Redis key outlives the quote by this much. Expiry is decided by the stored {@code
   * expiresAt} on the injected clock; the TTL only cleans up, and must never remove a quote that
   * the clock still considers valid.
   */
  private static final Duration TTL_GRACE = Duration.ofSeconds(30);

  private final RateCardService rateCards;
  private final RouteEstimator routes;
  private final SurgeProvider surge;
  private final ServiceArea serviceArea;
  private final IdGenerator ids;
  private final Clock clock;
  private final RideFlowProperties properties;
  private final StringRedisTemplate redis;
  private final JsonMapper json;
  private final RedisScript<List> quoteConsumeScript;
  private final KeyedRateLimiter quotesPerRider;

  /** The fallback when the route estimator fails or its breaker is open. */
  private final HaversineRouteEstimator straightLine;

  private final CircuitBreaker routeBreaker;

  QuoteService(
      RateCardService rateCards,
      RouteEstimator routes,
      SurgeProvider surge,
      ServiceArea serviceArea,
      IdGenerator ids,
      Clock clock,
      RideFlowProperties properties,
      StringRedisTemplate redis,
      JsonMapper json,
      RedisScript<List> quoteConsumeScript,
      HaversineRouteEstimator straightLine,
      CircuitBreakerRegistry breakers,
      RateLimitBuckets buckets) {
    this.straightLine = straightLine;
    this.routeBreaker = breakers.circuitBreaker("route-estimator");
    this.rateCards = rateCards;
    this.routes = routes;
    this.surge = surge;
    this.serviceArea = serviceArea;
    this.ids = ids;
    this.clock = clock;
    this.properties = properties;
    this.redis = redis;
    this.json = json;
    this.quoteConsumeScript = quoteConsumeScript;
    this.quotesPerRider =
        new KeyedRateLimiter(
            "quote-rider",
            properties.pricing().quoteRateLimitPerMinute(),
            Duration.ofMinutes(1),
            "quote requests",
            buckets);
  }

  /**
   * One quote per vehicle type that has rates in force.
   *
   * @throws BusinessException 422 when either end is outside the service area or the trip is too
   *     short or too long; 429 when the rider asks too often
   */
  public List<Quote> quote(UUID riderId, GeoPoint pickup, GeoPoint drop) {
    quotesPerRider.consume(riderId.toString());
    requireInServiceArea(pickup, "Pickup");
    requireInServiceArea(drop, "Drop");

    // Milliseconds, the precision it is stored and checked at, so the expiry the rider
    // is shown is exactly the one that is enforced.
    Instant expiresAt =
        clock
            .instant()
            .truncatedTo(ChronoUnit.MILLIS)
            .plus(Duration.ofMinutes(properties.pricing().quoteTtlMinutes()));

    List<Quote> quotes = new ArrayList<>();
    for (VehicleType type : VehicleType.values()) {
      Optional<RateCard> card = rateCards.activeFor(type);
      if (card.isEmpty()) {
        // A misconfigured type should not take the other types down with it.
        log.warn("No rate card in force for {}; leaving it out of the quote", type);
        continue;
      }
      RouteEstimate route = estimateRoute(pickup, drop, type);
      requireSensibleLength(route.distanceMeters());

      BigDecimal multiplier = safeSurge(pickup, type);
      FareBreakdown fare =
          FareCalculator.calculate(
              card.get(), route.distanceMeters(), route.durationSeconds(), multiplier);

      Quote quote =
          new Quote(
              ids.newId(),
              riderId,
              type,
              pickup,
              drop,
              card.get().id(),
              multiplier,
              route.distanceMeters(),
              route.durationSeconds(),
              fare,
              expiresAt);
      quotes.add(quote);
    }

    if (quotes.isEmpty()) {
      // Every type missing rates is a deployment fault, not something the rider did.
      throw new IllegalStateException("No rate card is in force for any vehicle type");
    }
    // Only once every type is priced, so a failure part-way leaves nothing behind.
    quotes.forEach(this::store);
    return quotes;
  }

  @Override
  public ConsumedQuote consume(UUID quoteId, UUID riderId, VehicleType vehicleType) {
    @SuppressWarnings("unchecked")
    List<Object> result =
        redis.execute(
            quoteConsumeScript,
            List.of(key(quoteId)),
            riderId.toString(),
            vehicleType.name(),
            Long.toString(clock.millis()));

    String outcome = String.valueOf(result.getFirst());
    return switch (outcome) {
      case "consumed" ->
          toConsumed(json.readValue(String.valueOf(result.get(1)), StoredQuote.class));
      case "missing", "expired" ->
          throw new BusinessException(
              "QUOTE_EXPIRED",
              HttpStatus.GONE,
              "That quote has expired or was already used. Ask for a new one.");
      case "wrong_rider" ->
          // Not "forbidden": PROJECT_SPEC section 10 never confirms that someone else's
          // resource exists.
          throw new BusinessException("QUOTE_NOT_FOUND", HttpStatus.NOT_FOUND, "Quote not found");
      case "wrong_vehicle_type" ->
          throw new BusinessException(
              "QUOTE_VEHICLE_TYPE_MISMATCH",
              HttpStatus.UNPROCESSABLE_CONTENT,
              "That quote was priced for a different vehicle type");
      default -> throw new IllegalStateException("quote_consume.lua returned " + outcome);
    };
  }

  @Override
  public void restore(ConsumedQuote quote) {
    Duration remaining = Duration.between(clock.instant(), quote.expiresAt());
    if (remaining.isNegative() || remaining.isZero()) {
      return;
    }
    // setIfAbsent: a restore must never overwrite a quote that somehow reappeared.
    redis
        .opsForValue()
        .setIfAbsent(
            key(quote.quoteId()),
            json.writeValueAsString(StoredQuote.of(quote)),
            remaining.plus(TTL_GRACE));
  }

  private void store(Quote quote) {
    Duration ttl = Duration.ofMinutes(properties.pricing().quoteTtlMinutes()).plus(TTL_GRACE);
    redis
        .opsForValue()
        .set(
            key(quote.quoteId()), json.writeValueAsString(StoredQuote.of(quote.toConsumed())), ttl);
  }

  private void requireInServiceArea(GeoPoint point, String which) {
    if (!serviceArea.contains(point)) {
      throw new BusinessException(
          "OUTSIDE_SERVICE_AREA",
          HttpStatus.UNPROCESSABLE_CONTENT,
          which + " is outside the area RideFlow serves");
    }
  }

  /** Limits on the estimated route distance - the distance the fare is billed on. */
  private void requireSensibleLength(long distanceMeters) {
    var pricing = properties.pricing();
    if (distanceMeters < pricing.minTripMeters()) {
      throw new BusinessException(
          "TRIP_TOO_SHORT",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "Trips must be at least %d metres".formatted(pricing.minTripMeters()));
    }
    if (distanceMeters > pricing.maxTripKm() * 1000L) {
      throw new BusinessException(
          "TRIP_TOO_LONG",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "Trips can be at most %d km".formatted(pricing.maxTripKm()));
    }
  }

  /**
   * The surge multiplier, never below 1.0 and never an error.
   *
   * <p>PROJECT_SPEC section 7 has surge fail safe to 1.0: a broken surge engine should cost
   * revenue, not stop riders booking.
   */
  private BigDecimal safeSurge(GeoPoint pickup, VehicleType type) {
    try {
      BigDecimal value = surge.multiplierAt(pickup, type);
      return value == null || value.compareTo(BigDecimal.ONE) < 0 ? BigDecimal.ONE : value;
    } catch (RuntimeException e) {
      log.warn("Surge lookup failed for {}; pricing at 1.0", type, e);
      return BigDecimal.ONE;
    }
  }

  private static String key(UUID quoteId) {
    return "quote:" + quoteId;
  }

  private static ConsumedQuote toConsumed(StoredQuote stored) {
    return new ConsumedQuote(
        UUID.fromString(stored.quoteId()),
        UUID.fromString(stored.riderId()),
        VehicleType.valueOf(stored.vehicleType()),
        new GeoPoint(stored.pickupLat(), stored.pickupLng()),
        new GeoPoint(stored.dropLat(), stored.dropLng()),
        UUID.fromString(stored.rateCardId()),
        new BigDecimal(stored.surgeMultiplier()),
        stored.distanceMeters(),
        stored.durationSeconds(),
        new FareBreakdown(
            Money.ofPaise(stored.baseFarePaise()),
            Money.ofPaise(stored.distanceFarePaise()),
            Money.ofPaise(stored.timeFarePaise()),
            Money.ofPaise(stored.minimumFareTopUpPaise()),
            Money.ofPaise(stored.surgeChargePaise()),
            stored.roundingPaise(),
            Money.ofPaise(stored.totalPaise())),
        Instant.ofEpochMilli(stored.expiresAtMs()));
  }

  /** A priced trip for one vehicle type, as returned to the rider. */
  public record Quote(
      UUID quoteId,
      UUID riderId,
      VehicleType vehicleType,
      GeoPoint pickup,
      GeoPoint drop,
      UUID rateCardId,
      BigDecimal surgeMultiplier,
      long distanceMeters,
      long durationSeconds,
      FareBreakdown fare,
      Instant expiresAt) {

    ConsumedQuote toConsumed() {
      return new ConsumedQuote(
          quoteId,
          riderId,
          vehicleType,
          pickup,
          drop,
          rateCardId,
          surgeMultiplier,
          distanceMeters,
          durationSeconds,
          fare,
          expiresAt);
    }
  }

  /**
   * The JSON shape stored in Redis.
   *
   * <p>Flat and primitive on purpose: {@code quote_consume.lua} reads {@code riderId}, {@code
   * vehicleType} and {@code expiresAtMs} straight out of it with {@code cjson}, so those names are
   * a contract with the script. The surge multiplier is a string, so a decimal never passes through
   * a double.
   */
  record StoredQuote(
      String quoteId,
      String riderId,
      String vehicleType,
      double pickupLat,
      double pickupLng,
      double dropLat,
      double dropLng,
      String rateCardId,
      String surgeMultiplier,
      long distanceMeters,
      long durationSeconds,
      long baseFarePaise,
      long distanceFarePaise,
      long timeFarePaise,
      long minimumFareTopUpPaise,
      long surgeChargePaise,
      long roundingPaise,
      long totalPaise,
      long expiresAtMs) {

    static StoredQuote of(ConsumedQuote quote) {
      FareBreakdown fare = quote.fare();
      return new StoredQuote(
          quote.quoteId().toString(),
          quote.riderId().toString(),
          quote.vehicleType().name(),
          quote.pickup().latitude(),
          quote.pickup().longitude(),
          quote.drop().latitude(),
          quote.drop().longitude(),
          quote.rateCardId().toString(),
          quote.surgeMultiplier().toPlainString(),
          quote.distanceMeters(),
          quote.durationSeconds(),
          fare.baseFare().paise(),
          fare.distanceFare().paise(),
          fare.timeFare().paise(),
          fare.minimumFareTopUp().paise(),
          fare.surgeCharge().paise(),
          fare.roundingPaise(),
          fare.total().paise(),
          quote.expiresAt().toEpochMilli());
    }
  }

  /**
   * The configured estimator behind a circuit breaker, falling back to the straight line.
   *
   * <p>ponytail: today the configured estimator is the straight line itself, which cannot fail; the
   * breaker is the seam a remote router plugs into (ADR 0007), and its HTTP client brings the time
   * limit with it.
   */
  private RouteEstimate estimateRoute(GeoPoint pickup, GeoPoint drop, VehicleType type) {
    try {
      return routeBreaker.executeSupplier(() -> routes.estimate(pickup, drop, type));
    } catch (CallNotPermittedException open) {
      return straightLine.estimate(pickup, drop, type);
    } catch (RuntimeException failure) {
      log.warn("Route estimator failed; quoting on the straight line", failure);
      return straightLine.estimate(pickup, drop, type);
    }
  }
}
