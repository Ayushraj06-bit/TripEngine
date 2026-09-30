package com.rideflow.shared.internal;

import com.rideflow.shared.RideFlowProperties;
import com.rideflow.shared.ServiceArea;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Turns the configured vertices into the value object the application uses.
 *
 * <p>Built once at startup, so a malformed polygon fails the application rather than every request
 * that happens to touch it.
 */
@Configuration
class ServiceAreaConfig {

  @Bean
  ServiceArea serviceArea(RideFlowProperties properties) {
    return properties.serviceArea().toServiceArea();
  }
}
