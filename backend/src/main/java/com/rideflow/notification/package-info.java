/**
 * Real-time push to users, fanned out across instances.
 *
 * <p>See PROJECT_SPEC section 5. Only this top-level package is the module's API; {@code internal},
 * {@code persistence} and {@code web} subpackages are private to it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Notification")
package com.rideflow.notification;
