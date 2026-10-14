package com.rideflow.simulator;

import com.rideflow.simulator.BackendApi.ApiException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Creates the simulated users through the public API, or finds them again.
 *
 * <p>Idempotent: user {@code i} always has the same phone number and plate, registering an existing
 * number logs in instead, a vehicle is added only if missing, approval asked for only if not given,
 * and a wallet topped up only when low. A restarted simulator carries on with the same accounts.
 */
@Component
class Accounts {

  private static final Logger log = LoggerFactory.getLogger(Accounts.class);

  /** Registrations hash passwords with BCrypt: a few at once, not hundreds. */
  private static final int CONCURRENCY = 16;

  private final BackendApi api;
  private final Clock clock;

  Accounts(BackendApi api, Clock clock) {
    this.api = api;
    this.clock = clock;
  }

  record Population(List<UserSession> drivers, List<UserSession> riders) {}

  /** A driver set up, and whether an admin still has to approve them. */
  private record Driver(UserSession session, UUID id, boolean approved) {}

  Population setUp(SimulatorProperties properties, World world) {
    var accounts = properties.accounts();
    List<Driver> drivers =
        inParallel(
            properties.drivers(),
            i -> {
              UserSession driver =
                  user("DRIVER", driverPhone(i), "Simulated driver " + i, properties);
              JsonNode profile = api.driverProfile(driver);
              if (profile.get("vehicle").isNull()) {
                api.registerVehicle(driver, world.vehicleType(i), "SIM%06d".formatted(i));
              }
              return new Driver(
                  driver,
                  UUID.fromString(profile.get("driverId").asString()),
                  "APPROVED".equals(profile.get("approvalStatus").asString()));
            });
    List<UUID> unapproved = drivers.stream().filter(d -> !d.approved()).map(Driver::id).toList();
    if (!unapproved.isEmpty()) {
      UserSession admin = admin(accounts);
      unapproved.forEach(id -> api.approve(admin, id));
    }
    List<UserSession> riders =
        inParallel(
            properties.riders(),
            i -> {
              UserSession rider = user("RIDER", riderPhone(i), "Simulated rider " + i, properties);
              if ("WALLET".equals(properties.demand().paymentMethod())
                  && api.walletBalance(rider) < accounts.minBalancePaise()) {
                api.topUp(rider, accounts.topUpPaise());
              }
              return rider;
            });
    log.info(
        "Set up {} drivers ({} newly approved) and {} riders",
        drivers.size(),
        unapproved.size(),
        riders.size());
    return new Population(drivers.stream().map(Driver::session).toList(), riders);
  }

  /** Simulated drivers are +91 70xxxxxxxx; riders +91 71xxxxxxxx. */
  static String driverPhone(int index) {
    return "+9170%08d".formatted(index);
  }

  static String riderPhone(int index) {
    return "+9171%08d".formatted(index);
  }

  private UserSession user(String role, String phone, String name, SimulatorProperties p) {
    String password = p.accounts().password();
    try {
      return new UserSession(
          api, clock, phone, password, api.registerOrLogin(role, phone, name, password));
    } catch (ApiException e) {
      if ("RATE_LIMITED".equals(e.code())) {
        throw new IllegalStateException(
            "The backend rate-limits registrations and logins per address. Run it with the sim"
                + " profile (--spring.profiles.active=local,sim): see simulator/README.md",
            e);
      }
      throw e;
    }
  }

  /** Only when a driver needs approving: a restart whose drivers are all approved needs none. */
  private UserSession admin(SimulatorProperties.Accounts accounts) {
    if (accounts.adminPhone() == null || accounts.adminPhone().isBlank()) {
      throw new IllegalStateException(
          "Drivers need approving: set RIDEFLOW_ADMIN_PHONE and RIDEFLOW_ADMIN_PASSWORD to the"
              + " backend's bootstrap admin");
    }
    return new UserSession(
        api,
        clock,
        accounts.adminPhone(),
        accounts.adminPassword(),
        api.login(accounts.adminPhone(), accounts.adminPassword()));
  }

  /** {@code count} users made at once on virtual threads, a few at a time, in index order. */
  private static <T> List<T> inParallel(int count, IntFunction<T> make) {
    Semaphore permits = new Semaphore(CONCURRENCY);
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<T>> made = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        int index = i;
        made.add(
            threads.submit(
                () -> {
                  permits.acquire();
                  try {
                    return make.apply(index);
                  } finally {
                    permits.release();
                  }
                }));
      }
      List<T> users = new ArrayList<>(count);
      for (Future<T> user : made) {
        users.add(user.get());
      }
      return users;
    } catch (ExecutionException e) {
      throw e.getCause() instanceof RuntimeException runtime
          ? runtime
          : new IllegalStateException(e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted during setup", e);
    }
  }
}
