package com.rideflow.location.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rideflow.driver.DriverStatus;
import com.rideflow.driver.DriverStatuses;
import com.rideflow.shared.VehicleType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class DriverStateRepairTest {

  private final DriverStatuses statuses = mock(DriverStatuses.class);
  private final TripContextListener trips = mock(TripContextListener.class);
  private final DriverStateStore store = mock(DriverStateStore.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final DriverStateRepair repair = new DriverStateRepair(statuses, trips, store, meters);

  private final UUID driver = UUID.randomUUID();

  private double counted(String result) {
    return meters.get("driver.state.repairs").tag("result", result).counter().count();
  }

  /** The status marks the state whole: written without the trip, nothing would try again. */
  @Test
  void leavesTheStatusForTheNextPositionWhenPostgresqlIsDown() {
    when(statuses.current(driver))
        .thenReturn(
            Optional.of(new DriverStatuses.Current(DriverStatus.AVAILABLE, VehicleType.CAR, 7)));
    doThrow(new DataAccessResourceFailureException("down")).when(trips).follow(driver);

    // More failures than there are permits: each gave its own back.
    for (int i = 0; i < 3; i++) {
      repair.repair(driver);
    }
    verify(store, never()).applyStatusChange(any(), any(), any(), anyLong());

    reset(trips);
    repair.repair(driver);
    verify(store, times(1)).applyStatusChange(driver, "AVAILABLE", VehicleType.CAR, 7);
    assertThat(counted("failed")).isEqualTo(3);
    assertThat(counted("restored")).isOne();
  }

  @Test
  void writesNoStatusForADriverWithoutAProfile() {
    when(statuses.current(driver)).thenReturn(Optional.empty());

    repair.repair(driver);

    verify(store, never()).applyStatusChange(any(), any(), any(), anyLong());
  }

  /** A restart with every driver reporting at once: two repairs run, the third returns at once. */
  @Test
  void runsTwoAtOnceAndTurnsTheRestAway() throws Exception {
    UUID turnedAway = UUID.randomUUID();
    CountDownLatch bothInside = new CountDownLatch(2);
    CountDownLatch finish = new CountDownLatch(1);
    when(statuses.current(any()))
        .thenAnswer(
            call -> {
              bothInside.countDown();
              assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
              return Optional.empty();
            });

    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<?> first = pool.submit(() -> repair.repair(UUID.randomUUID()));
      Future<?> second = pool.submit(() -> repair.repair(UUID.randomUUID()));
      assertThat(bothInside.await(10, TimeUnit.SECONDS)).isTrue();

      repair.repair(turnedAway);
      verify(trips, never()).follow(turnedAway);
      assertThat(counted("deferred")).isOne();

      finish.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
  }
}
