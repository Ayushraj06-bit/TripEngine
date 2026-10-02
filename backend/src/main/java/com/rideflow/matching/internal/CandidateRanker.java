package com.rideflow.matching.internal;

import com.rideflow.driver.CandidateFacts;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Who gets offered a trip first. Pure: no Redis, no database, no clock.
 *
 * <p>Nearest first, but not to the metre. Distances are grouped into buckets of {@code
 * fairness-bucket-meters}, and within a bucket the driver who has waited longest goes first, then
 * the one who accepts more often. Without the buckets, a driver parked 20 m closer to a busy spot
 * would take every trip from the one who has been waiting for an hour.
 */
final class CandidateRanker {

  /** A driver found near the pickup, with what ranking needs to know about them. */
  record Candidate(double distanceMeters, CandidateFacts facts) {

    UUID driverId() {
      return facts.driverId();
    }
  }

  private final int fairnessBucketMeters;
  private final double minDriverRating;

  CandidateRanker(int fairnessBucketMeters, double minDriverRating) {
    this.fairnessBucketMeters = fairnessBucketMeters;
    this.minDriverRating = minDriverRating;
  }

  /**
   * The candidates allowed an offer, best first. A driver rated below {@code min-driver-rating} is
   * left out; one not yet rated is not.
   */
  List<Candidate> rank(List<Candidate> candidates) {
    Comparator<Candidate> order =
        Comparator.comparingLong(this::bucket)
            .thenComparing(candidate -> candidate.facts().availableSince())
            .thenComparing(
                candidate -> candidate.facts().acceptanceRate(), Comparator.reverseOrder())
            .thenComparingDouble(Candidate::distanceMeters);
    return candidates.stream().filter(this::ratedHighEnough).sorted(order).toList();
  }

  private long bucket(Candidate candidate) {
    return (long) (candidate.distanceMeters() / fairnessBucketMeters);
  }

  private boolean ratedHighEnough(Candidate candidate) {
    Double rating = candidate.facts().averageRating();
    return rating == null || rating >= minDriverRating;
  }
}
