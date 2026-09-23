/**
 * Shared kernel: the value types, id generation, error model and {@link java.time.Clock}
 * configuration that every other module builds on.
 *
 * <p>PROJECT_SPEC section 5 is explicit that this module holds <em>no business logic</em>. Nothing
 * here may know what a trip, a fare or a driver is. If a type needs that knowledge, it belongs in
 * the module that owns the concept.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Shared Kernel")
package com.rideflow.shared;
