package com.rideflow.shared;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * Base class for every error RideFlow reports deliberately.
 *
 * <p>Carrying the code and status on the exception means the HTTP response is decided where the
 * rule lives, not guessed by the exception handler. Anything that escapes without being one of
 * these becomes a 500 with a generic message, which is the signal that a case was not thought
 * through.
 *
 * <p>Not {@code sealed}: modules subclass it to add their own errors. It is unchecked, so business
 * rules do not have to be declared through every intermediate signature.
 */
public class BusinessException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String code;
  private final transient HttpStatus status;
  private final transient Map<String, Object> properties;

  /**
   * @param code stable machine-readable code in SCREAMING_SNAKE_CASE, for example {@code
   *     TRIP_INVALID_TRANSITION}. Clients branch on it, so it is part of the public API.
   * @param status the HTTP status to report
   * @param message explanation safe to return to the caller. Never put a password, token, OTP or
   *     full phone number in here (PROJECT_SPEC section 11).
   */
  public BusinessException(String code, HttpStatus status, String message) {
    this(code, status, message, Map.of(), null);
  }

  public BusinessException(String code, HttpStatus status, String message, Throwable cause) {
    this(code, status, message, Map.of(), cause);
  }

  /**
   * @param properties extra fields to merge into the problem detail, for example the allowed
   *     transitions of a rejected state change
   */
  public BusinessException(
      String code, HttpStatus status, String message, Map<String, Object> properties) {
    this(code, status, message, properties, null);
  }

  public BusinessException(
      String code,
      HttpStatus status,
      String message,
      Map<String, Object> properties,
      Throwable cause) {
    super(Objects.requireNonNull(message, "message"), cause);
    this.code = requireCode(code);
    this.status = Objects.requireNonNull(status, "status");
    this.properties =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(properties, "properties")));
  }

  private static String requireCode(String code) {
    Objects.requireNonNull(code, "code");
    if (code.isBlank()) {
      throw new IllegalArgumentException("code must not be blank");
    }
    return code;
  }

  public String code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }

  /** Extra fields to merge into the problem detail. Never null, possibly empty. */
  public Map<String, Object> properties() {
    return properties;
  }

  /**
   * A resource that does not exist, or that the caller may not see.
   *
   * <p>PROJECT_SPEC section 10 collapses both cases into 404 deliberately: answering 403 for
   * someone else's trip would confirm that the trip exists. The message must not reveal it either.
   */
  public static BusinessException notFound(String message) {
    return new BusinessException("NOT_FOUND", HttpStatus.NOT_FOUND, message);
  }

  /** A request that conflicts with the current state, or duplicates an existing resource. */
  public static BusinessException conflict(String code, String message) {
    return new BusinessException(code, HttpStatus.CONFLICT, message);
  }

  /** A well-formed request that breaks a business rule. */
  public static BusinessException businessRule(String code, String message) {
    return new BusinessException(code, HttpStatus.UNPROCESSABLE_CONTENT, message);
  }
}
