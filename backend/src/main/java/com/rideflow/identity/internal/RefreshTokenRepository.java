package com.rideflow.identity.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /**
   * The same lookup, holding a row lock until the transaction ends.
   *
   * <p>Rotation is read-then-write: without the lock, ten concurrent callers presenting the same
   * token all pass the usable check and all receive a live new token for one session. The lock
   * makes them queue, so the first rotates and the rest correctly see a spent token.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
  Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash);

  /**
   * Revokes every still-live token in a family.
   *
   * <p>One statement rather than a read-modify-write loop, so a replay cannot race a concurrent
   * rotation and leave one token alive.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update RefreshToken t set t.revokedAt = :now "
          + "where t.familyId = :familyId and t.revokedAt is null")
  int revokeFamily(UUID familyId, Instant now);
}
