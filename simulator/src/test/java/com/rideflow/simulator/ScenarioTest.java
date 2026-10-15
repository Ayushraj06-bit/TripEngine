package com.rideflow.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** The presets, over the shipped configuration. */
class ScenarioTest {

  private static SimulatorProperties shipped(String... overrides) throws Exception {
    return ShippedProperties.load(overrides);
  }

  @Test
  void normalChangesNothing() throws Exception {
    SimulatorProperties properties = shipped();

    assertThat(properties.withScenario()).isSameAs(properties);
  }

  @Test
  void rushHourTriplesTheDemandAroundHotspotsOnly() throws Exception {
    SimulatorProperties rush =
        shipped(
                "simulator.scenario=rush-hour",
                "simulator.demand.requests-per-minute=20",
                "simulator.demand.hotspot-share=0.6")
            .withScenario();

    // 12 a minute at hotspots become 36; the 8 elsewhere stay 8.
    assertThat(rush.demand().requestsPerMinute()).isCloseTo(44, within(1e-9));
    assertThat(rush.demand().requestsPerMinute() * rush.demand().hotspotShare())
        .isCloseTo(36, within(1e-9));
  }

  @Test
  void driverShortageLeavesAQuarterOfTheDrivers() throws Exception {
    assertThat(
            shipped("simulator.scenario=driver-shortage", "simulator.drivers=50")
                .withScenario()
                .drivers())
        .isEqualTo(12);
    assertThat(
            shipped("simulator.scenario=driver-shortage", "simulator.drivers=2")
                .withScenario()
                .drivers())
        .isOne();
  }

  @Test
  void chaosDropsConnectionsAndCancelsMore() throws Exception {
    SimulatorProperties chaos =
        shipped(
                "simulator.scenario=chaos",
                "simulator.driver.disconnect-probability=0.002",
                "simulator.driver.spoof-probability=0.2",
                "simulator.demand.cancel-probability=0.5",
                "simulator.driver.cancel-probability=0.03")
            .withScenario();

    assertThat(chaos.driver().disconnectProbability()).isCloseTo(0.02, within(1e-12));
    // Ten times 0.2 is no probability: capped at certainty.
    assertThat(chaos.driver().spoofProbability()).isEqualTo(1.0);
    // Raised to at least the chaos level, never lowered below what was configured.
    assertThat(chaos.demand().cancelProbability()).isEqualTo(0.5);
    assertThat(chaos.driver().cancelProbability()).isEqualTo(Scenario.CHAOS_DRIVER_CANCEL);
  }
}
