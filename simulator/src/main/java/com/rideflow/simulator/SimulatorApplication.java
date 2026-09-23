package com.rideflow.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the RideFlow traffic simulator.
 *
 * <p>The simulator drives fake drivers and riders against the backend's public API and serves the
 * live map. It is an external client by design and never depends on the backend's code.
 */
@SpringBootApplication
public class SimulatorApplication {

  public static void main(String[] args) {
    SpringApplication.run(SimulatorApplication.class, args);
  }
}
