package com.rideflow.shared.internal.web;

import com.rideflow.shared.CommonErrorCode;
import com.rideflow.shared.ErrorCode;
import com.rideflow.shared.RideFlowException;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 9457 problem details.
 *
 * <p>PROJECT_SPEC section 10 requires every error to be a problem detail carrying an extra {@code
 * code} field. This handler is the single place that guarantees it, including for the exceptions
 * Spring MVC raises itself.
 *
 * <p>It extends {@link ResponseEntityExceptionHandler} rather than catching {@link Exception}
 * alone. A lone {@code Exception} handler would also swallow Spring's own exceptions — an
 * unsupported method, an unreadable body — and report them as 500, hiding client mistakes as server
 * faults. The base class handles those with their correct statuses, and {@link
 * #createResponseEntity} stamps the {@code code} field onto the result.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private static final String CODE_PROPERTY = "code";
  private static final String ERRORS_PROPERTY = "errors";

  /** An error raised deliberately by a business rule. */
  @ExceptionHandler(RideFlowException.class)
  public ResponseEntity<ProblemDetail> handleRideFlowException(RideFlowException exception) {
    ErrorCode errorCode = exception.errorCode();
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(errorCode.status(), exception.getMessage());
    problem.setTitle(errorCode.title());
    problem.setProperty(CODE_PROPERTY, errorCode.code());
    exception.properties().forEach(problem::setProperty);

    if (errorCode.status().is5xxServerError()) {
      log.error("Request failed with {}", errorCode.code(), exception);
    } else {
      // A 4xx is the caller's mistake, not a fault: log it without a stack trace.
      log.debug("Request rejected with {}: {}", errorCode.code(), exception.getMessage());
    }
    return ResponseEntity.status(errorCode.status()).body(problem);
  }

  /** Bean Validation failures on {@code @Validated} method parameters. */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ProblemDetail> handleConstraintViolation(
      ConstraintViolationException exception) {
    List<Map<String, String>> errors =
        exception.getConstraintViolations().stream()
            .map(
                violation ->
                    Map.of(
                        "field", String.valueOf(violation.getPropertyPath()),
                        "message", violation.getMessage()))
            .sorted(Comparator.comparing(error -> error.get("field")))
            .toList();

    ProblemDetail problem =
        problemFor(CommonErrorCode.VALIDATION_FAILED, "One or more fields are invalid");
    problem.setProperty(ERRORS_PROPERTY, errors);
    log.debug("Request rejected with VALIDATION_FAILED: {}", errors);
    return ResponseEntity.status(CommonErrorCode.VALIDATION_FAILED.status()).body(problem);
  }

  /**
   * Anything not handled above.
   *
   * <p>The cause is logged in full and never returned: an exception message can carry a SQL
   * fragment, a file path or an internal host name.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
    log.error("Unhandled exception", exception);
    ProblemDetail problem =
        problemFor(CommonErrorCode.INTERNAL_ERROR, "An unexpected error occurred");
    return ResponseEntity.status(CommonErrorCode.INTERNAL_ERROR.status()).body(problem);
  }

  /** Bean Validation failures on an {@code @Valid @RequestBody}, reported field by field. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {

    List<Map<String, String>> errors =
        exception.getBindingResult().getFieldErrors().stream()
            .map(
                fieldError ->
                    Map.of(
                        "field",
                        fieldError.getField(),
                        "message",
                        fieldError.getDefaultMessage() == null
                            ? "is invalid"
                            : fieldError.getDefaultMessage()))
            .sorted(Comparator.comparing(error -> error.get("field")))
            .toList();

    ProblemDetail problem =
        problemFor(CommonErrorCode.VALIDATION_FAILED, "One or more fields are invalid");
    problem.setProperty(ERRORS_PROPERTY, errors);
    log.debug("Request rejected with VALIDATION_FAILED: {}", errors);
    return ResponseEntity.status(CommonErrorCode.VALIDATION_FAILED.status()).body(problem);
  }

  /**
   * Adds the {@code code} field to the problem details produced by the base class for Spring's own
   * exceptions, so no error response can reach a client without one.
   */
  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

    if (body instanceof ProblemDetail problem
        && (problem.getProperties() == null
            || !problem.getProperties().containsKey(CODE_PROPERTY))) {
      problem.setProperty(CODE_PROPERTY, defaultCodeFor(statusCode).code());
    }
    return super.createResponseEntity(body, headers, statusCode, request);
  }

  private static ProblemDetail problemFor(ErrorCode errorCode, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.status(), detail);
    problem.setTitle(errorCode.title());
    problem.setProperty(CODE_PROPERTY, errorCode.code());
    return problem;
  }

  /** Maps a status raised by Spring itself onto the closest code from PROJECT_SPEC section 10. */
  private static CommonErrorCode defaultCodeFor(HttpStatusCode statusCode) {
    return switch (HttpStatus.valueOf(statusCode.value())) {
      case BAD_REQUEST -> CommonErrorCode.VALIDATION_FAILED;
      case UNAUTHORIZED -> CommonErrorCode.UNAUTHENTICATED;
      case FORBIDDEN -> CommonErrorCode.FORBIDDEN;
      case NOT_FOUND -> CommonErrorCode.NOT_FOUND;
      case CONFLICT -> CommonErrorCode.CONFLICT;
      case UNPROCESSABLE_CONTENT -> CommonErrorCode.BUSINESS_RULE_VIOLATED;
      case TOO_MANY_REQUESTS -> CommonErrorCode.RATE_LIMITED;
      default ->
          statusCode.is4xxClientError()
              ? CommonErrorCode.VALIDATION_FAILED
              : CommonErrorCode.INTERNAL_ERROR;
    };
  }
}
