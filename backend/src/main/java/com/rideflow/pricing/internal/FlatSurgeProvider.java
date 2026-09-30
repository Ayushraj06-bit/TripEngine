package com.rideflow.pricing.internal;

import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.VehicleType;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/** No surge anywhere, until the Phase 8 engine replaces this. */
@Component
class FlatSurgeProvider implements SurgeProvider {

  @Override
  public BigDecimal multiplierAt(GeoPoint pickup, VehicleType type) {
    return BigDecimal.ONE;
  }
}
