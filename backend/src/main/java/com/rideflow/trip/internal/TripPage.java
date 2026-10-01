package com.rideflow.trip.internal;

import java.util.List;
import java.util.UUID;

/** One page of trip history, newest first, and the cursor for the next page (null on the last). */
public record TripPage(List<TripView> trips, UUID nextCursor) {}
