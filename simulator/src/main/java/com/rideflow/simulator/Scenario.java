package com.rideflow.simulator;

import com.rideflow.simulator.SimulatorProperties.Demand;
import com.rideflow.simulator.SimulatorProperties.DriverBehaviour;

/**
 * A preset applied on top of the configured values ({@code --simulator.scenario=rush-hour}), so a
 * command-line override still counts: {@code rush-hour} with 20 requests a minute triples the
 * hotspot part of those 20.
 */
public enum Scenario {

  /** As configured. */
  NORMAL {
    @Override
    SimulatorProperties apply(SimulatorProperties p) {
      return p;
    }
  },

  /** Three times the demand around hotspots; elsewhere unchanged. Surge should rise there. */
  RUSH_HOUR {
    @Override
    SimulatorProperties apply(SimulatorProperties p) {
      Demand d = p.demand();
      double hotspot = d.requestsPerMinute() * d.hotspotShare() * RUSH_HOUR_FACTOR;
      double elsewhere = d.requestsPerMinute() * (1 - d.hotspotShare());
      double total = hotspot + elsewhere;
      return withDemand(
          p,
          new Demand(
              total,
              d.hotspots(),
              total == 0 ? d.hotspotShare() : hotspot / total,
              d.hotspotRadiusMeters(),
              d.minTripMeters(),
              d.maxTripMeters(),
              d.vehicleMix(),
              d.paymentMethod(),
              d.cancelProbability(),
              d.cancelAfter()));
    }
  },

  /** A quarter of the drivers for the same demand: offers run out, no-driver trips appear. */
  DRIVER_SHORTAGE {
    @Override
    SimulatorProperties apply(SimulatorProperties p) {
      return new SimulatorProperties(
          p.backendUrl(),
          p.scenario(),
          Math.max(1, p.drivers() / SHORTAGE_DIVISOR),
          p.riders(),
          p.tick(),
          p.demand(),
          p.driver(),
          p.serviceArea(),
          p.gpsNoiseMeters(),
          p.accounts(),
          p.kpiLogInterval());
    }
  },

  /**
   * Connections dropping, riders and drivers cancelling, spoofed positions: every recovery path.
   */
  CHAOS {
    @Override
    SimulatorProperties apply(SimulatorProperties p) {
      Demand d = p.demand();
      DriverBehaviour b = p.driver();
      return new SimulatorProperties(
          p.backendUrl(),
          p.scenario(),
          p.drivers(),
          p.riders(),
          p.tick(),
          new Demand(
              d.requestsPerMinute(),
              d.hotspots(),
              d.hotspotShare(),
              d.hotspotRadiusMeters(),
              d.minTripMeters(),
              d.maxTripMeters(),
              d.vehicleMix(),
              d.paymentMethod(),
              Math.max(d.cancelProbability(), CHAOS_RIDER_CANCEL),
              d.cancelAfter()),
          new DriverBehaviour(
              b.acceptProbability(),
              b.responseDelay(),
              Math.max(b.cancelProbability(), CHAOS_DRIVER_CANCEL),
              Math.min(1, b.disconnectProbability() * CHAOS_FACTOR),
              b.offlineFor(),
              Math.min(1, b.spoofProbability() * CHAOS_FACTOR),
              b.minSpeedKmph(),
              b.maxSpeedKmph()),
          p.serviceArea(),
          p.gpsNoiseMeters(),
          p.accounts(),
          p.kpiLogInterval());
    }
  };

  static final double RUSH_HOUR_FACTOR = 3;
  static final int SHORTAGE_DIVISOR = 4;
  static final double CHAOS_FACTOR = 10;
  static final double CHAOS_RIDER_CANCEL = 0.3;
  static final double CHAOS_DRIVER_CANCEL = 0.15;

  abstract SimulatorProperties apply(SimulatorProperties properties);

  private static SimulatorProperties withDemand(SimulatorProperties p, Demand demand) {
    return new SimulatorProperties(
        p.backendUrl(),
        p.scenario(),
        p.drivers(),
        p.riders(),
        p.tick(),
        demand,
        p.driver(),
        p.serviceArea(),
        p.gpsNoiseMeters(),
        p.accounts(),
        p.kpiLogInterval());
  }
}
