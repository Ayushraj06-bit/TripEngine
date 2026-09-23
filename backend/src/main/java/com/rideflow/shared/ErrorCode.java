package com.rideflow.shared;

import org.springframework.http.HttpStatus;

/**
 * A machine-readable error identifier and the HTTP status it maps to.
 *
 * <p>PROJECT_SPEC section 10 requires every error response to be an RFC 9457 problem detail
 * carrying an extra {@code code} field, such as {@code TRIP_INVALID_TRANSITION} or {@code
 * QUOTE_EXPIRED}.
 *
 * <p>This is an interface rather than one central enum on purpose. A single enum listing every code
 * in the system would have to be edited by every module, which turns the shared kernel into a
 * coupling point and defeats the module boundaries of PROJECT_SPEC section 5. Instead each module
 * declares its own enum implementing this interface and keeps its codes next to the rules that
 * raise them. {@link CommonErrorCode} covers only the cross-cutting cases.
 */
public interface ErrorCode {

  /**
   * The stable, machine-readable code, in {@code SCREAMING_SNAKE_CASE}.
   *
   * <p>Clients branch on this value, so it is part of the public API: never rename one without
   * versioning the endpoint.
   */
  String code();

  /** The HTTP status this error is reported with. */
  HttpStatus status();

  /** A short, human-readable summary used as the problem detail's title. */
  String title();
}
