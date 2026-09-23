package com.rideflow.shared.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.rideflow.shared.CommonErrorCode;
import com.rideflow.shared.ErrorCode;
import com.rideflow.shared.RideFlowException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

/**
 * The handler is exercised directly rather than through MockMvc: it has no controllers to serve
 * yet, and what matters at this stage is the shape of the problem detail required by PROJECT_SPEC
 * section 10.
 */
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  private static final WebRequest REQUEST =
      new ServletWebRequest(new MockHttpServletRequest("POST", "/api/v1/trips"));

  /** A module-specific code, exactly as a real module would declare one. */
  private enum TripErrorCode implements ErrorCode {
    TRIP_INVALID_TRANSITION(HttpStatus.CONFLICT, "Invalid trip transition");

    private final HttpStatus status;
    private final String title;

    TripErrorCode(HttpStatus status, String title) {
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

  @Nested
  class DeliberateErrors {

    @Test
    void reportsTheModulesOwnCodeAndStatus() {
      RideFlowException exception =
          new RideFlowException(
              TripErrorCode.TRIP_INVALID_TRANSITION, "Cannot complete a trip that has not started");

      ResponseEntity<ProblemDetail> response = handler.handleRideFlowException(exception);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      ProblemDetail problem = response.getBody();
      assertThat(problem).isNotNull();
      assertThat(problem.getStatus()).isEqualTo(409);
      assertThat(problem.getTitle()).isEqualTo("Invalid trip transition");
      assertThat(problem.getDetail()).isEqualTo("Cannot complete a trip that has not started");
      assertThat(problem.getProperties()).containsEntry("code", "TRIP_INVALID_TRANSITION");
    }

    @Test
    void mergesExtraPropertiesIntoTheProblemDetail() {
      RideFlowException exception =
          new RideFlowException(
              TripErrorCode.TRIP_INVALID_TRANSITION,
              "Cannot complete a trip that has not started",
              Map.of("currentStatus", "REQUESTED", "allowed", List.of("DRIVER_ASSIGNED")));

      ProblemDetail problem = handler.handleRideFlowException(exception).getBody();

      assertThat(problem).isNotNull();
      assertThat(problem.getProperties())
          .containsEntry("currentStatus", "REQUESTED")
          .containsEntry("allowed", List.of("DRIVER_ASSIGNED"))
          .containsEntry("code", "TRIP_INVALID_TRANSITION");
    }

    @Test
    void reportsAMissingResourceAsNotFound() {
      ResponseEntity<ProblemDetail> response =
          handler.handleRideFlowException(RideFlowException.notFound("Trip not found"));

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().getProperties()).containsEntry("code", "NOT_FOUND");
    }

    @Test
    void reportsABusinessRuleBreachAsUnprocessable() {
      ResponseEntity<ProblemDetail> response =
          handler.handleRideFlowException(RideFlowException.businessRule("Quote has expired"));

      assertThat(response.getStatusCode().value()).isEqualTo(422);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().getProperties())
          .containsEntry("code", "BUSINESS_RULE_VIOLATED");
    }
  }

  @Nested
  class UnexpectedErrors {

    @Test
    void reportsAnUnexpectedFailureAsAGenericInternalError() {
      ResponseEntity<ProblemDetail> response =
          handler.handleUnexpected(new IllegalStateException("connection to db-primary-7 refused"));

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }

    @Test
    void neverLeaksTheUnderlyingCauseToTheCaller() {
      ResponseEntity<ProblemDetail> response =
          handler.handleUnexpected(new IllegalStateException("connection to db-primary-7 refused"));

      assertThat(response.getBody()).isNotNull();
      // An internal host name in an error body is an information leak.
      assertThat(response.getBody().getDetail())
          .isEqualTo("An unexpected error occurred")
          .doesNotContain("db-primary-7");
    }
  }

  @Nested
  class ValidationErrors {

    @Test
    void listsEachInvalidFieldFromARequestBody() throws Exception {
      Method method = Target.class.getDeclaredMethod("book", String.class);
      BeanPropertyBindingResult bindingResult =
          new BeanPropertyBindingResult(new Object(), "bookingRequest");
      bindingResult.addError(
          new FieldError("bookingRequest", "phone", "must be a valid E.164 number"));
      bindingResult.addError(new FieldError("bookingRequest", "dropLat", "must be in [-90, 90]"));

      ResponseEntity<Object> response =
          handler.handleMethodArgumentNotValid(
              new MethodArgumentNotValidException(new MethodParameter(method, 0), bindingResult),
              new HttpHeaders(),
              HttpStatus.BAD_REQUEST,
              REQUEST);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      ProblemDetail problem = (ProblemDetail) response.getBody();
      assertThat(problem).isNotNull();
      assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_FAILED");

      @SuppressWarnings("unchecked")
      List<Map<String, String>> errors =
          (List<Map<String, String>>) problem.getProperties().get("errors");
      // Sorted by field name, so the response is stable and easy to assert on.
      assertThat(errors)
          .containsExactly(
              Map.of("field", "dropLat", "message", "must be in [-90, 90]"),
              Map.of("field", "phone", "message", "must be a valid E.164 number"));
    }

    @Test
    void listsViolationsFromValidatedMethodParameters() {
      try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
        Validator validator = factory.getValidator();
        ConstraintViolationException exception =
            new ConstraintViolationException(validator.validate(new Booking("")));

        ResponseEntity<ProblemDetail> response = handler.handleConstraintViolation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_FAILED");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> errors =
            (List<Map<String, String>>) problem.getProperties().get("errors");
        assertThat(errors)
            .singleElement()
            .extracting(error -> error.get("field"))
            .isEqualTo("pickupAddress");
      }
    }

    private record Booking(@NotBlank String pickupAddress) {}

    @SuppressWarnings("unused")
    private static final class Target {
      void book(String request) {}
    }
  }

  @Nested
  class SpringsOwnErrors {

    @Test
    void stampsACodeOntoProblemDetailsRaisedBySpring() {
      // Spring produces this itself for an unsupported method; without the override it
      // would reach the client with no code field at all.
      ProblemDetail problem =
          ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "POST not supported");

      handler.createResponseEntity(
          problem, new HttpHeaders(), HttpStatus.METHOD_NOT_ALLOWED, REQUEST);

      assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    void mapsEachStatusToItsCode() {
      assertThat(codeFor(HttpStatus.UNAUTHORIZED)).isEqualTo("UNAUTHENTICATED");
      assertThat(codeFor(HttpStatus.FORBIDDEN)).isEqualTo("FORBIDDEN");
      assertThat(codeFor(HttpStatus.NOT_FOUND)).isEqualTo("NOT_FOUND");
      assertThat(codeFor(HttpStatus.CONFLICT)).isEqualTo("CONFLICT");
      assertThat(codeFor(HttpStatus.TOO_MANY_REQUESTS)).isEqualTo("RATE_LIMITED");
      assertThat(codeFor(HttpStatus.BAD_GATEWAY)).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void leavesAnExistingCodeAlone() {
      ProblemDetail problem =
          ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Already assigned");
      problem.setProperty("code", CommonErrorCode.IDEMPOTENCY_KEY_REUSED.code());

      handler.createResponseEntity(problem, new HttpHeaders(), HttpStatus.CONFLICT, REQUEST);

      assertThat(problem.getProperties()).containsEntry("code", "IDEMPOTENCY_KEY_REUSED");
    }

    private String codeFor(HttpStatus status) {
      ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "detail");
      handler.createResponseEntity(problem, new HttpHeaders(), status, REQUEST);
      return (String) problem.getProperties().get("code");
    }
  }
}
