package com.rideflow.identity.internal;

import com.rideflow.identity.Role;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
class User {

  @Id private UUID id;

  /** Always E.164, normalised by {@link PhoneNumber} before it gets here. */
  private String phone;

  private String name;
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  private Role role;

  private int failedLoginCount;
  private Instant lockedUntil;
  private Instant createdAt;
  private Instant updatedAt;

  protected User() {} // JPA

  User(UUID id, String phone, String name, String passwordHash, Role role, Instant now) {
    this.id = id;
    this.phone = phone;
    this.name = name;
    this.passwordHash = passwordHash;
    this.role = role;
    this.createdAt = now;
    this.updatedAt = now;
  }

  UUID id() {
    return id;
  }

  String phone() {
    return phone;
  }

  String name() {
    return name;
  }

  String passwordHash() {
    return passwordHash;
  }

  Role role() {
    return role;
  }

  Instant createdAt() {
    return createdAt;
  }

  boolean isLockedAt(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  /**
   * Counts a failed attempt, locking the account once the limit is reached.
   *
   * <p>The counter resets when the lock is applied, so the next lock needs a fresh run of failures
   * rather than one more attempt after the lock expires.
   */
  void recordFailedLogin(Instant now, int maxAttempts, Duration lockFor) {
    failedLoginCount++;
    if (failedLoginCount >= maxAttempts) {
      lockedUntil = now.plus(lockFor);
      failedLoginCount = 0;
    }
    updatedAt = now;
  }

  void recordSuccessfulLogin(Instant now) {
    failedLoginCount = 0;
    lockedUntil = null;
    updatedAt = now;
  }
}
