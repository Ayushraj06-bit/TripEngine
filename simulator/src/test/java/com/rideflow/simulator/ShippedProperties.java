package com.rideflow.simulator;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

/** application.yml as an unset environment sees it, with overrides on top. */
final class ShippedProperties {

  private ShippedProperties() {}

  static SimulatorProperties load(String... overrides) throws Exception {
    Map<String, Object> values = new HashMap<>();
    for (String override : overrides) {
      String[] pair = override.split("=", 2);
      values.put(pair[0], pair[1]);
    }
    MutablePropertySources sources = new MutablePropertySources();
    sources.addFirst(new MapPropertySource("overrides", values));
    new YamlPropertySourceLoader()
        .load("application", new ClassPathResource("application.yml"))
        .forEach(sources::addLast);
    return new Binder(
            ConfigurationPropertySources.from(sources),
            new PropertySourcesPlaceholdersResolver(sources))
        .bind("simulator", SimulatorProperties.class)
        .get();
  }
}
