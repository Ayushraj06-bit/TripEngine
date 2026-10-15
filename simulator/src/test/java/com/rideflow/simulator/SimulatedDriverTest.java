package com.rideflow.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.rideflow.simulator.SimulatedDriver.Decision;
import org.junit.jupiter.api.Test;

class SimulatedDriverTest {

  @Test
  void acceptsAtTheConfiguredRateAndSplitsTheRestBetweenRejectingAndIgnoring() {
    assertThat(SimulatedDriver.decide(0.0, 0.8)).isEqualTo(Decision.ACCEPT);
    assertThat(SimulatedDriver.decide(0.799, 0.8)).isEqualTo(Decision.ACCEPT);
    assertThat(SimulatedDriver.decide(0.8, 0.8)).isEqualTo(Decision.REJECT);
    assertThat(SimulatedDriver.decide(0.899, 0.8)).isEqualTo(Decision.REJECT);
    assertThat(SimulatedDriver.decide(0.9, 0.8)).isEqualTo(Decision.IGNORE);
    assertThat(SimulatedDriver.decide(0.999, 0.8)).isEqualTo(Decision.IGNORE);
  }

  @Test
  void acceptsEverythingAtCertaintyAndNothingAtZero() {
    assertThat(SimulatedDriver.decide(0.999, 1.0)).isEqualTo(Decision.ACCEPT);
    assertThat(SimulatedDriver.decide(0.0, 0.0)).isEqualTo(Decision.REJECT);
    assertThat(SimulatedDriver.decide(0.5, 0.0)).isEqualTo(Decision.IGNORE);
  }
}
