package com.rideflow.pricing.internal;

import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.VehicleType;
import java.math.BigDecimal;

/**
 * The surge multiplier at a pickup point.
 *
 * <p>The real engine arrives in Phase 8. Callers must treat any failure as 1.0 - PROJECT_SPEC
 * section 7 has surge fail safe - and must never apply a value below 1.0.
 */
interface SurgeProvider {

  BigDecimal multiplierAt(GeoPoint pickup, VehicleType type);
}
