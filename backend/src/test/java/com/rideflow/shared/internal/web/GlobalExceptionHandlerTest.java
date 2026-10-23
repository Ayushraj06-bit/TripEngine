package com.rideflow.shared.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.rideflow.shared.BusinessException;
import com.rideflow.shared.KeyedRateLimiter;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * The error contract of PROJECT_SPEC section 10, one path at a time: whatever goes wrong, the
 * client gets a problem detail with a {@code code} it can branch on.
 *
 * <p>Called directly rather than through HTTP, because several of these paths - an unexpected
 * failure, a constraint no module translated - should never be reachable on purpose.
 */
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  private static Object codeOf(ResponseEntity<?> response) {
    ProblemDetail problem = (ProblemDetail) response.getBody();
    return problem.getProperties().get("code");
  }

  /** Redis or PostgreSQL timing out or refusing: the service is unwell, the request is fine. */
  @Test
  void answers503WithRetryAfterWhenABackingStoreIsAway() {
    for (var away :
        java.util.List.of(
            new org.springframework.dao.QueryTimeoutException("Redis command timed out"),
            new org.springframework.data.redis.RedisConnectionFailureException("refused"),
            new org.springframework.transaction.CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction"))) {
      var response = handler.handleUnavailable(away);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
      assertThat(codeOf(response)).isEqualTo("SERVICE_UNAVAILABLE");
      // Nothing about the store reaches the client.
      assertThat(((ProblemDetail) response.getBody()).getDetail()).doesNotContain("Redis");
    }
  }

  /** A lock timeout, a broken deadlock, a moved version: a conflict to retry, not a 500. */
  @Test
  void answers409WithRetryAfterForAConcurrentUpdate() {
    for (var conflict :
        java.util.List.of(
            new org.springframework.dao.CannotAcquireLockException("lock timeout"),
            new org.springframework.dao.PessimisticLockingFailureException("deadlock detected"),
            new org.springframework.orm.ObjectOptimisticLockingFailureException(
                "Trip", java.util.UUID.randomUUID()))) {
      var response = handler.handleConcurrentUpdate(conflict);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
      assertThat(codeOf(response)).isEqualTo("CONCURRENT_UPDATE");
    }
  }

  @Test
  void keepsTheStatusAndCodeOfAServerSideBusinessException() {
    var response =
        handler.handleBusinessException(
            new BusinessException(
                "LEDGER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "Try again shortly"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(codeOf(response)).isEqualTo("LEDGER_UNAVAILABLE");
  }

  @Test
  void saysWhenToComeBackForAnyStatusThatCarriesAWait() {
    var response =
        handler.handleBusinessException(
            new BusinessException(
                "IDEMPOTENCY_KEY_IN_FLIGHT",
                HttpStatus.CONFLICT,
                "Retry shortly",
                Map.of(KeyedRateLimiter.RETRY_AFTER_SECONDS, 1L)));

    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
  }

  private record Probe(@NotNull String name, @Min(1) int count) {}

  @Test
  @SuppressWarnings("unchecked")
  void listsEachInvalidMethodParameterInFieldOrder() {
    try (ValidatorFactory validation = Validation.buildDefaultValidatorFactory()) {
      var violations = validation.getValidator().validate(new Probe(null, 0));

      var response =
          handler.handleConstraintViolation(new ConstraintViolationException(violations));

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(codeOf(response)).isEqualTo("VALIDATION_FAILED");
      assertThat((List<Map<String, String>>) response.getBody().getProperties().get("errors"))
          .extracting(error -> error.get("field"))
          .containsExactly("count", "name");
    }
  }

  @Test
  void answersAConstraintNoModuleTranslatedWithAGenericConflict() {
    var response =
        handler.handleDataIntegrityViolation(new DataIntegrityViolationException("duplicate key"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(codeOf(response)).isEqualTo("CONFLICT");
  }

  @Test
  void keepsTheDetailOfAnUnexpectedFailureToItself() {
    var response =
        handler.handleUnexpected(new IllegalStateException("connection string with a password"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(codeOf(response)).isEqualTo("INTERNAL_ERROR");
    assertThat(response.getBody().getDetail()).doesNotContain("password");
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "400, VALIDATION_FAILED",
    "401, UNAUTHENTICATED",
    "403, FORBIDDEN",
    "404, NOT_FOUND",
    "405, METHOD_NOT_ALLOWED",
    "409, CONFLICT",
    "415, UNSUPPORTED_MEDIA_TYPE",
    "418, VALIDATION_FAILED",
    "422, BUSINESS_RULE_VIOLATED",
    "429, RATE_LIMITED",
    "503, INTERNAL_ERROR"
  })
  void codesSpringsOwnErrorsByStatus(int status, String code) throws Exception {
    ResponseEntity<Object> response =
        handler.handleException(
            new ResponseStatusException(HttpStatusCode.valueOf(status)),
            new ServletWebRequest(new MockHttpServletRequest()));

    assertThat(response.getStatusCode().value()).isEqualTo(status);
    assertThat(codeOf(response)).isEqualTo(code);
  }
}
