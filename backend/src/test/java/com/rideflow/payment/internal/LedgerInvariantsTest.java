package com.rideflow.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rideflow.identity.Role;
import com.rideflow.payment.LedgerReconciliation;
import com.rideflow.payment.Refunds;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.Money;
import com.rideflow.shared.RideFlowProperties.Payment.Outcome;
import com.rideflow.trip.PaymentMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Property: whatever happens to the money, the ledger adds up.
 *
 * <p>Each seed drives a random sequence of top-ups (some failing), wallet and cash trips, fees,
 * refunds (some refused), and redelivered charges through the real services and database. After it,
 * every transaction balances, every account's balance equals the sum of its entries, and all
 * balances together come to zero.
 *
 * <p>This is the jqwik property the brief asks for, written as a seeded parameterized test because
 * no jqwik release runs on JUnit Platform 6 yet (ADR 0003). A failure names its seed and lists the
 * operations, so it replays exactly; what is lost is jqwik's shrinking to a minimal sequence.
 */
class LedgerInvariantsTest extends PaymentIntegrationTestBase {

  private static final int OPERATIONS = 30;

  @Autowired TopupService topups;
  @Autowired TripCharges charges;
  @Autowired Refunds refunds;
  @Autowired LedgerReconciliation reconciliation;

  static LongStream seeds() {
    return LongStream.rangeClosed(1, 40);
  }

  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void keepsEveryTransactionBalancedAndEveryBalanceTrue(long seed) {
    Random random = new Random(seed);
    List<User> riders = List.of(userRow(Role.RIDER), userRow(Role.RIDER), userRow(Role.RIDER));
    List<User> drivers = List.of(userRow(Role.DRIVER), userRow(Role.DRIVER));
    UUID admin = UUID.randomUUID();
    List<String> done = new ArrayList<>();
    List<Charge> charged = new ArrayList<>();

    for (int i = 0; i < OPERATIONS; i++) {
      User rider = riders.get(random.nextInt(riders.size()));
      User driver = drivers.get(random.nextInt(drivers.size()));
      // Odd amounts on purpose: the commission split must never lose a paisa.
      long amount = random.nextLong(1_000, 30_001);
      switch (random.nextInt(6)) {
        case 0 -> {
          UUID topupId = topups.start(rider.id(), UUID.randomUUID().toString(), amount).topupId();
          Outcome outcome = random.nextInt(5) == 0 ? Outcome.FAILED : Outcome.SUCCEEDED;
          topups.confirm(new GatewayNotification("evt", gatewayRefOf(topupId), outcome, amount));
          done.add("top up %d %s".formatted(amount, outcome));
        }
        case 1, 2 -> {
          PaymentMethod method = random.nextBoolean() ? PaymentMethod.WALLET : PaymentMethod.CASH;
          Charge charge = new Charge(UUID.randomUUID(), rider, driver, amount, method);
          charge.apply(charges);
          charged.add(charge);
          done.add("trip %d %s".formatted(amount, method));
        }
        case 3 -> {
          long fee = random.nextLong(1, 8_001);
          charges.chargeCancellationFee(
              UUID.randomUUID(), rider.id(), driver.id(), Money.ofPaise(fee));
          done.add("fee " + fee);
        }
        case 4 -> {
          UUID target = refundTarget(random);
          try {
            refunds.refund(target, admin);
            done.add("refund " + target);
          } catch (BusinessException refused) {
            done.add("refund refused " + refused.code());
          }
        }
        default -> {
          if (!charged.isEmpty()) {
            charged.get(random.nextInt(charged.size())).apply(charges);
            done.add("charge delivered again");
          }
        }
      }
    }

    LedgerReconciliation.Report report = reconciliation.reconcile();
    assertThat(report.consistent())
        .withFailMessage("seed %d broke the ledger: %s%nafter %s", seed, report, done)
        .isTrue();
    assertThat(report.totalBalancePaise()).isZero();
  }

  /** Mostly something refundable; sometimes a top-up, a refund, or nothing at all. */
  private UUID refundTarget(Random random) {
    List<UUID> ids =
        jdbc.queryForList("SELECT id FROM ledger_transactions ORDER BY id", UUID.class);
    if (ids.isEmpty() || random.nextInt(6) == 0) {
      return UUID.randomUUID();
    }
    return ids.get(random.nextInt(ids.size()));
  }

  private record Charge(UUID tripId, User rider, User driver, long fare, PaymentMethod method) {

    void apply(TripCharges charges) {
      charges.chargeFare(tripId, rider.id(), driver.id(), Money.ofPaise(fare), method);
    }
  }
}
