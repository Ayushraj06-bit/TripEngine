package com.rideflow.shared;

import org.springframework.http.HttpStatus;

/**
 * Error codes that are not specific to any one module.
 *
 * <p>Module-specific codes do not belong here. A module declares its own enum implementing {@link
 * ErrorCode} next to the rules that raise it.
 *
 * <p>The status mapping follows PROJECT_SPEC section 10.
 */
public enum CommonErrorCode implements ErrorCode {

  /** Input failed Bean Validation or could not be parsed. */
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),

  /** No credentials, or credentials that are not valid. */
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),

  /** Authenticated, but the caller's role does not permit this operation. */
  FORBIDDEN(HttpStatus.FORBIDDEN, "Not permitted"),

  /**
   * The resource does not exist, or does not belong to the caller.
   *
   * <p>PROJECT_SPEC section 10 deliberately collapses both cases into 404: answering 403 for
   * someone else's trip would confirm that the trip exists.
   */
  NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),

  /** The request conflicts with the current state, or duplicates an existing resource. */
  CONFLICT(HttpStatus.CONFLICT, "Conflict"),

  /** The request was well formed but breaks a business rule. */
  BUSINESS_RULE_VIOLATED(HttpStatus.UNPROCESSABLE_CONTENT, "Business rule violated"),

  /** The same {@code Idempotency-Key} was replayed with a different body. */
  IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency key reused"),

  /** The caller exceeded its rate limit. */
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),

  /** Anything unexpected. The cause is logged, never returned. */
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

  private final HttpStatus status;
  private final String title;

  CommonErrorCode(HttpStatus status, String title) {
    this.status = status;
    this.title = title;
  }

  @Override
  public String code() {
    return name();
  }

  @Override
  public HttpStatus status() {
    return status;
  }

  @Override
  public String title() {
    return title;
  }
}
