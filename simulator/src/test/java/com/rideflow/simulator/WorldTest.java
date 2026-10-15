package com.rideflow.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.rideflow.simulator.Geo.Point;
import com.rideflow.simulator.SimulatorProperties.Bounds;
import com.rideflow.simulator.SimulatorProperties.Hotspot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Where riders are picked up, and what they ride: weighted as configured. Seeded. */
class WorldTest {

  private static final Bounds BENGALURU = new Bounds(12.85, 13.10, 77.50, 77.75);

  private static World world(String... overrides) throws Exception {
    return new World(
        ShippedProperties.load(overrides), null, null, null, null, null, null, null, null);
  }

  @Test
  void picksHotspotsInProportionToTheirWeights() {
    List<Hotspot> hotspots =
        List.of(new Hotspot("busy", 12.97, 77.60, 3), new Hotspot("quiet", 12.93, 77.62, 1));
    Random random = new Random(5);
    Map<String, Integer> picked = new HashMap<>();
    for (int i = 0; i < 40_000; i++) {
      picked.merge(Geo.pick(hotspots, random).name(), 1, Integer::sum);
    }

    assertThat(picked.get("busy") / 40_000.0).isCloseTo(0.75, within(0.01));
  }

  @Test
  void picksUpNearAHotspotWithinItsRadiusAndTheArea() {
    Random random = new Random(9);
    Point mgRoad = new Point(12.9752, 77.6064);
    for (int i = 0; i < 10_000; i++) {
      Point pickup = Geo.near(mgRoad, 800, BENGALURU, random);
      assertThat(Geo.distanceMeters(mgRoad, pickup)).isLessThanOrEqualTo(800.001);
    }
    // A hotspot on the edge keeps its pickups inside the area.
    Point corner = new Point(BENGALURU.maxLat(), BENGALURU.maxLng());
    for (int i = 0; i < 1_000; i++) {
      Point pickup = Geo.near(corner, 800, BENGALURU, random);
      assertThat(pickup.lat()).isLessThanOrEqualTo(BENGALURU.maxLat());
      assertThat(pickup.lng()).isLessThanOrEqualTo(BENGALURU.maxLng());
    }
  }

  @Test
  void sendsTheConfiguredShareOfPickupsToHotspots() throws Exception {
    World world =
        world("simulator.demand.hotspot-share=0.6", "simulator.demand.hotspot-radius-meters=800");
    Random random = new Random(13);
    int nearAHotspot = 0;
    int samples = 20_000;
    for (int i = 0; i < samples; i++) {
      Point pickup = world.pickup(random);
      boolean near =
          world.properties().demand().hotspots().stream()
              .anyMatch(h -> Geo.distanceMeters(new Point(h.lat(), h.lng()), pickup) <= 800.001);
      nearAHotspot += near ? 1 : 0;
    }

    // 60% by choice, plus the few uniform pickups that happen to land near one.
    assertThat(nearAHotspot / (double) samples).isBetween(0.59, 0.66);
  }

  @Test
  void dropsATripLengthAwayFromThePickup() throws Exception {
    World world =
        world("simulator.demand.min-trip-meters=1500", "simulator.demand.max-trip-meters=8000");
    Random random = new Random(17);
    Point centre = new Point(12.975, 77.625);
    for (int i = 0; i < 10_000; i++) {
      assertThat(Geo.distanceMeters(centre, world.drop(centre, random)))
          .isBetween(1_499.0, 8_001.0);
    }
  }

  @Test
  void givesDriverIAlwaysTheSameVehicleTypeInTheConfiguredMix() throws Exception {
    World world = world();
    Map<String, Integer> fleet = new HashMap<>();
    for (int i = 0; i < 1_000; i++) {
      fleet.merge(world.vehicleType(i), 1, Integer::sum);
      assertThat(world.vehicleType(i)).as("restart-stable").isEqualTo(world.vehicleType(i));
    }

    // BIKE 3, AUTO 3, CAR 4, exactly, over whole rounds of ten.
    assertThat(fleet)
        .containsExactlyInAnyOrderEntriesOf(Map.of("BIKE", 300, "AUTO", 300, "CAR", 400));
  }
}
