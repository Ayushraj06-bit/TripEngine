package com.rideflow.simulator;

import com.rideflow.simulator.BackendApi.ApiException;
import com.rideflow.simulator.BackendApi.Tokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One simulated user's tokens. The access token is refreshed a minute before it expires, and a
 * refresh that fails - the token was rotated by another session, or it expired - falls back to
 * logging in again.
 *
 * <p>A lock rather than {@code synchronized}: the refresh is an HTTP call, and on Java 21 a virtual
 * thread blocking inside {@code synchronized} pins its carrier thread.
 */
public final class UserSession {

  /** Refreshed this long before the access token expires, so no request carries a dead one. */
  static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

  private final BackendApi api;
  private final Clock clock;
  private final String phone;
  private final String password;
  private final ReentrantLock lock = new ReentrantLock();
  private Tokens tokens;
  private Instant expiresAt;

  public UserSession(BackendApi api, Clock clock, String phone, String password, Tokens tokens) {
    this.api = api;
    this.clock = clock;
    this.phone = phone;
    this.password = password;
    accept(tokens);
  }

  /** A valid access token, refreshed first if it is about to expire. */
  public String accessToken() {
    lock.lock();
    try {
      if (expiresWithin(REFRESH_MARGIN)) {
        refresh();
      }
      return tokens.accessToken();
    } finally {
      lock.unlock();
    }
  }

  boolean expiresWithin(Duration margin) {
    return !clock.instant().plus(margin).isBefore(expiresAt);
  }

  /** New tokens now, whatever the old ones' expiry. */
  void refresh() {
    lock.lock();
    try {
      try {
        accept(api.refresh(tokens.refreshToken()));
      } catch (ApiException rotatedOrExpired) {
        accept(api.login(phone, password));
      }
    } finally {
      lock.unlock();
    }
  }

  /** For logs: never the whole number (PROJECT_SPEC section 11). */
  String maskedPhone() {
    return "******" + phone.substring(phone.length() - 4);
  }

  private void accept(Tokens fresh) {
    this.tokens = fresh;
    this.expiresAt = clock.instant().plusSeconds(fresh.expiresInSeconds());
  }
}
