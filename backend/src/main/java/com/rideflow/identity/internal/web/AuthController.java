package com.rideflow.identity.internal.web;

import com.rideflow.identity.Role;
import com.rideflow.identity.internal.AuthService;
import com.rideflow.identity.internal.RefreshCookie;
import com.rideflow.shared.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration, login, rotation and logout.
 *
 * <p>Separate registration endpoints per role rather than a role field in the body: a caller must
 * not be able to ask for ADMIN. With one endpoint, forgetting to validate that field once is a
 * privilege escalation; with two, the role is decided here and the request cannot influence it.
 *
 * <p>Phone numbers are not validated by pattern here. {@code PhoneNumber} normalises them in the
 * service, so {@code 98765 43210} is accepted and stored as {@code +919876543210} - a regex at this
 * layer would reject the input before it could be normalised.
 *
 * <p>A browser sends {@code X-RideFlow-Client: web} and gets its refresh token as an httpOnly
 * cookie instead of in the body ({@link RefreshCookie}); refresh and logout then read it from the
 * cookie. Every other client sends and receives the token in JSON, as before.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

  private final AuthService auth;
  private final RefreshCookie cookie;

  AuthController(AuthService auth, RefreshCookie cookie) {
    this.auth = auth;
    this.cookie = cookie;
  }

  @PostMapping("/register/rider")
  @ResponseStatus(HttpStatus.CREATED)
  AuthService.Tokens registerRider(
      @Valid @RequestBody RegisterRequest request,
      @RequestHeader(name = RefreshCookie.CLIENT_HEADER, required = false) String client,
      HttpServletRequest http,
      HttpServletResponse response) {
    return deliver(
        auth.register(request.phone(), request.name(), request.password(), Role.RIDER),
        client,
        http,
        response);
  }

  @PostMapping("/register/driver")
  @ResponseStatus(HttpStatus.CREATED)
  AuthService.Tokens registerDriver(
      @Valid @RequestBody RegisterRequest request,
      @RequestHeader(name = RefreshCookie.CLIENT_HEADER, required = false) String client,
      HttpServletRequest http,
      HttpServletResponse response) {
    return deliver(
        auth.register(request.phone(), request.name(), request.password(), Role.DRIVER),
        client,
        http,
        response);
  }

  @PostMapping("/login")
  AuthService.Tokens login(
      @Valid @RequestBody LoginRequest request,
      @RequestHeader(name = RefreshCookie.CLIENT_HEADER, required = false) String client,
      HttpServletRequest http,
      HttpServletResponse response) {
    return deliver(auth.login(request.phone(), request.password()), client, http, response);
  }

  /** The refresh token from the body, or - for a browser, which sends no body - its cookie. */
  @PostMapping("/refresh")
  AuthService.Tokens refresh(
      @Valid @RequestBody(required = false) RefreshRequest request,
      @CookieValue(name = RefreshCookie.NAME, required = false) String fromCookie,
      @RequestHeader(name = RefreshCookie.CLIENT_HEADER, required = false) String client,
      HttpServletRequest http,
      HttpServletResponse response) {
    String token = request != null ? request.refreshToken() : fromCookie;
    if (token == null || token.isBlank()) {
      throw new BusinessException(
          "REFRESH_TOKEN_INVALID", HttpStatus.UNAUTHORIZED, "Refresh token is not valid");
    }
    return deliver(auth.refresh(token), client, http, response);
  }

  /** Ends the session the token belongs to, and has the browser forget its cookie. */
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout(
      @Valid @RequestBody(required = false) RefreshRequest request,
      @CookieValue(name = RefreshCookie.NAME, required = false) String fromCookie,
      HttpServletRequest http,
      HttpServletResponse response) {
    String token = request != null ? request.refreshToken() : fromCookie;
    if (token != null && !token.isBlank()) {
      auth.logout(token);
    }
    if (fromCookie != null) {
      cookie.clear(http, response);
    }
  }

  private AuthService.Tokens deliver(
      AuthService.Tokens tokens,
      String client,
      HttpServletRequest http,
      HttpServletResponse response) {
    return RefreshCookie.wanted(client) ? cookie.issue(tokens, http, response) : tokens;
  }

  record RegisterRequest(
      @NotBlank String phone,
      @NotBlank @Size(max = 100) String name,
      // BCrypt truncates beyond 72 bytes, so accepting more would silently ignore it.
      @NotBlank @Size(min = 8, max = 72) String password) {}

  record LoginRequest(@NotBlank String phone, @NotBlank String password) {}

  record RefreshRequest(@NotBlank String refreshToken) {}
}
