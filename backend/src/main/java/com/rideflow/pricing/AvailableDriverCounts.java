package com.rideflow.pricing;

import com.rideflow.shared.GeoPoint;
import java.util.List;

/**
 * Supply for the surge engine: how many drivers are available inside an area.
 *
 * <p>Declared here and implemented by {@code location}, which owns the drivers' positions - the
 * same direction as {@code trip}'s {@code DriverPositions}, for the same reason: {@code pricing}
 * depending on {@code location} would close a module cycle.
 */
public interface AvailableDriverCounts {

  /** For each box, in order, how many available drivers of any vehicle type are inside it. */
  List<Long> countIn(List<Box> boxes);

  /** A rectangle centred on {@code center}. */
  record Box(GeoPoint center, double widthMeters, double heightMeters) {}
}
