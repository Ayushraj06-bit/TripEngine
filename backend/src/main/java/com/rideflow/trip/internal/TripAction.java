package com.rideflow.trip.internal;

/** What someone can do to a trip. Booking is not one: it creates the trip rather than moving it. */
enum TripAction {
  ASSIGN_DRIVER,
  MARK_NO_DRIVER_FOUND,
  ARRIVE,
  START,
  COMPLETE,
  CANCEL
}
