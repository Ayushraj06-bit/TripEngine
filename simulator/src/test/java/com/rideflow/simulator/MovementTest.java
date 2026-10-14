package com.rideflow.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.rideflow.simulator.Geo.Point;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MovementTest {

  private static final Point MG_ROAD = new Point(12.9752, 77.6064);
  private static final Duration TICK = Duration.ofSeconds(3);

  @Test
  void coversSpeedTimesTimeInOneStep() {
    Movement movement = new Movement(MG_ROAD);
    movement.headFor(Geo.offset(MG_ROAD, 90, 5_000), 36);

    movement.advance(TICK);

    // 36 km/h is 10 m/s: 30 m in three seconds.
    assertThat(Geo.distanceMeters(MG_ROAD, movement.position())).isCloseTo(30, within(0.01));
    assertThat(movement.speedKmph()).isCloseTo(36, within(1e-9));
  }

  @Test
  void headsStraightForTheWaypoint() {
    for (double bearing : new double[] {0, 45, 90, 180, 270}) {
      Movement movement = new Movement(MG_ROAD);
      movement.headFor(Geo.offset(MG_ROAD, bearing, 2_000), 30);

      movement.advance(TICK);

      assertThat(movement.headingDegrees()).as("heading").isCloseTo(bearing, within(0.05));
      assertThat(Geo.bearingDegrees(MG_ROAD, movement.position()))
          .as("where it went")
          .isCloseTo(bearing, within(0.05));
    }
  }

  @Test
  void stopsOnTheWaypointInsteadOfOvershootingIt() {
    Point waypoint = Geo.offset(MG_ROAD, 0, 20);
    Movement movement = new Movement(MG_ROAD);
    movement.headFor(waypoint, 36);

    // 30 m of driving for 20 m of road.
    assertThat(movement.advance(TICK)).isTrue();

    assertThat(movement.position()).isEqualTo(waypoint);
    assertThat(movement.arrived()).isTrue();
    assertThat(movement.speedKmph()).isZero();
  }

  @Test
  void reachesAFarWaypointAfterTheRightNumberOfSteps() {
    // 1 km at 30 km/h is 120 s: forty three-second ticks.
    Movement movement = new Movement(MG_ROAD);
    movement.headFor(Geo.offset(MG_ROAD, 123, 1_000), 30);

    int ticks = 0;
    while (!movement.advance(TICK)) {
      ticks++;
    }

    assertThat(ticks + 1).isEqualTo(40);
  }

  @Test
  void isAlreadyThereWhenHeadingForWhereItIs() {
    Movement movement = new Movement(MG_ROAD);
    movement.headFor(MG_ROAD, 30);

    assertThat(movement.arrived()).isTrue();
    assertThat(movement.advance(TICK)).isTrue();
  }

  @Test
  void measuresOneDegreeOfLatitudeOnTheBackendsEarth() {
    // Pi times the 6,371,008.8 m mean radius, over 180.
    assertThat(Geo.distanceMeters(new Point(0, 0), new Point(1, 0)))
        .isCloseTo(111_195.08, within(0.01));
  }

  @Test
  void blursPositionsByTheConfiguredNoise() {
    Random random = new Random(42);
    double sum = 0;
    double sumOfSquares = 0;
    int samples = 10_000;
    for (int i = 0; i < samples; i++) {
      double meters = Geo.distanceMeters(MG_ROAD, Geo.noisy(MG_ROAD, 8, random));
      sum += meters;
      sumOfSquares += meters * meters;
    }

    // Normal north and east errors of sigma 8 m: the distance's mean square is 2 * 8^2.
    assertThat(sumOfSquares / samples).isCloseTo(128, within(5.0));
    assertThat(sum / samples).isLessThan(15);
  }
}
