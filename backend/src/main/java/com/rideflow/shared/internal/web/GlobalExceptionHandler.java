package com.rideflow.shared.internal.web;

import com.rideflow.shared.BusinessException;
import com.rideflow.shared.KeyedRateLimiter;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
 * alone. A lone {@code Exception} handler would also swallow Spring's own exceptions - an
 * unsupported method, an unreadable body, an unknown route - and report them as 500, hiding client
 * mistakes as server faults. The base class handles those with their correct statuses, and {@link
 * #createResponseEntity} stamps the {@code code} field onto the result.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private static final String CODE_PROPERTY = "code";
  private static final String ERRORS_PROPERTY = "errors";

  static final String VALIDATION_FAILED = "VALIDATION_FAILED";
  static final String UNAUTHENTICATED = "UNAUTHENTICATED";
  static final String FORBIDDEN = "FORBIDDEN";
  static final String NOT_FOUND = "NOT_FOUND";
  static final String CONFLICT = "CONFLICT";
  static final String BUSINESS_RULE_VIOLATED = "BUSINESS_RULE_VIOLATED";
  static final String RATE_LIMITED = "RATE_LIMITED";
  static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
  static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
  static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  /** An error raised deliberately by a business rule. */
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ProblemDetail> handleBusinessException(BusinessException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
    problem.setProperty(CODE_PROPERTY, exception.code());
    exception.properties().forEach(problem::setProperty);

    if (exception.status().is5xxServerError()) {
      log.error("Request failed with {}", exception.code(), exception);
    } else {
      // A 4xx is the caller's mistake, not a fault: log it without a stack trace.
      log.debug("Request rejected with {}: {}", exception.code(), exception.getMessage());
    }
    var response = ResponseEntity.status(exception.status());
    // RFC 9110: a 429 should say when to come back. Emitted here, once, for every
    // limit raised through BusinessException rather than by each limiter.
    Object retryAfter = exception.properties().get(KeyedRateLimiter.RETRY_AFTER_SECONDS);
    if (exception.status() == HttpStatus.TOO_MANY_REQUESTS && retryAfter != null) {
      response.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
    }
    return response.body(problem);
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

    log.debug("Request rejected with {}: {}", VALIDATION_FAILED, errors);
    return ResponseEntity.badRequest().body(validationProblem(errors));
  }

  /**
   * A unique or check constraint rejected the write.
   *
   * <p>Almost every "does it already exist?" check in the application is read-then-write, so two
   * concurrent requests can both pass the check and one loses at the constraint. Without this
   * handler that loser gets a 500 for what is really a duplicate, and the caller cannot tell a
   * conflict from an outage. The database is the authority PROJECT_SPEC section 8 says it is; this
   * translates its verdict into the 409 section 10 asks for.
   *
   * <p>The cause is logged at warn with the constraint name, because this also fires when a genuine
   * bug violates a constraint, and that must not be indistinguishable from a race.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
      DataIntegrityViolationException exception) {
    log.warn("Constraint violation translated to 409", exception);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, "That record already exists or conflicts with an existing one");
    problem.setProperty(CODE_PROPERTY, CONFLICT);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
  }

  /**
   * An authenticated caller without the right role.
   *
   * <p>This handler is not optional. Method security throws {@link AccessDeniedException} inside
   * the DispatcherServlet, so this advice sees it before Spring Security's {@code
   * ExceptionTranslationFilter} ever does - and without an explicit handler the catch-all below
   * would report a routine authorization denial as a 500.
   *
   * <p>403 rather than 404 here because the caller's role is wrong for the endpoint, which reveals
   * nothing about anyone's data. Section 10's "404 for someone else's resource" rule applies to
   * ownership checks inside a service, not to role checks on a route.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception) {
    log.debug("Request rejected with {}: {}", FORBIDDEN, exception.getMessage());
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN, "Your role does not permit this operation");
    problem.setProperty(CODE_PROPERTY, FORBIDDEN);
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
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
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    problem.setProperty(CODE_PROPERTY, INTERNAL_ERROR);
    return ResponseEntity.internalServerError().body(problem);
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

    log.debug("Request rejected with {}: {}", VALIDATION_FAILED, errors);
    return ResponseEntity.badRequest().body(validationProblem(errors));
  }

  /**
   * Adds the {@code code} field to the problem details the base class produces for Spring's own
   * exceptions, so no error response can reach a client without one.
   */
  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

    if (body instanceof ProblemDetail problem
        && (problem.getProperties() == null
            || !problem.getProperties().containsKey(CODE_PROPERTY))) {
      problem.setProperty(CODE_PROPERTY, defaultCodeFor(statusCode));
    }
    return super.createResponseEntity(body, headers, statusCode, request);
  }

  private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more fields are invalid");
    problem.setProperty(CODE_PROPERTY, VALIDATION_FAILED);
    problem.setProperty(ERRORS_PROPERTY, errors);
    return problem;
  }

  /** Maps a status raised by Spring itself onto the closest code from PROJECT_SPEC section 10. */
  private static String defaultCodeFor(HttpStatusCode statusCode) {
    return switch (HttpStatus.valueOf(statusCode.value())) {
      case BAD_REQUEST -> VALIDATION_FAILED;
      case UNAUTHORIZED -> UNAUTHENTICATED;
      case FORBIDDEN -> FORBIDDEN;
      case NOT_FOUND -> NOT_FOUND;
      case METHOD_NOT_ALLOWED -> METHOD_NOT_ALLOWED;
      case UNSUPPORTED_MEDIA_TYPE -> UNSUPPORTED_MEDIA_TYPE;
      case CONFLICT -> CONFLICT;
      case UNPROCESSABLE_CONTENT -> BUSINESS_RULE_VIOLATED;
      case TOO_MANY_REQUESTS -> RATE_LIMITED;
      default -> statusCode.is4xxClientError() ? VALIDATION_FAILED : INTERNAL_ERROR;
    };
  }
}
