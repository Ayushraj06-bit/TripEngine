package com.rideflow.identity.internal;

import com.rideflow.shared.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Mints access tokens and the opaque refresh tokens that renew them. */
@Service
class TokenService {

  /** 256 bits of entropy, so the token itself is not guessable. */
  private static final int REFRESH_TOKEN_BYTES = 32;

  static final String ROLE_CLAIM = "role";

  private final JwtEncoder jwtEncoder;
  private final IdGenerator ids;
  private final Clock clock;
  private final IdentityProperties properties;
  private final SecureRandom random = new SecureRandom();

  TokenService(JwtEncoder jwtEncoder, IdGenerator ids, Clock clock, IdentityProperties properties) {
    this.jwtEncoder = jwtEncoder;
    this.ids = ids;
    this.clock = clock;
    this.properties = properties;
  }

  /** A signed access token. The subject is the user id; {@code role} carries the authority. */
  String accessTokenFor(User user) {
    Instant now = clock.instant();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .subject(user.id().toString())
            .issuedAt(now)
            .expiresAt(now.plus(properties.accessTokenTtl()))
            .claim(ROLE_CLAIM, user.role().name())
            .build();

    // Name the algorithm explicitly. The encoder infers one from the key, and an
    // inferred mismatch surfaces as "failed to select a JWK signing key" at runtime
    // rather than as anything that points at the cause.
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  /**
   * A fresh refresh token in {@code familyId}.
   *
   * <p>Returns the plaintext to hand to the caller alongside the row, which stores only its hash.
   */
  Issued issueRefreshToken(UUID userId, UUID familyId) {
    byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
    random.nextBytes(bytes);
    String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

    Instant now = clock.instant();
    RefreshToken token =
        new RefreshToken(
            ids.newId(),
            userId,
            hash(plaintext),
            familyId,
            now.plus(properties.refreshTokenTtl()),
            now);
    return new Issued(plaintext, token);
  }

  /**
   * SHA-256 of the token.
   *
   * <p>Not BCrypt. The token is 256 bits from a CSPRNG, so there is nothing to brute force, and a
   * lookup has to be one indexed query rather than a scan BCrypt-comparing every row.
   */
  String hash(String plaintextToken) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(plaintextToken.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }
  }

  long accessTokenSeconds() {
    return properties.accessTokenTtl().toSeconds();
  }

  record Issued(String plaintext, RefreshToken token) {}
}
