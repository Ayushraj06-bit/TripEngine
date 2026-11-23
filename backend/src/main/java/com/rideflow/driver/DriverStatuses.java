package com.rideflow.driver;

import com.rideflow.shared.VehicleType;
import java.util.Optional;
import java.util.UUID;

/**
 * A driver's status as the database has it now, for {@code location} to restore what Redis lost.
 * Changes reach it as {@link DriverStatusChanged}; nothing changes when Redis restarts.
 */
public interface DriverStatuses {

  /** Empty for a driver without a profile. */
  Optional<Current> current(UUID driverId);

  /**
   * @param vehicleType null until the driver registers a vehicle
   * @param driverVersion the driver row's version, as in {@link DriverStatusChanged}
   */
  record Current(DriverStatus status, VehicleType vehicleType, long driverVersion) {}
}
