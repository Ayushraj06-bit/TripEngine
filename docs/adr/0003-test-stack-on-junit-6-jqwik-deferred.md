# 3. Test stack on JUnit 6, jqwik deferred

- **Status:** Accepted
- **Date:** 2026-09-23

## Context

PROJECT_SPEC section 3 lists the testing stack as "JUnit 5, AssertJ, Mockito,
Testcontainers 2.x, Awaitility, Spring Modulith test support, jqwik
(property-based), k6".

Two of those do not match what Spring Boot 4.1.1 actually brings in, which we
found while assembling the Phase 0 build:

1. **JUnit 6, not 5.** `spring-boot-dependencies:4.1.1` manages
   `junit-jupiter` at `6.0.3`. The programming model is unchanged — still
   `org.junit.jupiter.api` — but the underlying JUnit Platform moved from the
   `1.x` line to `6.x`.

2. **jqwik cannot run on it.** `net.jqwik:jqwik-engine:1.10.1`, the latest
   release, depends on `org.junit.platform:junit-platform-{commons,engine}` at
   `1.14.4`. JUnit Platform 6 is not binary compatible with 1.x, so putting
   jqwik on the test classpath either fails to resolve a consistent platform
   version or breaks engine discovery for every other test.

Forcing the platform back to 1.x to satisfy jqwik would break Spring Boot's own
test support, which is the larger dependency.

## Decision

- Use the test stack Boot 4.1.1 manages, JUnit 6 included. Test code is written
  against the `org.junit.jupiter` API exactly as before, so this is a version
  change, not a style change.
- Do **not** put jqwik on the classpath for now. Property-style tests are
  written with JUnit's `@ParameterizedTest` plus explicit generator methods
  until a jqwik release targets JUnit Platform 6.
- Revisit when jqwik publishes a JUnit Platform 6 compatible release. Replace
  this ADR then.

PROJECT_SPEC section 3 should be read as "JUnit (version managed by Boot)" and
its jqwik entry as pending. Section 11's rule against unapproved dependencies is
respected here by removing one, not adding one.

## Consequences

- The build resolves cleanly and `./mvnw verify` passes.
- Property-based tests are more verbose and shrink failures less helpfully than
  jqwik would. The pricing and surge calculators are where this is felt most.
- One spec deviation to carry, recorded here so it is not rediscovered later.
- If a JUnit 4 style dependency appears through a transitive path, it will not
  run; the vintage engine is not on the classpath.
