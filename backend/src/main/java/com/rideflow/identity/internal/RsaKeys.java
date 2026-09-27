package com.rideflow.identity.internal;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The RSA keypair that signs and verifies access tokens.
 *
 * <p>PROJECT_SPEC section 3 rules out hand-written JWT filters, so signing and verification are
 * left to Spring's {@code JwtEncoder} and {@code JwtDecoder}. Asymmetric keys rather than a shared
 * secret: only this module needs the private key, and a module extracted later could verify tokens
 * with the public key alone.
 *
 * <p>With no key configured, an ephemeral pair is generated and a warning logged. That is
 * convenient for local development and wrong everywhere else: tokens would not survive a restart,
 * and the two-instance profile would reject tokens issued by the other instance.
 */
final class RsaKeys {

  private static final Logger log = LoggerFactory.getLogger(RsaKeys.class);

  private static final int KEY_SIZE = 2048;

  private final RSAPublicKey publicKey;
  private final RSAPrivateKey privateKey;

  private RsaKeys(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
    this.publicKey = publicKey;
    this.privateKey = privateKey;
  }

  RSAPublicKey publicKey() {
    return publicKey;
  }

  RSAPrivateKey privateKey() {
    return privateKey;
  }

  /** Reads the configured PEM pair, or generates one if none is set. */
  static RsaKeys from(IdentityProperties properties) {
    boolean hasPrivate = isPresent(properties.rsaPrivateKey());
    boolean hasPublic = isPresent(properties.rsaPublicKey());

    if (hasPrivate != hasPublic) {
      // Half a keypair is a configuration mistake worth failing on, not guessing at.
      throw new IllegalStateException(
          "rideflow.security.rsa-private-key and rsa-public-key must be set together");
    }
    if (hasPrivate) {
      return new RsaKeys(
          parsePublic(properties.rsaPublicKey()), parsePrivate(properties.rsaPrivateKey()));
    }

    log.warn(
        "No rideflow.security.rsa-private-key configured; generated an ephemeral RSA keypair. "
            + "Access tokens will not survive a restart and will be rejected by any other "
            + "instance. Set the key pair before running more than one backend.");
    KeyPair generated = generate();
    return new RsaKeys(
        (RSAPublicKey) generated.getPublic(), (RSAPrivateKey) generated.getPrivate());
  }

  private static boolean isPresent(String pem) {
    return pem != null && !pem.isBlank();
  }

  private static KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(KEY_SIZE);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA is required by the Java platform", e);
    }
  }

  private static RSAPrivateKey parsePrivate(String pem) {
    try {
      return (RSAPrivateKey)
          KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decode(pem)));
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException(
          "rideflow.security.rsa-private-key is not a valid PKCS#8 PEM private key", e);
    }
  }

  private static RSAPublicKey parsePublic(String pem) {
    try {
      return (RSAPublicKey)
          KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decode(pem)));
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException(
          "rideflow.security.rsa-public-key is not a valid X.509 PEM public key", e);
    }
  }

  /** Strips the PEM header, footer and line breaks, leaving the base64 body. */
  private static byte[] decode(String pem) {
    String body = pem.replaceAll("-----(BEGIN|END)[^-]+-----", "").replaceAll("\\s", "");
    try {
      return Base64.getDecoder().decode(body);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("RSA key is not valid base64 PEM content", e);
    }
  }
}
