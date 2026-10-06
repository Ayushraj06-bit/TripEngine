package com.rideflow.trip.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.rideflow.pricing.ConsumedQuote;
import com.rideflow.pricing.FareBreakdown;
import com.rideflow.pricing.TripFees;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.Money;
import com.rideflow.shared.RideFlowProperties;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.PaymentMethod;
import com.rideflow.trip.RoutePoint;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * The final-fare rules, one at a time, with the real {@code application.yml} values and a fee port
 * that records what it was asked: the quote is 154 rupees for an estimated 10 km.
 */
class FinalFaresTest {

  private static final Instant BOOKED = Instant.parse("2026-10-01T10:00:00Z");
  private static final Instant ARRIVED = BOOKED.plus(Duration.ofMinutes(5));
  private static final GeoPoint PICKUP = new GeoPoint(12.9752, 77.6064);
  private static final UUID RATE_CARD = UUID.randomUUID();
  private static final long QUOTED = 15_400;
  private static final long ESTIMATED_METERS = 10_000;

  private final RecordingFees fees = new RecordingFees();
  private final FinalFares finalFares = new FinalFares(fees, properties());

  /** The fee port: a simple linear fare with an 80-rupee minimum, and 3 rupees a minute waiting. */
  private static final class RecordingFees implements TripFees {

    Long repricedMeters;
    BigDecimal repricedSurge;
    UUID repricedCard;

    @Override
    public Money cancellationFee(UUID rateCardId) {
      return Money.ofPaise(5_000);
    }

    @Override
    public FareBreakdown fareFor(
        UUID rateCardId, long distanceMeters, long durationSeconds, BigDecimal surge) {
      repricedCard = rateCardId;
      repricedMeters = distanceMeters;
      repricedSurge = surge;
      long total = Math.max(8_000, 5_000 + distanceMeters);
      Money zero = Money.ofPaise(0);
      return new FareBreakdown(
          Money.ofPaise(total), zero, zero, zero, zero, 0, Money.ofPaise(total));
    }

    @Override
    public Money waitingPerMinute(UUID rateCardId) {
      return Money.ofPaise(300);
    }
  }

