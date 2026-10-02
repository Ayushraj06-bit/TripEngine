package com.rideflow.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rideflow.driver.CandidateFacts;
import com.rideflow.matching.internal.CandidateRanker.Candidate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateRankerTest {

  private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

  /** The section 9 values: 300 m buckets, 3.5 stars minimum. */
  private final CandidateRanker ranker = new CandidateRanker(300, 3.5);

  private static Candidate driver(
      double meters, Double rating, int received, int accepted, Duration idle) {
    return new Candidate(
        meters, new CandidateFacts(UUID.randomUUID(), rating, received, accepted, NOW.minus(idle)));
  }

  /** An unremarkable driver: rated well, accepts everything, just became available. */
  private static Candidate driverAt(double meters) {
    return driver(meters, 4.8, 10, 10, Duration.ZERO);
  }

  @Test
  void putsNearerDriversFirst() {
    Candidate far = driverAt(1_200);
    Candidate near = driverAt(200);
    Candidate middle = driverAt(700);

    assertThat(ranker.rank(List.of(far, near, middle))).containsExactly(near, middle, far);
  }

  @Test
  void prefersWhoeverHasWaitedLongestAmongDriversAboutAsClose() {
    // Both inside the first 300 m bucket: 150 m is not a reason to skip someone who has
    // been waiting twenty minutes.
    Candidate closerButJustArrived = driver(100, 4.8, 10, 10, Duration.ofMinutes(1));
    Candidate waitingLonger = driver(250, 4.8, 10, 10, Duration.ofMinutes(20));

    assertThat(ranker.rank(List.of(closerButJustArrived, waitingLonger)))
        .containsExactly(waitingLonger, closerButJustArrived);
  }

  @Test
  void stillPrefersAMuchNearerDriverOverOneWhoWaitedLonger() {
    Candidate near = driver(100, 4.8, 10, 10, Duration.ofMinutes(1));
    Candidate twoBucketsAway = driver(700, 4.8, 10, 10, Duration.ofMinutes(60));

    assertThat(ranker.rank(List.of(twoBucketsAway, near))).containsExactly(near, twoBucketsAway);
  }

  @Test
  void thenPrefersTheDriverWhoAcceptsMoreOften() {
    Candidate choosy = driver(100, 4.8, 10, 5, Duration.ofMinutes(5));
    Candidate reliable = driver(200, 4.8, 10, 9, Duration.ofMinutes(5));

    assertThat(ranker.rank(List.of(choosy, reliable))).containsExactly(reliable, choosy);
  }

  @Test
  void thenTheNearer() {
    Candidate further = driver(250, 4.8, 10, 10, Duration.ofMinutes(5));
    Candidate nearer = driver(50, 4.8, 10, 10, Duration.ofMinutes(5));

    assertThat(ranker.rank(List.of(further, nearer))).containsExactly(nearer, further);
  }

  @Test
  void leavesOutDriversRatedBelowTheMinimum() {
    Candidate poorlyRated = driver(100, 3.4, 10, 10, Duration.ZERO);
    Candidate justEnough = driver(200, 3.5, 10, 10, Duration.ZERO);

    assertThat(ranker.rank(List.of(poorlyRated, justEnough))).containsExactly(justEnough);
  }

  @Test
  void keepsNewDriversWhoHaveNoRatingYet() {
    Candidate unrated = driver(100, null, 0, 0, Duration.ZERO);

    assertThat(ranker.rank(List.of(unrated))).containsExactly(unrated);
  }

  @Test
  void doesNotRankANewDriverAsThoughTheyHadRefusedWork() {
    Candidate brandNew = driver(100, null, 0, 0, Duration.ofMinutes(5));
    Candidate experienced = driver(100, 4.9, 10, 8, Duration.ofMinutes(5));

    assertThat(brandNew.facts().acceptanceRate()).isEqualTo(1.0);
    assertThat(ranker.rank(List.of(experienced, brandNew))).containsExactly(brandNew, experienced);
  }
}
