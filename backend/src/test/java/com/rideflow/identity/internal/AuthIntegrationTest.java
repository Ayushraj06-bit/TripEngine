package com.rideflow.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.rideflow.TestcontainersConfiguration;
import com.rideflow.identity.Role;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * Identity against real PostgreSQL.
 *
 * <p>The clock is a {@link MutableClock}, so the lockout window and token expiry are exercised by
 * moving time rather than by sleeping, per PROJECT_SPEC section 12.
 */
@SpringBootTest(
    properties = {
      "rideflow.security.max-login-attempts=10",
      "rideflow.security.login-lock-duration=15m",
      "rideflow.security.access-token-ttl=15m",
      "rideflow.security.refresh-token-ttl=7d"
    })
@ActiveProfiles("test")
// Imported explicitly: a @TestConfiguration nested in the outer class is silently
// ignored for @Nested tests, which would leave the real system clock in place.
@Import({TestcontainersConfiguration.class, AuthIntegrationTest.Clocks.class})
class AuthIntegrationTest {

  private static final String PHONE = "+919876543210";
  private static final String PASSWORD = "correct-horse-battery";

  private static final Instant START = Instant.parse("2026-09-27T10:00:00Z");
  private static final MutableClock CLOCK = new MutableClock(START);

  @TestConfiguration
  static class Clocks {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @Autowired private AuthService auth;
  @Autowired private UserRepository users;
  @Autowired private RefreshTokenRepository refreshTokens;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PhoneRateLimiter phoneRateLimiter;

  @BeforeEach
  void reset() {
    CLOCK.set(START);
    // The limiter is a singleton whose buckets outlive each test, and a wall-clock
    // bucket does not refill when the clock is moved back to START.
    phoneRateLimiter.reset();
    jdbc.execute("TRUNCATE refresh_tokens, vehicles, drivers, users CASCADE");
  }

  private AuthService.Tokens registerRider() {
    return auth.register(PHONE, "Asha", PASSWORD, Role.RIDER);
  }

  @Nested
  class Registration {

    @Test
    void storesTheUserAndReturnsAUsableTokenPair() {
      AuthService.Tokens tokens = registerRider();

      assertThat(tokens.accessToken()).isNotBlank();
      assertThat(tokens.refreshToken()).isNotBlank();
      assertThat(tokens.expiresInSeconds()).isEqualTo(Duration.ofMinutes(15).toSeconds());
    }

    @Test
    void normalisesThePhoneNumberBeforeStoringIt() {
      auth.register("98765 43210", "Asha", PASSWORD, Role.RIDER);

      assertThat(users.findByPhone(PHONE)).isPresent();
    }

    @Test
    void rejectsTheSameNumberWrittenDifferently() {
      auth.register("+919876543210", "Asha", PASSWORD, Role.RIDER);

      // The case that motivates normalisation at all.
      for (String sameNumber : List.of("98765 43210", "+91-98765-43210", "09876543210")) {
        assertThatExceptionOfType(BusinessException.class)
            .isThrownBy(() -> auth.register(sameNumber, "Impostor", PASSWORD, Role.RIDER))
            .satisfies(e -> assertThat(e.code()).isEqualTo("PHONE_ALREADY_REGISTERED"))
            .withFailMessage("%s should have collided with the existing account", sameNumber);
      }
    }

    @Test
    void neverStoresThePasswordInPlaintext() {
      registerRider();

      assertThat(users.findByPhone(PHONE).orElseThrow().passwordHash())
          .isNotEqualTo(PASSWORD)
          .startsWith("$2");
    }

    @Test
    void storesOnlyTheHashOfTheRefreshToken() {
      AuthService.Tokens tokens = registerRider();

      // A database leak must not hand out usable refresh tokens.
      assertThat(refreshTokens.findByTokenHash(tokens.refreshToken())).isEmpty();
      assertThat(refreshTokens.findAll()).hasSize(1);
    }

    @Test
    void createsAPendingDriverProfileForADriver() {
      auth.register(PHONE, "Ravi", PASSWORD, Role.DRIVER);

      // Created in the same transaction, so posting a vehicle straight afterwards
      // cannot race an event.
      Integer profiles = jdbc.queryForObject("SELECT count(*) FROM drivers", Integer.class);
      assertThat(profiles).isOne();
      assertThat(jdbc.queryForObject("SELECT approval_status FROM drivers", String.class))
          .isEqualTo("PENDING");
    }

    @Test
    void createsNoDriverProfileForARider() {
      registerRider();

      assertThat(jdbc.queryForObject("SELECT count(*) FROM drivers", Integer.class)).isZero();
    }
  }

  @Nested
  class Login {

    @Test
    void succeedsWithTheCorrectPassword() {
      registerRider();

      assertThat(auth.login(PHONE, PASSWORD).accessToken()).isNotBlank();
    }

    @Test
    void acceptsAnyFormatOfTheRegisteredNumber() {
      registerRider();

      assertThat(auth.login("98765 43210", PASSWORD).accessToken()).isNotBlank();
    }

