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
 * <p>{@code trip} consumes this from Phase 4 to notice a driver who vanished mid-trip; without it a
 * rider would watch a frozen marker with nothing happening.
 *
 * <p>Going stale is not the same as going offline. The driver never asked to stop working, so their
 * stored status is untouched: reappearing restores them without a round trip through the driver
 * module.
 */
public record DriverWentStale(UUID driverId, Instant lastSeenAt, Instant detectedAt) {}
