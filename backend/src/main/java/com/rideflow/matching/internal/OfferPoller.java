package com.rideflow.matching.internal;

import com.rideflow.shared.RideFlowProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires offers and runs due searches, on every instance.
 *
 * <p>No ShedLock: each due item is claimed with a ZREM, and only the instance that gets 1 back
 * processes it. The work spreads across instances instead of queueing behind one lock holder, and a
 * dead instance costs nothing but its share of one poll.
 */
@Component
class OfferPoller {

  private static final Logger log = LoggerFactory.getLogger(OfferPoller.class);

  private final OfferStore store;
  private final Matcher matcher;
  private final Clock clock;
  private final RideFlowProperties properties;

  OfferPoller(OfferStore store, Matcher matcher, Clock clock, RideFlowProperties properties) {
    this.store = store;
    this.matcher = matcher;
    this.clock = clock;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${rideflow.matching.offer-poll-interval}",
      initialDelayString = "${rideflow.matching.offer-poll-interval}")
  void poll() {
    Instant now = clock.instant();
    int batch = properties.matching().offerPollBatchSize();
    for (UUID offerId : store.dueOffers(now, batch)) {
      if (store.claimOffer(offerId)) {
        handle(offerId, matcher::expire, "expire offer");
      }
    }
    for (UUID tripId : store.dueRetries(now, batch)) {
      if (store.claimRetry(tripId)) {
        handle(tripId, matcher::advance, "search again for trip");
      }
    }
  }

  /** One failure must not strand the rest of the batch; the recovery sweep repairs that one. */
  private static void handle(UUID id, Consumer<UUID> step, String what) {
    try {
      step.accept(id);
    } catch (RuntimeException e) {
      log.warn("Could not {} {}", what, id, e);
    }
  }
}
