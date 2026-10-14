package com.rideflow.simulator;

import com.rideflow.simulator.Geo.Point;
import com.rideflow.simulator.SimulatorProperties.Demand;
import com.rideflow.simulator.SimulatorProperties.Hotspot;
import com.rideflow.simulator.SimulatorProperties.Range;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.random.RandomGenerator;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * What every simulated user shares: the configuration with its scenario applied, the API, the one
 * scheduler that wakes them all, and the OTPs riders hand their drivers.
 *
 * @param otps by trip: a rider puts the code it reads in its own trip view, its driver takes it -
 *     the stand-in for a rider reading four digits aloud
 */
record World(
    SimulatorProperties properties,
    BackendApi api,
    WebSocketStompClient stomp,
    String stompUrl,
    JsonMapper json,
    ScheduledExecutorService scheduler,
    Map<UUID, String> otps,
    MeterRegistry meters,
    Clock clock) {

  /** Where a rider asks to be picked up: near a hotspot, by weight, or anywhere. */
  Point pickup(RandomGenerator random) {
    Demand demand = properties.demand();
    if (random.nextDouble() < demand.hotspotShare()) {
      Hotspot hotspot = Geo.pick(demand.hotspots(), random);
      return Geo.near(
          new Point(hotspot.lat(), hotspot.lng()),
          demand.hotspotRadiusMeters(),
          properties.serviceArea(),
          random);
    }
    return Geo.anywhereIn(properties.serviceArea(), random);
  }

  /** Somewhere a trip's length from {@code pickup}, inside the area. */
  Point drop(Point pickup, RandomGenerator random) {
    Demand demand = properties.demand();
    double meters = random.nextDouble(demand.minTripMeters(), demand.maxTripMeters() + 1);
    return Geo.clamp(Geo.offset(pickup, random.nextDouble(360), meters), properties.serviceArea());
  }

  /** A vehicle type by the configured mix. */
  String vehicleType(RandomGenerator random) {
    return vehicleType(random.nextInt(totalMixWeight()));
  }

  /** The type at {@code ticket} in the mix's weights: the same ticket, the same type. */
  String vehicleType(int ticket) {
    int remaining = Math.floorMod(ticket, totalMixWeight());
    for (Map.Entry<String, Integer> entry : properties.demand().vehicleMix().entrySet()) {
      remaining -= entry.getValue();
      if (remaining < 0) {
        return entry.getKey();
      }
    }
    throw new IllegalStateException("vehicle mix changed while picking");
  }

  double speedKmph(RandomGenerator random) {
    var driver = properties.driver();
    return driver.minSpeedKmph() == driver.maxSpeedKmph()
        ? driver.minSpeedKmph()
        : random.nextDouble(driver.minSpeedKmph(), driver.maxSpeedKmph());
  }

  static Duration within(Range range, RandomGenerator random) {
    long min = range.min().toMillis();
    long max = range.max().toMillis();
    return Duration.ofMillis(min == max ? min : random.nextLong(min, max + 1));
  }

  private int totalMixWeight() {
    return properties.demand().vehicleMix().values().stream().mapToInt(Integer::intValue).sum();
  }
}
