package com.rideflow.identity.internal;

import com.rideflow.driver.DriverRegistration;
import com.rideflow.identity.Role;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login, refresh-token rotation and logout.
 *
 * <p>Every method that can fail is {@code noRollbackFor = BusinessException.class}. A failed login
 * has to persist its attempt counter and a replayed refresh token has to persist the revocation of
 * its family; if those rolled back with the exception, neither lockout nor theft detection would
 * ever take effect. That is not a detail - it is the whole mechanism.
 */
@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  /** One code for a wrong phone and a wrong password, so neither can be probed. */
  private static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";

  private final UserRepository users;
  private final RefreshTokenRepository refreshTokens;
  private final PasswordEncoder passwordEncoder;
  private final TokenService tokenService;
  private final PhoneRateLimiter phoneRateLimiter;
  private final DriverRegistration driverRegistration;
  private final IdGenerator ids;
  private final Clock clock;
  private final IdentityProperties properties;

  AuthService(
      UserRepository users,
      RefreshTokenRepository refreshTokens,
      PasswordEncoder passwordEncoder,
      TokenService tokenService,
      PhoneRateLimiter phoneRateLimiter,
      DriverRegistration driverRegistration,
      IdGenerator ids,
      Clock clock,
      IdentityProperties properties) {
    this.users = users;
    this.refreshTokens = refreshTokens;
    this.passwordEncoder = passwordEncoder;
    this.tokenService = tokenService;
    this.phoneRateLimiter = phoneRateLimiter;
    this.driverRegistration = driverRegistration;
    this.ids = ids;
    this.clock = clock;
    this.properties = properties;
  }

  /**
   * Registers a rider or a driver.
   *
   * <p>For a driver, the PENDING profile is created in this same transaction through {@code
   * driver}'s public API, so a client can post its vehicle immediately afterwards without racing an
   * event.
   */
  @Transactional(noRollbackFor = BusinessException.class)
  public Tokens register(String rawPhone, String name, String rawPassword, Role role) {
    String phone = PhoneNumber.normalise(rawPhone);

    if (users.existsByPhone(phone)) {
      throw new BusinessException(
          "PHONE_ALREADY_REGISTERED",
          HttpStatus.CONFLICT,
          "That phone number is already registered");
    }

    User user =
        new User(
            ids.newId(), phone, name, passwordEncoder.encode(rawPassword), role, clock.instant());
    users.save(user);

    if (role == Role.DRIVER) {
      driverRegistration.createPendingProfile(user.id());
    }

    log.info("Registered {} as {}", PhoneNumber.mask(phone), role);
    return startSession(user);
  }

  @Transactional(noRollbackFor = BusinessException.class)
  public Tokens login(String rawPhone, String rawPassword) {
    String phone = PhoneNumber.normalise(rawPhone);
    // Throttled before the database is touched, and before the account even has to
    // exist, so guessing across many numbers costs the same as guessing one.
    phoneRateLimiter.checkLoginAttempt(phone);
    Instant now = clock.instant();

    User user = users.findByPhone(phone).orElseThrow(AuthService::invalidCredentials);

    if (user.isLockedAt(now)) {
      throw new BusinessException(
          "ACCOUNT_LOCKED", HttpStatus.UNAUTHORIZED, "Too many failed attempts. Try again later.");
    }

    if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
      user.recordFailedLogin(now, properties.maxLoginAttempts(), properties.loginLockDuration());
      log.info("Failed login for {}", PhoneNumber.mask(phone));
      throw invalidCredentials();
    }

    user.recordSuccessfulLogin(now);
    return startSession(user);
  }

  /**
   * Exchanges a refresh token for a new pair, rotating it.
   *
   * <p>Presenting a token that was already spent or revoked means either a replay or a stolen
   * token, and there is no way to tell which apart. The whole family is revoked, which logs that
   * session out everywhere rather than leaving an attacker holding a live token.
   */
  @Transactional(noRollbackFor = BusinessException.class)
  public Tokens refresh(String plaintextToken) {
    Instant now = clock.instant();
    RefreshToken presented =
        refreshTokens
            .findByTokenHashForUpdate(tokenService.hash(plaintextToken))
            .orElseThrow(AuthService::invalidRefreshToken);

    if (!presented.isUsable(now)) {
      int revoked = refreshTokens.revokeFamily(presented.familyId(), now);
      log.warn(
          "Refresh token replay for user {}; revoked {} live token(s) in family {}",
          presented.userId(),
          revoked,
          presented.familyId());
      throw invalidRefreshToken();
    }

    User user = users.findById(presented.userId()).orElseThrow(AuthService::invalidRefreshToken);

    TokenService.Issued issued = tokenService.issueRefreshToken(user.id(), presented.familyId());
    refreshTokens.save(issued.token()); // saved first: replaced_by_id is a foreign key
    presented.replaceWith(issued.token().id(), now);

    return new Tokens(
        tokenService.accessTokenFor(user), issued.plaintext(), tokenService.accessTokenSeconds());
  }

  /**
   * Revokes the session the token belongs to. Unknown tokens are ignored, so this is idempotent.
   */
  @Transactional
  public void logout(String plaintextToken) {
    refreshTokens
        .findByTokenHash(tokenService.hash(plaintextToken))
        .ifPresent(token -> refreshTokens.revokeFamily(token.familyId(), clock.instant()));
  }

  /** A login and a registration each begin a new family. */
  private Tokens startSession(User user) {
    TokenService.Issued issued = tokenService.issueRefreshToken(user.id(), ids.newId());
    refreshTokens.save(issued.token());
    return new Tokens(
        tokenService.accessTokenFor(user), issued.plaintext(), tokenService.accessTokenSeconds());
  }

  private static BusinessException invalidCredentials() {
    return new BusinessException(
        INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Phone or password is incorrect");
  }

  private static BusinessException invalidRefreshToken() {
    return new BusinessException(
        "REFRESH_TOKEN_INVALID", HttpStatus.UNAUTHORIZED, "Refresh token is not valid");
  }

  public record Tokens(String accessToken, String refreshToken, long expiresInSeconds) {}
}
