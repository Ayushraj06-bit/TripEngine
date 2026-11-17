package com.rideflow.trip.internal;

/**
 * What the rider called the pickup and the drop when booking - "MG Road", "Indiranagar" - for
 * showing the trip later. Never used for pricing, matching or the fare, which go by the points.
 * Either may be null: older trips, and clients that do not send them.
 */
public record PlaceNames(String pickup, String drop) {

  public static final PlaceNames NONE = new PlaceNames(null, null);

  /** Trimmed; a blank name is no name. */
  public static PlaceNames of(String pickup, String drop) {
    return new PlaceNames(clean(pickup), clean(drop));
  }

  private static String clean(String name) {
    return name == null || name.isBlank() ? null : name.strip();
  }
}
