package com.rideflow.payment.internal;

import com.rideflow.shared.RideFlowProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/** The real {@code application.yml}, with a few values changed, for unit tests. */
final class PaymentProperties {

  private PaymentProperties() {}

  static RideFlowProperties with(Map<String, String> overrides) {
    try {
      List<ConfigurationPropertySource> sources = new ArrayList<>();
      sources.add(new MapConfigurationPropertySource(overrides));
      ConfigurationPropertySources.from(
              new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml")))
          .forEach(sources::add);
      return new Binder(sources).bind("rideflow", RideFlowProperties.class).get();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