  private static RideFlowProperties properties() {
    try {
      var sources =
          new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"));
      return new Binder(ConfigurationPropertySources.from(sources))
          .bind("rideflow", RideFlowProperties.class)
          .get();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * A trip quoted at {@link #QUOTED} for {@link #ESTIMATED_METERS}, started after {@code waited}.
   */
  private static Trip startedTrip(Duration waited) {
    Money zero = Money.ofPaise(0);
    ConsumedQuote quote =
        new ConsumedQuote(
            UUID.randomUUID(),
            UUID.randomUUID(),
            VehicleType.CAR,
            PICKUP,
            new GeoPoint(13.0652, 77.6064),
            RATE_CARD,
            new BigDecimal("1.3"),
            ESTIMATED_METERS,
            1_800,
            new FareBreakdown(
                Money.ofPaise(QUOTED), zero, zero, zero, zero, 0, Money.ofPaise(QUOTED)),
            BOOKED.plus(Duration.ofMinutes(5)));
    Trip trip =
        Trip.request(UUID.randomUUID(), quote.riderId(), quote, PaymentMethod.WALLET, BOOKED);
    trip.assign(UUID.randomUUID(), "1234", BOOKED.plusSeconds(30));
    trip.arrive(ARRIVED);
    trip.start(ARRIVED.plus(waited));
    return trip;
  }

  /** A route due north from the pickup: one point every 10 s, 100 m apart - 36 km/h. */
  private static List<RoutePoint> routeOf(long meters, Instant from) {
    List<RoutePoint> route = new ArrayList<>();
    for (long done = 0, i = 0; done <= meters; done += 100, i++) {
      route.add(new RoutePoint(north(done), from.plusSeconds(10 * i)));
    }
    return route;
  }

  private static GeoPoint north(double meters) {
    return new GeoPoint(
        PICKUP.latitude() + Math.toDegrees(meters / 6_371_008.8), PICKUP.longitude());
  }

  private FinalFares.FinalFare settle(Trip trip, List<RoutePoint> route) {
    Instant completed =
        route.isEmpty() ? trip.startedAt().plusSeconds(1_800) : route.getLast().at();
    return finalFares.settle(trip, route, completed);
  }

  @Nested
  class Repricing {

    @Test
    void keepsTheQuoteWhenTheRouteMatchesTheEstimate() {
      Trip trip = startedTrip(Duration.ZERO);

      var fare = settle(trip, routeOf(10_000, trip.startedAt()));

      assertThat(fare.repriced()).isFalse();
      assertThat(fare.farePaise()).isEqualTo(QUOTED);
      assertThat(fare.distanceMeters()).isCloseTo(10_000, within(1L));
    }

    @Test
    void keepsTheQuoteForATenPercentDetour() {
      Trip trip = startedTrip(Duration.ZERO);

      var fare = settle(trip, routeOf(11_000, trip.startedAt()));

      assertThat(fare.repriced()).isFalse();
      assertThat(fare.farePaise()).isEqualTo(QUOTED);
    }

    /** Strictly more than 20% reprices: exactly 20% is still the quote. */
    @Test
    void keepsTheQuoteAtExactlyTheAllowedDeviation() {
      Trip trip = startedTrip(Duration.ZERO);

      var fare = settle(trip, routeOf(12_000, trip.startedAt()));

      assertThat(fare.distanceMeters()).isEqualTo(12_000);
      assertThat(fare.repriced()).isFalse();
      assertThat(fare.farePaise()).isEqualTo(QUOTED);
    }

    @Test
    void repricesAThirtyPercentDetourWithTheStoredCardAndTheQuotedSurge() {
      Trip trip = startedTrip(Duration.ZERO);

      var fare = settle(trip, routeOf(13_000, trip.startedAt()));

      assertThat(fare.repriced()).isTrue();
      assertThat(fees.repricedCard).isEqualTo(RATE_CARD);
      assertThat(fees.repricedSurge).isEqualByComparingTo("1.3");
      assertThat(fees.repricedMeters).isCloseTo(13_000L, within(1L));
      assertThat(fare.farePaise()).isEqualTo(5_000 + fees.repricedMeters);
    }

    @Test
    void repricesATripThatEndedWellShortOfTheEstimate() {
      Trip trip = startedTrip(Duration.ZERO);

      var fare = settle(trip, routeOf(7_000, trip.startedAt()));

      assertThat(fare.repriced()).isTrue();
      assertThat(fare.farePaise()).isLessThan(QUOTED);
    }

    @Test
    void ignoresAGpsJumpInTheRoute() {
      Trip trip = startedTrip(Duration.ZERO);
      List<RoutePoint> route = new ArrayList<>(routeOf(10_000, trip.startedAt()));
      // Five kilometres away and back within ten seconds: a reflection, not a drive.
      RoutePoint before = route.get(50);
      route.add(51, new RoutePoint(north(15_000), before.at().plusSeconds(5)));

      var fare = settle(trip, route);

      assertThat(fare.distanceMeters()).isCloseTo(10_000, within(1L));
      assertThat(fare.repriced()).isFalse();
    }

    @Test
    void chargesTheMinimumForATripThatNeverMovedAndFlagsIt() {
      Trip trip = startedTrip(Duration.ZERO);
      Instant start = trip.startedAt();
      List<RoutePoint> stayedPut =
          List.of(
              new RoutePoint(PICKUP, start),
              new RoutePoint(PICKUP, start.plusSeconds(15)),
              new RoutePoint(PICKUP, start.plusSeconds(30)));

      var fare = settle(trip, stayedPut);

      assertThat(fare.distanceMeters()).isZero();
      assertThat(fare.farePaise()).isEqualTo(8_000);
      assertThat(fare.flag()).isEqualTo("Completed in 30 s over 0 m");
    }

    @Test
    void flagsARepricingThatRaisesTheFareByMoreThanHalf() {
      Trip trip = startedTrip(Duration.ZERO);

      var fare = settle(trip, routeOf(20_000, trip.startedAt()));

      assertThat(fare.farePaise()).isGreaterThan((long) (QUOTED * 1.5));
      assertThat(fare.flag()).startsWith("Repriced from 15400 to");
    }

    /**
     * Started and completed at once, before a second position arrived: not "no route", which would
     * charge the whole quote, but a trip that went nowhere.
     */
    @Test
    void chargesTheMinimumForATripOverInSecondsWithAlmostNoRoute() {
      Trip trip = startedTrip(Duration.ZERO);
      Instant start = trip.startedAt();

      var onePoint =
          finalFares.settle(trip, List.of(new RoutePoint(PICKUP, start)), start.plusSeconds(20));
      var none = finalFares.settle(trip, List.of(), start.plusSeconds(20));

      for (var fare : List.of(onePoint, none)) {
        assertThat(fare.distanceMeters()).isZero();
        assertThat(fare.farePaise()).isEqualTo(8_000);
        assertThat(fare.flag()).isEqualTo("Completed in 20 s over 0 m");
      }
    }

    /** Raised by exactly half: flagged only beyond that. */
    @Test
    void doesNotFlagARepricingOfExactlyHalfAgain() {
      Trip trip = startedTrip(Duration.ZERO);

      var fare = settle(trip, routeOf(18_100, trip.startedAt()));

      assertThat(fare.farePaise()).isEqualTo(QUOTED * 3 / 2);
      assertThat(fare.flag()).isNull();
    }

    @Test
    void keepsTheQuoteAndFlagsTheTripWhenNoRouteWasRecorded() {
      Trip trip = startedTrip(Duration.ZERO);

      var fare = settle(trip, List.of());

      assertThat(fare.repriced()).isFalse();
      assertThat(fare.farePaise()).isEqualTo(QUOTED);
      assertThat(fare.flag()).isEqualTo("No route was recorded; charged the quoted fare");
    }
  }

  @Nested
  class Waiting {

    @Test
    void costsNothingForExactlyTheFreeMinutes() {
      Trip trip = startedTrip(Duration.ofMinutes(3));

      var fare = settle(trip, routeOf(10_000, trip.startedAt()));

      assertThat(fare.waitingChargePaise()).isZero();
      assertThat(fare.farePaise()).isEqualTo(QUOTED);
    }

    @Test
    void chargesOneMinuteForOneMinuteMore() {
      Trip trip = startedTrip(Duration.ofMinutes(4));

      var fare = settle(trip, routeOf(10_000, trip.startedAt()));

      assertThat(fare.waitingChargePaise()).isEqualTo(300);
      assertThat(fare.farePaise()).isEqualTo(QUOTED + 300);
    }

    @Test
    void chargesOnlyWholeMinutes() {
      Trip trip = startedTrip(Duration.ofMinutes(4).plusSeconds(59));

      assertThat(settle(trip, routeOf(10_000, trip.startedAt())).waitingChargePaise())
          .isEqualTo(300);
    }
  }
}