    @Test
    void rejectsTheWrongPassword() {
      registerRider();

      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> auth.login(PHONE, "wrong"))
          .satisfies(
              e -> {
                assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS");
                assertThat(e.status().value()).isEqualTo(401);
              });
    }

    @Test
    void givesTheSameAnswerForAnUnknownNumberAsForAWrongPassword() {
      registerRider();

      // Distinguishing them would let an attacker enumerate who is registered.
      BusinessException unknown =
          catchBusinessException(() -> auth.login("+919999999999", PASSWORD));
      BusinessException wrongPassword = catchBusinessException(() -> auth.login(PHONE, "wrong"));

      assertThat(unknown.code()).isEqualTo(wrongPassword.code());
      assertThat(unknown.getMessage()).isEqualTo(wrongPassword.getMessage());
    }
  }

  @Nested
  class Lockout {

    @Test
    void locksAfterTenFailuresAndRejectsEvenTheCorrectPassword() {
      registerRider();

      for (int attempt = 0; attempt < 10; attempt++) {
        assertThatExceptionOfType(BusinessException.class)
            .isThrownBy(() -> auth.login(PHONE, "wrong"));
      }

      // The counter has to survive ten rolled-back-looking failures, or lockout never
      // triggers. This is what noRollbackFor is for.
      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> auth.login(PHONE, PASSWORD))
          .satisfies(e -> assertThat(e.code()).isEqualTo("ACCOUNT_LOCKED"));
    }

    @Test
    void acceptsTheCorrectPasswordOnceTheLockExpires() {
      registerRider();
      for (int attempt = 0; attempt < 10; attempt++) {
        assertThatExceptionOfType(BusinessException.class)
            .isThrownBy(() -> auth.login(PHONE, "wrong"));
      }

      CLOCK.advance(Duration.ofMinutes(16));

      assertThat(auth.login(PHONE, PASSWORD).accessToken()).isNotBlank();
    }

