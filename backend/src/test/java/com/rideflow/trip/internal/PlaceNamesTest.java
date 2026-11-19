package com.rideflow.trip.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlaceNamesTest {

  @Test
  void keepsANameAsOneTrimmedLine() {
    assertThat(PlaceNames.of("  MG Road ", "Indiranagar"))
        .isEqualTo(new PlaceNames("MG Road", "Indiranagar"));
    assertThat(PlaceNames.of("MG\n\nRoad", "Indira\t nagar"))
        .isEqualTo(new PlaceNames("MG Road", "Indira nagar"));
  }

  @Test
  void takesABlankOrMissingNameAsNone() {
    assertThat(PlaceNames.of(" \n\t ", null)).isEqualTo(PlaceNames.NONE);
  }
}
