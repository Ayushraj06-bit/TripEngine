package com.rideflow.pricing.internal;

import com.rideflow.shared.RideFlowProperties;
import com.rideflow.shared.VehicleType;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The rates in force for each vehicle type.
 *
 * <p>Two separate concerns, deliberately kept apart:
 *
 * <ul>
 *   <li><strong>Which cards exist</strong> is cached, and re-read from the database once {@code
 *       rate-card-refresh-seconds} have passed on the injected clock. The table is a handful of
 *       rows and changes by migration, so this is about not querying it on every quote.
 *   <li><strong>Which card is active</strong> is decided on every read, against the current time. A
 *       card with a future {@code effective_from} is in the cache but not used, and becomes active
 *       at the exact moment the clock passes it - no refresh has to happen at the right time.
 * </ul>
 *
 * <p>The refresh is driven by the injected clock rather than a {@code @Scheduled} timer, so a test
 * can move time and see a future card switch on without waiting, and there is no background job to
 * race it.
 */
@Service
class RateCardService {

  private final RateCardRepository repository;
  private final Clock clock;
  private final RideFlowProperties properties;

  private volatile Snapshot snapshot;

  RateCardService(RateCardRepository repository, Clock clock, RideFlowProperties properties) {
    this.repository = repository;
    this.clock = clock;
    this.properties = properties;
  }

  /**
   * The card in force now for {@code type}: the latest {@code effective_from} that is not in the
   * future, and the highest version among cards sharing it.
   */
  Optional<RateCard> activeFor(VehicleType type) {
    Instant now = clock.instant();
    return cards(now).stream()
        .filter(card -> card.vehicleType() == type)
        .filter(card -> !card.effectiveFrom().isAfter(now))
        .max(Comparator.comparing(RateCard::effectiveFrom).thenComparing(RateCard::version));
  }

  /** Forgets the cache, for tests that change the table underneath it. */
  void invalidate() {
    snapshot = null;
  }

  private List<RateCard> cards(Instant now) {
    Snapshot current = snapshot;
    if (current == null || current.isStaleAt(now, properties.pricing().rateCardRefreshSeconds())) {
      current =
          new Snapshot(repository.findAll().stream().map(RateCardRow::toRateCard).toList(), now);
      // Two threads may both reload at the boundary; both read the same table, so
      // whichever write wins is correct.
      snapshot = current;
    }
    return current.cards();
  }

  private record Snapshot(List<RateCard> cards, Instant loadedAt) {

    boolean isStaleAt(Instant now, int refreshSeconds) {
      // A clock that moved backwards (an NTP correction, or a test) also counts as
      // stale, rather than trusting a load time that is apparently in the future.
      return now.isBefore(loadedAt) || !now.isBefore(loadedAt.plusSeconds(refreshSeconds));
    }
  }
}
