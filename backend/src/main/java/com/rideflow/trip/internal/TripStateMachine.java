package com.rideflow.trip.internal;

import static com.rideflow.trip.TripActor.DRIVER;
import static com.rideflow.trip.TripActor.RIDER;
import static com.rideflow.trip.TripActor.SYSTEM;
import static com.rideflow.trip.TripStatus.CANCELLED;
import static com.rideflow.trip.TripStatus.COMPLETED;
import static com.rideflow.trip.TripStatus.DRIVER_ARRIVED;
import static com.rideflow.trip.TripStatus.DRIVER_ASSIGNED;
import static com.rideflow.trip.TripStatus.IN_PROGRESS;
import static com.rideflow.trip.TripStatus.NO_DRIVER_FOUND;
import static com.rideflow.trip.TripStatus.REQUESTED;

import com.rideflow.shared.BusinessException;
import com.rideflow.trip.TripActor;
import com.rideflow.trip.TripStatus;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The transition table of PROJECT_SPEC section 6, as data.
 *
 * <p>It answers one question: may this actor take this action on a trip in this state, and if so,
 * what state does it move to. The conditions in the table's "Rule" column - the geofence, the OTP,
 * the grace period, the no-show wait - are checked by {@link TripService} around it, because they
 * need a clock, a position or a rate card rather than just the three inputs here.
 *
 * <p>Keeping it as a list rather than a switch is what lets the tests generate every combination
 * from the same table the code runs on.
 */
final class TripStateMachine {

  record Transition(TripStatus from, TripAction action, TripActor actor, TripStatus to) {}

  static final List<Transition> TRANSITIONS =
      List.of(
          new Transition(REQUESTED, TripAction.ASSIGN_DRIVER, SYSTEM, DRIVER_ASSIGNED),
          new Transition(REQUESTED, TripAction.CANCEL, RIDER, CANCELLED),
          new Transition(REQUESTED, TripAction.MARK_NO_DRIVER_FOUND, SYSTEM, NO_DRIVER_FOUND),
          new Transition(DRIVER_ASSIGNED, TripAction.ARRIVE, DRIVER, DRIVER_ARRIVED),
          new Transition(DRIVER_ASSIGNED, TripAction.CANCEL, RIDER, CANCELLED),
          // The driver backing out does not end the trip: it goes back to matching.
          new Transition(DRIVER_ASSIGNED, TripAction.CANCEL, DRIVER, REQUESTED),
          new Transition(DRIVER_ARRIVED, TripAction.START, DRIVER, IN_PROGRESS),
          new Transition(DRIVER_ARRIVED, TripAction.CANCEL, RIDER, CANCELLED),
          new Transition(DRIVER_ARRIVED, TripAction.CANCEL, DRIVER, CANCELLED),
          new Transition(IN_PROGRESS, TripAction.COMPLETE, DRIVER, COMPLETED));

  private TripStateMachine() {}

  /**
   * @return the state {@code action} moves the trip to
   * @throws BusinessException 409 {@code TRIP_INVALID_TRANSITION}, carrying the current state
   */
  static TripStatus next(TripStatus from, TripAction action, TripActor actor) {
    return TRANSITIONS.stream()
        .filter(t -> t.from() == from && t.action() == action && t.actor() == actor)
        .map(Transition::to)
        .findFirst()
        .orElseThrow(
            () ->
                new BusinessException(
                    "TRIP_INVALID_TRANSITION",
                    HttpStatus.CONFLICT,
                    "A %s cannot %s a trip that is %s"
                        .formatted(
                            actor.name().toLowerCase(),
                            action.name().toLowerCase().replace('_', ' '),
                            from),
                    Map.of("tripStatus", from)));
  }
}
