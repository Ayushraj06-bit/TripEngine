package com.rideflow.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.rideflow.shared.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumberTest {

  private static final String CANONICAL = "+919876543210";

  @ParameterizedTest
  @ValueSource(
      strings = {
        "+919876543210",
        "9876543210",
        "98765 43210",
        "98765-43210",
        "+91-98765-43210",
        "+91 98765 43210",
        "(+91) 98765 43210",
        "09876543210",
        "0091 9876543210",
        "00919876543210",
        "  9876543210  ",
      })
  void normalisesEveryWayThatNumberIsWritten(String raw) {
    // All of these are one person. If any of them survived as a distinct string, they
    // would get a second account, and lockout and per-phone rate limiting would both
    // be trivially bypassed by re-typing the number differently.
    assertThat(PhoneNumber.normalise(raw)).isEqualTo(CANONICAL);
  }

  @Test
  void treatsDifferentFormatsOfTheSameNumberAsEqual() {
    assertThat(PhoneNumber.normalise("98765 43210"))
        .isEqualTo(PhoneNumber.normalise("+91-98765-43210"))
        .isEqualTo(PhoneNumber.normalise("+919876543210"));
  }

  @ParameterizedTest
  @CsvSource({
    "+14155552671, +14155552671", // already E.164, another country
    "+442071838750, +442071838750",
  })
  void leavesAnAlreadyValidInternationalNumberAlone(String raw, String expected) {
    assertThat(PhoneNumber.normalise(raw)).isEqualTo(expected);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\t"})
  void rejectsAMissingNumber(String raw) {
    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> PhoneNumber.normalise(raw))
        .satisfies(e -> assertThat(e.code()).isEqualTo("PHONE_INVALID"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "12345", // too short for anything
        "987654321", // nine digits, not a valid Indian mobile
        "98765432101", // eleven, and no leading zero to strip
        "+0123456789", // country code cannot start with zero
        "+9", // far too short
        "+1234567890123456", // sixteen digits, beyond E.164
        "abcdefghij",
        "9876 5432 10 ext 5",
      })
  void rejectsAnythingThatIsNotAPhoneNumber(String raw) {
    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> PhoneNumber.normalise(raw))
        .satisfies(e -> assertThat(e.code()).isEqualTo("PHONE_INVALID"));
  }

  @Test
  void reportsAnInvalidNumberAsABadRequestNotAServerError() {
    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> PhoneNumber.normalise("nope"))
        .satisfies(e -> assertThat(e.status().value()).isEqualTo(400));
  }

  @Test
  void masksAllButTheLastFourDigits() {
    // PROJECT_SPEC section 11: a full phone number must never reach a log.
    assertThat(PhoneNumber.mask(CANONICAL)).isEqualTo("****3210").doesNotContain("98765");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"12", "1234"})
  void masksShortOrAbsentInputCompletely(String raw) {
    assertThat(PhoneNumber.mask(raw)).isEqualTo("****");
  }
}
