package com.rideflow.simulator;

import com.redis.testcontainers.RedisContainer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The whole stack in Testcontainers, started once for the run: PostgreSQL, Redis and Kafka on a
 * private network, and the backend - the jar this build just packaged - on a plain JRE beside them.
 * A black box: the tests reach it only through its port, as the apps do, and check its database and
 * logs from outside.
 */
final class FullStack {

  static final String ADMIN_PHONE = "+919999900001";
  static final String ADMIN_PASSWORD = "e2e-admin-password";

  private static final Network NETWORK = Network.newNetwork();

  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.11-alpine")
          .withNetwork(NETWORK)
          .withNetworkAliases("postgres")
          .withDatabaseName("rideflow")
          .withUsername("rideflow")
          .withPassword("rideflow");

  static final RedisContainer REDIS =
      new RedisContainer("redis:7.4.11-alpine").withNetwork(NETWORK).withNetworkAliases("redis");

  /** A second listener for the backend inside the network; the simulator uses the mapped one. */
  static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.2.1").withNetwork(NETWORK).withListener("kafka:19092");

  static final GenericContainer<?> BACKEND =
      new GenericContainer<>("eclipse-temurin:21-jre-alpine")
          .withNetwork(NETWORK)
          .withCopyFileToContainer(MountableFile.forHostPath(backendJar()), "/app.jar")
          .withCommand("java", "-jar", "/app.jar")
          .withEnv(
              Map.of(
                  "SPRING_PROFILES_ACTIVE", "sim",
                  "RIDEFLOW_DB_URL", "jdbc:postgresql://postgres:5432/rideflow",
                  "RIDEFLOW_REDIS_HOST", "redis",
                  "RIDEFLOW_KAFKA_BOOTSTRAP", "kafka:19092",
                  "RIDEFLOW_ADMIN_PHONE", ADMIN_PHONE,
                  "RIDEFLOW_ADMIN_PASSWORD", ADMIN_PASSWORD,
                  "RIDEFLOW_GATEWAY_WEBHOOK_SECRET", "e2e-webhook-secret",
                  // No trace collector here: sample nothing rather than fail every export.
                  "RIDEFLOW_TRACE_SAMPLE", "0.0"))
          .withExposedPorts(8080)
          .dependsOn(POSTGRES, REDIS, KAFKA)
          .waitingFor(
              Wait.forHttp("/actuator/health/readiness")
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(3)));

  private FullStack() {}

  static synchronized void start() {
    if (!BACKEND.isRunning()) {
      BACKEND.start();
    }
  }

  static URI backendUrl() {
    return URI.create("http://" + BACKEND.getHost() + ":" + BACKEND.getMappedPort(8080));
  }

  /**
   * A simulator against this stack; closing the context stops it. The settings go in as
   * command-line arguments, which outrank application.yml - default properties would not.
   */
  static ConfigurableApplicationContext simulator(String... properties) {
    List<String> all =
        new ArrayList<>(
            List.of(
                "simulator.backend-url=" + backendUrl(),
                "simulator.accounts.admin-phone=" + ADMIN_PHONE,
                "simulator.accounts.admin-password=" + ADMIN_PASSWORD,
                "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "server.port=0"));
    all.addAll(List.of(properties));
    return new SpringApplicationBuilder(SimulatorApplication.class)
        .run(all.stream().map(setting -> "--" + setting).toArray(String[]::new));
  }

  /** The first column of every row {@code sql} returns, from the backend's own database. */
  static List<Object> query(String sql) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      List<Object> values = new ArrayList<>();
      while (rows.next()) {
        values.add(rows.getObject(1));
      }
      return values;
    }
  }

  static long count(String sql) throws SQLException {
    return ((Number) query(sql).getFirst()).longValue();
  }

  private static String backendJar() {
    String jar = System.getProperty("backend.jar");
    if (jar == null || !Files.isRegularFile(Path.of(jar))) {
      throw new IllegalStateException(
          "No backend jar at " + jar + ": run ./mvnw -Pe2e verify from the repository root");
    }
    return jar;
  }
}
