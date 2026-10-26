package com.rideflow.driver;

import com.rideflow.shared.VehicleType;
import java.time.Instant;
import java.util.UUID;

/**
 * A driver's availability changed.
 *
 * <p>Published inside the transaction that made the change, so the Modulith event registry persists
 * it in the same commit. That is the outbox: if the listener fails, the event is retried rather
 * than lost, and the live map cannot end up disagreeing with the database.
 *
 * <p>Consumed by {@code location} from Phase 2, which adds the driver to or removes them from the
 * Redis GEO set for their vehicle type. {@code previousVehicleType} is carried so the listener
 * knows which set to remove them from when a driver swaps vehicles - without it, the driver would
 * linger in the old type's set and be offered the wrong kind of trip.
 *
 * <p>Not externalised to Kafka: PROJECT_SPEC section 5 puts only trip and payment events on a
 * topic.
 *
 * @param previousVehicleType null on the first status change, or when the driver has no vehicle yet
 * @param driverVersion the driver row's version once this change is made. Listeners run on several
 *     threads, so two changes for one driver can be handled in either order; {@code location}
 *     applies one only if no later version already has been.
 */
public record DriverStatusChanged(
    UUID driverId,
    DriverStatus previousStatus,
    DriverStatus newStatus,
    VehicleType vehicleType,
    VehicleType previousVehicleType,
    Instant occurredAt,
    long driverVersion) {}
