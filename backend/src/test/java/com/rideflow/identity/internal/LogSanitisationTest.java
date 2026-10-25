package com.rideflow.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rideflow.TestcontainersConfiguration;
import com.rideflow.identity.Role;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.internal.web.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Nothing sensitive reaches a log.
 *
 * <p>PROJECT_SPEC section 11 forbids logging passwords, tokens, OTPs and full phone numbers. That
 * is a rule no review reliably enforces, because the offending line is usually added later and
 * looks harmless - so this captures real log output while the whole auth flow runs and asserts on
 * it.
 *
 * <p>Deliberately asserts on the <em>root</em> logger at DEBUG, so a careless {@code log.debug} in
 * any package fails this test rather than only the classes somebody thought to check.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class LogSanitisationTest {

  private static final String PHONE = "+919876543210";
  private static final String PASSWORD = "super-secret-passphrase";

  @Autowired private AuthService auth;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PhoneRateLimiter phoneRateLimiter;
  @Autowired private UserRepository users;
  @Autowired private GlobalExceptionHandler errors;

  private Logger rootLogger;
  private ListAppender<ILoggingEvent> captured;
  private Level originalLevel;

  @BeforeEach
  void captureLogs() {
    jdbc.execute("TRUNCATE refresh_tokens, vehicles, drivers, users CASCADE");
    phoneRateLimiter.reset();

    rootLogger = (Logger) LoggerFactory.getLogger("com.rideflow");
    originalLevel = rootLogger.getLevel();
    rootLogger.setLevel(Level.DEBUG);

    captured = new ListAppender<>();
    captured.start();
    rootLogger.addAppender(captured);
  }

  @AfterEach
  void releaseLogs() {
    rootLogger.detachAppender(captured);
    captured.stop();
    rootLogger.setLevel(originalLevel);
  }

  /** Every captured message plus its formatted arguments, as one blob to search. */
  private String allLogOutput() {
    StringBuilder everything = new StringBuilder();
    for (ILoggingEvent event : List.copyOf(captured.list)) {
      everything.append(event.getFormattedMessage()).append('\n');
      if (event.getThrowableProxy() != null) {
        everything.append(event.getThrowableProxy().getMessage()).append('\n');
      }
    }
    return everything.toString();
  }

  @Test
  void neverLogsThePassword() {
    auth.register(PHONE, "Asha", PASSWORD, Role.RIDER);
    auth.login(PHONE, PASSWORD);
    assertThatExceptionOfType(BusinessException.class).isThrownBy(() -> auth.login(PHONE, "wrong"));

    assertThat(allLogOutput()).doesNotContain(PASSWORD).doesNotContain("wrong");
  }

  @Test
  void neverLogsAFullPhoneNumber() {
    var tokens = auth.register(PHONE, "Asha", PASSWORD, Role.RIDER);
    auth.login(PHONE, PASSWORD);
    auth.refresh(tokens.refreshToken());

    String output = allLogOutput();
    // The whole number must not appear, nor the subscriber part without its country code.
    assertThat(output).doesNotContain(PHONE).doesNotContain("9876543210");
    // The masked form is fine, and is what makes the log useful at all.
    assertThat(output).contains("****3210");
  }

  /**
   * A duplicate number that reaches the database, as two registrations at once can: PostgreSQL's
   * error quotes the value that clashed, and both Hibernate and the 409 handler logged it whole.
   */
  @Test
  void neverLogsThePhoneNumberAUniqueConstraintQuotes() {
    auth.register(PHONE, "Asha", PASSWORD, Role.RIDER);
    // Hibernate logs outside com.rideflow: listen to everything for this one.
    Logger everything = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    everything.addAppender(captured);
    try {
      DataIntegrityViolationException duplicate =
          catchThrowableOfType(
              DataIntegrityViolationException.class,
              () ->
                  users.saveAndFlush(
                      new User(UUID.randomUUID(), PHONE, "Asha", "x", Role.RIDER, Instant.now())));
      errors.handleDataIntegrityViolation(duplicate);
    } finally {
      everything.detachAppender(captured);
    }

    String output = allLogOutput();
    assertThat(output).contains("users_phone_unique");
    assertThat(output).doesNotContain(PHONE).doesNotContain("9876543210");
  }

  @Test
  void neverLogsARefreshTokenOrItsHash() {
    var tokens = auth.register(PHONE, "Asha", PASSWORD, Role.RIDER);
    var rotated = auth.refresh(tokens.refreshToken());
    auth.logout(rotated.refreshToken());

    assertThat(allLogOutput())
        .doesNotContain(tokens.refreshToken())
        .doesNotContain(rotated.refreshToken());
  }

  @Test
  void neverLogsAnAccessToken() {
    var tokens = auth.register(PHONE, "Asha", PASSWORD, Role.RIDER);

    assertThat(allLogOutput()).doesNotContain(tokens.accessToken());
  }

  @Test
  void keepsTheReplayWarningUsefulWithoutLeakingTheToken() {
    var first = auth.register(PHONE, "Asha", PASSWORD, Role.RIDER);
    auth.refresh(first.refreshToken());
    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> auth.refresh(first.refreshToken()));

    String output = allLogOutput();
    // The operator needs to know a replay happened and whose session it was.
    assertThat(output).contains("replay");
    // But not the token that was replayed.
    assertThat(output).doesNotContain(first.refreshToken());
  }
}
