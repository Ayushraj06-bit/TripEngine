package com.rideflow.identity.internal;

import com.rideflow.identity.Role;
import com.rideflow.shared.BusinessException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of identity: the caller's own account. */
@Service
public class UserQueries {

  private final UserRepository users;

  UserQueries(UserRepository users) {
    this.users = users;
  }

  @Transactional(readOnly = true)
  public UserProfile profileOf(UUID userId) {
    return users
        .findById(userId)
        .map(
            user ->
                new UserProfile(
                    user.id(), user.phone(), user.name(), user.role(), user.createdAt()))
        // A token whose subject no longer exists: 404, never a 500.
        .orElseThrow(() -> BusinessException.notFound("User not found"));
  }

  /**
   * The caller's own profile, so the full phone number is theirs to see.
   *
   * <p>PROJECT_SPEC section 11 forbids putting it in a log, which is a different thing from
   * returning it to its owner over TLS.
   */
  public record UserProfile(UUID id, String phone, String name, Role role, Instant registeredAt) {}
}
