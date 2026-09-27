package com.rideflow.driver.internal;

import com.rideflow.shared.VehicleType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "vehicles")
class Vehicle {

  @Id private UUID id;

  private UUID driverId;

  @Enumerated(EnumType.STRING)
  private VehicleType type;

  /** Always normalised by {@link PlateNumber} before it gets here. */
  private String plateNumber;

  private String model;
  private String colour;

  protected Vehicle() {} // JPA

  Vehicle(
      UUID id, UUID driverId, VehicleType type, String plateNumber, String model, String colour) {
    this.id = id;
    this.driverId = driverId;
    this.type = type;
    this.plateNumber = plateNumber;
    this.model = model;
    this.colour = colour;
  }

  UUID driverId() {
    return driverId;
  }

  VehicleType type() {
    return type;
  }

  String plateNumber() {
    return plateNumber;
  }

  String model() {
    return model;
  }

  String colour() {
    return colour;
  }

  /** Replaces the vehicle's details in place; a driver has at most one. */
  void update(VehicleType type, String plateNumber, String model, String colour) {
    this.type = type;
    this.plateNumber = plateNumber;
    this.model = model;
    this.colour = colour;
  }
}
