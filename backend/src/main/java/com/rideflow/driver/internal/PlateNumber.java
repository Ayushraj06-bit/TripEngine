package com.rideflow.driver.internal;

import com.rideflow.shared.BusinessException;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Normalises a registration plate before it reaches the database.
 *
 * <p>Same reason as phone numbers: the unique constraint on {@code vehicles.plate_number} only
 * means something if the stored form is canonical. Without this, {@code KA 01 AB 1234}, {@code
 * ka01ab1234} and {@code KA-01-AB-1234} are three different plates, and one car could be registered
 * to three drivers.
 */
final class PlateNumber {

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Za-z0-9]");

  /**
   * Indian plates run 8 to 11 characters once separators are stripped; allow a little either way.
   */
  private static final int MIN_LENGTH = 5;

  private static final int MAX_LENGTH = 15;

  private PlateNumber() {}

  /**
   * The canonical form of {@code raw}: uppercase, letters and digits only.
   *
   * @throws BusinessException 400 if nothing usable is left
   */
  static String normalise(String raw) {
    if (raw == null || raw.isBlank()) {
      throw invalid("Plate number is required");
    }

    String normalised =
        NON_ALPHANUMERIC.matcher(raw).replaceAll("").toUpperCase(java.util.Locale.ROOT);

    if (normalised.length() < MIN_LENGTH || normalised.length() > MAX_LENGTH) {
      throw invalid(
          "Plate number must be %d to %d letters and digits, for example KA01AB1234"
              .formatted(MIN_LENGTH, MAX_LENGTH));
    }
    return normalised;
  }

  private static BusinessException invalid(String message) {
    return new BusinessException("PLATE_NUMBER_INVALID", HttpStatus.BAD_REQUEST, message);
  }
}