    @Test
    void staysLockedWhileTheWindowIsStillOpen() {
      registerRider();
      for (int attempt = 0; attempt < 10; attempt++) {
        assertThatExceptionOfType(BusinessException.class)
            .isThrownBy(() -> auth.login(PHONE, "wrong"));
      }

      CLOCK.advance(Duration.ofMinutes(14));

      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> auth.login(PHONE, PASSWORD))
          .satisfies(e -> assertThat(e.code()).isEqualTo("ACCOUNT_LOCKED"));
    }

    @Test
    void resetsTheCounterOnASuccessfulLogin() {
      registerRider();
      for (int attempt = 0; attempt < 9; attempt++) {
        assertThatExceptionOfType(BusinessException.class)
            .isThrownBy(() -> auth.login(PHONE, "wrong"));
      }

      auth.login(PHONE, PASSWORD);

      // Past the flood guard's window, so this test measures the lockout counter
      // rather than the rate limiter.
      CLOCK.advance(Duration.ofMinutes(2));

      // Nine more failures must not reach the limit if the counter really reset.
      for (int attempt = 0; attempt < 9; attempt++) {
        assertThatExceptionOfType(BusinessException.class)
            .isThrownBy(() -> auth.login(PHONE, "wrong"));
      }
      assertThat(auth.login(PHONE, PASSWORD).accessToken()).isNotBlank();
    }
  }

  @Nested
  class AccessTokens {

    @Test
    void carriesTheSubjectAndRoleSoMethodSecurityCanSeeThem() {
      AuthService.Tokens tokens = registerRider();

      var jwt = jwtDecoder.decode(tokens.accessToken());
      assertThat(jwt.getSubject())
          .isEqualTo(users.findByPhone(PHONE).orElseThrow().id().toString());
      assertThat(jwt.getClaimAsString("role")).isEqualTo("RIDER");
      assertThat(jwt.getIssuer()).hasToString("https://rideflow.local");
    }

    @Test
    void rejectsATamperedPayload() {
      AuthService.Tokens tokens = registerRider();
      String[] parts = tokens.accessToken().split("\\.");
      // Re-sign nothing: swap the payload and keep the original signature.
      String tampered =
          parts[0]
              + "."
              + java.util.Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString("{\"sub\":\"attacker\",\"role\":\"ADMIN\"}".getBytes())
              + "."
              + parts[2];

      assertThatExceptionOfType(Exception.class).isThrownBy(() -> jwtDecoder.decode(tampered));
    }

    @Test
    void rejectsAnExpiredToken() {
      AuthService.Tokens tokens = registerRider();
      // Valid while inside its fifteen-minute life.
      assertThat(jwtDecoder.decode(tokens.accessToken())).isNotNull();

      CLOCK.advance(Duration.ofMinutes(16));

      // The decoder is given the injected Clock, so expiry means the same thing here as
      // it does in the token. Asserting on the exp claim instead would only check that
      // we wrote a date, not that anything enforces it.
      assertThatExceptionOfType(JwtValidationException.class)
          .isThrownBy(() -> jwtDecoder.decode(tokens.accessToken()));
    }

    @Test
    void rejectsATokenFromAnotherIssuer() {
      // The decoder validates iss, so a token minted for a different deployment with
      // the same key is still refused.
      AuthService.Tokens tokens = registerRider();
      assertThat(jwtDecoder.decode(tokens.accessToken()).getIssuer())
          .hasToString("https://rideflow.local");
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() throws Exception {
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var foreign = generator.generateKeyPair();

      var key =
          new com.nimbusds.jose.jwk.RSAKey.Builder(
                  (java.security.interfaces.RSAPublicKey) foreign.getPublic())
              .privateKey((java.security.interfaces.RSAPrivateKey) foreign.getPrivate())
              .build();
      var encoder =
          new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
              new com.nimbusds.jose.jwk.source.ImmutableJWKSet<
                  com.nimbusds.jose.proc.SecurityContext>(new com.nimbusds.jose.jwk.JWKSet(key)));
      String foreignToken =
          encoder
              .encode(
                  org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                      org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                          .issuer("rideflow")
                          .subject("attacker")
                          .issuedAt(CLOCK.instant())
                          .expiresAt(CLOCK.instant().plusSeconds(600))
                          .claim("role", "ADMIN")
                          .build()))
              .getTokenValue();

      assertThatExceptionOfType(Exception.class).isThrownBy(() -> jwtDecoder.decode(foreignToken));
    }
  }

  @Nested
  class RefreshRotation {

    @Test
    void exchangesARefreshTokenForANewPair() {
      AuthService.Tokens first = registerRider();

      AuthService.Tokens second = auth.refresh(first.refreshToken());

      assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
      assertThat(second.accessToken()).isNotBlank();
    }

    @Test
    void refusesTheOldTokenAfterRotation() {
      AuthService.Tokens first = registerRider();
      auth.refresh(first.refreshToken());

      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> auth.refresh(first.refreshToken()))
          .satisfies(e -> assertThat(e.code()).isEqualTo("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void revokesTheWholeFamilyWhenASpentTokenIsReplayed() {
      AuthService.Tokens first = registerRider();
      AuthService.Tokens second = auth.refresh(first.refreshToken());

      // Replaying the spent token means a replay or a theft, and there is no way to
      // tell which. The session is burned either way.
      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> auth.refresh(first.refreshToken()));

      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> auth.refresh(second.refreshToken()))
          .satisfies(e -> assertThat(e.code()).isEqualTo("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void rejectsATokenThatWasNeverIssued() {
      registerRider();

      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> auth.refresh("not-a-real-token"))
          .satisfies(e -> assertThat(e.code()).isEqualTo("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void rejectsATokenPastItsSevenDayLife() {
      AuthService.Tokens tokens = registerRider();

      CLOCK.advance(Duration.ofDays(8));

      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> auth.refresh(tokens.refreshToken()))
          .satisfies(e -> assertThat(e.code()).isEqualTo("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void keepsSeparateLoginsInSeparateFamilies() {
      registerRider();
      AuthService.Tokens phone = auth.login(PHONE, PASSWORD);
      AuthService.Tokens laptop = auth.login(PHONE, PASSWORD);

      auth.logout(phone.refreshToken());

      // Logging out of one device must not log out the other.
      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> auth.refresh(phone.refreshToken()));
      assertThat(auth.refresh(laptop.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void issuesExactlyOneNewPairWhenOneTokenIsRefreshedConcurrently() throws Exception {
      AuthService.Tokens original = registerRider();
      int threads = 10;

      Set<String> issued = new ConcurrentSkipListSet<>();
      AtomicInteger rejected = new AtomicInteger();
      CountDownLatch startGate = new CountDownLatch(1);
      CountDownLatch finished = new CountDownLatch(threads);

      try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
        for (int i = 0; i < threads; i++) {
          executor.execute(
              () -> {
                try {
                  startGate.await();
                  issued.add(auth.refresh(original.refreshToken()).refreshToken());
                } catch (BusinessException e) {
                  rejected.incrementAndGet();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                  // Anything other than a clean rejection is a defect, not a race.
                  rejected.incrementAndGet();
                } finally {
                  finished.countDown();
                }
              });
        }
        startGate.countDown();
        assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
      }

      // One winner. Every other caller must be turned away rather than handed a
      // second live token for the same session.
      assertThat(issued).as("distinct refresh tokens issued").hasSize(1);
      assertThat(rejected.get()).isEqualTo(threads - 1);
    }
  }

  @Nested
  class Logout {

    @Test
    void revokesTheSessionSoRefreshStopsWorking() {
      AuthService.Tokens tokens = registerRider();

      auth.logout(tokens.refreshToken());

      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> auth.refresh(tokens.refreshToken()))
          .satisfies(e -> assertThat(e.code()).isEqualTo("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void isIdempotentForAnUnknownToken() {
      auth.logout("never-issued");
      auth.logout("never-issued");
    }
  }

  private static BusinessException catchBusinessException(Runnable action) {
    try {
      action.run();
      throw new AssertionError("expected a BusinessException");
    } catch (BusinessException e) {
      return e;
    }
  }
}
