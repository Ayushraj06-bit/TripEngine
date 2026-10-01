package com.rideflow.trip.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.rideflow.shared.BusinessException;
import com.rideflow.trip.TripActor;
import com.rideflow.trip.TripStatus;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

/**
 * Every state, every action, every actor: 126 combinations, each either the transition the table
 * allows or a 409. Generated from {@link TripStateMachine#TRANSITIONS} - the list the code runs on
 * - so a row added there is tested here without anyone remembering to.
 */
class TripStateMachineTest {

  static Stream<Arguments> everyCombination() {
    return Arrays.stream(TripStatus.values())
        .flatMap(
            state ->
                Arrays.stream(TripAction.values())
                    .flatMap(
                        action ->
                            Arrays.stream(TripActor.values())
                                .map(actor -> Arguments.of(state, action, actor))));
  }

  private static Optional<TripStatus> allowedTarget(
      TripStatus state, TripAction action, TripActor actor) {
    return TripStateMachine.TRANSITIONS.stream()
        .filter(t -> t.from() == state && t.action() == action && t.actor() == actor)
        .map(TripStateMachine.Transition::to)
        .findFirst();
  }

  @ParameterizedTest(name = "{2} {1} on {0}")
  @MethodSource("everyCombination")
  void allowsExactlyWhatTheTableAllows(TripStatus state, TripAction action, TripActor actor) {
    Optional<TripStatus> target = allowedTarget(state, action, actor);

    if (target.isPresent()) {
      assertThat(TripStateMachine.next(state, action, actor)).isEqualTo(target.get());
    } else {
      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> TripStateMachine.next(state, action, actor))
          .satisfies(
              e -> {
                assertThat(e.code()).isEqualTo("TRIP_INVALID_TRANSITION");
                assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.properties()).containsEntry("tripStatus", state);
              });
    }
  }

  @Test
  void holdsExactlyTheTenTransitionsOfTheSpec() {
    // PROJECT_SPEC section 6 lists ten rows. A duplicate or a missing one would still
    // leave the parameterised test green, so count them.
    assertThat(TripStateMachine.TRANSITIONS)
        .hasSize(10)
        .doesNotHaveDuplicates()
        .extracting(t -> t.from() + "/" + t.action() + "/" + t.actor())
        .doesNotHaveDuplicates();
  }

  @Test
  void neverLeavesATerminalState() {
    assertThat(TripStateMachine.TRANSITIONS)
        .extracting(TripStateMachine.Transition::from)
        .allMatch(TripStatus::isActive);
  }
}
