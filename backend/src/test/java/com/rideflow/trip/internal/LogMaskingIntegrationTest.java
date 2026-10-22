package com.rideflow.trip.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * PROJECT_SPEC section 11: never log passwords, tokens, OTPs or full phone numbers. A whole trip,
 * wrong password and wrong code included, at the DEBUG level the test profile logs at - and none of
 * them appears anywhere in the output.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogMaskingIntegrationTest extends TripIntegrationTestBase {

  private static final String PHONE = "+919911100001";
  private static final String PASSWORD = "correct-horse-battery-staple";

  private ResultActions json(String path, String token, String body) throws Exception {
    var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return mockMvc.perform(request);
  }

  @Test
  void logsNoPhoneNumberTokenPasswordOrOtpThroughAWholeTrip(CapturedOutput output)
      throws Exception {
    // Sign-up, a wrong password, a login and a refresh: every token there is.
    String registered =
        json(
                "/api/v1/auth/register/rider",
                null,
                """
                {"phone":"%s","name":"Asha","password":"%s"}"""
                    .formatted(PHONE, PASSWORD))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    json(
            "/api/v1/auth/login",
            null,
            """
            {"phone":"%s","password":"not-the-password"}"""
                .formatted(PHONE))
        .andExpect(status().isUnauthorized());
    String loggedIn =
        json(
                "/api/v1/auth/login",
                null,
                """
                {"phone":"%s","password":"%s"}"""
                    .formatted(PHONE, PASSWORD))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String refreshed =
        json(
                "/api/v1/auth/refresh",
                null,
                """
                {"refreshToken":"%s"}"""
                    .formatted((String) JsonPath.read(loggedIn, "$.refreshToken")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String accessToken = JsonPath.read(refreshed, "$.accessToken");
    User rider = new User(subjectOf(accessToken), accessToken);

    // A trip, a wrong code, then the right one.
    User driver = driver();
    UUID tripId = assignedTrip(rider, driver);
    String otp = otpOf(tripId);
    json("/api/v1/trips/" + tripId + "/arrive", driver.token(), "{}").andExpect(status().isOk());
    String wrong = otp.equals("0000") ? "1111" : "0000";
    json("/api/v1/trips/" + tripId + "/start", driver.token(), "{\"otp\":\"" + wrong + "\"}")
        .andExpect(status().is4xxClientError());
    json("/api/v1/trips/" + tripId + "/start", driver.token(), "{\"otp\":\"" + otp + "\"}")
        .andExpect(status().isOk());
    json("/api/v1/trips/" + tripId + "/complete", driver.token(), "{}").andExpect(status().isOk());

    String log = output.getAll();
    // The flow really ran, and was logged - masked.
    assertThat(log).contains("****0001");
    assertThat(log).doesNotContain(PHONE.substring(3)).doesNotContain(PASSWORD);
    for (String json : new String[] {registered, loggedIn, refreshed}) {
      assertThat(log)
          .doesNotContain((String) JsonPath.read(json, "$.accessToken"))
          .doesNotContain((String) JsonPath.read(json, "$.refreshToken"));
    }
    // An OTP is four digits, which appear everywhere; next to the word, they must not.
    assertThat(Pattern.compile("(?i)otp\\W{0,4}" + otp).matcher(log).find())
        .as("the OTP logged")
        .isFalse();
  }
}
