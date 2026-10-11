package com.rideflow.payment.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.rideflow.payment.internal.MockPaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The page that credits any wallet that asks must not exist unless someone turned it on. */
class MockGatewayControllerTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(MockGatewayController.class)
          .withBean(MockPaymentGateway.class, () -> mock(MockPaymentGateway.class));

  @Test
  void doesNotExistUnlessTurnedOn() {
    runner.run(context -> assertThat(context).doesNotHaveBean(MockGatewayController.class));
    runner
        .withPropertyValues("rideflow.payment.mock-gateway.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(MockGatewayController.class));
  }

  @Test
  void existsWhereTurnedOn() {
    runner
        .withPropertyValues("rideflow.payment.mock-gateway.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(MockGatewayController.class));
  }
}
