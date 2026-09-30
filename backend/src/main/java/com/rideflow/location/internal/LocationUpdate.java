package com.rideflow.location.internal;

import java.time.Instant;

/**
 * One position reported by a driver's app.
 *
 * <p>There is deliberately no driver id. PROJECT_SPEC section 10 requires identity to come from the
 * authenticated principal, and a driver id in the payload would be a field somebody could put
 * another driver's id into.
 *
 * @param seq increases within one session, so a packet that overtook a newer one can be discarded.
 *     Reset to zero on reconnect, which is why the stored sequence lives beside the position and
 *     both are replaced together.
 * @param clientTime the device's own clock. Recorded for diagnosing a misbehaving app, never used
 *     for anything that matters - PROJECT_SPEC section 11 gets time from the injected Clock, and a
 *     phone's clock is whatever its owner set it to.
 * @param headingDegrees optional, for drawing the car facing the right way
 * @param speedKmph optional, as the device reports it. The spoof check uses the speed implied by
 *     two consecutive positions instead, because that is the number a faked location cannot lie
 *     about without also faking a plausible path.
 */
public record LocationUpdate(
    double lat,
    double lng,
    double accuracyMeters,
    Double headingDegrees,
    Double speedKmph,
    long seq,
    Instant clientTime) {}
