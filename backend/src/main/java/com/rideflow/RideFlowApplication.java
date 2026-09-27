package com.rideflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the RideFlow backend.
 *
 * <p>This class is the root of the Spring Modulith application: every package directly beneath
 * {@code com.rideflow} is an application module. See PROJECT_SPEC section 5 for the module list and
 * the rules that govern their boundaries.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RideFlowApplication {

  public static void main(String[] args) {
    SpringApplication.run(RideFlowApplication.class, args);
  }
}
