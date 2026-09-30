package com.rideflow.location.internal.web;

import com.rideflow.location.NearbyDriver;
import com.rideflow.location.internal.NearbyDriverService;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.VehicleType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rider's "cars around you" view.
 *
 * <p>Returns approximate positions and nothing else: no driver ids, no exact coordinates. That view
 * exists to show the service is alive nearby, and an identifiable position would let anyone follow
 * a particular car.
 */
@RestController
@RequestMapping("/api/v1/drivers")
@Validated
class NearbyDriversController {

  private final NearbyDriverService nearby;

  NearbyDriversController(NearbyDriverService nearby) {
    this.nearby = nearby;
  }

  @GetMapping("/nearby")
  @PreAuthorize("hasRole('RIDER')")
  List<NearbyDriver> nearby(
      @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
      @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
      @RequestParam @NotNull VehicleType type) {
    return nearby.nearby(new GeoPoint(lat, lng), type);
  }
}
