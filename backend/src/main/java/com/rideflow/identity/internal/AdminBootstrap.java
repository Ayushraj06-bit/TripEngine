package com.rideflow.identity.internal;

import com.rideflow.identity.Role;
import com.rideflow.shared.IdGenerator;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first admin, so a fresh deployment has somebody who can approve drivers.
 *
 * <p>Credentials come from the environment and are never defaulted or committed: with no {@code
 * RIDEFLOW_ADMIN_PHONE} and {@code RIDEFLOW_ADMIN_PASSWORD} set, this does nothing and says so. A
 * hard-coded fallback admin would be a backdoor in every deployment that forgot to change it.
 *
 * <p>Runs only when no admin exists at all, so it cannot resurrect a deliberately removed account
 * or silently reset a password on every restart.
 *
 * <p>Every instance starts it, so instances started together take turns on a PostgreSQL advisory
 * lock: without it, both found no admin, both inserted one, and the loser failed to start on the
 * unique phone number (the one-command demo in CI).
 */
@Component
class AdminBootstrap {

  private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

  /** BCrypt truncates beyond 72 bytes, so a longer secret would be silently shortened. */
  private static final int MIN_PASSWORD_LENGTH = 12;

  private final UserRepository users;
  private final JdbcTemplate jdbc;
  private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
  private final IdGenerator ids;
  private final Clock clock;
  private final String phone;
  private final String password;
  private final String name;

  AdminBootstrap(
      UserRepository users,
      JdbcTemplate jdbc,
      org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
      IdGenerator ids,
      Clock clock,
      IdentityProperties properties) {
    this.users = users;
    this.jdbc = jdbc;
    this.passwordEncoder = passwordEncoder;
    this.ids = ids;
    this.clock = clock;
    this.phone = orEmpty(properties.bootstrapAdmin().phone());
    this.password = orEmpty(properties.bootstrapAdmin().password());
    this.name = properties.bootstrapAdmin().name();
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  void createFirstAdminIfNoneExists() {
    // Released when this transaction ends; the next instance then finds the admin.
    jdbc.query("SELECT pg_advisory_xact_lock(hashtext('rideflow:admin-bootstrap'))", row -> {});
    if (users.existsByRole(Role.ADMIN)) {
      return;
    }
    if (phone.isBlank() || password.isBlank()) {
      log.warn(
          "No admin account exists and no bootstrap credentials are configured. "
              + "Set rideflow.security.bootstrap-admin.phone and .password "
              + "(RIDEFLOW_SECURITY_BOOTSTRAPADMIN_PHONE / _PASSWORD) to create one. "
              + "Driver approval is unavailable until an admin exists.");
      return;
    }
    if (password.length() < MIN_PASSWORD_LENGTH) {
      throw new IllegalStateException(
          "Bootstrap admin password must be at least %d characters".formatted(MIN_PASSWORD_LENGTH));
    }

    String normalised = PhoneNumber.normalise(phone);
    if (users.existsByPhone(normalised)) {
      // The number already belongs to a rider or driver; promoting it silently would be
      // a privilege escalation nobody asked for.
      log.error(
          "Cannot create the bootstrap admin: {} is already registered in another role",
          PhoneNumber.mask(normalised));
      return;
    }

    users.save(
        new User(
            ids.newId(),
            normalised,
            name,
            passwordEncoder.encode(password),
            Role.ADMIN,
            clock.instant()));
    // The number, masked, and never the password.
    log.info("Created the first admin account for {}", PhoneNumber.mask(normalised));
  }
}
