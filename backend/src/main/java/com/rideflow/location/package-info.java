/**
 * Location ingestion over WebSocket, Redis GEO state, stale-driver detection, nearby search.
 *
 * <p>See PROJECT_SPEC section 5. Only this top-level package is the module's API; {@code internal},
 * {@code persistence} and {@code web} subpackages are private to it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Location")
package com.rideflow.location;
