package com.rideflow.identity.internal.web;

import com.rideflow.identity.Role;
import com.rideflow.identity.internal.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

  private final AuthService auth;

  AuthController(AuthService auth) {
    this.auth = auth;
  }

  @PostMapping("/register/rider")
  @ResponseStatus(HttpStatus.CREATED)
  AuthService.Tokens registerRider(@Valid @RequestBody RegisterRequest request) {
    return auth.register(request.phone(), request.name(), request.password(), Role.RIDER);
  }

  @PostMapping("/register/driver")
  @ResponseStatus(HttpStatus.CREATED)
  AuthService.Tokens registerDriver(@Valid @RequestBody RegisterRequest request) {
    return auth.register(request.phone(), request.name(), request.password(), Role.DRIVER);
  }

  @PostMapping("/login")
  AuthService.Tokens login(@Valid @RequestBody LoginRequest request) {
    return auth.login(request.phone(), request.password());
  }

  @PostMapping("/refresh")
  AuthService.Tokens refresh(@Valid @RequestBody RefreshRequest request) {
    return auth.refresh(request.refreshToken());
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout(@Valid @RequestBody RefreshRequest request) {
    auth.logout(request.refreshToken());
  }

  record RegisterRequest(
      @NotBlank String phone,
      @NotBlank @Size(max = 100) String name,
      // BCrypt truncates beyond 72 bytes, so accepting more would silently ignore it.
      @NotBlank @Size(min = 8, max = 72) String password) {}

  record LoginRequest(@NotBlank String phone, @NotBlank String password) {}

  record RefreshRequest(@NotBlank String refreshToken) {}
}
