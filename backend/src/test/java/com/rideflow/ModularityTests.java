package com.rideflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Enforces the module boundaries described in PROJECT_SPEC section 5.
 *
 * <p>Module rule 3 requires this verification to pass at all times, so this test guards every later
 * phase: a module reaching into another module's internal package fails the build here rather than
 * in review.
 */
class ModularityTests {

  private final ApplicationModules modules = ApplicationModules.of(RideFlowApplication.class);

  @Test
  void modulesRespectTheirBoundaries() {
    modules.verify();
  }

  @Test
  void detectsEveryModuleFromTheSpec() {
    // Guards against a module package being renamed or dropped by accident, which
    // verify() alone would not notice.
    // Modulith 2.x replaced getName() with an ApplicationModuleIdentifier.
    assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
        .containsExactlyInAnyOrder(
            "shared",
            "identity",
            "driver",
            "location",
            "pricing",
            "trip",
            "matching",
            "payment",
            "rating",
            "notification",
            "admin");
  }

  @Test
  void writesModuleDocumentation() throws Exception {
    new Documenter(modules).writeDocumentation();

    // Documenter's default output lives under target/spring-modulith-docs.
    Path output = Path.of("target", "spring-modulith-docs");
    assertThat(Files.isDirectory(output)).isTrue();
    try (var entries = Files.list(output)) {
      assertThat(entries).isNotEmpty();
    }
  }
}
