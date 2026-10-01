package com.rideflow.location;

import java.time.Instant;
import java.util.UUID;

/**
 * A driver stopped reporting their position for longer than {@code
 * rideflow.location.stale-after-seconds}.
 *
 * <p>They have been removed from every availability set, so matching will not offer them a trip.
 * Published by the sweeper rather than inferred by each consumer, because the decision has to be
 * made once per cluster - ShedLock guarantees that - and a driver who is merely in a tunnel should
 * not be declared gone by three instances independently.
 *
 * <p>Nothing consumes it yet. It was meant for {@code trip}, to notice a driver who vanished
 * mid-trip, but {@code trip} cannot depend on {@code location} without closing a module cycle (see
 * {@code docs/events.md}); matching or notification will pick it up instead.
 *
 * <p>Going stale is not the same as going offline. The driver never asked to stop working, so their
 * stored status is untouched: reappearing restores them without a round trip through the driver
 * module.
 */
public record DriverWentStale(UUID driverId, Instant lastSeenAt, Instant detectedAt) {}
