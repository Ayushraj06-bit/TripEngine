package com.rideflow.shared.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

  /**
   * Events whose listeners have not all completed: the outbox's backlog. Read from the registry's
   * own table, by its completion_date index, on each scrape.
   */
  @Bean
  MeterBinder eventPublicationBacklog(JdbcTemplate jdbc) {
    return registry ->
        Gauge.builder(
                "events.publications.incomplete",
                jdbc,
                db ->
                    db.queryForObject(
                        "SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
                        Long.class))
            .description("Event publications with a listener not yet completed")
            .register(registry);
  }
}
