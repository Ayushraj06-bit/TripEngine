package com.rideflow.location.internal;

import com.rideflow.shared.VehicleType;
import java.util.List;
import java.util.UUID;

/**
 * The Redis key names from PROJECT_SPEC section 7, in one place.
 *
 * <p>Built here rather than inline so a typo is a compile error in one file instead of a silent
 * miss that writes to a key nothing reads.
 */
final class LocationKeys {

  static final String LAST_SEEN = "drivers:lastseen";

  private LocationKeys() {}

  static String state(UUID driverId) {
    return "driver:state:" + driverId;
  }

  /** Section 7: the route a trip takes while IN_PROGRESS. */
  static String route(UUID tripId) {
    return "trip:route:" + tripId;
  }

  static String available(VehicleType type) {
    return "drivers:available:" + type.name();
  }

  /**
   * Every availability key, in the fixed order the Lua scripts index by.
   *
   * <p>All of them are passed on every call, even though one update touches at most one: a script
   * must declare every key it may touch, and removing a driver from "all sets" needs them anyway.
   */
  static List<String> allAvailable() {
    return List.of(
        available(VehicleType.BIKE), available(VehicleType.AUTO), available(VehicleType.CAR));
  }
}
