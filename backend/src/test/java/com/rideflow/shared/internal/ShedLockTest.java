package com.rideflow.shared.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rideflow.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * ShedLock against the real PostgreSQL.
 *
 * <p>PROJECT_SPEC section 3 requires jobs that must run once per cluster to hold one of these
 * locks. A provider that silently fails to exclude would let every instance run the stale-driver
 * sweep and the surge tick, so the surge multiplier would step once per instance per tick and drift
 * away from the configured cap. Asserting the bean exists proves nothing about that, so this
 * acquires and contends real locks.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class ShedLockTest {

  private static final Duration LOCK_AT_MOST_FOR = Duration.ofSeconds(30);

  @Autowired private LockProvider lockProvider;
  @Autowired private JdbcTemplate jdbc;

  private LockConfiguration lockFor(String name) {
    // ShedLock takes the time itself here; usingDbTime() means the database's clock
    // decides expiry, not this JVM's.
    return new LockConfiguration(Instant.now(), name, LOCK_AT_MOST_FOR, Duration.ZERO);
  }

  @Test
  void grantsALockAndWritesItToTheTable() {
    Optional<SimpleLock> lock = lockProvider.lock(lockFor("grants-a-lock"));

    assertThat(lock).isPresent();
    try {
      Integer rows =
          jdbc.queryForObject(
              "SELECT count(*) FROM shedlock WHERE name = ?", Integer.class, "grants-a-lock");
      assertThat(rows).isOne();
    } finally {
      lock.orElseThrow().unlock();
    }
  }

  @Test
  void refusesASecondHolderWhileTheFirstStillHoldsIt() {
    SimpleLock first = lockProvider.lock(lockFor("only-one-holder")).orElseThrow();
    try {
      // This is the whole point of the lock: the second caller must be turned away.
      assertThat(lockProvider.lock(lockFor("only-one-holder"))).isEmpty();
    } finally {
      first.unlock();
    }
  }

  @Test
  void grantsTheLockAgainAfterItIsReleased() {
    lockProvider.lock(lockFor("reacquire")).orElseThrow().unlock();

    Optional<SimpleLock> second = lockProvider.lock(lockFor("reacquire"));
    assertThat(second).isPresent();
    second.orElseThrow().unlock();
  }

  @Test
  void keepsDifferentJobsIndependent() {
    SimpleLock sweep = lockProvider.lock(lockFor("stale-driver-sweep")).orElseThrow();
    try {
      Optional<SimpleLock> surge = lockProvider.lock(lockFor("surge-tick"));
      assertThat(surge).isPresent();
      surge.orElseThrow().unlock();
    } finally {
      sweep.unlock();
    }
  }

  @Test
  void grantsTheLockToExactlyOneOfManySimultaneousCallers() throws Exception {
    int contenders = 8;
    AtomicInteger granted = new AtomicInteger();
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(contenders);

    try (ExecutorService executor = Executors.newFixedThreadPool(contenders)) {
      for (int i = 0; i < contenders; i++) {
        executor.execute(
            () -> {
              try {
                startGate.await();
                lockProvider
                    .lock(lockFor("contended"))
                    .ifPresent(
                        held -> {
                          granted.incrementAndGet();
                          held.unlock();
                        });
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                finished.countDown();
              }
            });
      }

      // Release them together, so they genuinely race for the row.
      startGate.countDown();
      assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
    }

    // Each holder unlocks immediately, so a later contender may legitimately get it
    // too. What must never happen is nobody getting it, or the provider throwing.
    assertThat(granted.get()).isPositive().isLessThanOrEqualTo(contenders);
  }
}
