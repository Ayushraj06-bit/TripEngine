package com.rideflow.trip.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.rideflow.identity.Role;
import com.rideflow.shared.BusinessException;
import com.rideflow.trip.TripActor;
import com.rideflow.trip.TripEvent;
import com.rideflow.trip.TripStatus;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * The state machine again, through the real service and database, for every kind of caller.
 *
 * <p>The trip's own rider or driver gets exactly what {@link TripStateMachine#TRANSITIONS} allows,
 * or a 409. Anyone else gets a 404 in every state, so probing cannot tell another user's trip from
 * a missing one. Every allowed change writes exactly one audit row and publishes exactly one event;
 * everything else writes and publishes nothing.
 */
@RecordApplicationEvents
class TripTransitionMatrixIntegrationTest extends TripIntegrationTestBase {

  /** Who acts, relative to the trip. */
  enum Party {
    RIDER,
    DRIVER,
    SYSTEM,
    OTHER_RIDER,
    OTHER_DRIVER
  }

  private enum Outcome {
    MOVES,
    UNCHANGED,
    INVALID_TRANSITION,
    NOT_FOUND
  }

  @Autowired ApplicationEvents events;

  static Stream<Arguments> everyCombination() {
    return Arrays.stream(TripStatus.values())
        .flatMap(
            state ->
                Arrays.stream(TripAction.values())
                    .flatMap(
                        action ->
                            partiesFor(action).map(party -> Arguments.of(state, action, party))));
  }

  /** Only the system can assign or give up; only people can do the rest. */
  private static Stream<Party> partiesFor(TripAction action) {
    return action == TripAction.ASSIGN_DRIVER || action == TripAction.MARK_NO_DRIVER_FOUND
        ? Stream.of(Party.SYSTEM)
        : Stream.of(Party.RIDER, Party.DRIVER, Party.OTHER_RIDER, Party.OTHER_DRIVER);
  }

  @ParameterizedTest(name = "{2} {1} on {0}")
  @MethodSource("everyCombination")
  void answersEveryCallerAsTheTableSays(TripStatus state, TripAction action, Party party) {
    User rider = userRow(Role.RIDER);
    User driver = userRow(Role.DRIVER);
    User otherRider = userRow(Role.RIDER);
    User otherDriver = userRow(Role.DRIVER);
    UUID tripId = tripIn(state, rider, driver);
    TripTestConfiguration.POSITIONS.place(driver.id(), MG_ROAD);

    ThrowingCallable call =
        () -> {
          Caller caller =
              switch (party) {
                case RIDER -> rider.asRider();
                case DRIVER -> driver.asDriver();
                case OTHER_RIDER -> otherRider.asRider();
                case OTHER_DRIVER -> otherDriver.asDriver();
                case SYSTEM -> null;
              };
          switch (action) {
            case ASSIGN_DRIVER -> trips.assignDriver(tripId, otherDriver.id());
            case MARK_NO_DRIVER_FOUND -> trips.markNoDriverFound(tripId);
            case ARRIVE -> trips.arrive(tripId, caller);
            case START -> trips.start(tripId, caller, OTP);
            case COMPLETE -> trips.complete(tripId, caller);
            case CANCEL -> trips.cancel(tripId, caller, "Plans changed");
          }
        };

    switch (expected(state, action, party)) {
      case MOVES -> {
        assertThatNoException().isThrownBy(call);
        assertThat(statusOf(tripId)).isEqualTo(target(state, action, party));
        assertRecorded(tripId, 1);
      }
      case UNCHANGED -> {
        assertThatNoException().isThrownBy(call);
        assertThat(statusOf(tripId)).isEqualTo(state);
        assertRecorded(tripId, 0);
      }
      case INVALID_TRANSITION -> {
        assertRefused(call, "TRIP_INVALID_TRANSITION", HttpStatus.CONFLICT);
        assertThat(statusOf(tripId)).isEqualTo(state);
        assertRecorded(tripId, 0);
      }
      case NOT_FOUND -> {
        assertRefused(call, "NOT_FOUND", HttpStatus.NOT_FOUND);
        assertThat(statusOf(tripId)).isEqualTo(state);
        assertRecorded(tripId, 0);
      }
    }
  }

  private static Outcome expected(TripStatus state, TripAction action, Party party) {
    boolean hasDriver = state != TripStatus.REQUESTED && state != TripStatus.NO_DRIVER_FOUND;
    if (party == Party.OTHER_RIDER
        || party == Party.OTHER_DRIVER
        || (party == Party.DRIVER && !hasDriver)) {
      return Outcome.NOT_FOUND;
    }
    // Repeats of something already done succeed quietly: a retry must not become an error.
    if ((action == TripAction.CANCEL && state == TripStatus.CANCELLED)
        || (action == TripAction.MARK_NO_DRIVER_FOUND && state == TripStatus.NO_DRIVER_FOUND)) {
      return Outcome.UNCHANGED;
    }
    return target(state, action, party) != null ? Outcome.MOVES : Outcome.INVALID_TRANSITION;
  }

  private static TripStatus target(TripStatus state, TripAction action, Party party) {
    TripActor actor =
        switch (party) {
          case RIDER -> TripActor.RIDER;
          case DRIVER -> TripActor.DRIVER;
          default -> TripActor.SYSTEM;
        };
    return TripStateMachine.TRANSITIONS.stream()
        .filter(t -> t.from() == state && t.action() == action && t.actor() == actor)
        .map(TripStateMachine.Transition::to)
        .findFirst()
        .orElse(null);
  }

  private void assertRecorded(UUID tripId, long expected) {
    assertThat(auditRows(tripId)).as("trip_events rows").isEqualTo(expected);
    assertThat(events.stream(TripEvent.class).filter(event -> event.tripId().equals(tripId)))
        .as("published trip events")
        .hasSize((int) expected);
  }

  private static void assertRefused(ThrowingCallable call, String code, HttpStatus status) {
    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(call)
        .satisfies(
            e -> {
              assertThat(e.code()).isEqualTo(code);
              assertThat(e.status()).isEqualTo(status);
            });
  }
}
