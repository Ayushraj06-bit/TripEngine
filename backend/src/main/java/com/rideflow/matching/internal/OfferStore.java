package com.rideflow.matching.internal;

import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.WaitingTrip;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Matching's working state in Redis (PROJECT_SPEC section 7), and the scripts that change it.
 *
 * <p>Working state only. Redis can lose any of it at any moment; the recovery sweep rebuilds it
 * from PostgreSQL, which alone decides who has a trip.
 */
@Component
class OfferStore {

  static final String OFFERS_EXPIRY = "offers:expiry";

  /**
   * When to search again for trips nobody could take. Not in section 7's list: it is the same shape
   * as {@link #OFFERS_EXPIRY} for the same reason - a wait that holds no thread - and is cleaned
   * the same way, by the poller.
   */
  static final String RETRIES = "match:retry";

  enum OfferStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    EXPIRED,
    WITHDRAWN;

    /** A driver's lock is real only while the offer it guards is out or was just taken. */
    boolean holdsDriver() {
      return this == PENDING || this == ACCEPTED;
    }
  }

  /** A trip being matched. {@code currentOffer} is null before the first offer. */
  record Match(
      UUID tripId,
      VehicleType vehicleType,
      GeoPoint pickup,
      long farePaise,
      Instant startedAt,
      Instant deadline,
      UUID currentOffer) {}

  record Offer(UUID offerId, UUID tripId, UUID driverId, OfferStatus status, Instant expiresAt) {}

  enum Creation {
    CREATED,
    /** Another instance moved the trip on first. */
    SUPERSEDED,
    /** Matching ended: the trip was assigned, cancelled or given up on. */
    NO_MATCH
  }

  /** What {@code offer_accept.lua} decided. {@code first} is false on a repeated accept. */
  record Acceptance(String outcome, UUID tripId, boolean first) {}

  /** What {@code offer_close.lua} decided. {@code status} is the offer's status afterwards. */
  record Closing(String outcome, UUID tripId, UUID driverId, OfferStatus status) {

    boolean closed() {
      return "closed".equals(outcome);
    }
  }

  private final StringRedisTemplate redis;
  private final RedisScript<Long> matchStartScript;
  private final RedisScript<String> offerCreateScript;
  private final RedisScript<List> offerAcceptScript;
  private final RedisScript<List> offerCloseScript;
  private final RedisScript<Long> unlockScript;
  private final RideFlowProperties.Matching rules;

  OfferStore(
      StringRedisTemplate redis,
      RedisScript<Long> matchStartScript,
      RedisScript<String> offerCreateScript,
      RedisScript<List> offerAcceptScript,
      RedisScript<List> offerCloseScript,
      RedisScript<Long> unlockScript,
      RideFlowProperties properties) {
    this.redis = redis;
    this.matchStartScript = matchStartScript;
    this.offerCreateScript = offerCreateScript;
    this.offerAcceptScript = offerAcceptScript;
    this.offerCloseScript = offerCloseScript;
    this.unlockScript = unlockScript;
    this.rules = properties.matching();
  }

  static String matchKey(UUID tripId) {
    return "match:" + tripId;
  }

  static String triedKey(UUID tripId) {
    return "match:" + tripId + ":tried";
  }

  static String offerKey(UUID offerId) {
    return "offer:" + offerId;
  }

  static String lockKey(UUID driverId) {
    return "driver:lock:" + driverId;
  }

  // --- Matches ---------------------------------------------------------------------------------

  /**
   * @return true if this call started matching, false if it was already running
   */
  boolean startMatch(WaitingTrip trip, Instant deadline, UUID excludedDriver) {
    Long started =
        redis.execute(
            matchStartScript,
            List.of(matchKey(trip.tripId()), triedKey(trip.tripId())),
            trip.vehicleType().name(),
            Double.toString(trip.pickup().latitude()),
            Double.toString(trip.pickup().longitude()),
            Long.toString(trip.quotedFarePaise()),
            Long.toString(trip.waitingSince().toEpochMilli()),
            Long.toString(deadline.toEpochMilli()),
            Long.toString(stateTtl().toMillis()),
            excludedDriver == null ? "" : excludedDriver.toString());
    return Long.valueOf(1).equals(started);
  }

  Optional<Match> match(UUID tripId) {
    Map<Object, Object> hash = redis.opsForHash().entries(matchKey(tripId));
    if (hash.isEmpty()) {
      return Optional.empty();
    }
    String current = (String) hash.get("currentOffer");
    return Optional.of(
        new Match(
            tripId,
            VehicleType.valueOf((String) hash.get("vehicleType")),
            new GeoPoint(
                Double.parseDouble((String) hash.get("pickupLat")),
                Double.parseDouble((String) hash.get("pickupLng"))),
            Long.parseLong((String) hash.get("farePaise")),
            Instant.ofEpochMilli(Long.parseLong((String) hash.get("startedAt"))),
            Instant.ofEpochMilli(Long.parseLong((String) hash.get("deadline"))),
            current == null || current.isEmpty() ? null : UUID.fromString(current)));
  }

  Set<UUID> tried(UUID tripId) {
    Set<String> members = redis.opsForSet().members(triedKey(tripId));
    return members == null
        ? Set.of()
        : members.stream().map(UUID::fromString).collect(Collectors.toSet());
  }

  /** Ends matching for a trip: its state, its tried set and any pending search. */
  void finish(UUID tripId) {
    redis.delete(List.of(matchKey(tripId), triedKey(tripId)));
    redis.opsForZSet().remove(RETRIES, tripId.toString());
  }

  // --- Driver locks ----------------------------------------------------------------------------

  /** Of these drivers, the ones holding a live offer, who must not be sent another. */
  Set<UUID> busy(List<UUID> driverIds) {
    if (driverIds.isEmpty()) {
      return Set.of();
    }
    List<String> holders =
        redis.opsForValue().multiGet(driverIds.stream().map(OfferStore::lockKey).toList());
    Set<UUID> busy = new HashSet<>();
    for (int i = 0; i < driverIds.size(); i++) {
      // ponytail: one HGET per locked candidate; pipeline them if search latency matters.
      if (holders.get(i) != null && holdsDriver(holders.get(i))) {
        busy.add(driverIds.get(i));
      }
    }
    return busy;
  }

  /**
   * Locks the driver for {@code offerId}: {@code SET NX PX}, for the offer timeout plus a grace.
   *
   * <p>A lock whose offer no longer holds the driver - left behind by a crash, or by offer state
   * Redis lost - is taken over rather than waited out. Nothing would ever release it, and its TTL
   * runs on Redis's own clock.
   */
  boolean lock(UUID driverId, UUID offerId) {
    if (setLock(driverId, offerId)) {
      return true;
    }
    String holder = redis.opsForValue().get(lockKey(driverId));
    if (holder != null && holdsDriver(holder)) {
      return false;
    }
    if (holder != null) {
      // Compare-and-delete, so a lock taken by a live offer in the meantime survives.
      unlock(driverId, UUID.fromString(holder));
    }
    return setLock(driverId, offerId);
  }

  /** Releases the driver, if the lock is still the one taken for {@code offerId}. */
  void unlock(UUID driverId, UUID offerId) {
    redis.execute(unlockScript, List.of(lockKey(driverId)), offerId.toString());
  }

  /** The offer the driver is locked for, if any. */
  Optional<UUID> lockedOffer(UUID driverId) {
    return Optional.ofNullable(redis.opsForValue().get(lockKey(driverId))).map(UUID::fromString);
  }

  private boolean setLock(UUID driverId, UUID offerId) {
    Duration ttl =
        Duration.ofSeconds((long) rules.offerTimeoutSeconds() + rules.lockGraceSeconds());
    return Boolean.TRUE.equals(
        redis.opsForValue().setIfAbsent(lockKey(driverId), offerId.toString(), ttl));
  }

  private boolean holdsDriver(String offerId) {
    Object status = redis.opsForHash().get(offerKey(UUID.fromString(offerId)), "status");
    return status != null && OfferStatus.valueOf((String) status).holdsDriver();
  }

  // --- Offers ----------------------------------------------------------------------------------

  /** Records an offer, replacing the match's current one only if no one else has. */
  Creation createOffer(
      Match match, UUID offerId, UUID driverId, double radiusKm, Instant expiresAt) {
    String result =
        redis.execute(
            offerCreateScript,
            List.of(
                matchKey(match.tripId()),
                triedKey(match.tripId()),
                offerKey(offerId),
                OFFERS_EXPIRY,
                RETRIES),
            offerId.toString(),
            match.currentOffer() == null ? "" : match.currentOffer().toString(),
            match.tripId().toString(),
            driverId.toString(),
            Long.toString(expiresAt.toEpochMilli()),
            Long.toString(stateTtl().toMillis()),
            Double.toString(radiusKm));
    return switch (String.valueOf(result)) {
      case "created" -> Creation.CREATED;
      case "superseded" -> Creation.SUPERSEDED;
      default -> Creation.NO_MATCH;
    };
  }

  @SuppressWarnings("unchecked")
  Acceptance accept(UUID offerId, UUID driverId, Instant now) {
    List<Object> result =
        redis.execute(
            offerAcceptScript,
            List.of(offerKey(offerId), OFFERS_EXPIRY),
            offerId.toString(),
            driverId.toString(),
            Long.toString(now.toEpochMilli()));
    String outcome = String.valueOf(result.getFirst());
    return "accepted".equals(outcome)
        ? new Acceptance(
            outcome, UUID.fromString(String.valueOf(result.get(1))), "1".equals(result.get(2)))
        : new Acceptance(outcome, null, false);
  }

  /**
   * Moves the offer from {@code from} to {@code to}, and out of the expiry index.
   *
   * @param requiredDriver the driver the offer must belong to, or null for any
   */
  @SuppressWarnings("unchecked")
  Closing close(UUID offerId, OfferStatus to, OfferStatus from, UUID requiredDriver) {
    List<Object> result =
        redis.execute(
            offerCloseScript,
            List.of(offerKey(offerId), OFFERS_EXPIRY),
            offerId.toString(),
            to.name(),
            from.name(),
            requiredDriver == null ? "" : requiredDriver.toString());
    String outcome = String.valueOf(result.getFirst());
    return switch (outcome) {
      case "closed" ->
          new Closing(
              outcome,
              UUID.fromString(String.valueOf(result.get(1))),
              UUID.fromString(String.valueOf(result.get(2))),
              to);
      case "not_open" ->
          new Closing(outcome, null, null, OfferStatus.valueOf(String.valueOf(result.get(1))));
      default -> new Closing(outcome, null, null, null);
    };
  }

  Optional<Offer> offer(UUID offerId) {
    Map<Object, Object> hash = redis.opsForHash().entries(offerKey(offerId));
    if (hash.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Offer(
            offerId,
            UUID.fromString((String) hash.get("tripId")),
            UUID.fromString((String) hash.get("driverId")),
            OfferStatus.valueOf((String) hash.get("status")),
            Instant.ofEpochMilli(Long.parseLong((String) hash.get("expiresAt")))));
  }

  // --- Timers: offer expiry and searching again ------------------------------------------------

  /** Offers whose expiry, by the injected clock, has passed. */
  List<UUID> dueOffers(Instant now, int limit) {
    return due(OFFERS_EXPIRY, now, limit);
  }

  /**
   * Claims an expired offer. ZREM is atomic, so of every instance polling, exactly one gets 1 back
   * and processes it - no lock needed.
   */
  boolean claimOffer(UUID offerId) {
    return Long.valueOf(1).equals(redis.opsForZSet().remove(OFFERS_EXPIRY, offerId.toString()));
  }

  void scheduleRetry(UUID tripId, Instant at) {
    redis.opsForZSet().add(RETRIES, tripId.toString(), at.toEpochMilli());
  }

  List<UUID> dueRetries(Instant now, int limit) {
    return due(RETRIES, now, limit);
  }

  /** As {@link #claimOffer}. */
  boolean claimRetry(UUID tripId) {
    return Long.valueOf(1).equals(redis.opsForZSet().remove(RETRIES, tripId.toString()));
  }

  /** When the trip's next search is due, if one is scheduled. */
  Optional<Instant> retryAt(UUID tripId) {
    return Optional.ofNullable(redis.opsForZSet().score(RETRIES, tripId.toString()))
        .map(score -> Instant.ofEpochMilli(score.longValue()));
  }

  private List<UUID> due(String key, Instant now, int limit) {
    Set<String> members =
        redis
            .opsForZSet()
            .rangeByScore(key, Double.NEGATIVE_INFINITY, now.toEpochMilli(), 0, limit);
    return members == null ? List.of() : members.stream().map(UUID::fromString).toList();
  }

  private Duration stateTtl() {
    return Duration.ofMinutes(rules.stateTtlMinutes());
  }
}
