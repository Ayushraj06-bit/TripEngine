package com.rideflow.identity.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
class RefreshToken {

  @Id private UUID id;

  private UUID userId;

  /** SHA-256 of the token. The plaintext is never stored. */
  private String tokenHash;

  /** Shared by every token rotated from one login, so theft can revoke the whole session. */
  private UUID familyId;

  private Instant expiresAt;
  private Instant revokedAt;
  private UUID replacedById;
  private Instant createdAt;

  protected RefreshToken() {} // JPA

  RefreshToken(
      UUID id, UUID userId, String tokenHash, UUID familyId, Instant expiresAt, Instant now) {
    this.id = id;
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.familyId = familyId;
    this.expiresAt = expiresAt;
    this.createdAt = now;
  }

  UUID id() {
    return id;
  }

  UUID userId() {
    return userId;
  }

  UUID familyId() {
    return familyId;
  }

  boolean isUsable(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }

  void revoke(Instant now) {
    if (revokedAt == null) {
      revokedAt = now;
    }
  }

  void replaceWith(UUID successorId, Instant now) {
    revoke(now);
    this.replacedById = successorId;
  }
}
