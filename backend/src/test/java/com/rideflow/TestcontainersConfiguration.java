package com.rideflow;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The real infrastructure every integration test runs against.
 *
 * <p>PROJECT_SPEC section 12 rules out H2 and embedded Redis, so these are the same images that
 * {@code infra/docker-compose.yml} pins, and tests exercise the same SQL, the same Lua and the same
 * Kafka protocol that production will.
 *
 * <p>{@link ServiceConnection} means no test ever hard-codes a host or port: Spring Boot reads them
 * off the started container.
 *
 * <p>Import it with {@code @Import(TestcontainersConfiguration.class)}. Do not declare it as a
 * nested class of a test: a {@code @TestConfiguration} nested in an outer class is silently ignored
 * for {@code @Nested} tests, which is a failure mode that looks like a logic bug rather than a
 * wiring one.
 *
 * <p>The containers are beans, so each test context gets its own set, and Spring's context cache
 * keeps that context - and its containers - for the rest of the run. Test classes with the same
 * configuration share one set. Every distinct configuration (a {@code @MockitoSpyBean}, an extra
 * {@code @Import}) starts another: a full run starts about fourteen, which a Docker VM with too
 * little memory answers with 500s. Keep distinct configurations few. Testcontainers' cross-JVM
 * {@code withReuse(true)} is deliberately <em>not</em> used: it needs a machine-level opt-in that
 * CI does not have, and it leaves data behind between runs, which makes tests pass or fail
 * depending on what ran before. Tests must still not assume an empty database, because they share
 * these containers with each other.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:17.11-alpine");
  }

  @Bean
  @ServiceConnection
  RedisContainer redis() {
    return new RedisContainer("redis:7.4.11-alpine");
  }

  @Bean
  @ServiceConnection
  KafkaContainer kafka() {
    // The Apache KRaft image, matching PROJECT_SPEC section 3: no ZooKeeper.
    return new KafkaContainer("apache/kafka:4.2.1");
  }
}
