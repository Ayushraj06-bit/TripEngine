package com.rideflow.identity.internal;

import com.rideflow.identity.Role;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface UserRepository extends JpaRepository<User, UUID> {

  Optional<User> findByPhone(String phone);

  boolean existsByPhone(String phone);

  boolean existsByRole(Role role);

  /**
   * The user, locked for the rest of the transaction. Concurrent logins then record their failures
   * one after another: read without the lock, two could both write the same count, and fifteen
   * guesses at once - all the per-phone limit allows - might never reach the lockout.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.id = :id")
  Optional<User> findForUpdate(UUID id);
}
