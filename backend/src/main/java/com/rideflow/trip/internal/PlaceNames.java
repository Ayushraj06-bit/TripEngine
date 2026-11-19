package com.rideflow.trip.internal;

import java.util.regex.Pattern;

/**
 * What the rider called the pickup and the drop when booking - "MG Road", "Indiranagar" - for
 * showing the trip later. Never used for pricing, matching or the fare, which go by the points.
 * Either may be null: older trips, and clients that do not send them.
 */
public record PlaceNames(String pickup, String drop) {

  public static final PlaceNames NONE = new PlaceNames(null, null);

  private static final Pattern SPACE = Pattern.compile("\\s+");

  /**
   * One line each, as they are shown: trimmed, with any run of spaces, tabs or line breaks inside
   * as one space. A blank name is no name.
   */
  public static PlaceNames of(String pickup, String drop) {
    return new PlaceNames(clean(pickup), clean(drop));
  }

  private static String clean(String name) {
    return name == null || name.isBlank() ? null : SPACE.matcher(name.strip()).replaceAll(" ");
  }
}
