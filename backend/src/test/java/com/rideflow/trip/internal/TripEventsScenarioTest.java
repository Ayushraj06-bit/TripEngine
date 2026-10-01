package com.rideflow.trip.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rideflow.shared.VehicleType;
import com.rideflow.trip.DriverArrived;
import com.rideflow.trip.DriverAssigned;
import com.rideflow.trip.PaymentMethod;
import com.rideflow.trip.TripActor;
import com.rideflow.trip.TripCancelled;
import com.rideflow.trip.TripCompleted;
import com.rideflow.trip.TripEvent;
import com.rideflow.trip.TripNoDriverFound;
import com.rideflow.trip.TripRequested;
import com.rideflow.trip.TripReturnedToMatching;
import com.rideflow.trip.TripStarted;
import com.rideflow.trip.TripStatus;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * Each step of a trip, driven through the Modulith {@link Scenario} API: stimulate, then wait for
 * the event the step must publish. After every step the trip has exactly one more audit row and
 * exactly one more event - never two, never none.
 */
@EnableScenarios
@RecordApplicationEvents
class TripEventsScenarioTest extends TripIntegrationTestBase {

  @Autowired ApplicationEvents events;
  @Autowired BookingService bookings;

  private void assertRecorded(UUID tripId, int steps) {
    assertThat(auditRows(tripId)).as("trip_events rows").isEqualTo(steps);
    assertThat(events.stream(TripEvent.class).filter(event -> event.tripId().equals(tripId)))
        .as("published trip events")
        .hasSize(steps);
  }

  @Test
  void aTripFromBookingToCompletion(Scenario scenario) {
    User rider = rider();
    User driver = driver();
    UUID quoteId = quote(rider, VehicleType.CAR).quoteId();
    AtomicReference<UUID> booked = new AtomicReference<>();

    scenario
        .stimulate(
            () ->
                bookings.book(rider.id(), newKey(), quoteId, VehicleType.CAR, PaymentMethod.WALLET))
        .andWaitForEventOfType(TripRequested.class)
        .matchingMappedValue(TripRequested::riderId, rider.id())
        .toArriveAndVerify(event -> booked.set(event.tripId()));
    UUID tripId = booked.get();
    assertRecorded(tripId, 1);

    scenario
        .stimulate(() -> trips.assignDriver(tripId, driver.id()))
        .andWaitForEventOfType(DriverAssigned.class)
        .matchingMappedValue(DriverAssigned::tripId, tripId)
        .toArriveAndVerify(event -> assertThat(event.driverId()).isEqualTo(driver.id()));
    assertRecorded(tripId, 2);

    TripTestConfiguration.POSITIONS.place(driver.id(), MG_ROAD);
    scenario
        .stimulate(() -> trips.arrive(tripId, driver.asDriver()))
        .andWaitForEventOfType(DriverArrived.class)
        .matchingMappedValue(DriverArrived::tripId, tripId)
        .toArrive();
    assertRecorded(tripId, 3);

    String otp = otpOf(tripId);
    scenario
        .stimulate(() -> trips.start(tripId, driver.asDriver(), otp))
        .andWaitForEventOfType(TripStarted.class)
        .matchingMappedValue(TripStarted::tripId, tripId)
        .toArrive();
    assertRecorded(tripId, 4);

    scenario
        .stimulate(() -> trips.complete(tripId, driver.asDriver()))
        .andWaitForEventOfType(TripCompleted.class)
        .matchingMappedValue(TripCompleted::tripId, tripId)
        .toArriveAndVerify(event -> assertThat(event.finalFarePaise()).isPositive());
    assertRecorded(tripId, 5);
  }

  @Test
  void theWaysATripEndsEarly(Scenario scenario) throws Exception {
    User rider = rider();
    User driver = driver();
    UUID tripId = assignedTrip(rider, driver);
    assertRecorded(tripId, 2);

    scenario
        .stimulate(() -> trips.cancel(tripId, driver.asDriver(), "Car broke down"))
        .andWaitForEventOfType(TripReturnedToMatching.class)
        .matchingMappedValue(TripReturnedToMatching::tripId, tripId)
        .toArriveAndVerify(event -> assertThat(event.previousDriverId()).isEqualTo(driver.id()));
    assertRecorded(tripId, 3);

    scenario
        .stimulate(() -> trips.markNoDriverFound(tripId))
        .andWaitForEventOfType(TripNoDriverFound.class)
        .matchingMappedValue(TripNoDriverFound::tripId, tripId)
        .toArrive();
    assertRecorded(tripId, 4);

    UUID second = bookedTrip(rider);
    scenario
        .stimulate(() -> trips.cancel(second, rider.asRider(), "Plans changed"))
        .andWaitForEventOfType(TripCancelled.class)
        .matchingMappedValue(TripCancelled::tripId, second)
        .toArriveAndVerify(
            event -> {
              assertThat(event.cancelledBy()).isEqualTo(TripActor.RIDER);
              assertThat(event.previousStatus()).isEqualTo(TripStatus.REQUESTED);
              assertThat(event.cancellationFeePaise()).isZero();
            });
    assertRecorded(second, 2);
  }
}
