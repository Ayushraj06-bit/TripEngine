package com.rideflow.driver.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.rideflow.shared.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PlateNumberTest {

  private static final String CANONICAL = "KA01AB1234";

  @ParameterizedTest
  @ValueSource(
      strings = {
        "KA01AB1234",
        "KA 01 AB 1234",
        "ka01ab1234",
        "ka 01 ab 1234",
        "KA-01-AB-1234",
        "KA.01.AB.1234",
        "  KA01AB1234  ",
        "kA 01 Ab 1234",
      })
  void normalisesEveryWayThatPlateIsWritten(String raw) {
    // All of these are one car. Without normalisation the unique constraint means
    // nothing and the same vehicle could be registered to several drivers.
    assertThat(PlateNumber.normalise(raw)).isEqualTo(CANONICAL);
  }

  @Test
  void treatsSpacedAndLowercaseFormsAsTheSamePlate() {
    assertThat(PlateNumber.normalise("KA 01 AB 1234"))
        .isEqualTo(PlateNumber.normalise("ka01ab1234"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "---", "   -  - "})
  void rejectsAPlateWithNothingUsableInIt(String raw) {
    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> PlateNumber.normalise(raw))
        .satisfies(e -> assertThat(e.code()).isEqualTo("PLATE_NUMBER_INVALID"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"KA01", "AB12", "KA012345678901234567"})
  void rejectsAPlateOfAnImplausibleLength(String raw) {
    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> PlateNumber.normalise(raw))
        .satisfies(e -> assertThat(e.status().value()).isEqualTo(400));
  }

  @Test
  void producesOnlyCharactersTheDatabaseConstraintAllows() {
    // V4 has CHECK (plate_number ~ '^[A-Z0-9]+$'); anything else would fail on insert
    // rather than at the edge where it can be explained.
    assertThat(PlateNumber.normalise("ka-01/ab.1234")).matches("^[A-Z0-9]+$");
  }
}
