package com.rideflow.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token as a cookie, for browsers (ADR 0017).
 *
 * <p>A token in a JSON body ends up somewhere a script can read it, and one injected script is
 * enough to take a session for its full seven days. As an {@code httpOnly} cookie it never reaches
 * script at all:
 *
 * <ul>
 *   <li><strong>SameSite=Strict</strong>: never sent with a request another site starts, so a
 *       forged request cannot renew or end a session.
 *   <li><strong>Path=/api/v1/auth</strong>: sent to the sign-in endpoints and nowhere else.
 *   <li><strong>Secure</strong> whenever the request arrived over HTTPS, which behind the
 *       production proxy it always does. Over plain HTTP on a developer's machine it is left off,
 *       since browsers would drop it - and there everything travels in the clear anyway.
 * </ul>
 *
 * <p>Opt-in, by the {@value #CLIENT_HEADER} header: the simulator, the load tests and any other API
 * client keep receiving the token in the body, as before.
 */
@Component
public class RefreshCookie {

  /** Sent by the web app; with {@value #WEB}, the refresh token travels as a cookie. */
  public static final String CLIENT_HEADER = "X-RideFlow-Client";

  public static final String WEB = "web";

  public static final String NAME = "rf_refresh";

  private static final String PATH = "/api/v1/auth";

  private final Duration lifetime;

  RefreshCookie(IdentityProperties properties) {
    this.lifetime = properties.refreshTokenTtl();
  }

  /** Whether the caller asked for the cookie. */
  public static boolean wanted(String client) {
    return WEB.equals(client);
  }

  /** Sets the cookie, and returns the tokens with the refresh token taken out of the body. */
  public AuthService.Tokens issue(
      AuthService.Tokens tokens, HttpServletRequest request, HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(tokens.refreshToken(), lifetime, request));
    return new AuthService.Tokens(tokens.accessToken(), null, tokens.expiresInSeconds());
  }

  /** Tells the browser to forget the cookie. */
  public void clear(HttpServletRequest request, HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, request));
  }

  private static String cookie(String value, Duration maxAge, HttpServletRequest request) {
    return ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .secure(request.isSecure())
        .sameSite("Strict")
        .path(PATH)
        .maxAge(maxAge)
        .build()
        .toString();
  }
}
