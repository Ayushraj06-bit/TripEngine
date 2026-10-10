package com.rideflow.payment.internal;

import com.rideflow.shared.RideFlowProperties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a unit of payment work in its own transaction, again if it lost a race.
 *
 * <p>A balance is written under its account's version, so of two postings that read the same
 * balance, the second fails at commit. Run again, it reads the balance the first wrote, and both
 * count. A short random pause first keeps a crowd of losers from colliding again in step.
 *
 * <p>ponytail: every charge also writes the one platform-revenue row, so under heavy load postings
 * queue on it and retry. Split it into sub-accounts if load tests show the retries (ADR 0012).
 */
@Component
class Postings {

  private final TransactionTemplate transactions;
  private final int maxAttempts;

  Postings(PlatformTransactionManager transactionManager, RideFlowProperties properties) {
    this.transactions = new TransactionTemplate(transactionManager);
    this.maxAttempts = properties.payment().postingMaxAttempts();
  }

  /**
   * @throws ConcurrencyFailureException if every attempt lost
   */
  <T> T run(Supplier<T> work) {
    for (int attempt = 1; ; attempt++) {
      try {
        return transactions.execute(status -> work.get());
      } catch (ConcurrencyFailureException lost) {
        if (attempt >= maxAttempts) {
          throw lost;
        }
        pause(attempt);
      }
    }
  }

  void run(Runnable work) {
    run(
        () -> {
          work.run();
          return null;
        });
  }

  private static void pause(int attempt) {
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(1, 5L * attempt + 2));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while retrying a posting", e);
    }
  }
}
