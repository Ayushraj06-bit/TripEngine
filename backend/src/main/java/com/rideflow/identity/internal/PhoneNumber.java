package com.rideflow.identity.internal;

import com.rideflow.shared.BusinessException;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Normalises a phone number to E.164 before it reaches the database.
 *
 * <p>Normalisation is what makes the unique constraint on {@code users.phone} mean what it should.
 * Without it, {@code 98765 43210}, {@code +91-98765-43210} and {@code +919876543210} are three
 * different strings and one person gets three accounts, which then defeats rate limiting per phone
 * and account lockout as well.
 *
 * <p>Deliberately not libphonenumber. PROJECT_SPEC section 1 scopes the service to one city, so the
 * only real case is India: strip separators, then add or correct the country code. A wider rollout
 * is the point at which libphonenumber earns its place, and this class is the single spot that
 * changes.
 */
final class PhoneNumber {

  /** E.164: a leading +, a non-zero country code, 8 to 15 digits in total. */
  private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

  private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().]");

  private static final String INDIA = "+91";
  private static final int INDIAN_SUBSCRIBER_DIGITS = 10;

  private PhoneNumber() {}

  /**
   * The E.164 form of {@code raw}.
   *
   * <p>Accepts {@code 9876543210}, {@code 98765 43210}, {@code 098765 43210}, {@code
   * +91-98765-43210} and {@code 0091 9876543210}, all of which become {@code +919876543210}.
   *
   * @throws BusinessException 400 if the number cannot be read as a phone number
   */
  static String normalise(String raw) {
    if (raw == null || raw.isBlank()) {
      throw invalid("Phone number is required");
    }

    String digitsAndPlus = SEPARATORS.matcher(raw.trim()).replaceAll("");

    // 00 is the other way of writing a leading +.
    if (digitsAndPlus.startsWith("00")) {
      digitsAndPlus = "+" + digitsAndPlus.substring(2);
    }

    String candidate;
    if (digitsAndPlus.startsWith("+")) {
      candidate = digitsAndPlus;
    } else if (digitsAndPlus.length() == INDIAN_SUBSCRIBER_DIGITS) {
      candidate = INDIA + digitsAndPlus;
    } else if (digitsAndPlus.length() == INDIAN_SUBSCRIBER_DIGITS + 1
        && digitsAndPlus.startsWith("0")) {
      // A domestic trunk prefix, as written on a business card.
      candidate = INDIA + digitsAndPlus.substring(1);
    } else {
      throw invalid("Phone number must be 10 digits, or include a country code such as +91");
    }

    if (!E164.matcher(candidate).matches()) {
      throw invalid("Phone number must be a valid E.164 number, for example +919876543210");
    }
    return candidate;
  }

  /** The last four digits, for logs. PROJECT_SPEC section 11 forbids logging the whole number. */
  static String mask(String phone) {
    if (phone == null || phone.length() <= 4) {
      return "****";
    }
    return "****" + phone.substring(phone.length() - 4);
  }

  private static BusinessException invalid(String message) {
    return new BusinessException("PHONE_INVALID", HttpStatus.BAD_REQUEST, message);
  }
}
