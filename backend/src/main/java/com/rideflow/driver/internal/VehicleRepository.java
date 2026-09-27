package com.rideflow.driver.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

  Optional<Vehicle> findByDriverId(UUID driverId);

  boolean existsByDriverId(UUID driverId);

  Optional<Vehicle> findByPlateNumber(String plateNumber);
}
