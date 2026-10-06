package com.rideflow.trip.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rideflow.notification.RealtimeMessage;
import com.rideflow.notification.RealtimePublisher;
import com.rideflow.pricing.ConsumedQuote;
import com.rideflow.pricing.FareBreakdown;
import com.rideflow.shared.GeoPoint;
import com.rideflow.shared.Money;
import com.rideflow.shared.VehicleType;
import com.rideflow.trip.DriverArrived;
import com.rideflow.trip.PaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class TripUpdatePusherTest {

  private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

  private final TripRepository trips = mock(TripRepository.class);
  private final RealtimePublisher realtime = mock(RealtimePublisher.class);
  private final TripUpdatePusher pusher = new TripUpdatePusher(trips, realtime);

  private static Trip assignedTrip() {
    Money fare = Money.ofPaise(15_400);
    Money zero = Money.ofPaise(0);
    ConsumedQuote quote =
        new ConsumedQuote(
            UUID.randomUUID(),
            UUID.randomUUID(),
            VehicleType.CAR,
            new GeoPoint(12.9752, 77.6064),
            new GeoPoint(13.0652, 77.6064),
            UUID.randomUUID(),
            BigDecimal.ONE,
            10_000,
            1_800,
            new FareBreakdown(fare, zero, zero, zero, zero, 0, fare),
            NOW.plusSeconds(300));
    Trip trip = Trip.request(UUID.randomUUID(), quote.riderId(), quote, PaymentMethod.WALLET, NOW);
    trip.assign(UUID.randomUUID(), "1234", NOW.plusSeconds(30));
    return trip;
  }

  /**
   * A state read after the number is reserved is never older than one read before it, so a higher
   * seq can never carry an older trip.
   */
  @Test
  void reservesTheSeqBeforeReadingTheStateItSends() {
    Trip trip = assignedTrip();
    when(realtime.nextSeq(trip.id())).thenReturn(7L);
    when(trips.findById(trip.id())).thenReturn(Optional.of(trip));

    pusher.on(
        new DriverArrived(UUID.randomUUID(), NOW, trip.id(), trip.riderId(), trip.driverId()));

    InOrder order = inOrder(realtime, trips);
    order.verify(realtime).nextSeq(trip.id());
    order.verify(trips).findById(trip.id());
    ArgumentCaptor<RealtimeMessage> sent = ArgumentCaptor.forClass(RealtimeMessage.class);
    verify(realtime, times(2)).toUser(any(), eq(TripUpdatePusher.DESTINATION), sent.capture());
    // The rider's copy and the driver's: one event, one number.
    assertThat(sent.getAllValues()).extracting(RealtimeMessage::seq).containsOnly(7L);
  }
}
