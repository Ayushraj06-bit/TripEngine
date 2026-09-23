package com.rideflow.shared;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Base class for every error RideFlow reports deliberately.
 *
 * <p>Carrying an {@link ErrorCode} means the HTTP status and the {@code code} field of the response
 * are decided where the rule lives, not guessed by the exception handler. Anything that escapes
 * without being one of these becomes a 500 with a generic message, which is the signal that a case
 * was not thought through.
 *
 * <p>Not {@code sealed}: modules subclass it to add their own errors. It is deliberately unchecked,
 * so business rules do not have to be declared through every intermediate signature.
 */
public class RideFlowException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient ErrorCode errorCode;
  private final transient Map<String, Object> properties;

  public RideFlowException(ErrorCode errorCode, String detail) {
    this(errorCode, detail, Map.of(), null);
  }

  public RideFlowException(ErrorCode errorCode, String detail, Throwable cause) {
    this(errorCode, detail, Map.of(), cause);
  }

  public RideFlowException(ErrorCode errorCode, String detail, Map<String, Object> properties) {
    this(errorCode, detail, properties, null);
  }

  /**
   * @param errorCode the code and HTTP status to report
   * @param detail human-readable explanation, safe to return to the caller. Never put a password,
   *     token, OTP or full phone number in here (PROJECT_SPEC section 11).
   * @param properties extra fields to add to the problem detail, for example the allowed
   *     transitions of a rejected state change
   * @param cause the underlying failure, logged but never returned
   */
  public RideFlowException(
      ErrorCode errorCode, String detail, Map<String, Object> properties, Throwable cause) {
    super(Objects.requireNonNull(detail, "detail"), cause);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    this.properties =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(properties, "properties")));
  }

  public ErrorCode errorCode() {
    return errorCode;
  }

  /** Extra fields to merge into the problem detail. Never null, possibly empty. */
  public Map<String, Object> properties() {
    return properties;
  }

  /**
   * A resource that does not exist, or that the caller may not see.
   *
   * <p>The detail must not reveal whether the resource exists for somebody else.
   */
  public static RideFlowException notFound(String detail) {
    return new RideFlowException(CommonErrorCode.NOT_FOUND, detail);
  }

  /** A request that conflicts with the current state. */
  public static RideFlowException conflict(String detail) {
    return new RideFlowException(CommonErrorCode.CONFLICT, detail);
  }

  /** A well-formed request that breaks a business rule. */
  public static RideFlowException businessRule(String detail) {
    return new RideFlowException(CommonErrorCode.BUSINESS_RULE_VIOLATED, detail);
  }
}
