package com.rideflow.driver;

import java.util.UUID;

/**
 * How {@code identity} creates a driver profile during registration.
 *
 * <p>This is a direct call rather than an event, which is a deliberate exception to PROJECT_SPEC
 * section 5's preference for events. Registering a driver has to produce the user row and the
 * {@code PENDING} driver row in one commit: with an event, the profile would appear a moment later,
 * and a mobile client posting its vehicle immediately after signing up would get a 404 it did
 * nothing to deserve.
 *
 * <p>The dependency direction stays clean - {@code identity} calls {@code driver}'s public API and
 * knows nothing of its internals.
 */
public interface DriverRegistration {

  /**
   * Creates the driver profile for a newly registered user, PENDING approval and OFFLINE.
   *
   * <p>Idempotent: calling it again for the same user leaves the existing profile alone, so a
   * retried registration cannot fail on a duplicate key.
   */
  void createPendingProfile(UUID userId);
}
