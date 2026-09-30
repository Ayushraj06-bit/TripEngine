package com.rideflow.location;

/**
 * An approximate driver position for the rider's "cars around you" view.
 *
 * <p>Carries no driver id and no exact position. The view exists to show that the service is alive
 * in this area, and neither of those is needed for that: an exact position plus a stable id would
 * let anyone follow a named driver around the city from a phone.
 */
public record NearbyDriver(double lat, double lng) {}
