package com.rideflow.identity.internal.web;

import com.rideflow.identity.internal.UserQueries;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own account.
 *
 * <p>No id in the path, and no id read from the body. PROJECT_SPEC section 10 requires identity to
 * come from the authenticated principal, which makes an IDOR impossible here rather than merely
 * guarded against: there is no parameter to tamper with.
 */
@RestController
@RequestMapping("/api/v1/users")
class UserController {

  private final UserQueries users;

  UserController(UserQueries users) {
    this.users = users;
  }

  /** Any authenticated role. A driver is also a user. */
  @GetMapping("/me")
  UserQueries.UserProfile me(@AuthenticationPrincipal Jwt principal) {
    return users.profileOf(UUID.fromString(principal.getSubject()));
  }
}
