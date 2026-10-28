package com.rideflow.trip.internal;

import com.rideflow.shared.BusinessException;
import com.rideflow.shared.RideFlowProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code idempotency_keys} table (PROJECT_SPEC sections 7 and 10).
 *
 * <p>A key is <em>claimed</em> with an insert before any work starts, and its response filled in
 * when the work is done. Claiming first is what makes concurrent retries safe: of two requests with
 * one key, exactly one inserts the row and does the work, and the other sees it. Looking the key up
 * without claiming it lets both through - both would try to consume the same quote, and the loser
 * would answer {@code 410} to a booking that in fact succeeded.
 *
 * <p>Plain JDBC rather than JPA: the claim needs {@code ON CONFLICT DO NOTHING}, and the response
 * is JSON the table only stores and returns.
 */
@Component
class IdempotencyStore {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

  /** The outcome of trying to claim a key. */
  sealed interface Claim {

    /** New key: this request does the work. */
    record Claimed() implements Claim {}

    /** Another request with this key is still working. */
    record InFlight() implements Claim {}

    /** The key was used before, for a different request. */
    record Mismatch() implements Claim {}

    /** The key was used before, for this request: answer as it was answered then. */
    record Replay(int status, String body) implements Claim {}
  }

  /** How a rejection is stored, so a replay can raise the same exception again. */
  record StoredRejection(String code, String message, Map<String, Object> properties) {}

  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final Clock clock;
  private final RideFlowProperties properties;

  IdempotencyStore(JdbcTemplate jdbc, JsonMapper json, Clock clock, RideFlowProperties properties) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
    this.properties = properties;
  }

  /**
   * Claims {@code key} for {@code userId}, in its own short transaction.
   *
   * <p>ponytail: a process that dies between claiming and answering leaves the key in flight until
   * the purge, so a client retrying that key is refused until then. Take over claims older than a
   * request can take if that ever shows up in practice.
   */
  Claim claim(UUID userId, String key, String requestHash) {
    int inserted =
        jdbc.update(
            """
            INSERT INTO idempotency_keys (user_id, key, request_hash, created_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (user_id, key) DO NOTHING""",
            userId,
            key,
            requestHash,
            utc(clock.instant()));
    if (inserted == 1) {
      return new Claim.Claimed();
    }

    List<Claim> existing =
        jdbc.query(
            """
            SELECT request_hash, response_status, response_body::text AS body
            FROM idempotency_keys WHERE user_id = ? AND key = ?""",
            (row, index) -> {
              if (!row.getString("request_hash").equals(requestHash)) {
                return new Claim.Mismatch();
              }
              int status = row.getInt("response_status");
              return row.wasNull()
                  ? new Claim.InFlight()
                  : new Claim.Replay(status, row.getString("body"));
            },
            userId,
            key);
    // Released in the moment between the two statements by a request that failed: as far as
    // this caller can tell, still in flight. Their retry will claim it.
    return existing.isEmpty() ? new Claim.InFlight() : existing.getFirst();
  }

  /** Records the response. Inside the caller's transaction when there is one. */
  void storeResponse(UUID userId, String key, int status, String body) {
    jdbc.update(
        """
        UPDATE idempotency_keys SET response_status = ?, response_body = ?::jsonb
        WHERE user_id = ? AND key = ?""",
        status,
        body,
        userId,
        key);
  }

  /** Records a 4xx, so a retry is refused the same way rather than trying again. */
  void storeRejection(UUID userId, String key, BusinessException rejection) {
    storeResponse(
        userId,
        key,
        rejection.status().value(),
        json.writeValueAsString(
            new StoredRejection(rejection.code(), rejection.getMessage(), rejection.properties())));
  }

  /** The exception a stored rejection was made from, to throw again. */
  BusinessException rejectionFrom(Claim.Replay replay) {
    StoredRejection stored = json.readValue(replay.body(), StoredRejection.class);
    return new BusinessException(
        stored.code(), HttpStatus.valueOf(replay.status()), stored.message(), stored.properties());
  }

  /**
   * Forgets a claim whose request failed with a server error, so that a retry does the work instead
   * of replaying a failure that was never the client's fault.
   */
  void release(UUID userId, String key) {
    jdbc.update("DELETE FROM idempotency_keys WHERE user_id = ? AND key = ?", userId, key);
  }

  /** PROJECT_SPEC section 7: keys are kept for {@code idempotency-retention-hours}. */
  @Scheduled(
      fixedDelayString = "${rideflow.trip.idempotency-purge-interval}",
      initialDelayString = "${rideflow.trip.idempotency-purge-interval}")
  @SchedulerLock(name = "idempotency-key-purge", lockAtMostFor = "PT5M")
  void purgeExpired() {
    Instant cutoff =
        clock.instant().minus(Duration.ofHours(properties.trip().idempotencyRetentionHours()));
    int purged = jdbc.update("DELETE FROM idempotency_keys WHERE created_at < ?", utc(cutoff));
    if (purged > 0) {
      log.info("Purged {} idempotency keys created before {}", purged, cutoff);
    }
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
