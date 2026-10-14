package com.rideflow.trip.internal;

import com.rideflow.trip.TripEvent;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;

/**
 * Spring Modulith's defaults - every {@code @Externalized} event, routed by its annotation - plus
 * an {@value #EVENT_TYPE} header on each trip event. Without it a consumer cannot tell {@code
 * DriverArrived} from {@code TripStarted}: their JSON has the same fields. See {@code
 * docs/events.md}.
 */
@Configuration
class TripEventExternalization {

  static final String EVENT_TYPE = "eventType";

  @Bean
  EventExternalizationConfiguration eventExternalizationConfiguration() {
    return EventExternalizationConfiguration.externalizing()
        .select(EventExternalizationConfiguration.annotatedAsExternalized())
        .headers(TripEvent.class, event -> Map.of(EVENT_TYPE, event.getClass().getSimpleName()))
        .build();
  }
}
