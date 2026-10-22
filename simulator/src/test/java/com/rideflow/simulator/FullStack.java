package com.rideflow.simulator;

import com.redis.testcontainers.RedisContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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

  /** Before BACKEND, which reads it: static fields are set in the order they are written. */
  private static final Keys KEYS = keys();

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
          .withEnv("SPRING_PROFILES_ACTIVE", "sim")
          .withEnv("RIDEFLOW_DB_URL", "jdbc:postgresql://postgres:5432/rideflow")
          .withEnv("RIDEFLOW_REDIS_HOST", "redis")
          .withEnv("RIDEFLOW_KAFKA_BOOTSTRAP", "kafka:19092")
          .withEnv("RIDEFLOW_ADMIN_PHONE", ADMIN_PHONE)
          .withEnv("RIDEFLOW_ADMIN_PASSWORD", ADMIN_PASSWORD)
          .withEnv("RIDEFLOW_GATEWAY_WEBHOOK_SECRET", "e2e-webhook-secret")
          // A key pair of its own for the run, so tokens outlive a restart of the backend.
          .withEnv("RIDEFLOW_RSA_PRIVATE_KEY", KEYS.privateKey())
          .withEnv("RIDEFLOW_RSA_PUBLIC_KEY", KEYS.publicKey())
          // No trace collector here: sample nothing rather than fail every export.
          .withEnv("RIDEFLOW_TRACE_SAMPLE", "0.0")
          // Short enough that the simulated apps renew their tokens - and their sockets - inside
          // the one-minute run.
          .withEnv("RIDEFLOW_SECURITY_ACCESSTOKENTTL", "150s")
          // The chaos tests wait on recovery: redelivery every few seconds rather than minutes,
          // and a trip outlives a restart of the backend before matching gives up.
          .withEnv("RIDEFLOW_EVENTS_REDELIVERAFTERSECONDS", "10")
          .withEnv("RIDEFLOW_EVENTS_REDELIVERYINTERVAL", "PT5S")
          .withEnv("RIDEFLOW_MATCHING_DEADLINESECONDS", "240")
          // 8090 is actuator: metrics, for the chaos tests.
          .withExposedPorts(8080, 8090)
          .dependsOn(POSTGRES, REDIS, KAFKA)
          .waitingFor(
              Wait.forHttp("/readyz")
                  .forPort(8080)
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(3)));

  private FullStack() {}

  private record Keys(String privateKey, String publicKey) {}

  private static Keys keys() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair pair = generator.generateKeyPair();
      Base64.Encoder base64 = Base64.getEncoder();
      return new Keys(
          base64.encodeToString(pair.getPrivate().getEncoded()),
          base64.encodeToString(pair.getPublic().getEncoded()));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Actuator, on the backend's management port. */
  static URI managementUrl() {
    return URI.create("http://" + BACKEND.getHost() + ":" + BACKEND.getMappedPort(8090));
  }

  /** The backend killed and started again - same database, Redis and Kafka. */
  static synchronized void restartBackend() {
    BACKEND.stop();
    BACKEND.start();
  }

  /** One sample of a backend metric, summed over its series; 0 if it has none yet. */
  static double metric(String name, String labelFilter) throws Exception {
    String body =
        HTTP.send(
                HttpRequest.newBuilder(managementUrl().resolve("/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString())
            .body();
    return body.lines()
        .filter(line -> line.startsWith(name + "{") || line.startsWith(name + " "))
        .filter(line -> line.contains(labelFilter))
        .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
        .sum();
  }

  /** A GET to the public port: its status, and how long the answer took. */
  record Timed(int status, Duration took) {}

  static Timed get(String path) throws Exception {
    long start = System.nanoTime();
    int status =
        HTTP.send(
                HttpRequest.newBuilder(backendUrl().resolve(path))
                    .timeout(Duration.ofSeconds(30))
                    .build(),
                HttpResponse.BodyHandlers.discarding())
            .statusCode();
    return new Timed(status, Duration.ofNanos(System.nanoTime() - start));
  }

  private static final HttpClient HTTP = HttpClient.newHttpClient();

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
