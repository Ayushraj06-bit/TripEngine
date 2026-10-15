package com.rideflow.shared.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SimProfileGuardTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(SimProfileGuard.class);

  private ApplicationContextRunner with(String... profiles) {
    return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles));
  }

  @Test
  void refusesToStartWithTheSimProfileInProduction() {
    with("prod", "sim").run(context -> assertThat(context).hasFailed());
  }

  @Test
  void startsWithEitherAlone() {
    with("prod").run(context -> assertThat(context).hasNotFailed());
    with("local", "sim").run(context -> assertThat(context).hasNotFailed());
  }
}
