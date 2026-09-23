package com.rideflow;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Enforces the module boundaries described in PROJECT_SPEC section 5.
 *
 * <p>PROJECT_SPEC rule 3 requires this verification to pass at all times, so this test guards every
 * later phase: a module reaching into another module's internal package fails the build here rather
 * than in review.
 */
class ModularityTests {

  private final ApplicationModules modules = ApplicationModules.of(RideFlowApplication.class);

  @Test
  void modulesRespectTheirBoundaries() {
    modules.verify();
  }

  @Test
  void writesTheModuleDocumentation() {
    // Prints the detected modules so the build log shows the structure as it grows.
    modules.forEach(System.out::println);
  }
}
