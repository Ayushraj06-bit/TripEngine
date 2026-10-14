package com.rideflow.identity.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Authentication tuning.
 *
 * <p>PROJECT_SPEC section 9 does not cover authentication, so this extends it under the same {@code
 * rideflow.*} prefix rather than hard-coding the values, which section 11 forbids. Validated, so a
 * nonsensical value fails startup instead of producing a token that never expires.
 *
 * @param issuer the {@code iss} claim. A URI, because Spring's {@code Jwt.getIssuer()} parses it as
 *     a URL and throws on a bare word.
 * @param rsaPrivateKey PKCS#8 PEM. Blank generates an ephemeral keypair and logs a warning.
 * @param rsaPublicKey X.509 PEM. Required whenever a private key is supplied.
 * @param loginAttemptsPerAddressPerMinute logins one client address may try, against password
 *     spraying across many numbers
 * @param registrationsPerAddressPerHour registrations one client address may make, against account
 *     floods
 */
@ConfigurationProperties("rideflow.security")
@Validated
record IdentityProperties(
    @NotNull String issuer,
    @NotNull Duration accessTokenTtl,
    @NotNull Duration refreshTokenTtl,
    @Positive int maxLoginAttempts,
    @NotNull Duration loginLockDuration,
    @Positive int loginAttemptsPerAddressPerMinute,
    @Positive int registrationsPerAddressPerHour,
    String rsaPrivateKey,
    String rsaPublicKey,
    @NotNull BootstrapAdmin bootstrapAdmin) {

  /**
   * The first admin, created at startup only when no admin exists.
   *
   * <p>No defaults: a committed fallback admin is a backdoor in every deployment that forgets to
   * change it. Blank means "do not create one".
   */
  record BootstrapAdmin(String phone, String password, String name) {}
}
