package com.rideflow.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rideflow.simulator.BackendApi.ApiException;
import com.rideflow.simulator.BackendApi.Tokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Tokens renewed before they expire, and a lost refresh token is not the end of the session. */
class UserSessionTest {

  /** A clock the test moves. */
  private static final class StepClock extends Clock {
    private Instant now = Instant.parse("2026-10-14T10:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final BackendApi api = mock(BackendApi.class);
  private final StepClock clock = new StepClock();
  private final UserSession session =
      new UserSession(api, clock, "+917000000001", "secret", new Tokens("a1", "r1", 900));

  @Test
  void keepsItsTokenUntilAMinuteBeforeExpiry() {
    clock.advance(Duration.ofSeconds(839));

    assertThat(session.accessToken()).isEqualTo("a1");
    verify(api, never()).refresh(anyString());
  }

  @Test
  void refreshesInTheLastMinute() {
    when(api.refresh("r1")).thenReturn(new Tokens("a2", "r2", 900));
    clock.advance(Duration.ofSeconds(840));

    assertThat(session.accessToken()).isEqualTo("a2");
    // And the rotated refresh token is the one used next time.
    when(api.refresh("r2")).thenReturn(new Tokens("a3", "r3", 900));
    clock.advance(Duration.ofSeconds(840));
    assertThat(session.accessToken()).isEqualTo("a3");
  }

  @Test
  void logsInAgainWhenTheRefreshIsRefused() {
    when(api.refresh("r1")).thenThrow(new ApiException("auth.refresh", 401, "TOKEN_INVALID", null));
    when(api.login("+917000000001", "secret")).thenReturn(new Tokens("a2", "r2", 900));
    clock.advance(Duration.ofMinutes(20));

    assertThat(session.accessToken()).isEqualTo("a2");
  }

  @Test
  void tellsTheAppWhenItsSocketIsDueForANewToken() {
    clock.advance(Duration.ofSeconds(779));
    assertThat(session.expiresWithin(Duration.ofSeconds(120))).isFalse();

    clock.advance(Duration.ofSeconds(1));
    assertThat(session.expiresWithin(Duration.ofSeconds(120))).isTrue();
  }
}
