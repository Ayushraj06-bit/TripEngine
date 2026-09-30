package com.rideflow.location.internal.web;

import com.rideflow.location.internal.LocationIngestService;
import com.rideflow.location.internal.LocationUpdate;
import java.security.Principal;
import java.util.UUID;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

/**
 * Receives driver positions.
 *
 * <p>The driver id comes from the session principal, never from the payload. PROJECT_SPEC section
 * 10 requires it, and here it is what stops one driver reporting positions as another - the
 * interceptor has already proved who this session belongs to.
 *
 * <p>Returns nothing. A driver streaming positions cannot act on a per-message reply, and sending
 * one would double the traffic on the hottest path in the system; outcomes go to the {@code
 * location_updates_total} counter instead.
 */
@Controller
class DriverLocationController {

  private final LocationIngestService ingest;

  DriverLocationController(LocationIngestService ingest) {
    this.ingest = ingest;
  }

  @MessageMapping("/driver/location")
  void handle(LocationUpdate update, Principal principal) {
    ingest.accept(UUID.fromString(principal.getName()), update);
  }
}
