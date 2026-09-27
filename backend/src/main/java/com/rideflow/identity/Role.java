package com.rideflow.identity;

/**
 * Who a user is.
 *
 * <p>Stored as the enum name, carried in the {@code role} claim of the access token, and mapped to
 * a Spring Security authority of {@code ROLE_<name>}.
 */
public enum Role {
  RIDER,
  DRIVER,
  ADMIN;

  /** The Spring Security authority for this role. */
  public String authority() {
    return "ROLE_" + name();
  }
}
