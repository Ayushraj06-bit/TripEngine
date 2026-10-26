package com.rideflow.driver.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.rideflow.TestcontainersConfiguration;
import com.rideflow.driver.ApprovalStatus;
import com.rideflow.driver.DriverAdministration;
import com.rideflow.driver.DriverStatus;
import com.rideflow.driver.DriverStatusChanged;
import com.rideflow.identity.Role;
import com.rideflow.identity.internal.AuthService;
import com.rideflow.shared.BusinessException;
import com.rideflow.shared.VehicleType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Driver onboarding, approval and availability against real PostgreSQL. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@RecordApplicationEvents
class DriverOnboardingIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private AuthService auth;
  @Autowired private DriverService drivers;
  @Autowired private DriverAdministration administration;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationEvents events;

  private UUID driverId;

  @BeforeEach
  void registerADriver() {
    jdbc.execute("TRUNCATE refresh_tokens, vehicles, drivers, users CASCADE");
    auth.register("+919000001111", "Ravi", PASSWORD, Role.DRIVER);
    driverId = jdbc.queryForObject("SELECT user_id FROM drivers", UUID.class);
  }

  private void addVehicle(String plate) {
    drivers.registerVehicle(driverId, VehicleType.CAR, plate, "Swift Dzire", "White");
  }

  private void approve() {
    administration.approve(driverId);
  }

  @Nested
  class Profile {

    @Test
    void startsPendingAndOffline() {
      var profile = drivers.profile(driverId);

      assertThat(profile.approvalStatus()).isEqualTo(ApprovalStatus.PENDING);
      assertThat(profile.status()).isEqualTo(DriverStatus.OFFLINE);
      assertThat(profile.vehicle()).isNull();
      // No ratings yet, so an average would be a lie rather than a zero.
      assertThat(profile.averageRating()).isNull();
      assertThat(profile.ratingCount()).isZero();
    }

    @Test
    void reportsANotFoundForSomebodyWhoIsNotADriver() {
      UUID rider = UUID.randomUUID();

      // 404 rather than 403: PROJECT_SPEC section 10 never confirms whether someone
      // else's resource exists.
      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> drivers.profile(rider))
          .satisfies(e -> assertThat(e.status().value()).isEqualTo(404));
    }
  }

  @Nested
  class VehicleRegistration {

    @Test
    void storesTheVehicleAgainstTheDriver() {
      addVehicle("KA01AB1234");

      assertThat(drivers.profile(driverId).vehicle())
          .satisfies(
              vehicle -> {
                assertThat(vehicle.type()).isEqualTo(VehicleType.CAR);
                assertThat(vehicle.plateNumber()).isEqualTo("KA01AB1234");
                assertThat(vehicle.model()).isEqualTo("Swift Dzire");
              });
    }

    @Test
    void normalisesThePlateBeforeStoringIt() {
      addVehicle("ka 01 ab 1234");

      assertThat(drivers.profile(driverId).vehicle().plateNumber()).isEqualTo("KA01AB1234");
    }

    @Test
    void replacesTheDriversOwnVehicleRatherThanAddingASecond() {
      addVehicle("KA01AB1234");
      drivers.registerVehicle(driverId, VehicleType.BIKE, "KA02CD5678", "Activa", "Grey");

      assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicles", Integer.class)).isOne();
      assertThat(drivers.profile(driverId).vehicle().type()).isEqualTo(VehicleType.BIKE);
    }

    @Test
    void acceptsTheDriverReSubmittingTheSamePlate() {
      addVehicle("KA01AB1234");

      // Their own plate is not a duplicate of itself.
      addVehicle("KA 01 AB 1234");

      assertThat(drivers.profile(driverId).vehicle().plateNumber()).isEqualTo("KA01AB1234");
    }

    @Test
    void refusesAPlateAlreadyRegisteredToAnotherDriver() {
      addVehicle("KA01AB1234");
      auth.register("+919000002222", "Second", PASSWORD, Role.DRIVER);
      UUID other =
          jdbc.queryForObject(
              "SELECT user_id FROM drivers WHERE user_id <> ?", UUID.class, driverId);

      // The same car written differently is still the same car.
      for (String sameCar : List.of("KA01AB1234", "ka01ab1234", "KA-01-AB-1234")) {
        assertThatExceptionOfType(BusinessException.class)
            .isThrownBy(
                () -> drivers.registerVehicle(other, VehicleType.CAR, sameCar, "Swift", "Red"))
            .satisfies(e -> assertThat(e.code()).isEqualTo("PLATE_ALREADY_REGISTERED"))
            .withFailMessage("%s should have collided with the existing plate", sameCar);
      }
    }
  }

  @Nested
  class GoingOnline {

    @Test
    void refusesWhileApprovalIsPending() {
      addVehicle("KA01AB1234");

      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> drivers.goOnline(driverId))
          .satisfies(
              e -> {
                assertThat(e.code()).isEqualTo("DRIVER_NOT_APPROVED");
                assertThat(e.status().value()).isEqualTo(422);
              });
    }

    @Test
    void refusesWhenApprovedButWithoutAVehicle() {
      approve();

      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> drivers.goOnline(driverId))
          .satisfies(
              e -> {
                assertThat(e.code()).isEqualTo("DRIVER_HAS_NO_VEHICLE");
                assertThat(e.status().value()).isEqualTo(422);
              });
    }

    @Test
    void refusesAfterRejection() {
      addVehicle("KA01AB1234");
      administration.reject(driverId, "Licence expired");

      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> drivers.goOnline(driverId))
          .satisfies(e -> assertThat(e.code()).isEqualTo("DRIVER_NOT_APPROVED"));
    }

    @Test
    void succeedsOnceApprovedWithAVehicle() {
      addVehicle("KA01AB1234");
      approve();

      assertThat(drivers.goOnline(driverId).status()).isEqualTo(DriverStatus.AVAILABLE);
    }

    @Test
    void isIdempotentWhenAlreadyOnline() {
      addVehicle("KA01AB1234");
      approve();
      drivers.goOnline(driverId);

      assertThat(drivers.goOnline(driverId).status()).isEqualTo(DriverStatus.AVAILABLE);
    }
  }

  @Nested
  class GoingOffline {

    @Test
    void succeedsFromAvailable() {
      addVehicle("KA01AB1234");
      approve();
      drivers.goOnline(driverId);

      assertThat(drivers.goOffline(driverId).status()).isEqualTo(DriverStatus.OFFLINE);
    }

    @Test
    void isIdempotentWhenAlreadyOffline() {
      assertThat(drivers.goOffline(driverId).status()).isEqualTo(DriverStatus.OFFLINE);
    }

    @Test
    void isRefusedDuringATrip() {
      addVehicle("KA01AB1234");
      approve();
      drivers.goOnline(driverId);
      // No trip module yet, so the status is set directly - the rule under test is the
      // transition guard, not how ON_TRIP was reached.
      jdbc.update("UPDATE drivers SET status = 'ON_TRIP' WHERE user_id = ?", driverId);

      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> drivers.goOffline(driverId))
          .satisfies(
              e -> {
                assertThat(e.code()).isEqualTo("DRIVER_ON_TRIP");
                assertThat(e.status().value()).isEqualTo(409);
              });
    }
  }

  @Nested
  class Approval {

    @Test
    void isIdempotentWhenApprovedTwice() {
      approve();
      approve();

      assertThat(drivers.profile(driverId).approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void recordsWhyADriverWasRejected() {
      administration.reject(driverId, "Licence expired");

      var profile = drivers.profile(driverId);
      assertThat(profile.approvalStatus()).isEqualTo(ApprovalStatus.REJECTED);
      assertThat(profile.rejectionReason()).isEqualTo("Licence expired");
    }

    @Test
    void refusesARejectionWithNoReason() {
      // V4 has CHECK ((approval_status = 'REJECTED') = (rejection_reason IS NOT NULL)),
      // so a reasonless rejection would fail at the constraint; reject it at the edge
      // where the caller can be told why.
      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> administration.reject(driverId, "  "))
          .satisfies(e -> assertThat(e.code()).isEqualTo("REJECTION_REASON_REQUIRED"));
    }

    @Test
    void allowsApprovalAfterRejection() {
      administration.reject(driverId, "Photo unreadable");
      approve();

      // ADR 0005: a rejection is usually a fixable document problem, and forcing
      // re-registration would orphan the phone number against its unique constraint.
      var profile = drivers.profile(driverId);
      assertThat(profile.approvalStatus()).isEqualTo(ApprovalStatus.APPROVED);
      // The reason must be cleared, or the CHECK constraint would reject the row.
      assertThat(profile.rejectionReason()).isNull();
    }

    @Test
    void forcesARejectedDriverOffline() {
      addVehicle("KA01AB1234");
      approve();
      drivers.goOnline(driverId);

      administration.reject(driverId, "Complaint upheld");

      // A rejected driver must not stay on the live map.
      assertThat(drivers.profile(driverId).status()).isEqualTo(DriverStatus.OFFLINE);
    }
  }

  @Nested
  class AdminListing {

    @Test
    void listsPendingDriversOldestFirst() {
      auth.register("+919000003333", "Second", PASSWORD, Role.DRIVER);
      auth.register("+919000004444", "Third", PASSWORD, Role.DRIVER);

      var page = administration.listByApprovalStatus(ApprovalStatus.PENDING, null, 20);

      assertThat(page.drivers()).hasSize(3);
      // Ids are UUIDv7, so ascending id is registration order.
      assertThat(page.drivers()).first().extracting(e -> e.driverId()).isEqualTo(driverId);
      assertThat(page.nextCursor()).isNull();
    }

    @Test
    void pagesThroughWithACursorWithoutRepeatingOrSkipping() {
      auth.register("+919000003333", "Second", PASSWORD, Role.DRIVER);
      auth.register("+919000004444", "Third", PASSWORD, Role.DRIVER);

      var first = administration.listByApprovalStatus(ApprovalStatus.PENDING, null, 2);
      assertThat(first.drivers()).hasSize(2);
      assertThat(first.nextCursor()).isNotNull();

      var second =
          administration.listByApprovalStatus(ApprovalStatus.PENDING, first.nextCursor(), 2);

      assertThat(second.drivers()).hasSize(1);
      assertThat(second.nextCursor()).isNull();
      assertThat(second.drivers())
          .map(e -> e.driverId())
          .doesNotContainAnyElementsOf(first.drivers().stream().map(e -> e.driverId()).toList());
    }

    @Test
    void excludesDriversWhoAreNoLongerPending() {
      approve();

      assertThat(administration.listByApprovalStatus(ApprovalStatus.PENDING, null, 20).drivers())
          .isEmpty();
      assertThat(administration.listByApprovalStatus(ApprovalStatus.APPROVED, null, 20).drivers())
          .hasSize(1);
    }

    @Test
    void capsThePageSizeAtFifty() {
      // PROJECT_SPEC section 10 caps a page at 50; a caller asking for more must not get it.
      var page = administration.listByApprovalStatus(ApprovalStatus.PENDING, null, 5000);

      assertThat(page.drivers()).hasSizeLessThanOrEqualTo(50);
    }

    @Test
    void showsTheVehicleSoAnAdminCanReviewIt() {
      addVehicle("KA01AB1234");

      var page = administration.listByApprovalStatus(ApprovalStatus.PENDING, null, 20);

      assertThat(page.drivers())
          .first()
          .satisfies(
              entry -> {
                assertThat(entry.vehiclePlateNumber()).isEqualTo("KA01AB1234");
                assertThat(entry.vehicleType()).isEqualTo("CAR");
              });
    }

    @Test
    void reportsANotFoundWhenApprovingSomebodyWhoIsNotADriver() {
      assertThatExceptionOfType(BusinessException.class)
          .isThrownBy(() -> administration.approve(UUID.randomUUID()))
          .satisfies(e -> assertThat(e.status().value()).isEqualTo(404));
    }
  }

  /**
   * A driver's status follows whether they have a trip right now, not which trip event arrived
   * last. These drivers have no trip at all, which is exactly what a late or repeated event looks
   * like once its trip is over.
   */
  @Nested
  class FollowingTrips {

    private void onlineWithACar() {
      approve();
      addVehicle("KA01AB1234");
      drivers.goOnline(driverId);
    }

    @Test
    void doesNotPutADriverOnATripThatHasAlreadyEnded() {
      onlineWithACar();

      // What a DriverAssigned handled after its trip was handed back amounts to.
      drivers.followTrips(driverId);

      assertThat(drivers.profile(driverId).status()).isEqualTo(DriverStatus.AVAILABLE);
    }

    @Test
    void freesADriverWhoseTripIsOver() {
      onlineWithACar();
      jdbc.update("UPDATE drivers SET status = 'ON_TRIP' WHERE user_id = ?", driverId);

      drivers.followTrips(driverId);

      assertThat(drivers.profile(driverId).status()).isEqualTo(DriverStatus.AVAILABLE);
    }

    /**
     * Another change to the same driver, not yet committed - here, a DriverAssigned handled a
     * moment earlier putting them ON_TRIP. Unlocked, this call read the old AVAILABLE, changed
     * nothing, and the ON_TRIP committed after it: a driver held on a trip that no longer existed.
     */
    @Test
    void waitsForAConcurrentChangeToTheSameDriverBeforeReadingTheirTrips() throws Exception {
      onlineWithACar();
      try (Connection other = jdbc.getDataSource().getConnection();
          ExecutorService listener = Executors.newSingleThreadExecutor()) {
        other.setAutoCommit(false);
        try (PreparedStatement onTrip =
            other.prepareStatement("UPDATE drivers SET status = 'ON_TRIP' WHERE user_id = ?")) {
          onTrip.setObject(1, driverId);
          onTrip.executeUpdate();
        }

        Future<?> following = listener.submit(() -> drivers.followTrips(driverId));
        // Under PostgreSQL's 2 s lock_timeout: it waits rather than acting on the old row.
        assertThatExceptionOfType(TimeoutException.class)
            .isThrownBy(() -> following.get(1, TimeUnit.SECONDS));

        other.commit();
        following.get(10, TimeUnit.SECONDS);
      }

      assertThat(drivers.profile(driverId).status()).isEqualTo(DriverStatus.AVAILABLE);
    }

    /**
     * Each change carries the version it gives the driver's row, so location can tell an older
     * change arriving late from a newer one. Read before the flush, every event carried the same.
     */
    @Test
    void numbersEachStatusChangeWithTheRowsNewVersion() {
      onlineWithACar();
      drivers.goOffline(driverId);
      drivers.goOnline(driverId);

      List<Long> versions =
          events.stream(DriverStatusChanged.class).map(DriverStatusChanged::driverVersion).toList();
      assertThat(versions).hasSize(3).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void leavesAnOfflineDriverOffline() {
      approve();
      addVehicle("KA01AB1234");

      drivers.followTrips(driverId);

      assertThat(drivers.profile(driverId).status()).isEqualTo(DriverStatus.OFFLINE);
    }
  }
}
