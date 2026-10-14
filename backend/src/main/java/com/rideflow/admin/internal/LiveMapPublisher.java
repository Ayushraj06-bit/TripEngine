package com.rideflow.admin.internal;

import com.rideflow.location.LiveDrivers;
import com.rideflow.location.LiveDrivers.LiveDriver;
import com.rideflow.notification.RealtimeMessage;
import com.rideflow.notification.RealtimePublisher;
import com.rideflow.pricing.SurgeAdministration;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.trip.TripKpis;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The admin live map: one snapshot every {@code live-map-interval}, pushed to {@value #TOPIC}.
 *
 * <p>Aggregated on purpose. Forwarding every driver's ping would send a browser some 670 messages a
 * second at 2,000 drivers; a snapshot is one message, whatever the fleet.
 *
 * <p>Built once per cluster (ShedLock) and broadcast through the notification fan-out, so every
 * instance delivers it to the admins connected there. The lock is held at least a second, so a
 * second instance whose schedule runs a little behind skips the round rather than repeating it.
 */
@Component
class LiveMapPublisher {

  static final String TOPIC = "/topic/admin/live-map";

  /**
   * @param drivers everyone on the map, with where they are heading on a trip
   * @param surgeCells every cell the last surge tick priced, with its bounds
   * @param surgeOff the kill switch is on: every fare is quoted at 1.0, whatever the cells say
   */
  record LiveMap(
      Instant at,
      List<LiveDriver> drivers,
      List<SurgeAdministration.Cell> surgeCells,
      boolean surgeOff,
      TripKpis.Kpis kpis) {}

  private final LiveDrivers drivers;
  private final TripKpis trips;
  private final SurgeAdministration surge;
  private final RealtimePublisher realtime;
  private final Duration kpiWindow;
  private final Clock clock;

  LiveMapPublisher(
      LiveDrivers drivers,
      TripKpis trips,
      SurgeAdministration surge,
      RealtimePublisher realtime,
      RideFlowProperties properties,
      Clock clock) {
    this.drivers = drivers;
    this.trips = trips;
    this.surge = surge;
    this.realtime = realtime;
    this.kpiWindow = Duration.ofMinutes(properties.admin().liveMapKpiWindowMinutes());
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${rideflow.admin.live-map-interval}")
  @SchedulerLock(name = "live-map", lockAtMostFor = "PT10S", lockAtLeastFor = "PT1S")
  void scheduled() {
    publish();
  }

  /** Builds one snapshot and sends it to every admin watching, on every instance. */
  void publish() {
    realtime.toTopic(TOPIC, RealtimeMessage.of("LIVE_MAP", snapshot()));
  }

  LiveMap snapshot() {
    SurgeAdministration.Heatmap heatmap = surge.heatmap();
    return new LiveMap(
        clock.instant(),
        drivers.liveDrivers(),
        heatmap.cells(),
        heatmap.killSwitchOn(),
        trips.over(kpiWindow));
  }
}
