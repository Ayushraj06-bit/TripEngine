package com.rideflow.location;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rideflow.shared.VehicleType;
import java.util.List;
import java.util.UUID;

/** Every driver on the map right now, for {@code admin}'s live map. */
public interface LiveDrivers {

  /** Drivers heard from within {@code stale-after-seconds}, in no particular order. */
  List<LiveDriver> liveDrivers();

  /**
   * One driver as Redis last saw them. Absent fields are left out of the JSON: the live map sends
   * thousands of these every two seconds.
   *
   * @param status AVAILABLE, ON_TRIP or OFFLINE, as the driver module last reported it
   * @param tripPhase on a trip, TO_PICKUP or TO_DROP
   * @param targetLat on a trip, where the driver is heading: the pickup, then the drop
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record LiveDriver(
      UUID id,
      String status,
      VehicleType vehicleType,
      double lat,
      double lng,
      String tripPhase,
      Double targetLat,
      Double targetLng) {}
}
